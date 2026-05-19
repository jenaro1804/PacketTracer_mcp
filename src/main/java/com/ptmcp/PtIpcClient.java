package com.ptmcp;

import com.cisco.pt.impl.IPAddressImpl;
import com.cisco.pt.impl.OptionsManager;
import com.cisco.pt.ipc.IPCFactory;
import com.cisco.pt.ipc.enums.CommandStatus;
import com.cisco.pt.ipc.enums.ConnectType;
import com.cisco.pt.ipc.enums.DeviceType;
import com.cisco.pt.ipc.sim.CiscoDevice;
import com.cisco.pt.util.Pair;
import com.cisco.pt.ipc.sim.Device;
import com.cisco.pt.ipc.sim.HostPort;
import com.cisco.pt.ipc.sim.Network;
import com.cisco.pt.ipc.sim.Pc;
import com.cisco.pt.ipc.ui.IPC;
import com.cisco.pt.ipc.ui.LogicalWorkspace;
import com.cisco.pt.ptmp.ConnectionNegotiationProperties;
import com.cisco.pt.ptmp.PacketTracerSession;
import com.cisco.pt.ptmp.PacketTracerSessionFactory;
import com.cisco.pt.ptmp.impl.PacketTracerSessionFactoryImpl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Envuelve el framework Java de Cisco (pt-cep-java-framework) con una API
 * minima orientada a las operaciones que expondra el servidor MCP.
 *
 * Ciclo de vida: construir, llamar a connect(), usar los metodos de operacion,
 * llamar a disconnect() al final (preferentemente desde try-with-resources).
 */
public class PtIpcClient implements AutoCloseable {

    public static final String DEFAULT_HOST = "localhost";
    public static final int DEFAULT_PORT = 39000;

    // Variables de entorno y claves de local.properties para las credenciales.
    public static final String ENV_AUTH_APPLICATION = "PT_MCP_AUTH_APPLICATION";
    public static final String ENV_AUTH_SECRET = "PT_MCP_AUTH_SECRET";
    public static final String PROP_AUTH_APPLICATION = "pt.mcp.auth.application";
    public static final String PROP_AUTH_SECRET = "pt.mcp.auth.secret";

    private final String host;
    private final int port;
    private final String authApplication;
    private final String authSecret;

    private PacketTracerSession session;
    private IPC ipc;

    /**
     * Constructor por defecto: carga las credenciales desde el entorno o el
     * archivo {@code local.properties} en la raiz del proyecto.
     * Lanza IllegalStateException si no encuentra ID/KEY validas.
     */
    public PtIpcClient() {
        this(DEFAULT_HOST, DEFAULT_PORT,
                loadCredential(ENV_AUTH_APPLICATION, PROP_AUTH_APPLICATION, "application ID"),
                loadCredential(ENV_AUTH_SECRET, PROP_AUTH_SECRET, "secret"));
    }

    public PtIpcClient(String host, int port, String authApplication, String authSecret) {
        this.host = host;
        this.port = port;
        this.authApplication = authApplication;
        this.authSecret = authSecret;
    }

    /**
     * Abre la sesion PTMP contra Packet Tracer y autentica.
     * Lanza IllegalStateException si la autenticacion falla o si ya hay sesion.
     */
    public void connect() {
        if (session != null) {
            throw new IllegalStateException("Ya hay una sesion abierta.");
        }

        PacketTracerSessionFactory factory = PacketTracerSessionFactoryImpl.getInstance();
        ConnectionNegotiationProperties cnp = OptionsManager.getInstance().getConnectOpts();
        cnp.setAuthenticationApplication(authApplication);
        cnp.setAuthenticationSecret(authSecret);

        PacketTracerSession opened;
        try {
            opened = factory.openSession(host, port, cnp);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "No se pudo abrir la sesion PTMP contra " + host + ":" + port + ": " + e.getMessage(), e);
        }

        // Tras una auth fallida el framework devuelve una sesion sin conexion
        // interna; isConnected() lanzaria NullPointerException si la consultamos
        // directo. Por eso lo envolvemos.
        boolean connected;
        try {
            connected = opened.isConnected();
        } catch (NullPointerException npe) {
            connected = false;
        }
        if (!connected) {
            try { opened.close(); } catch (Exception ignored) { }
            throw new IllegalStateException(
                    "Autenticacion rechazada por Packet Tracer. "
                            + "Revisa que '" + authApplication + "' este registrada en "
                            + "Extensions > IPC > Configure Apps con la KEY correcta.");
        }

        this.session = opened;
        this.ipc = new IPCFactory(opened).getIPC();
    }

    /** true si la sesion esta abierta y autenticada. */
    public boolean isConnected() {
        if (session == null) return false;
        try {
            return session.isConnected();
        } catch (NullPointerException npe) {
            return false;
        }
    }

    /** Cierra la sesion. Idempotente. */
    public void disconnect() {
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignored) {
                // best-effort
            }
            session = null;
            ipc = null;
        }
    }

    @Override
    public void close() {
        disconnect();
    }

    /**
     * Lee la topologia actual: lista de dispositivos (nombre + tipo + modelo)
     * y total de enlaces. Solo lectura.
     */
    public Topology getTopology() {
        Network network = network();
        int deviceCount = network.getDeviceCount();
        List<DeviceInfo> devices = new ArrayList<>(deviceCount);
        for (int i = 0; i < deviceCount; i++) {
            Device d = network.getDeviceAt(i);
            devices.add(new DeviceInfo(d.getName(), d.getType().name(), d.getModel()));
        }
        int linkCount = network.getLinkCount();
        return new Topology(devices, linkCount);
    }

    /**
     * Crea un dispositivo en el lienzo logico. PT le asigna el nombre.
     *
     * @param type  tipo (case-insensitive). Acepta cualquier valor del enum
     *              com.cisco.pt.ipc.enums.DeviceType: "router", "switch", "pc",
     *              "server", "laptop", "hub", "access_point", "wireless_router",
     *              "multi_layer_switch", "asa", etc.
     * @param model modelo concreto (ej. "2911" para router, "2960" para switch,
     *              "PC-PT" para pc generica). Debe existir en el catalogo de PT
     *              para ese tipo.
     * @param x     coordenada X en el lienzo logico.
     * @param y     coordenada Y en el lienzo logico.
     * @return el nombre que PT asigno al dispositivo (ej. "Router0", "PC1").
     */
    public String addDevice(String type, String model, double x, double y) {
        if (type == null || type.isEmpty()) {
            throw new IllegalArgumentException("type es obligatorio.");
        }
        if (model == null || model.isEmpty()) {
            throw new IllegalArgumentException("model es obligatorio.");
        }
        DeviceType deviceType = parseDeviceType(type);
        String created = logicalWorkspace().addDevice(deviceType, model, x, y);
        if (created == null || created.isEmpty()) {
            throw new IllegalStateException(
                    "Packet Tracer rechazo la creacion del dispositivo (" + type + "/" + model + "). "
                            + "Probablemente el modelo no existe en el catalogo de PT para ese tipo.");
        }
        return created;
    }

    /**
     * Elimina un dispositivo por nombre.
     *
     * @return true si PT confirma el borrado.
     */
    public boolean deleteDevice(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name es obligatorio.");
        }
        return logicalWorkspace().removeDevice(name);
    }

    /**
     * Lista los nombres de los puertos/interfaces de un dispositivo
     * (ej. "GigabitEthernet0/0", "Serial0/3/0", "FastEthernet0").
     */
    public List<String> getPorts(String deviceName) {
        Device d = network().getDevice(deviceName);
        if (d == null) {
            throw new IllegalArgumentException("No existe dispositivo '" + deviceName + "'.");
        }
        return d.getPorts();
    }

    /**
     * Conecta dos dispositivos con un cable.
     *
     * @param deviceA   nombre del primer dispositivo (ej. "Router0").
     * @param ifaceA    nombre de la interfaz en A (ej. "GigabitEthernet0/0", "FastEthernet0").
     * @param deviceB   nombre del segundo dispositivo.
     * @param ifaceB    nombre de la interfaz en B.
     * @param cableType tipo de cable (case-insensitive). Valores del enum
     *                  com.cisco.pt.ipc.enums.ConnectType: "ethernet_straight",
     *                  "ethernet_cross", "fiber_singlemode", "fiber_multimode",
     *                  "serial", "console", "coaxial", "wireless", "auto", etc.
     * @return true si PT confirma la creacion del enlace.
     */
    public boolean connectDevices(String deviceA, String ifaceA,
                                  String deviceB, String ifaceB,
                                  String cableType) {
        if (deviceA == null || deviceA.isEmpty()) throw new IllegalArgumentException("deviceA es obligatorio.");
        if (ifaceA == null || ifaceA.isEmpty()) throw new IllegalArgumentException("ifaceA es obligatorio.");
        if (deviceB == null || deviceB.isEmpty()) throw new IllegalArgumentException("deviceB es obligatorio.");
        if (ifaceB == null || ifaceB.isEmpty()) throw new IllegalArgumentException("ifaceB es obligatorio.");
        if (cableType == null || cableType.isEmpty()) throw new IllegalArgumentException("cableType es obligatorio.");
        ConnectType ct = parseConnectType(cableType);
        return logicalWorkspace().createLink(deviceA, ifaceA, deviceB, ifaceB, ct);
    }

    /**
     * Ejecuta un comando IOS en un dispositivo Cisco.
     *
     * @param deviceName nombre del dispositivo (debe ser un CiscoDevice: router,
     *                   switch, ASA, etc.).
     * @param command    el comando IOS (ej. "show ip interface brief").
     * @param mode       modo en que se entra el comando: "user", "enable",
     *                   "global", o "" (vacio) para el modo actual.
     * @return resultado con status (OK / ERROR_*) y la salida textual de PT.
     */
    public CliResult runCli(String deviceName, String command, String mode) {
        if (deviceName == null || deviceName.isEmpty()) {
            throw new IllegalArgumentException("deviceName es obligatorio.");
        }
        if (command == null) {
            throw new IllegalArgumentException("command no puede ser null.");
        }
        Device d = network().getDevice(deviceName);
        if (d == null) {
            throw new IllegalArgumentException("No existe dispositivo '" + deviceName + "'.");
        }
        if (!(d instanceof CiscoDevice)) {
            throw new IllegalArgumentException(
                    "El dispositivo '" + deviceName + "' no es un CiscoDevice "
                            + "(tipo=" + d.getType() + "). runCli solo aplica a routers, switches y ASAs.");
        }
        CiscoDevice cisco = (CiscoDevice) d;
        String modeArg = mode == null ? "" : mode.trim().toLowerCase();
        Pair<CommandStatus, String> result = cisco.enterCommand(command, modeArg);
        return new CliResult(result.getFirst().name(), result.getSecond());
    }

    /** Conveniencia: runCli en modo actual. */
    public CliResult runCli(String deviceName, String command) {
        return runCli(deviceName, command, "");
    }

    /**
     * Acelera el arranque de un dispositivo Cisco (router/switch/ASA).
     * Sin esto, los routers de PT tardan ~30-60s en arrancar y todos los
     * comandos IOS devuelven ERROR_INVALID hasta que terminan.
     *
     * @return true si el dispositivo es Cisco y se solicito el skip;
     *         false si el dispositivo no soporta esta operacion.
     */
    public boolean skipBoot(String deviceName) {
        Device d = network().getDevice(deviceName);
        if (d == null) {
            throw new IllegalArgumentException("No existe dispositivo '" + deviceName + "'.");
        }
        if (!(d instanceof CiscoDevice)) {
            return false;
        }
        CiscoDevice cisco = (CiscoDevice) d;
        if (cisco.isBooting()) {
            cisco.skipBoot();
        }
        return true;
    }

    /**
     * Espera a que un dispositivo Cisco termine de bootear, hasta un timeout.
     * @return true si arranco a tiempo, false si vencio el timeout.
     */
    public boolean waitForBoot(String deviceName, long timeoutMillis) {
        Device d = network().getDevice(deviceName);
        if (d == null) {
            throw new IllegalArgumentException("No existe dispositivo '" + deviceName + "'.");
        }
        if (!(d instanceof CiscoDevice)) {
            return true;
        }
        CiscoDevice cisco = (CiscoDevice) d;
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (cisco.isBooting()) {
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /**
     * Configura una IP estatica en una interfaz de un end-device (PC, Laptop,
     * Server). Apaga DHCP automaticamente antes de aplicar la IP, para que el
     * dispositivo quede efectivamente en modo estatico (si DHCP siguiera activo,
     * PT ignoraria la IP estatica). No aplica a routers/switches: para esos usar
     * runCli.
     *
     * @param deviceName nombre del end-device (ej. "PC0").
     * @param ifaceName  interfaz a configurar (ej. "FastEthernet0").
     * @param ip         IP estatica (ej. "192.168.1.10").
     * @param mask       mascara de subred (ej. "255.255.255.0").
     * @param gateway    gateway por defecto (ej. "192.168.1.1"); null o vacio
     *                   para omitirlo.
     */
    public EndpointIpResult setEndpointIp(String deviceName, String ifaceName,
                                          String ip, String mask, String gateway) {
        if (ip == null || ip.isEmpty()) throw new IllegalArgumentException("ip es obligatorio.");
        if (mask == null || mask.isEmpty()) throw new IllegalArgumentException("mask es obligatorio.");
        HostBinding hb = resolveHostBinding(deviceName, ifaceName);

        hb.pc.setDhcpFlag(false);
        hb.port.setDhcpClientFlag(false);
        hb.port.setIpSubnetMask(new IPAddressImpl(ip), new IPAddressImpl(mask));

        boolean gatewaySet = gateway != null && !gateway.isEmpty();
        if (gatewaySet) {
            hb.pc.setDefaultGateway(new IPAddressImpl(gateway));
        }
        return new EndpointIpResult(deviceName, ifaceName, ip, mask,
                gatewaySet ? gateway : null);
    }

    /**
     * Activa o desactiva DHCP en una interfaz de un end-device (PC, Laptop,
     * Server). Operacion inversa de {@link #setEndpointIp}: con enabled=true el
     * dispositivo vuelve a pedir IP por DHCP.
     */
    public EndpointDhcpResult setEndpointDhcp(String deviceName, String ifaceName, boolean enabled) {
        HostBinding hb = resolveHostBinding(deviceName, ifaceName);
        hb.pc.setDhcpFlag(enabled);
        hb.port.setDhcpClientFlag(enabled);
        return new EndpointDhcpResult(deviceName, ifaceName, enabled);
    }

    /**
     * Resuelve un end-device + interfaz a su par (Pc, HostPort), validando que
     * el dispositivo exista, sea un end-device (no CiscoDevice) y que la interfaz
     * sea de host. Compartido por setEndpointIp y setEndpointDhcp.
     */
    private HostBinding resolveHostBinding(String deviceName, String ifaceName) {
        if (deviceName == null || deviceName.isEmpty()) {
            throw new IllegalArgumentException("device es obligatorio.");
        }
        if (ifaceName == null || ifaceName.isEmpty()) {
            throw new IllegalArgumentException("iface es obligatorio.");
        }
        Device d = network().getDevice(deviceName);
        if (d == null) {
            throw new IllegalArgumentException("No existe dispositivo '" + deviceName + "'.");
        }
        if (!(d instanceof Pc)) {
            throw new IllegalArgumentException(
                    "El dispositivo '" + deviceName + "' no es un end-device "
                            + "(tipo=" + d.getType() + "). Esta operacion solo aplica a "
                            + "PC, Laptop y Server. Para routers/switches usa runCli.");
        }
        Pc pc = (Pc) d;
        var port = pc.getPort(ifaceName);
        if (port == null) {
            throw new IllegalArgumentException(
                    "No existe interfaz '" + ifaceName + "' en '" + deviceName + "'. "
                            + "Usa getPorts para ver las interfaces disponibles.");
        }
        if (!(port instanceof HostPort)) {
            throw new IllegalArgumentException(
                    "La interfaz '" + ifaceName + "' de '" + deviceName + "' no admite "
                            + "configuracion de IP de host.");
        }
        return new HostBinding(pc, (HostPort) port);
    }

    private static final class HostBinding {
        final Pc pc;
        final HostPort port;

        HostBinding(Pc pc, HostPort port) {
            this.pc = pc;
            this.port = port;
        }
    }

    private static ConnectType parseConnectType(String raw) {
        String normalized = raw.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        try {
            return ConnectType.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Tipo de cable desconocido: '" + raw + "'. "
                            + "Valores validos: ETHERNET_STRAIGHT, ETHERNET_CROSS, FIBER_SINGLEMODE, "
                            + "FIBER_MULTIMODE, SERIAL, CONSOLE, COAXIAL, WIRELESS, AUTO, ...");
        }
    }

    private static DeviceType parseDeviceType(String raw) {
        String normalized = raw.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        try {
            return DeviceType.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Tipo de dispositivo desconocido: '" + raw + "'. "
                            + "Valores validos: ROUTER, SWITCH, PC, SERVER, LAPTOP, HUB, "
                            + "ACCESS_POINT, WIRELESS_ROUTER, MULTI_LAYER_SWITCH, ASA, ...");
        }
    }

    // ---- carga de credenciales ----

    /**
     * Resuelve una credencial: env var primero, luego local.properties en la
     * raiz del proyecto (cwd), luego falla con mensaje claro.
     */
    private static String loadCredential(String envKey, String propKey, String humanName) {
        String fromEnv = System.getenv(envKey);
        if (fromEnv != null && !fromEnv.isEmpty()) return fromEnv;

        Path props = Path.of("local.properties");
        if (Files.exists(props)) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(props)) {
                p.load(in);
            } catch (IOException e) {
                throw new IllegalStateException("No se pudo leer local.properties: " + e.getMessage(), e);
            }
            String fromFile = p.getProperty(propKey);
            if (fromFile != null && !fromFile.isEmpty()) return fromFile;
        }

        throw new IllegalStateException(
                "Falta " + humanName + ". Define la variable de entorno " + envKey
                        + " o la propiedad '" + propKey + "' en local.properties. "
                        + "El valor debe coincidir con el del XML usado para generar el .pta.");
    }

    // ---- helpers internos ----

    private void requireConnected() {
        if (ipc == null) {
            throw new IllegalStateException("No hay sesion abierta. Llama a connect() primero.");
        }
    }

    Network network() {
        requireConnected();
        return ipc.network();
    }

    LogicalWorkspace logicalWorkspace() {
        requireConnected();
        return ipc.appWindow().getActiveWorkspace().getLogicalWorkspace();
    }

    // ---- DTOs de salida ----

    public static final class DeviceInfo {
        public final String name;
        public final String type;
        public final String model;

        public DeviceInfo(String name, String type, String model) {
            this.name = name;
            this.type = type;
            this.model = model;
        }

        @Override
        public String toString() {
            return name + " [" + type + " " + model + "]";
        }
    }

    public static final class CliResult {
        public final String status;  // STATUS_OK, ERROR_AMBIGUOUS, ERROR_INVALID, ...
        public final String output;

        public CliResult(String status, String output) {
            this.status = status;
            this.output = output;
        }

        public boolean isOk() {
            return "STATUS_OK".equals(status);
        }

        @Override
        public String toString() {
            return "[" + status + "] " + output;
        }
    }

    public static final class Topology {
        public final List<DeviceInfo> devices;
        public final int linkCount;

        public Topology(List<DeviceInfo> devices, int linkCount) {
            this.devices = devices;
            this.linkCount = linkCount;
        }
    }

    public static final class EndpointIpResult {
        public final String device;
        public final String iface;
        public final String ip;
        public final String mask;
        public final String gateway;   // null si no se configuro
        public final boolean dhcpDisabled = true;

        public EndpointIpResult(String device, String iface, String ip, String mask, String gateway) {
            this.device = device;
            this.iface = iface;
            this.ip = ip;
            this.mask = mask;
            this.gateway = gateway;
        }
    }

    public static final class EndpointDhcpResult {
        public final String device;
        public final String iface;
        public final boolean dhcpEnabled;

        public EndpointDhcpResult(String device, String iface, boolean dhcpEnabled) {
            this.device = device;
            this.iface = iface;
            this.dhcpEnabled = dhcpEnabled;
        }
    }
}

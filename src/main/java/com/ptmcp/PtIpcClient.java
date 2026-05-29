package com.ptmcp;

import com.cisco.pt.impl.IPAddressImpl;
import com.cisco.pt.impl.OptionsManager;
import com.cisco.pt.ipc.IPCFactory;
import com.cisco.pt.ipc.enums.CommandStatus;
import com.cisco.pt.ipc.enums.ConnectType;
import com.cisco.pt.ipc.enums.DeviceType;
import com.cisco.pt.ipc.enums.FileOpenReturnValue;
import com.cisco.pt.ipc.enums.ModuleType;
import com.cisco.pt.ipc.sim.CiscoDevice;
import com.cisco.pt.util.Pair;
import com.cisco.pt.ipc.sim.Device;
import com.cisco.pt.ipc.sim.HostPort;
import com.cisco.pt.ipc.sim.Link;
import com.cisco.pt.ipc.sim.Network;
import com.cisco.pt.ipc.sim.Pc;
import com.cisco.pt.ipc.sim.Port;
import com.cisco.pt.ipc.ui.AppWindow;
import com.cisco.pt.ipc.ui.IPC;
import com.cisco.pt.ipc.ui.LogicalWorkspace;
import com.cisco.pt.ipc.ui.NetworkComponentBox;
import com.cisco.pt.ptmp.ConnectionNegotiationProperties;
import com.cisco.pt.ptmp.PacketTracerSession;
import com.cisco.pt.ptmp.PacketTracerSessionFactory;
import com.cisco.pt.ptmp.impl.PacketTracerSessionFactoryImpl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
     * Reconstruye el cableado de la topologia. El SDK no expone los extremos de un
     * {@link Link} (solo su tipo de cable), asi que se itera cada puerto de cada
     * dispositivo, se anota el {@code Link} que cuelga de el y se emparejan los dos
     * extremos que comparten el mismo enlace.
     *
     * <p>La clave para deduplicar es el UUID del Link
     * ({@code link.getObjectUUID().getDecoratedHexString()}), estable entre ambos
     * extremos; NO se usa identidad de objeto porque los objetos IPC pueden venir
     * como proxies nuevos en cada llamada.
     *
     * <p>Cada llamada al SDK va blindada con {@link #tryGet} porque tocar un puerto
     * sin cable o un getter no soportado lanza {@code IPCError} (que extiende Error,
     * no Exception). Si solo se observa un extremo de un enlace (p.ej. inalambrico),
     * devB/ifB quedan en null.
     */
    public List<LinkInfo> getLinks() {
        Network network = network();
        int deviceCount = network.getDeviceCount();
        // Orden de descubrimiento estable para que la salida sea reproducible.
        Map<String, List<Endpoint>> byLink = new LinkedHashMap<>();
        for (int i = 0; i < deviceCount; i++) {
            Device d = network.getDeviceAt(i);
            String devName = tryGet(d::getName, null);
            int portCount = tryGet(d::getPortCount, 0);
            for (int j = 0; j < portCount; j++) {
                final int portIndex = j;
                Port p = tryGet(() -> d.getPortAt(portIndex), null);
                if (p == null) continue;
                Link link = tryGet(p::getLink, null);
                if (link == null) continue;  // puerto sin cable
                String ifName = tryGet(p::getName, null);
                String key = tryGet(() -> link.getObjectUUID().getDecoratedHexString(), null);
                if (key == null) {
                    // Sin UUID utilizable: tratarlo como enlace de un solo extremo
                    // para no fusionarlo por error con otro.
                    key = "__solo:" + devName + "/" + ifName;
                }
                String cable = tryGet(() -> link.getConnectionType().name(), null);
                byLink.computeIfAbsent(key, k -> new ArrayList<>())
                      .add(new Endpoint(devName, ifName, cable));
            }
        }

        List<LinkInfo> links = new ArrayList<>(byLink.size());
        for (List<Endpoint> ends : byLink.values()) {
            Endpoint a = ends.get(0);
            Endpoint b = ends.size() > 1 ? ends.get(1) : null;
            links.add(new LinkInfo(
                    a.device, a.iface,
                    b != null ? b.device : null,
                    b != null ? b.iface : null,
                    a.cableType));
        }
        return links;
    }

    /** Un extremo observado de un enlace (uso interno de getLinks). */
    private static final class Endpoint {
        final String device;
        final String iface;
        final String cableType;

        Endpoint(String device, String iface, String cableType) {
            this.device = device;
            this.iface = iface;
            this.cableType = cableType;
        }
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
     * Conecta dos dispositivos dejando que PT elija interfaces y tipo de cable
     * automaticamente (como arrastrar el cable "Automatico" en la GUI). Mas
     * comodo que connectDevices cuando no importan las interfaces concretas.
     * La API de Cisco es void: si no lanza excepcion, asumimos exito.
     *
     * @param deviceA nombre del primer dispositivo (ej. "PC0").
     * @param deviceB nombre del segundo dispositivo (ej. "Switch0").
     */
    public void autoConnect(String deviceA, String deviceB) {
        if (deviceA == null || deviceA.isEmpty()) throw new IllegalArgumentException("deviceA es obligatorio.");
        if (deviceB == null || deviceB.isEmpty()) throw new IllegalArgumentException("deviceB es obligatorio.");
        logicalWorkspace().autoConnectDevices(deviceA, deviceB);
    }

    /**
     * Borra el enlace conectado a una interfaz de un dispositivo, sin tocar los
     * dispositivos. Util para recablear una topologia existente.
     *
     * @param deviceName nombre del dispositivo (ej. "Router0").
     * @param ifaceName  interfaz cuyo cable se quita (ej. "GigabitEthernet0/0").
     * @return true si PT borro un enlace; false si esa interfaz no tenia cable.
     */
    public boolean deleteLink(String deviceName, String ifaceName) {
        if (deviceName == null || deviceName.isEmpty()) throw new IllegalArgumentException("device es obligatorio.");
        if (ifaceName == null || ifaceName.isEmpty()) throw new IllegalArgumentException("iface es obligatorio.");
        return logicalWorkspace().deleteLink(deviceName, ifaceName);
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
     * Enciende o apaga un dispositivo (cualquier tipo: router, switch, PC, ...).
     * Equivale al boton de power del dispositivo: lo reinicia sin borrarlo ni su
     * configuracion. Apagar y volver a encender un router dispara de nuevo el
     * arranque simulado (~30-60s); recordar pt_skip_boot tras encenderlo.
     *
     * @param name nombre del dispositivo (ej. "Router0").
     * @param on   true para encender, false para apagar.
     * @return el estado leido de vuelta desde PT tras aplicar el cambio.
     */
    public PowerResult setPower(String name, boolean on) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name es obligatorio.");
        }
        Device d = network().getDevice(name);
        if (d == null) {
            throw new IllegalArgumentException("No existe dispositivo '" + name + "'.");
        }
        d.setPower(on);
        return new PowerResult(name, d.getPower());
    }

    /**
     * Lista los tipos de dispositivo disponibles en el catalogo de PT
     * (los strings validos para pasar a {@link #getDeviceModels}). El formato
     * exacto lo decide PT (ej. "Router", "Switch", "PC", ...).
     */
    public List<String> getDeviceTypes() {
        return dedup(networkComponentBox().getDeviceTypes());
    }

    /**
     * Lista los modelos disponibles para un tipo de dispositivo (ej. para
     * "Routers" suele incluir "2911"). El {@code type} debe ser uno de los
     * valores EXACTOS que devuelve {@link #getDeviceTypes} (ej. "Routers", con
     * mayuscula y plural). El flujo normal es: pedir los tipos primero y copiar
     * el valor tal cual.
     */
    public List<String> getDeviceModels(String type) {
        if (type == null || type.isEmpty()) {
            throw new IllegalArgumentException("type es obligatorio.");
        }
        return dedup(networkComponentBox().getDeviceModels(type));
    }

    /**
     * Quita duplicados preservando el orden de aparicion. El catalogo de PT a
     * veces repite una entrada (ej. el modelo 2811 aparece al inicio y al final);
     * con esto la lista que devolvemos es limpia.
     */
    private static List<String> dedup(List<String> items) {
        return new ArrayList<>(new java.util.LinkedHashSet<>(items));
    }

    /**
     * Guarda la topologia actual en un archivo .pkt. La ruta es del lado de la
     * maquina donde corre Packet Tracer (en este proyecto, la misma). Usar ruta
     * absoluta.
     *
     * @param path ruta destino (ej. "C:\\redes\\lab1.pkt").
     * @return true si PT confirma el guardado.
     */
    public boolean saveFile(String path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("path es obligatorio.");
        }
        return appWindow().fileSaveAs(path);
    }

    /**
     * Abre un archivo .pkt en Packet Tracer, reemplazando la topologia actual.
     * La ruta es del lado de la maquina donde corre PT. Usar ruta absoluta.
     *
     * @param path ruta del archivo a abrir.
     * @return el nombre del codigo de retorno de PT: "FILE_RETURN_OK" si abrio
     *         bien, o un codigo de error (ej. "FILE_RETURN_UNABLE_TO_READ_FILE").
     */
    public String openFile(String path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("path es obligatorio.");
        }
        FileOpenReturnValue result = appWindow().fileOpen(path);
        return result == null ? "FILE_RETURN_UNEXPECTED_FORMAT" : result.name();
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
     * Configura el servidor DNS de una interfaz de un end-device (PC, Laptop,
     * Server). Equivale al campo "DNS Server" de la pestana IP Configuration.
     * No toca IP/mask/gateway ni el flag de DHCP.
     *
     * @param deviceName nombre del end-device (ej. "PC0").
     * @param ifaceName  interfaz a configurar (ej. "FastEthernet0").
     * @param dns        IP del servidor DNS (ej. "8.8.8.8").
     */
    public EndpointDnsResult setEndpointDns(String deviceName, String ifaceName, String dns) {
        if (dns == null || dns.isEmpty()) throw new IllegalArgumentException("dns es obligatorio.");
        HostBinding hb = resolveHostBinding(deviceName, ifaceName);
        hb.port.setDnsServerIp(new IPAddressImpl(dns));
        return new EndpointDnsResult(deviceName, ifaceName, dns);
    }

    /**
     * Lee la configuracion IP de una interfaz de un end-device (PC, Laptop,
     * Server) para verificarla. Solo lectura.
     *
     * Limitacion del SDK: el framework de Cisco NO expone getters para el
     * gateway ni el DNS de un HostPort (solo setters), asi que esos campos se
     * devuelven null. Lo IP/mask y el estado de DHCP si se pueden leer.
     *
     * @param deviceName nombre del end-device (ej. "PC0").
     * @param ifaceName  interfaz a consultar (ej. "FastEthernet0").
     */
    public EndpointConfigResult getEndpointConfig(String deviceName, String ifaceName) {
        HostBinding hb = resolveHostBinding(deviceName, ifaceName);
        String ip = hb.port.getIpAddress() == null ? null : hb.port.getIpAddress().toString();
        String mask = hb.port.getSubnetMask() == null ? null : hb.port.getSubnetMask().toString();
        boolean dhcp = hb.pc.getDhcpFlag() || hb.port.isDhcpClientOn();
        String note = "gateway y dns son null: el SDK de Cisco no expone getters para esos campos "
                + "(solo se pueden escribir con pt_set_endpoint_ip / pt_set_endpoint_dns). "
                + "OJO con ip/mask cuando dhcp=true: PT conserva el ultimo valor estatico en el "
                + "campo aunque DHCP este activo (el getter no se limpia solo), y esa ip puede NO "
                + "ser la que el DHCP asigne. Para saber el modo, fiarse del campo dhcp, no de ip.";
        return new EndpointConfigResult(deviceName, ifaceName, ip, mask, dhcp, null, null, note);
    }

    /**
     * Lista el arbol de modulos de un dispositivo (router, switch, ...). Util
     * para ver que bahias hay libres antes de instalar un modulo (ej. un HWIC-2T
     * serial en un 2911) con pt_add_module. Solo lectura.
     *
     * La estructura es un arbol: el modulo raiz suele tener un solo slot
     * "no-removible" (la placa madre), y las bahias HWIC/NM reales cuelgan de
     * ese submodulo. Por eso se recorre recursivamente. Cada nodo lleva su
     * nombre, slotPath, tipo, la lista de sus slots (index + tipo) y la lista de
     * sus submodulos instalados.
     */
    /**
     * Lista los modelos de modulo que ese dispositivo admite (los mismos que
     * aparecen en el navegador de modulos de la GUI). El agente usa esta lista
     * para descubrir el string EXACTO de modelo antes de llamar a addModule.
     * Aplica a routers/switches (HWIC, NM, ...) y tambien a end-devices (la NIC
     * swappable de una PC: Ethernet vs. WiFi).
     */
    public List<String> getSupportedModules(String deviceName) {
        Device d = requireDevice(deviceName);
        List<String> mods = tryGet(d::getSupportedModule, null);
        return mods == null ? new ArrayList<>() : mods;
    }

    /**
     * Instala un modulo en una bahia del dispositivo. Mapea 1-a-1 a
     * {@code Device.addModule(slot, ModuleType, model)}.
     *
     * <p>OJO: PT exige el dispositivo APAGADO para tocar modulos fisicos
     * (llamar pt_power(device,false) antes, y pt_power(device,true)+pt_skip_boot
     * despues). Si la bahia ya esta ocupada, addModule devuelve false: para
     * cambiar un modulo (ej. Ethernet->WiFi en una PC) hay que removeModule primero.
     *
     * @param deviceName dispositivo (ej. "Router0", "PC0").
     * @param slot       bahia destino (string del SDK, ej. "0/0" en un 2911, "0"
     *                   en la NIC de una PC). Ver getModules para inspeccionar.
     * @param type       categoria del modulo (enum ModuleType, case-insensitive):
     *                   INTERFACE_CARD para HWIC/NIC, NETWORK_MODULE para NM, etc.
     * @param model      nombre del modelo (ej. "HWIC-2T"). Debe salir de
     *                   getSupportedModules.
     * @return true si PT confirma la instalacion.
     */
    public boolean addModule(String deviceName, String slot, String type, String model) {
        if (slot == null || slot.isEmpty()) throw new IllegalArgumentException("slot es obligatorio.");
        if (type == null || type.isEmpty()) throw new IllegalArgumentException("type es obligatorio.");
        if (model == null || model.isEmpty()) throw new IllegalArgumentException("model es obligatorio.");
        Device d = requireDevice(deviceName);
        return d.addModule(slot, parseModuleType(type), model);
    }

    /**
     * Quita el modulo de una bahia. Mapea 1-a-1 a {@code Device.removeModule(slot)}.
     * Mismo requisito de apagado que addModule. Necesario para cambiar un modulo
     * por otro (quitar el existente antes de instalar el nuevo).
     *
     * @param slot bahia a vaciar (mismo string que addModule).
     * @return true si PT confirma el retiro (false si la bahia ya estaba vacia o
     *         no se pudo quitar).
     */
    public boolean removeModule(String deviceName, String slot) {
        if (slot == null || slot.isEmpty()) throw new IllegalArgumentException("slot es obligatorio.");
        Device d = requireDevice(deviceName);
        return d.removeModule(slot);
    }

    public ModulesResult getModules(String deviceName) {
        if (deviceName == null || deviceName.isEmpty()) {
            throw new IllegalArgumentException("device es obligatorio.");
        }
        Device d = network().getDevice(deviceName);
        if (d == null) {
            throw new IllegalArgumentException("No existe dispositivo '" + deviceName + "'.");
        }
        com.cisco.pt.ipc.sim.Module root = d.getRootModule();
        ModuleNode tree = root == null ? null : buildModuleNode(root, 0);
        return new ModulesResult(deviceName, tree);
    }

    // Profundidad maxima al recorrer el arbol de modulos. Un 2911 son ~2-3
    // niveles; el tope evita cualquier ciclo patologico del SDK.
    private static final int MAX_MODULE_DEPTH = 8;

    private static ModuleNode buildModuleNode(com.cisco.pt.ipc.sim.Module m, int depth) {
        // CADA getter puede reventar con "Making call on null IPC Object":
        //  - en una bahia VACIA, el proxy entero es nulo y truenan todos.
        //  - en un contenedor (ej. el modulo raiz) algunos getters como
        //    getModuleType() truenan aunque getSlotCount() si funcione.
        // Por eso se lee todo defensivamente y luego se decide si el nodo
        // vale la pena o es una bahia vacia que se descarta.
        String name = tryGet(m::getModuleNameAsString, null);
        String slotPath = tryGet(m::getSlotPath, null);
        String type = tryGet(() -> {
            var mt = m.getModuleType();
            return mt == null ? null : mt.name();
        }, null);

        List<SlotInfo> slots = new ArrayList<>();
        int slotCount = tryGet(m::getSlotCount, 0);
        for (int i = 0; i < slotCount; i++) {
            final int idx = i;
            String st = tryGet(() -> {
                var t = m.getSlotTypeAt(idx);
                return t == null ? null : t.name();
            }, null);
            slots.add(new SlotInfo(i, st));
        }

        List<ModuleNode> children = new ArrayList<>();
        if (depth < MAX_MODULE_DEPTH) {
            int modCount = tryGet(m::getModuleCount, 0);
            for (int i = 0; i < modCount; i++) {
                final int idx = i;
                com.cisco.pt.ipc.sim.Module child = tryGet(() -> m.getModuleAt(idx), null);
                if (child == null) continue;
                ModuleNode childNode = buildModuleNode(child, depth + 1);
                if (isEmptyBay(childNode)) continue; // bahia sin tarjeta
                children.add(childNode);
            }
        }
        return new ModuleNode(name, slotPath, type, slots, children);
    }

    /** Una bahia vacia: su proxy IPC es nulo, asi que no pudimos leer nada. */
    private static boolean isEmptyBay(ModuleNode n) {
        return n.name == null && n.type == null && n.slots.isEmpty() && n.modules.isEmpty();
    }

    /**
     * Ejecuta un getter del SDK devolviendo {@code def} si lanza. OJO: el SDK de
     * Cisco lanza {@code com.cisco.pt.ipc.IPCError} al tocar un objeto IPC nulo
     * (bahia vacia / contenedor sin tipo), y esa clase extiende
     * {@code java.lang.Error}, NO RuntimeException. Por eso aqui se captura
     * {@code IPCError} explicitamente ademas de RuntimeException.
     */
    private static <T> T tryGet(java.util.function.Supplier<T> getter, T def) {
        try {
            return getter.get();
        } catch (com.cisco.pt.ipc.IPCError | RuntimeException e) {
            return def;
        }
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

    private static ModuleType parseModuleType(String raw) {
        String normalized = raw.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        try {
            return ModuleType.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Tipo de modulo desconocido: '" + raw + "'. "
                            + "Valores comunes: INTERFACE_CARD (HWIC/NIC), NETWORK_MODULE (NM), "
                            + "LINE_CARD, SFP_MODULE, ...");
        }
    }

    /** Resuelve un dispositivo por nombre o lanza IllegalArgumentException claro. */
    private Device requireDevice(String deviceName) {
        if (deviceName == null || deviceName.isEmpty()) {
            throw new IllegalArgumentException("device es obligatorio.");
        }
        Device d = network().getDevice(deviceName);
        if (d == null) {
            throw new IllegalArgumentException("No existe dispositivo '" + deviceName + "'.");
        }
        return d;
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

    AppWindow appWindow() {
        requireConnected();
        return ipc.appWindow();
    }

    NetworkComponentBox networkComponentBox() {
        return appWindow().getNetworkComponentBox();
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

    public static final class LinkInfo {
        public final String devA;
        public final String ifA;
        public final String devB;       // null si solo se observo un extremo
        public final String ifB;        // null si solo se observo un extremo
        public final String cableType;  // tipo de cable (ConnectType); null si PT no lo da

        public LinkInfo(String devA, String ifA, String devB, String ifB, String cableType) {
            this.devA = devA;
            this.ifA = ifA;
            this.devB = devB;
            this.ifB = ifB;
            this.cableType = cableType;
        }

        @Override
        public String toString() {
            String b = devB == null ? "(?)" : devB + ":" + ifB;
            return devA + ":" + ifA + "  <-->  " + b + "  [" + cableType + "]";
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

    public static final class PowerResult {
        public final String device;
        public final boolean powered;

        public PowerResult(String device, boolean powered) {
            this.device = device;
            this.powered = powered;
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

    public static final class EndpointDnsResult {
        public final String device;
        public final String iface;
        public final String dns;

        public EndpointDnsResult(String device, String iface, String dns) {
            this.device = device;
            this.iface = iface;
            this.dns = dns;
        }
    }

    public static final class EndpointConfigResult {
        public final String device;
        public final String iface;
        public final String ip;        // null si no tiene IP asignada
        public final String mask;      // null si no tiene mascara
        public final boolean dhcp;
        public final String gateway;   // siempre null: el SDK no expone getter
        public final String dns;       // siempre null: el SDK no expone getter
        public final String note;

        public EndpointConfigResult(String device, String iface, String ip, String mask,
                                    boolean dhcp, String gateway, String dns, String note) {
            this.device = device;
            this.iface = iface;
            this.ip = ip;
            this.mask = mask;
            this.dhcp = dhcp;
            this.gateway = gateway;
            this.dns = dns;
            this.note = note;
        }
    }

    public static final class SlotInfo {
        public final int index;
        public final String type;   // tipo de slot (ej. HWIC, NM, ...); null si PT no lo da

        public SlotInfo(int index, String type) {
            this.index = index;
            this.type = type;
        }
    }

    /** Nodo del arbol de modulos: un modulo con sus slots y sus submodulos. */
    public static final class ModuleNode {
        public final String name;            // nombre del modulo (ej. "HWIC-2T", "None" para la placa)
        public final String slotPath;        // bahia donde esta instalado ("" para la raiz)
        public final String type;            // ModuleType del modulo
        public final List<SlotInfo> slots;   // bahias propias de este modulo
        public final List<ModuleNode> modules; // submodulos instalados en este modulo

        public ModuleNode(String name, String slotPath, String type,
                          List<SlotInfo> slots, List<ModuleNode> modules) {
            this.name = name;
            this.slotPath = slotPath;
            this.type = type;
            this.slots = slots;
            this.modules = modules;
        }
    }

    public static final class ModulesResult {
        public final String device;
        public final ModuleNode root;   // null si el dispositivo no tiene modulo raiz

        public ModulesResult(String device, ModuleNode root) {
            this.device = device;
            this.root = root;
        }
    }
}

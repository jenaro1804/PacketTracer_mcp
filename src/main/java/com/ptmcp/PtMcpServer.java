package com.ptmcp;

import com.ptmcp.PtIpcClient.CliResult;
import com.ptmcp.PtIpcClient.DeviceInfo;
import com.ptmcp.PtIpcClient.EndpointDhcpResult;
import com.ptmcp.PtIpcClient.EndpointIpResult;
import com.ptmcp.PtIpcClient.LinkInfo;
import com.ptmcp.PtIpcClient.PowerResult;
import com.ptmcp.PtIpcClient.Topology;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Servidor MCP que expone las operaciones de Packet Tracer como tools.
 *
 * Transporte: stdio. CRITICO: nada en stdout salvo el protocolo JSON-RPC del
 * SDK MCP. Todos los logs (los nuestros y los del framework Cisco) deben ir
 * a stderr; eso se configura via slf4j-simple en simplelogger.properties.
 */
public final class PtMcpServer {

    private static final String SERVER_NAME = "pt-mcp";
    private static final String SERVER_VERSION = "0.1.0";

    public static void main(String[] args) throws Exception {
        ConnectionManager conn = new ConnectionManager();
        // Shutdown hook: cerrar la sesion con PT si Claude/MCP nos mata.
        Runtime.getRuntime().addShutdownHook(new Thread(conn::close, "pt-mcp-shutdown"));

        McpJsonMapper jsonMapper = new JacksonMcpJsonMapperSupplier().get();
        StdioServerTransportProvider transport = new StdioServerTransportProvider(jsonMapper);

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .jsonMapper(jsonMapper)
                .capabilities(ServerCapabilities.builder().tools(true).build())
                .tools(buildTools(jsonMapper, conn))
                .build();

        // El server queda escuchando en stdio en threads del SDK; el main
        // bloquea hasta que el shutdown hook lo cierre.
        Thread.currentThread().join();
        // (no llega aqui salvo que algo interrumpa; el cierre va por hook)
        server.closeGracefully();
    }

    // ====== definicion de tools ======

    private static List<SyncToolSpecification> buildTools(io.modelcontextprotocol.json.McpJsonMapper jm, ConnectionManager conn) {
        return List.of(
                tool(jm, "pt_get_topology",
                        "Lista los dispositivos y el numero de enlaces actualmente en el lienzo de Packet Tracer.",
                        "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}",
                        (ex, req) -> handleGetTopology(conn)),

                tool(jm, "pt_get_links",
                        "Lista los enlaces (cables) actuales con sus dos extremos: dispositivo e "
                                + "interfaz de cada lado y el tipo de cable. Complementa a pt_get_topology, "
                                + "que solo da el numero de enlaces. dev_b/if_b pueden venir null si solo se "
                                + "detecto un extremo (p.ej. enlaces inalambricos).",
                        "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}",
                        (ex, req) -> handleGetLinks(conn)),

                tool(jm, "pt_add_device",
                        "Crea un dispositivo en el lienzo logico. PT asigna el nombre y lo devuelve. "
                                + "El argumento 'model' debe existir en el catalogo de PT para ese tipo "
                                + "(ej. router 2911, switch 2960, pc PC-PT).",
                        "{\"type\":\"object\","
                                + "\"properties\":{"
                                + "\"type\":{\"type\":\"string\",\"description\":\"Tipo: router, switch, pc, server, laptop, hub, access_point, wireless_router, multi_layer_switch, asa, ...\"},"
                                + "\"model\":{\"type\":\"string\",\"description\":\"Modelo concreto (ej. 2911, 2960, PC-PT).\"},"
                                + "\"x\":{\"type\":\"number\",\"description\":\"Coordenada X en el lienzo.\"},"
                                + "\"y\":{\"type\":\"number\",\"description\":\"Coordenada Y en el lienzo.\"}"
                                + "},"
                                + "\"required\":[\"type\",\"model\",\"x\",\"y\"],"
                                + "\"additionalProperties\":false}",
                        (ex, req) -> handleAddDevice(conn, req.arguments())),

                tool(jm, "pt_delete_device",
                        "Elimina un dispositivo por nombre. Tambien borra sus enlaces.",
                        "{\"type\":\"object\","
                                + "\"properties\":{\"name\":{\"type\":\"string\"}},"
                                + "\"required\":[\"name\"],\"additionalProperties\":false}",
                        (ex, req) -> handleDeleteDevice(conn, req.arguments())),

                tool(jm, "pt_get_ports",
                        "Lista las interfaces/puertos disponibles en un dispositivo (ej. GigabitEthernet0/0).",
                        "{\"type\":\"object\","
                                + "\"properties\":{\"name\":{\"type\":\"string\"}},"
                                + "\"required\":[\"name\"],\"additionalProperties\":false}",
                        (ex, req) -> handleGetPorts(conn, req.arguments())),

                tool(jm, "pt_connect_devices",
                        "Tiende un cable entre dos interfaces de dos dispositivos.",
                        "{\"type\":\"object\","
                                + "\"properties\":{"
                                + "\"device_a\":{\"type\":\"string\"},"
                                + "\"iface_a\":{\"type\":\"string\",\"description\":\"Ej. GigabitEthernet0/0\"},"
                                + "\"device_b\":{\"type\":\"string\"},"
                                + "\"iface_b\":{\"type\":\"string\"},"
                                + "\"cable_type\":{\"type\":\"string\",\"description\":\"ethernet_straight, ethernet_cross, fiber_singlemode, fiber_multimode, serial, console, coaxial, wireless, auto, ...\"}"
                                + "},"
                                + "\"required\":[\"device_a\",\"iface_a\",\"device_b\",\"iface_b\",\"cable_type\"],"
                                + "\"additionalProperties\":false}",
                        (ex, req) -> handleConnectDevices(conn, req.arguments())),

                tool(jm, "pt_auto_connect",
                        "Conecta dos dispositivos dejando que Packet Tracer elija las interfaces y el "
                                + "tipo de cable automaticamente (como el cable 'Automatico' de la GUI). "
                                + "Mas comodo que pt_connect_devices cuando no importan las interfaces concretas.",
                        "{\"type\":\"object\","
                                + "\"properties\":{"
                                + "\"device_a\":{\"type\":\"string\"},"
                                + "\"device_b\":{\"type\":\"string\"}"
                                + "},"
                                + "\"required\":[\"device_a\",\"device_b\"],"
                                + "\"additionalProperties\":false}",
                        (ex, req) -> handleAutoConnect(conn, req.arguments())),

                tool(jm, "pt_delete_link",
                        "Borra el cable conectado a una interfaz de un dispositivo, sin borrar los "
                                + "dispositivos. Util para recablear una topologia. Devuelve deleted=false "
                                + "(sin error) si esa interfaz no tenia cable.",
                        "{\"type\":\"object\","
                                + "\"properties\":{"
                                + "\"device\":{\"type\":\"string\"},"
                                + "\"iface\":{\"type\":\"string\",\"description\":\"Interfaz cuyo cable se quita (ej. GigabitEthernet0/0).\"}"
                                + "},"
                                + "\"required\":[\"device\",\"iface\"],"
                                + "\"additionalProperties\":false}",
                        (ex, req) -> handleDeleteLink(conn, req.arguments())),

                tool(jm, "pt_skip_boot",
                        "Salta el arranque simulado de un router/switch Cisco. Sin esto, los routers "
                                + "tardan ~30-60s en estar listos y los comandos IOS devuelven ERROR_INVALID. "
                                + "Llamar inmediatamente despues de pt_add_device para routers/switches.",
                        "{\"type\":\"object\","
                                + "\"properties\":{\"name\":{\"type\":\"string\"}},"
                                + "\"required\":[\"name\"],\"additionalProperties\":false}",
                        (ex, req) -> handleSkipBoot(conn, req.arguments())),

                tool(jm, "pt_power",
                        "Enciende o apaga un dispositivo (router, switch, PC, ...). Equivale al boton "
                                + "de power: lo reinicia sin borrarlo ni perder su configuracion. Apagar y "
                                + "reencender un router relanza el arranque (~30-60s); llamar pt_skip_boot "
                                + "despues de encenderlo.",
                        "{\"type\":\"object\","
                                + "\"properties\":{"
                                + "\"device\":{\"type\":\"string\",\"description\":\"Nombre del dispositivo (ej. Router0).\"},"
                                + "\"on\":{\"type\":\"boolean\",\"description\":\"true para encender, false para apagar.\"}"
                                + "},"
                                + "\"required\":[\"device\",\"on\"],"
                                + "\"additionalProperties\":false}",
                        (ex, req) -> handlePower(conn, req.arguments())),

                tool(jm, "pt_run_cli",
                        "Ejecuta un comando IOS en un dispositivo Cisco (router, switch, ASA). "
                                + "Modos: 'user', 'enable', 'global', o '' (modo actual). Llamadas "
                                + "consecutivas heredan el modo: tras 'interface gig0/0' en modo 'global', "
                                + "el siguiente comando con mode='' corre en interface-config.",
                        "{\"type\":\"object\","
                                + "\"properties\":{"
                                + "\"device\":{\"type\":\"string\"},"
                                + "\"command\":{\"type\":\"string\"},"
                                + "\"mode\":{\"type\":\"string\",\"description\":\"user, enable, global, o vacio para modo actual\",\"default\":\"\"}"
                                + "},"
                                + "\"required\":[\"device\",\"command\"],"
                                + "\"additionalProperties\":false}",
                        (ex, req) -> handleRunCli(conn, req.arguments())),

                tool(jm, "pt_set_endpoint_ip",
                        "Configura una IP estatica en una interfaz de un end-device (PC, Laptop, Server). "
                                + "Apaga DHCP automaticamente para que la IP estatica quede efectivamente "
                                + "aplicada. NO aplica a routers/switches: para esos usa pt_run_cli.",
                        "{\"type\":\"object\","
                                + "\"properties\":{"
                                + "\"device\":{\"type\":\"string\",\"description\":\"Nombre del end-device (ej. PC0).\"},"
                                + "\"iface\":{\"type\":\"string\",\"description\":\"Interfaz a configurar (ej. FastEthernet0).\"},"
                                + "\"ip\":{\"type\":\"string\",\"description\":\"IP estatica (ej. 192.168.1.10).\"},"
                                + "\"mask\":{\"type\":\"string\",\"description\":\"Mascara de subred (ej. 255.255.255.0).\"},"
                                + "\"gateway\":{\"type\":\"string\",\"description\":\"Gateway por defecto (ej. 192.168.1.1). Opcional.\"}"
                                + "},"
                                + "\"required\":[\"device\",\"iface\",\"ip\",\"mask\"],"
                                + "\"additionalProperties\":false}",
                        (ex, req) -> handleSetEndpointIp(conn, req.arguments())),

                tool(jm, "pt_set_endpoint_dhcp",
                        "Activa o desactiva DHCP en una interfaz de un end-device (PC, Laptop, Server). "
                                + "Operacion inversa de pt_set_endpoint_ip: con enabled=true el dispositivo "
                                + "vuelve a pedir IP por DHCP.",
                        "{\"type\":\"object\","
                                + "\"properties\":{"
                                + "\"device\":{\"type\":\"string\"},"
                                + "\"iface\":{\"type\":\"string\",\"description\":\"Interfaz (ej. FastEthernet0).\"},"
                                + "\"enabled\":{\"type\":\"boolean\",\"description\":\"true para activar DHCP, false para estatico.\"}"
                                + "},"
                                + "\"required\":[\"device\",\"iface\",\"enabled\"],"
                                + "\"additionalProperties\":false}",
                        (ex, req) -> handleSetEndpointDhcp(conn, req.arguments())),

                tool(jm, "pt_set_endpoint_dns",
                        "Configura el servidor DNS de una interfaz de un end-device (PC, Laptop, Server). "
                                + "Equivale al campo 'DNS Server' de IP Configuration. No toca IP/mask/gateway "
                                + "ni el flag de DHCP. NO aplica a routers/switches: para esos usa pt_run_cli.",
                        "{\"type\":\"object\","
                                + "\"properties\":{"
                                + "\"device\":{\"type\":\"string\",\"description\":\"Nombre del end-device (ej. PC0).\"},"
                                + "\"iface\":{\"type\":\"string\",\"description\":\"Interfaz a configurar (ej. FastEthernet0).\"},"
                                + "\"dns\":{\"type\":\"string\",\"description\":\"IP del servidor DNS (ej. 8.8.8.8).\"}"
                                + "},"
                                + "\"required\":[\"device\",\"iface\",\"dns\"],"
                                + "\"additionalProperties\":false}",
                        (ex, req) -> handleSetEndpointDns(conn, req.arguments())),

                tool(jm, "pt_get_endpoint_config",
                        "Lee la configuracion IP de una interfaz de un end-device (PC, Laptop, Server): "
                                + "ip, mascara y si esta en DHCP. LIMITACION del SDK: gateway y dns "
                                + "siempre vienen null (Cisco no expone getters para esos campos, solo "
                                + "setters). OJO: con dhcp=true PT conserva el ultimo ip/mask estatico en el "
                                + "campo (no se limpia) y puede no ser la ip real del DHCP; fiarse del campo "
                                + "dhcp para el modo. Solo lectura; aplica a PC/Laptop/Server.",
                        "{\"type\":\"object\","
                                + "\"properties\":{"
                                + "\"device\":{\"type\":\"string\",\"description\":\"Nombre del end-device (ej. PC0).\"},"
                                + "\"iface\":{\"type\":\"string\",\"description\":\"Interfaz a consultar (ej. FastEthernet0).\"}"
                                + "},"
                                + "\"required\":[\"device\",\"iface\"],"
                                + "\"additionalProperties\":false}",
                        (ex, req) -> handleGetEndpointConfig(conn, req.arguments())),

                tool(jm, "pt_get_modules",
                        "Devuelve el arbol de modulos de un dispositivo (router, switch, ...). Util para "
                                + "ver que bahias hay libres antes de instalar un modulo (ej. un HWIC-2T "
                                + "serial en un 2911) con pt_add_module. El campo 'root' es un arbol: cada "
                                + "nodo tiene name, slot_path, type, sus 'slots' (index + tipo) y sus "
                                + "'modules' (submodulos). Las bahias HWIC/NM suelen colgar de un submodulo "
                                + "no-removible, no de la raiz. Solo lectura.",
                        "{\"type\":\"object\","
                                + "\"properties\":{"
                                + "\"device\":{\"type\":\"string\",\"description\":\"Nombre del dispositivo (ej. Router0).\"}"
                                + "},"
                                + "\"required\":[\"device\"],"
                                + "\"additionalProperties\":false}",
                        (ex, req) -> handleGetModules(conn, req.arguments())),

                tool(jm, "pt_get_supported_modules",
                        "Lista los modelos de modulo que un dispositivo admite (los mismos del "
                                + "navegador de modulos de la GUI). Usar ANTES de pt_add_module para "
                                + "saber el string EXACTO de modelo a instalar. Aplica a routers/switches "
                                + "(HWIC, NM, ...) y a end-devices (la NIC swappable de una PC: Ethernet vs WiFi). "
                                + "Solo lectura.",
                        "{\"type\":\"object\","
                                + "\"properties\":{"
                                + "\"device\":{\"type\":\"string\",\"description\":\"Nombre del dispositivo (ej. Router0, PC0).\"}"
                                + "},"
                                + "\"required\":[\"device\"],"
                                + "\"additionalProperties\":false}",
                        (ex, req) -> handleGetSupportedModules(conn, req.arguments())),

                tool(jm, "pt_add_module",
                        "Instala un modulo en una bahia de un dispositivo (ej. un HWIC-2T serial en un "
                                + "2911, o una NIC WiFi en una PC). IMPORTANTE: PT exige el dispositivo APAGADO "
                                + "para tocar modulos fisicos: llamar pt_power(device,false) ANTES, y "
                                + "pt_power(device,true) + pt_skip_boot DESPUES. Si la bahia ya esta ocupada "
                                + "devuelve added=false: para cambiar un modulo (ej. Ethernet->WiFi en una PC) "
                                + "usar pt_remove_module primero. Descubrir el 'model' con pt_get_supported_modules "
                                + "y las bahias con pt_get_modules.",
                        "{\"type\":\"object\","
                                + "\"properties\":{"
                                + "\"device\":{\"type\":\"string\",\"description\":\"Dispositivo (ej. Router0, PC0).\"},"
                                + "\"slot\":{\"type\":\"string\",\"description\":\"Bahia destino (string del SDK, ej. '0/0' en un 2911, '0' en la NIC de una PC).\"},"
                                + "\"type\":{\"type\":\"string\",\"description\":\"Categoria del modulo: INTERFACE_CARD (HWIC/NIC), NETWORK_MODULE (NM), LINE_CARD, SFP_MODULE, ...\"},"
                                + "\"model\":{\"type\":\"string\",\"description\":\"Nombre del modelo (ej. HWIC-2T). Debe salir de pt_get_supported_modules.\"}"
                                + "},"
                                + "\"required\":[\"device\",\"slot\",\"type\",\"model\"],"
                                + "\"additionalProperties\":false}",
                        (ex, req) -> handleAddModule(conn, req.arguments())),

                tool(jm, "pt_remove_module",
                        "Quita el modulo de una bahia, dejandola libre. Mismo requisito de apagado que "
                                + "pt_add_module (pt_power off antes). Necesario para cambiar un modulo por otro "
                                + "(ej. quitar el Ethernet de una PC antes de instalarle una NIC WiFi). Devuelve "
                                + "removed=false (sin error) si la bahia ya estaba vacia.",
                        "{\"type\":\"object\","
                                + "\"properties\":{"
                                + "\"device\":{\"type\":\"string\",\"description\":\"Dispositivo (ej. Router0, PC0).\"},"
                                + "\"slot\":{\"type\":\"string\",\"description\":\"Bahia a vaciar (mismo string que pt_add_module).\"}"
                                + "},"
                                + "\"required\":[\"device\",\"slot\"],"
                                + "\"additionalProperties\":false}",
                        (ex, req) -> handleRemoveModule(conn, req.arguments())),

                tool(jm, "pt_save_file",
                        "Guarda la topologia actual en un archivo .pkt. La ruta es del lado de la "
                                + "maquina donde corre Packet Tracer; usar ruta absoluta (ej. C:\\\\redes\\\\lab1.pkt).",
                        "{\"type\":\"object\","
                                + "\"properties\":{"
                                + "\"path\":{\"type\":\"string\",\"description\":\"Ruta absoluta destino .pkt.\"}"
                                + "},"
                                + "\"required\":[\"path\"],"
                                + "\"additionalProperties\":false}",
                        (ex, req) -> handleSaveFile(conn, req.arguments())),

                tool(jm, "pt_open_file",
                        "Abre un archivo .pkt en Packet Tracer, reemplazando la topologia actual. "
                                + "La ruta es del lado de la maquina donde corre PT; usar ruta absoluta.",
                        "{\"type\":\"object\","
                                + "\"properties\":{"
                                + "\"path\":{\"type\":\"string\",\"description\":\"Ruta absoluta del .pkt a abrir.\"}"
                                + "},"
                                + "\"required\":[\"path\"],"
                                + "\"additionalProperties\":false}",
                        (ex, req) -> handleOpenFile(conn, req.arguments())),

                tool(jm, "pt_get_device_models",
                        "Lista el catalogo de PT para no adivinar el 'model' de pt_add_device. "
                                + "Sin 'type' devuelve los tipos de dispositivo disponibles; con 'type' "
                                + "(uno de esos valores EXACTOS, ej. 'Routers') devuelve los modelos de ese tipo (ej. 2911).",
                        "{\"type\":\"object\","
                                + "\"properties\":{"
                                + "\"type\":{\"type\":\"string\",\"description\":\"Tipo de dispositivo (uno de los que devuelve esta misma tool sin argumentos). Opcional.\"}"
                                + "},"
                                + "\"additionalProperties\":false}",
                        (ex, req) -> handleGetDeviceModels(conn, req.arguments()))
        );
    }

    private static SyncToolSpecification tool(
            McpJsonMapper jm, String name, String description, String schemaJson,
            BiFunction<io.modelcontextprotocol.server.McpSyncServerExchange,
                    McpSchema.CallToolRequest, CallToolResult> handler) {
        return SyncToolSpecification.builder()
                .tool(Tool.builder()
                        .name(name)
                        .description(description)
                        .inputSchema(jm, schemaJson)
                        .build())
                .callHandler(handler)
                .build();
    }

    // ====== handlers ======

    private static CallToolResult handleGetTopology(ConnectionManager conn) {
        try {
            Topology t = conn.withClient(PtIpcClient::getTopology);
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("link_count", t.linkCount);
            structured.put("devices", t.devices.stream().map(d -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", d.name);
                m.put("type", d.type);
                m.put("model", d.model);
                return m;
            }).collect(Collectors.toList()));

            String text = "Topologia: " + t.devices.size() + " dispositivos, " + t.linkCount + " enlaces.\n"
                    + t.devices.stream().map(d -> "  - " + d).collect(Collectors.joining("\n"));
            return ok(text, structured);
        } catch (Exception e) {
            return error("No se pudo leer la topologia: " + e.getMessage());
        }
    }

    private static CallToolResult handleGetLinks(ConnectionManager conn) {
        try {
            List<LinkInfo> links = conn.withClient(PtIpcClient::getLinks);
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("link_count", links.size());
            structured.put("links", links.stream().map(l -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("dev_a", l.devA);
                m.put("if_a", l.ifA);
                m.put("dev_b", l.devB);
                m.put("if_b", l.ifB);
                m.put("cable_type", l.cableType);
                return m;
            }).collect(Collectors.toList()));

            String text = "Enlaces: " + links.size() + "\n"
                    + links.stream().map(l -> "  - " + l).collect(Collectors.joining("\n"));
            return ok(text, structured);
        } catch (Exception e) {
            return error("No se pudo leer el cableado: " + e.getMessage());
        }
    }

    private static CallToolResult handleAddDevice(ConnectionManager conn, Map<String, Object> args) {
        try {
            String type = reqStr(args, "type");
            String model = reqStr(args, "model");
            double x = reqNum(args, "x");
            double y = reqNum(args, "y");
            String name = conn.withClient(c -> c.addDevice(type, model, x, y));
            Map<String, Object> structured = Map.of("name", name);
            return ok("Creado: " + name + " (" + type + " " + model + ") en (" + x + ", " + y + ").",
                    structured);
        } catch (Exception e) {
            return error("addDevice fallo: " + e.getMessage());
        }
    }

    private static CallToolResult handleDeleteDevice(ConnectionManager conn, Map<String, Object> args) {
        try {
            String name = reqStr(args, "name");
            boolean removed = conn.withClient(c -> c.deleteDevice(name));
            return ok("deleteDevice(" + name + ") -> " + removed, Map.of("removed", removed));
        } catch (Exception e) {
            return error("deleteDevice fallo: " + e.getMessage());
        }
    }

    private static CallToolResult handleGetPorts(ConnectionManager conn, Map<String, Object> args) {
        try {
            String name = reqStr(args, "name");
            List<String> ports = conn.withClient(c -> c.getPorts(name));
            String text = "Puertos de " + name + " (" + ports.size() + "):\n"
                    + ports.stream().map(p -> "  - " + p).collect(Collectors.joining("\n"));
            return ok(text, Map.of("ports", ports));
        } catch (Exception e) {
            return error("getPorts fallo: " + e.getMessage());
        }
    }

    private static CallToolResult handleConnectDevices(ConnectionManager conn, Map<String, Object> args) {
        try {
            String da = reqStr(args, "device_a");
            String ia = reqStr(args, "iface_a");
            String db = reqStr(args, "device_b");
            String ib = reqStr(args, "iface_b");
            String ct = reqStr(args, "cable_type");
            boolean linked = conn.withClient(c -> c.connectDevices(da, ia, db, ib, ct));
            return ok("connectDevices(" + da + ":" + ia + " <-> " + db + ":" + ib + ", " + ct + ") -> " + linked,
                    Map.of("linked", linked));
        } catch (Exception e) {
            return error("connectDevices fallo: " + e.getMessage());
        }
    }

    private static CallToolResult handleAutoConnect(ConnectionManager conn, Map<String, Object> args) {
        try {
            String da = reqStr(args, "device_a");
            String db = reqStr(args, "device_b");
            conn.withClientVoid(c -> c.autoConnect(da, db));
            return ok("autoConnect(" + da + " <-> " + db + ") -> ok", Map.of("ok", true));
        } catch (Exception e) {
            return error("autoConnect fallo: " + e.getMessage());
        }
    }

    private static CallToolResult handleDeleteLink(ConnectionManager conn, Map<String, Object> args) {
        try {
            String device = reqStr(args, "device");
            String iface = reqStr(args, "iface");
            boolean deleted = conn.withClient(c -> c.deleteLink(device, iface));
            String text = deleted
                    ? "Cable borrado en " + device + ":" + iface + "."
                    : "La interfaz " + device + ":" + iface + " no tenia cable (nada que borrar).";
            return ok(text, Map.of("deleted", deleted));
        } catch (Exception e) {
            return error("deleteLink fallo: " + e.getMessage());
        }
    }

    private static CallToolResult handleSkipBoot(ConnectionManager conn, Map<String, Object> args) {
        try {
            String name = reqStr(args, "name");
            boolean ok = conn.withClient(c -> {
                boolean r = c.skipBoot(name);
                c.waitForBoot(name, 30_000);
                return r;
            });
            return ok("skipBoot(" + name + ") -> " + ok + " (booteo finalizado o no aplica)",
                    Map.of("applied", ok));
        } catch (Exception e) {
            return error("skipBoot fallo: " + e.getMessage());
        }
    }

    private static CallToolResult handlePower(ConnectionManager conn, Map<String, Object> args) {
        try {
            String device = reqStr(args, "device");
            boolean on = reqBool(args, "on");
            PowerResult r = conn.withClient(c -> c.setPower(device, on));
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("device", r.device);
            structured.put("powered", r.powered);
            String text = r.device + " ahora esta " + (r.powered ? "encendido" : "apagado") + ".";
            return ok(text, structured);
        } catch (Exception e) {
            return error("power fallo: " + e.getMessage());
        }
    }

    private static CallToolResult handleRunCli(ConnectionManager conn, Map<String, Object> args) {
        try {
            String device = reqStr(args, "device");
            String command = reqStr(args, "command");
            String mode = optStr(args, "mode", "");
            CliResult r = conn.withClient(c -> c.runCli(device, command, mode));
            String text = "[" + r.status + "] " + (mode.isEmpty() ? "(current)" : mode) + " $ " + command + "\n"
                    + (r.output == null ? "" : r.output);
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("status", r.status);
            structured.put("output", r.output == null ? "" : r.output);
            return CallToolResult.builder()
                    .addTextContent(text)
                    .structuredContent(structured)
                    .isError(!r.isOk())
                    .build();
        } catch (Exception e) {
            return error("runCli fallo: " + e.getMessage());
        }
    }

    private static CallToolResult handleSetEndpointIp(ConnectionManager conn, Map<String, Object> args) {
        try {
            String device = reqStr(args, "device");
            String iface = reqStr(args, "iface");
            String ip = reqStr(args, "ip");
            String mask = reqStr(args, "mask");
            String gateway = optStr(args, "gateway", "");
            EndpointIpResult r = conn.withClient(c -> c.setEndpointIp(device, iface, ip, mask, gateway));
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("device", r.device);
            structured.put("iface", r.iface);
            structured.put("ip", r.ip);
            structured.put("mask", r.mask);
            structured.put("gateway", r.gateway);
            structured.put("dhcp_disabled", r.dhcpDisabled);
            String text = "IP estatica aplicada en " + r.device + ":" + r.iface
                    + " -> " + r.ip + " / " + r.mask
                    + (r.gateway == null ? "" : " gw " + r.gateway)
                    + " (DHCP desactivado).";
            return ok(text, structured);
        } catch (Exception e) {
            return error("setEndpointIp fallo: " + e.getMessage());
        }
    }

    private static CallToolResult handleSetEndpointDhcp(ConnectionManager conn, Map<String, Object> args) {
        try {
            String device = reqStr(args, "device");
            String iface = reqStr(args, "iface");
            boolean enabled = reqBool(args, "enabled");
            EndpointDhcpResult r = conn.withClient(c -> c.setEndpointDhcp(device, iface, enabled));
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("device", r.device);
            structured.put("iface", r.iface);
            structured.put("dhcp_enabled", r.dhcpEnabled);
            String text = "DHCP " + (r.dhcpEnabled ? "activado" : "desactivado")
                    + " en " + r.device + ":" + r.iface + ".";
            return ok(text, structured);
        } catch (Exception e) {
            return error("setEndpointDhcp fallo: " + e.getMessage());
        }
    }

    private static CallToolResult handleSetEndpointDns(ConnectionManager conn, Map<String, Object> args) {
        try {
            String device = reqStr(args, "device");
            String iface = reqStr(args, "iface");
            String dns = reqStr(args, "dns");
            PtIpcClient.EndpointDnsResult r = conn.withClient(c -> c.setEndpointDns(device, iface, dns));
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("device", r.device);
            structured.put("iface", r.iface);
            structured.put("dns", r.dns);
            structured.put("ok", true);
            String text = "DNS " + r.dns + " aplicado en " + r.device + ":" + r.iface + ".";
            return ok(text, structured);
        } catch (Exception e) {
            return error("setEndpointDns fallo: " + e.getMessage());
        }
    }

    private static CallToolResult handleGetEndpointConfig(ConnectionManager conn, Map<String, Object> args) {
        try {
            String device = reqStr(args, "device");
            String iface = reqStr(args, "iface");
            PtIpcClient.EndpointConfigResult r = conn.withClient(c -> c.getEndpointConfig(device, iface));
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("device", r.device);
            structured.put("iface", r.iface);
            structured.put("ip", r.ip);
            structured.put("mask", r.mask);
            structured.put("dhcp", r.dhcp);
            structured.put("gateway", r.gateway);
            structured.put("dns", r.dns);
            structured.put("note", r.note);
            String text = r.device + ":" + r.iface + " -> "
                    + (r.dhcp ? "DHCP" : "estatico")
                    + ", ip=" + r.ip + ", mask=" + r.mask
                    + " (gateway/dns no legibles via SDK).";
            return ok(text, structured);
        } catch (Exception e) {
            return error("getEndpointConfig fallo: " + e.getMessage());
        }
    }

    private static CallToolResult handleGetSupportedModules(ConnectionManager conn, Map<String, Object> args) {
        try {
            String device = reqStr(args, "device");
            List<String> models = conn.withClient(c -> c.getSupportedModules(device));
            String text = "Modulos soportados por " + device + " (" + models.size() + "):\n"
                    + models.stream().map(m -> "  - " + m).collect(Collectors.joining("\n"));
            return ok(text, Map.of("device", device, "modules", models));
        } catch (Exception e) {
            return error("getSupportedModules fallo: " + e.getMessage());
        }
    }

    private static CallToolResult handleAddModule(ConnectionManager conn, Map<String, Object> args) {
        try {
            String device = reqStr(args, "device");
            String slot = reqStr(args, "slot");
            String type = reqStr(args, "type");
            String model = reqStr(args, "model");
            boolean added = conn.withClient(c -> c.addModule(device, slot, type, model));
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("device", device);
            structured.put("slot", slot);
            structured.put("model", model);
            structured.put("added", added);
            String text = "addModule(" + device + ", slot=" + slot + ", " + model + ") -> " + added
                    + (added ? "" : " (¿bahia ocupada o slot/model invalido? El dispositivo debe estar apagado.)");
            return ok(text, structured);
        } catch (Exception e) {
            return error("addModule fallo: " + e.getMessage());
        }
    }

    private static CallToolResult handleRemoveModule(ConnectionManager conn, Map<String, Object> args) {
        try {
            String device = reqStr(args, "device");
            String slot = reqStr(args, "slot");
            boolean removed = conn.withClient(c -> c.removeModule(device, slot));
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("device", device);
            structured.put("slot", slot);
            structured.put("removed", removed);
            return ok("removeModule(" + device + ", slot=" + slot + ") -> " + removed, structured);
        } catch (Exception e) {
            return error("removeModule fallo: " + e.getMessage());
        }
    }

    private static CallToolResult handleGetModules(ConnectionManager conn, Map<String, Object> args) {
        try {
            String device = reqStr(args, "device");
            PtIpcClient.ModulesResult r = conn.withClient(c -> c.getModules(device));
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("device", r.device);
            structured.put("root", r.root == null ? null : moduleNodeToMap(r.root));

            StringBuilder sb = new StringBuilder();
            sb.append(r.device).append(":\n");
            if (r.root == null) {
                sb.append("  (sin modulo raiz)");
            } else {
                appendModuleNode(sb, r.root, 1);
            }
            return ok(sb.toString().stripTrailing(), structured);
        } catch (Exception e) {
            return error("getModules fallo: " + e.getMessage());
        }
    }

    private static Map<String, Object> moduleNodeToMap(PtIpcClient.ModuleNode n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", n.name);
        m.put("slot_path", n.slotPath);
        m.put("type", n.type);
        m.put("slots", n.slots.stream().map(s -> {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("index", s.index);
            sm.put("type", s.type);
            return sm;
        }).collect(Collectors.toList()));
        m.put("modules", n.modules.stream()
                .map(PtMcpServer::moduleNodeToMap)
                .collect(Collectors.toList()));
        return m;
    }

    private static void appendModuleNode(StringBuilder sb, PtIpcClient.ModuleNode n, int depth) {
        String pad = "  ".repeat(depth);
        sb.append(pad).append(n.name).append(" [").append(n.type).append("]")
                .append(n.slotPath == null || n.slotPath.isEmpty() ? "" : " @ " + n.slotPath)
                .append("\n");
        for (PtIpcClient.SlotInfo s : n.slots) {
            sb.append(pad).append("  slot[").append(s.index).append("] ").append(s.type).append("\n");
        }
        for (PtIpcClient.ModuleNode child : n.modules) {
            appendModuleNode(sb, child, depth + 1);
        }
    }

    private static CallToolResult handleSaveFile(ConnectionManager conn, Map<String, Object> args) {
        try {
            String path = reqStr(args, "path");
            boolean saved = conn.withClient(c -> c.saveFile(path));
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("path", path);
            structured.put("saved", saved);
            String text = saved
                    ? "Topologia guardada en " + path + "."
                    : "PT no confirmo el guardado en " + path + ".";
            return CallToolResult.builder()
                    .addTextContent(text)
                    .structuredContent(structured)
                    .isError(!saved)
                    .build();
        } catch (Exception e) {
            return error("saveFile fallo: " + e.getMessage());
        }
    }

    private static CallToolResult handleOpenFile(ConnectionManager conn, Map<String, Object> args) {
        try {
            String path = reqStr(args, "path");
            String result = conn.withClient(c -> c.openFile(path));
            boolean okResult = "FILE_RETURN_OK".equals(result);
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("path", path);
            structured.put("result", result);
            String text = okResult
                    ? "Archivo abierto: " + path + "."
                    : "No se pudo abrir " + path + " (" + result + ").";
            return CallToolResult.builder()
                    .addTextContent(text)
                    .structuredContent(structured)
                    .isError(!okResult)
                    .build();
        } catch (Exception e) {
            return error("openFile fallo: " + e.getMessage());
        }
    }

    private static CallToolResult handleGetDeviceModels(ConnectionManager conn, Map<String, Object> args) {
        try {
            String type = optStr(args, "type", "");
            if (type.isEmpty()) {
                List<String> types = conn.withClient(PtIpcClient::getDeviceTypes);
                String text = "Tipos de dispositivo (" + types.size() + "):\n"
                        + types.stream().map(t -> "  - " + t).collect(Collectors.joining("\n"))
                        + "\n(Llama de nuevo con 'type' para ver los modelos de un tipo.)";
                Map<String, Object> structured = new LinkedHashMap<>();
                structured.put("types", types);
                return ok(text, structured);
            }
            List<String> models = conn.withClient(c -> c.getDeviceModels(type));
            String text = "Modelos de '" + type + "' (" + models.size() + "):\n"
                    + models.stream().map(m -> "  - " + m).collect(Collectors.joining("\n"));
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("type", type);
            structured.put("models", models);
            return ok(text, structured);
        } catch (Exception e) {
            return error("getDeviceModels fallo: " + e.getMessage());
        }
    }

    // ====== helpers ======

    private static CallToolResult ok(String text, Object structured) {
        return CallToolResult.builder()
                .addTextContent(text)
                .structuredContent(structured)
                .isError(false)
                .build();
    }

    private static CallToolResult error(String text) {
        return CallToolResult.builder()
                .addTextContent(text)
                .isError(true)
                .build();
    }

    private static String reqStr(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        if (v == null) throw new IllegalArgumentException("Falta argumento '" + key + "'.");
        return v.toString();
    }

    private static String optStr(Map<String, Object> args, String key, String def) {
        Object v = args == null ? null : args.get(key);
        return v == null ? def : v.toString();
    }

    private static boolean reqBool(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        if (v == null) throw new IllegalArgumentException("Falta argumento '" + key + "'.");
        if (v instanceof Boolean b) return b;
        String s = v.toString().trim().toLowerCase();
        if (s.equals("true")) return true;
        if (s.equals("false")) return false;
        throw new IllegalArgumentException("Argumento '" + key + "' debe ser boolean, no '" + v + "'.");
    }

    private static double reqNum(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        if (v == null) throw new IllegalArgumentException("Falta argumento '" + key + "'.");
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Argumento '" + key + "' debe ser numero, no '" + v + "'.");
        }
    }
}

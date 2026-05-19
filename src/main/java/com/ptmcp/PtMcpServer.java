package com.ptmcp;

import com.ptmcp.PtIpcClient.CliResult;
import com.ptmcp.PtIpcClient.DeviceInfo;
import com.ptmcp.PtIpcClient.EndpointDhcpResult;
import com.ptmcp.PtIpcClient.EndpointIpResult;
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
                        (ex, req) -> handleOpenFile(conn, req.arguments()))
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

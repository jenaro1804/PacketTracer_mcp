package com.ptmcp;

import com.ptmcp.PtIpcClient.CliResult;
import com.ptmcp.PtIpcClient.DeviceInfo;
import com.ptmcp.PtIpcClient.Topology;

/**
 * Smoke test temporal de PtIpcClient.
 * Cuando el servidor MCP exista, este main desaparecera (o se movera a tests).
 */
public class Main {

    public static void main(String[] args) {
        System.out.println("pt-mcp: smoke test de PtIpcClient...");

        try (PtIpcClient client = new PtIpcClient()) {
            client.connect();
            System.out.println("Conexion OK. isConnected=" + client.isConnected());

            printTopology(client, "antes");

            System.out.println("Creando R1 (router 2911) en (150, 150)...");
            String r1 = client.addDevice("router", "2911", 150, 150);
            System.out.println("  PT asigno nombre: " + r1);

            System.out.println("Creando R2 (router 2911) en (350, 150)...");
            String r2 = client.addDevice("router", "2911", 350, 150);
            System.out.println("  PT asigno nombre: " + r2);

            System.out.println("Puertos de " + r1 + ":");
            for (String p : client.getPorts(r1)) {
                System.out.println("    " + p);
            }

            System.out.println("Conectando " + r1 + ":GigabitEthernet0/0 <-> "
                    + r2 + ":GigabitEthernet0/0 (ethernet_cross)...");
            boolean linked = client.connectDevices(
                    r1, "GigabitEthernet0/0",
                    r2, "GigabitEthernet0/0",
                    "ethernet_cross");
            System.out.println("  connectDevices -> " + linked);

            printTopology(client, "tras enlace");

            System.out.println("Saltando boot de " + r1 + "...");
            client.skipBoot(r1);
            boolean booted = client.waitForBoot(r1, 30_000);
            System.out.println("  booteado: " + booted);

            System.out.println("CLI: configurando IP en " + r1 + ":Gig0/0...");
            runAndPrint(client, r1, "show ip interface brief", "enable");
            runAndPrint(client, r1, "interface GigabitEthernet0/0", "global");
            runAndPrint(client, r1, "ip address 10.0.0.1 255.255.255.0", "");
            runAndPrint(client, r1, "no shutdown", "");
            runAndPrint(client, r1, "end", "");
            runAndPrint(client, r1, "show ip interface brief", "enable");

            System.out.println("Borrando enlace y dispositivos...");
            // borrar dispositivos elimina los enlaces tambien
            boolean removed = client.deleteDevice(r2);
            System.out.println("  removeDevice " + r2 + " -> " + removed);
            removed = client.deleteDevice(r1);
            System.out.println("  removeDevice " + r1 + " -> " + removed);

            printTopology(client, "tras borrado");
            System.out.println("OK.");
        } catch (Exception e) {
            System.err.println("FALLO: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void runAndPrint(PtIpcClient client, String device, String command, String mode) {
        CliResult r = client.runCli(device, command, mode);
        System.out.println("  $ (" + (mode.isEmpty() ? "current" : mode) + ") " + command);
        System.out.println("    -> [" + r.status + "]");
        if (r.output != null && !r.output.isEmpty()) {
            for (String line : r.output.split("\\r?\\n")) {
                System.out.println("    | " + line);
            }
        }
    }

    private static void printTopology(PtIpcClient client, String etiqueta) {
        Topology t = client.getTopology();
        System.out.println("Topologia (" + etiqueta + "): "
                + t.devices.size() + " dispositivos / " + t.linkCount + " enlaces");
        for (DeviceInfo d : t.devices) {
            System.out.println("  - " + d);
        }
    }
}

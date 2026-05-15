package com.ptmcp;

import java.util.function.Function;

/**
 * Mantiene una unica sesion PtIpcClient persistente para todo el ciclo de vida
 * del servidor MCP, con reconexion perezosa: si la operacion falla y la sesion
 * parece muerta, intenta reconectar y reintenta UNA vez.
 *
 * Por que persistente: evita el coste de auth + handshake (~500ms) en cada
 * tool call, y conserva el contexto del CLI entre comandos consecutivos del
 * mismo turno (ej. "interface gig0/0" seguido de "ip address ..." sin
 * re-especificar el modo).
 *
 * Thread-safe: los metodos withClient/ensureConnected son synchronized porque
 * la sesion PTMP no es segura para uso concurrente.
 */
public final class ConnectionManager implements AutoCloseable {

    private final PtIpcClient client;
    private boolean connected;

    public ConnectionManager() {
        this(new PtIpcClient());
    }

    /** Para tests: inyectar un PtIpcClient (eventualmente con mock). */
    public ConnectionManager(PtIpcClient client) {
        this.client = client;
        this.connected = false;
    }

    /**
     * Ejecuta {@code op} contra el cliente, asegurando primero que haya sesion.
     * Si la operacion lanza una excepcion que parece deberse a sesion muerta,
     * reconecta y reintenta una sola vez.
     */
    public synchronized <R> R withClient(Function<PtIpcClient, R> op) {
        ensureConnected();
        try {
            return op.apply(client);
        } catch (RuntimeException e) {
            if (!looksLikeDeadSession(e)) {
                throw e;
            }
            // Sesion muerta: reconectar y reintentar una vez.
            forceReconnect();
            return op.apply(client);
        }
    }

    /** Variante sin return, para operaciones que no devuelven nada. */
    public synchronized void withClientVoid(java.util.function.Consumer<PtIpcClient> op) {
        withClient(c -> { op.accept(c); return null; });
    }

    private void ensureConnected() {
        if (connected && client.isConnected()) {
            return;
        }
        // Cerrar lo que haya quedado a medias.
        try { client.disconnect(); } catch (RuntimeException ignored) { }
        client.connect();
        connected = true;
    }

    private void forceReconnect() {
        connected = false;
        try { client.disconnect(); } catch (RuntimeException ignored) { }
        ensureConnected();
    }

    /**
     * Heuristica: una excepcion del framework Cisco suele venir como
     * NullPointerException o RuntimeException con mensaje relacionado a
     * "connection", "socket", "closed", "session", o un IOException envuelto.
     * Ante la duda preferimos reintentar.
     */
    private static boolean looksLikeDeadSession(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof java.io.IOException) return true;
            String msg = t.getMessage();
            if (msg != null) {
                String low = msg.toLowerCase();
                if (low.contains("connection") || low.contains("socket")
                        || low.contains("closed") || low.contains("session")
                        || low.contains("pipe") || low.contains("reset")) {
                    return true;
                }
            }
            if (t instanceof NullPointerException) return true;
            t = t.getCause();
        }
        return false;
    }

    @Override
    public synchronized void close() {
        connected = false;
        try { client.disconnect(); } catch (RuntimeException ignored) { }
    }
}

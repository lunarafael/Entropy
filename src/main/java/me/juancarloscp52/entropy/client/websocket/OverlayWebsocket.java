package me.juancarloscp52.entropy.client.websocket;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.function.Consumer;

/** Orders opening connections and broadcasts around one immutable serialized snapshot. */
public class OverlayWebsocket extends WebSocketServer {
    private final Object deliveryLock = new Object();
    private final Runnable ready;
    private final Consumer<Exception> failure;
    private String snapshot;

    public OverlayWebsocket(InetSocketAddress address, String initialSnapshot,
                            Runnable ready, Consumer<Exception> failure) {
        super(address, 1);
        this.snapshot = connectionSnapshot(initialSnapshot);
        this.ready = ready;
        this.failure = failure;
        setReuseAddr(true);
        setConnectionLostTimeout(30);
    }

    public void publish(String serializedSnapshot) {
        synchronized (deliveryLock) {
            // A newly opened browser has no poll to update. Rehydrate it with CREATE.
            snapshot = connectionSnapshot(serializedSnapshot);
            broadcast(serializedSnapshot);
        }
    }

    private static String connectionSnapshot(String serialized) {
        JsonObject snapshot = JsonParser.parseString(serialized).getAsJsonObject();
        if ("UPDATE".equals(snapshot.get("request").getAsString())) snapshot.addProperty("request", "CREATE");
        return snapshot.toString();
    }

    /** A preview never replaces what a reconnecting OBS receives. */
    public void preview(String serializedPreview) {
        synchronized (deliveryLock) {
            broadcast(serializedPreview);
        }
    }

    public String cachedSnapshot() {
        synchronized (deliveryLock) {
            return snapshot;
        }
    }

    @Override
    public void onOpen(WebSocket connection, ClientHandshake handshake) {
        synchronized (deliveryLock) {
            connection.send(snapshot);
        }
    }

    @Override public void onClose(WebSocket connection, int code, String reason, boolean remote) { }
    @Override public void onMessage(WebSocket connection, String message) { }
    @Override public void onError(WebSocket connection, Exception error) {
        // A single disconnected browser does not take down the local service.
        if (connection == null) failure.accept(error);
    }
    @Override public void onStart() { ready.run(); }
}

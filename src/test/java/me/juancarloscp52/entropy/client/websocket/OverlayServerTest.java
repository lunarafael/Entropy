package me.juancarloscp52.entropy.client.websocket;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class OverlayServerTest {
    @BeforeAll static void bootstrapMinecraftCodecs() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void protocolCopiesInputAndKeepsLegacyAndPlainTextLabels() {
        JsonObject label = new JsonObject();
        label.addProperty("text", "Low Gravity");
        String[] matches = {"1"};
        OverlayVoteOption option = new OverlayVoteOption(label, "Low Gravity", matches, 7);
        matches[0] = "changed";
        label.addProperty("text", "changed");
        OverlayVoteOption[] options = {option};
        OverlayMessage message = new OverlayMessage("CREATE", 7, "MAJORITY", options,
                2, "test-session", 1, 1, 100, 600, false);
        options[0] = null;
        JsonObject json = JsonParser.parseString(new Gson().toJson(message)).getAsJsonObject();
        JsonObject result = json.getAsJsonArray("voteOptions").get(0).getAsJsonObject();
        assertEquals("Low Gravity", result.getAsJsonObject("label").get("text").getAsString());
        assertEquals("Low Gravity", result.get("labelText").getAsString());
        assertEquals("1", result.getAsJsonArray("matches").get(0).getAsString());
        assertEquals(7, json.get("totalVotes").getAsInt());
        assertEquals(2, json.get("protocolVersion").getAsInt());
    }

    @Test void translatedLabelsUsePublicCodecInsteadOfReflectingLanguageCaches() throws Exception {
        OverlayServer service = new OverlayServer(() -> true, () -> "PERCENTAGE");
        int ws = freePort();
        try {
            service.configure(true, ws, freePortExcept(ws));
            awaitStatus(service, "READY");
            Component label = Component.translatable("options.on").append(Component.literal(" — Ação"));
            String resolved = label.getString(); // Populate Minecraft's translation cache before publishing.
            service.onNewVote(2, List.of(label));
            service.updateVote(2, List.of(label), new int[]{7});
            TestClient client = new TestClient(ws);
            try {
                assertTrue(client.connectBlocking(3, TimeUnit.SECONDS));
                JsonObject snapshot = client.next();
                String encoded = snapshot.toString();
                assertTrue(encoded.length() < 4096);
                assertFalse(encoded.contains("decomposed"));
                assertEquals("CREATE", snapshot.get("request").getAsString());
                JsonObject option = snapshot.getAsJsonArray("voteOptions").get(0).getAsJsonObject();
                assertEquals(resolved, option.get("labelText").getAsString());
                assertEquals("5", option.getAsJsonArray("matches").get(0).getAsString());
            } finally { client.closeBlocking(); }
        } finally { service.stop(); }
    }

    @Test void activePollReconfigurationStillBootstrapsAFreshBrowserWithoutAnotherGameTick() throws Exception {
        OverlayServer service = new OverlayServer(() -> false, () -> "MAJORITY");
        int ws = freePort();
        try {
            service.configure(true, ws, freePortExcept(ws));
            awaitStatus(service, "READY");
            service.onNewVote(1, List.of(Component.literal("Low Gravity")));
            service.updateVote(1, List.of(Component.literal("Low Gravity")), new int[]{7});
            service.stop();
            int nextWs = freePortExcept(ws);
            service.configure(true, nextWs, freePortExcept(nextWs));
            awaitStatus(service, "READY");
            TestClient browser = new TestClient(nextWs);
            try {
                assertTrue(browser.connectBlocking(3, TimeUnit.SECONDS));
                JsonObject snapshot = browser.next();
                assertEquals("CREATE", snapshot.get("request").getAsString());
                assertEquals(7, snapshot.get("totalVotes").getAsInt());
            } finally { browser.closeBlocking(); }
        } finally { service.stop(); }
    }

    @Test void httpWebsocketPreviewReconnectAndRebind() throws Exception {
        int ws = freePort();
        int http = freePortExcept(ws);
        OverlayServer service = new OverlayServer(() -> false, () -> "MAJORITY");
        assertEquals("DISABLED", service.getStatus());
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            service.configure(true, ws, http);
            awaitStatus(service, "READY");
            service.configure(true, ws, http); // Repeated Done must not rebind or lose connections.
            assertEquals("READY", service.getStatus());
            String origin = service.getOverlayUrl();
            HttpResponse<String> config = get(client, origin + "config.json");
            assertEquals(200, config.statusCode());
            assertEquals("ws://127.0.0.1:" + ws,
                    JsonParser.parseString(config.body()).getAsJsonObject().get("websocketUrl").getAsString());
            assertEquals(404, get(client, origin + "%2e%2e/gradle.properties").statusCode());
            assertEquals(200, get(client, origin).statusCode());
            TestClient first = new TestClient(ws);
            try {
                assertTrue(first.connectBlocking(3, TimeUnit.SECONDS));
                assertEquals("END", first.next().get("request").getAsString());
                service.preview();
                assertTrue(first.next().get("preview").getAsBoolean());
                TestClient late = new TestClient(ws);
                try {
                    assertTrue(late.connectBlocking(3, TimeUnit.SECONDS));
                    JsonObject snapshot = late.next();
                    assertEquals("END", snapshot.get("request").getAsString());
                    assertFalse(snapshot.get("preview").getAsBoolean());
                } finally { late.closeBlocking(); }
                service.onNewVote(1, List.of(Component.literal("Low Gravity"), Component.literal("Super Jump")));
                int[] votes = {7, 2};
                service.updateVote(1, List.of(Component.literal("Low Gravity"), Component.literal("Super Jump")), votes);
                votes[0] = 999;
                service.updateTimer(100, 600);
                TestClient active = new TestClient(ws);
                try {
                    assertTrue(active.connectBlocking(3, TimeUnit.SECONDS));
                    JsonObject snapshot = active.next();
                    assertEquals("CREATE", snapshot.get("request").getAsString());
                    assertEquals(9, snapshot.get("totalVotes").getAsInt());
                    assertEquals(100, snapshot.get("remainingTicks").getAsInt());
                    assertEquals("Low Gravity", snapshot.getAsJsonArray("voteOptions").get(0)
                            .getAsJsonObject().get("labelText").getAsString());
                    service.onVoteEnd();
                    assertEquals("END", active.next().get("request").getAsString());
                } finally { active.closeBlocking(); }
            } finally { first.closeBlocking(); }
            service.stop();
            service.stop();
            assertEquals("DISABLED", service.getStatus());
            service.configure(true, ws, http);
            awaitStatus(service, "READY");
        } finally { service.stop(); }
    }

    @Test void occupiedPortsFailAndCanRecover() throws Exception {
        OverlayServer service = new OverlayServer(() -> false, () -> "MAJORITY");
        try (ServerSocket occupied = new ServerSocket(0)) {
            int port = occupied.getLocalPort();
            service.configure(true, freePortExcept(port), port);
            assertEquals("ERROR", service.getStatus());
            service.stop();
            int http = freePortExcept(port);
            service.configure(true, port, http);
            awaitStatus(service, "ERROR");
            service.configure(false, port, http);
            assertEquals("DISABLED", service.getStatus());
            int nextWs = freePortExcept(port);
            service.configure(true, nextWs, freePortExcept(nextWs));
            awaitStatus(service, "READY");
        } finally { service.stop(); }
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }
    private static int freePortExcept(int other) throws Exception {
        int port;
        do { port = freePort(); } while (port == other);
        return port;
    }
    private static void awaitStatus(OverlayServer service, String target) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!target.equals(service.getStatus()) && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(target, service.getStatus());
    }
    private static class TestClient extends WebSocketClient {
        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        TestClient(int port) { super(URI.create("ws://127.0.0.1:" + port)); }
        JsonObject next() throws Exception {
            String message = messages.poll(3, TimeUnit.SECONDS);
            assertNotNull(message, "Expected overlay snapshot");
            return JsonParser.parseString(message).getAsJsonObject();
        }
        @Override public void onOpen(ServerHandshake handshake) { }
        @Override public void onMessage(String message) { messages.add(message); }
        @Override public void onClose(int code, String reason, boolean remote) { }
        @Override public void onError(Exception error) { }
    }
}

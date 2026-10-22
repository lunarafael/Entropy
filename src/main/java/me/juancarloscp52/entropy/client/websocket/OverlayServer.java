package me.juancarloscp52.entropy.client.websocket;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import me.juancarloscp52.entropy.Entropy;
import me.juancarloscp52.entropy.EntropySettings;
import me.juancarloscp52.entropy.client.EntropyClient;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import com.mojang.serialization.JsonOps;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Client-lifetime local OBS service. Transport failures never interrupt voting. */
public class OverlayServer {
    private static final Gson GSON = new Gson();
    private static final org.apache.logging.log4j.Logger LOGGER = org.apache.logging.log4j.LogManager.getLogger("EntropyOverlay");
    private final String sessionId = UUID.randomUUID().toString();
    private final BooleanSupplier alternateOffsets;
    private final Supplier<String> votingMode;
    private volatile OverlayWebsocket websocket;
    private HttpServer http;
    private ExecutorService httpExecutor;
    private ScheduledExecutorService scheduler;
    private volatile String status = "DISABLED";
    private volatile int websocketPort = 9091;
    private volatile int httpPort = 9092;
    private volatile long generation;
    private long pollId;
    private long sequence;
    private int voteId;
    private int remainingTicks;
    private int durationTicks;
    private String mode = "MAJORITY";
    private boolean active;
    private OverlayVoteOption[] options = new OverlayVoteOption[0];
    private String snapshot;

    public OverlayServer() {
        this(() -> EntropyClient.getInstance().integrationsSettings.shouldUseAlternateOffsets(),
                () -> Entropy.getInstance().settings.votingMode == EntropySettings.VotingMode.MAJORITY
                        ? "MAJORITY" : "PERCENTAGE");
    }

    /** Dependency injection also allows the transport to be exercised without a Minecraft client. */
    public OverlayServer(BooleanSupplier alternateOffsets, Supplier<String> votingMode) {
        this.alternateOffsets = alternateOffsets;
        this.votingMode = votingMode;
        snapshot = serialize("END", options, false);
    }

    public synchronized void configure(boolean enabled, int websocketPort, int httpPort) {
        if (enabled && this.websocketPort == websocketPort && this.httpPort == httpPort
                && ("READY".equals(status) || "STARTING".equals(status))) return;
        stop();
        this.websocketPort = websocketPort;
        this.httpPort = httpPort;
        if (!enabled) return;
        if (websocketPort < 1 || websocketPort > 65535 || httpPort < 1 || httpPort > 65535
                || websocketPort == httpPort) {
            status = "ERROR";
            LOGGER.warn("OBS overlay ports must be distinct and between 1 and 65535.");
            return;
        }
        long currentGeneration = generation;
        status = "STARTING";
        try {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", httpPort), 0);
            httpExecutor = Executors.newSingleThreadExecutor(r -> daemon(r, "Entropy overlay HTTP"));
            http.setExecutor(httpExecutor);
            http.createContext("/", this::serve);
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "Entropy overlay preview"));
            websocket = new OverlayWebsocket(new InetSocketAddress("127.0.0.1", websocketPort), snapshot,
                    () -> {
                        if (generation == currentGeneration) status = "READY";
                    }, error -> {
                        if (generation == currentGeneration) {
                            status = "ERROR";
                            daemon(() -> failedStartup(currentGeneration), "Entropy overlay cleanup").start();
                            LOGGER.warn("OBS overlay WebSocket could not start on port {} ({}).",
                                    websocketPort, error.getClass().getSimpleName());
                        }
                    });
            http.start();
            websocket.start();
        } catch (Exception error) {
            stop();
            status = "ERROR";
            LOGGER.warn("OBS overlay could not start on ports {}/{} ({}).",
                    websocketPort, httpPort, error.getClass().getSimpleName());
        }
    }

    private synchronized void failedStartup(long failedGeneration) {
        if (generation != failedGeneration) return;
        stop();
        status = "ERROR";
    }

    public synchronized void stop() {
        generation++;
        OverlayWebsocket previous = websocket;
        websocket = null;
        if (http != null) { http.stop(0); http = null; }
        if (httpExecutor != null) { httpExecutor.shutdownNow(); httpExecutor = null; }
        if (scheduler != null) { scheduler.shutdownNow(); scheduler = null; }
        if (previous != null) {
            try { previous.stop(1000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (RuntimeException error) { LOGGER.warn("OBS overlay stopped with {}.", error.getClass().getSimpleName()); }
        }
        status = "DISABLED";
    }

    public String getOverlayUrl() { return "http://127.0.0.1:" + httpPort + "/"; }
    public String getStatus() { return status; }

    public synchronized void onNewVote(int voteId, List<Component> events) {
        this.voteId = voteId;
        pollId++;
        active = true;
        remainingTicks = 0;
        durationTicks = 0;
        mode = votingMode.get();
        options = createOptions(voteId, events, new int[events.size()]);
        publish("CREATE");
    }

    public synchronized void updateVote(int voteId, List<Component> events, int[] votes) {
        if (!active || voteId != this.voteId) return;
        options = createOptions(voteId, events, votes);
        publish("UPDATE");
    }

    public synchronized void updateTimer(int remainingTicks, int durationTicks) {
        if (!active) return;
        int remaining = Math.max(0, remainingTicks);
        int duration = Math.max(0, durationTicks);
        if (this.remainingTicks == remaining && this.durationTicks == duration) return;
        this.remainingTicks = remaining;
        this.durationTicks = duration;
        publish("UPDATE");
    }

    public synchronized void onVoteEnd() {
        active = false;
        options = new OverlayVoteOption[0];
        remainingTicks = 0;
        durationTicks = 0;
        publish("END");
    }

    public synchronized void preview() {
        if (active || websocket == null || scheduler == null || !"READY".equals(status)) return;
        OverlayVoteOption[] sample = new OverlayVoteOption[4];
        String[] names = {"Super Jump", "Low Gravity", "Rainbow Sheep", "Random Effect"};
        for (int i = 0; i < sample.length; i++) {
            JsonObject label = new JsonObject();
            label.addProperty("text", names[i]);
            sample[i] = new OverlayVoteOption(label, names[i], new String[]{Integer.toString(i + 1)}, (i + 1) * 3);
        }
        websocket.preview(GSON.toJson(new OverlayMessage("CREATE", 30, "MAJORITY", sample,
                2, sessionId, pollId, ++sequence, 600, 600, true)));
        long currentGeneration = generation;
        scheduler.schedule(() -> {
            synchronized (OverlayServer.this) {
                if (generation == currentGeneration && websocket != null) publish(active ? "UPDATE" : "END");
            }
        }, 8, TimeUnit.SECONDS);
    }

    private OverlayVoteOption[] createOptions(int voteId, List<Component> events, int[] votes) {
        int offset = alternateOffsets.getAsBoolean() && voteId % 2 == 0 ? 5 : 1;
        OverlayVoteOption[] result = new OverlayVoteOption[events.size()];
        for (int i = 0; i < result.length; i++) {
            Component label = events.get(i).copy();
            result[i] = new OverlayVoteOption(ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE, label).getOrThrow(), label.getString(),
                    new String[]{Integer.toString(i + offset)}, i < votes.length ? Math.max(0, votes[i]) : 0);
        }
        return result;
    }

    private void publish(String request) {
        snapshot = serialize(request, options, false);
        if (websocket != null) websocket.publish(snapshot);
    }

    private String serialize(String request, OverlayVoteOption[] options, boolean preview) {
        int total = 0;
        for (OverlayVoteOption option : options) total += option.value();
        return GSON.toJson(new OverlayMessage(request, total, mode, options, 2,
                sessionId, pollId, ++sequence, remainingTicks, durationTicks, preview));
    }

    private void serve(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod()) && !"HEAD".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                respond(exchange, 405, "text/plain", new byte[0]);
                return;
            }
            String path = exchange.getRequestURI().getRawPath();
            if ("/config.json".equals(path)) {
                JsonObject config = new JsonObject();
                config.addProperty("websocketUrl", "ws://127.0.0.1:" + websocketPort);
                respond(exchange, 200, "application/json; charset=utf-8", GSON.toJson(config).getBytes(StandardCharsets.UTF_8));
                return;
            }
            String resource;
            String type;
            switch (path) {
                case "/", "/index.html" -> { resource = "index.html"; type = "text/html; charset=utf-8"; }
                case "/style.css" -> { resource = "style.css"; type = "text/css; charset=utf-8"; }
                case "/overlay.js" -> { resource = "overlay.js"; type = "text/javascript; charset=utf-8"; }
                default -> { respond(exchange, 404, "text/plain", new byte[0]); return; }
            }
            try (InputStream input = OverlayServer.class.getResourceAsStream("/overlay/" + resource)) {
                if (input == null) respond(exchange, 404, "text/plain", new byte[0]);
                else respond(exchange, 200, type, input.readAllBytes());
            }
        } finally { exchange.close(); }
    }

    private static void respond(HttpExchange exchange, int status, String type, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        boolean head = "HEAD".equals(exchange.getRequestMethod());
        exchange.sendResponseHeaders(status, head || bytes.length == 0 ? -1 : bytes.length);
        if (!head && bytes.length > 0) exchange.getResponseBody().write(bytes);
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }
}

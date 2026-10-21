package me.juancarloscp52.entropy.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IntegrationSettingsCodecTest {
    @Test void defaultsAreCompatibleButNotSharedMutableObjects() {
        EntropyIntegrationsSettings first = decode("{}");
        EntropyIntegrationsSettings second = decode("{}");
        first.twitch.enabled = true;
        first.overlay.minecraftHudEnabled = false;
        assertFalse(second.twitch.enabled);
        assertTrue(second.overlay.minecraftHudEnabled);
        assertTrue(second.websocket.enabled);
        assertEquals(9091, second.websocket.port);
        assertEquals(9092, second.websocket.httpPort);
    }

    @Test void preferencesAndCredentialsRoundTrip() {
        EntropyIntegrationsSettings original = decode("""
            {"twitch":{"enabled":true,"token":"test-only","channel":"sample"},
            "show_upcoming_events":false,"show_current_percentage":false,"send_chat_messages":false,
            "overlay":{"minecraft_hud_enabled":false,"show_timer":false,"show_event_queue":false,"show_poll_status":false},
            "websocket":{"enabled":true,"port":12345,"http_port":12346},
            "youtube":{"poll_interval":6200}}
            """);
        JsonObject json = EntropyIntegrationsSettings.CODEC.encodeStart(JsonOps.INSTANCE, original).getOrThrow().getAsJsonObject();
        EntropyIntegrationsSettings restored = EntropyIntegrationsSettings.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(original.twitch, restored.twitch);
        assertFalse(restored.overlay.minecraftHudEnabled);
        assertFalse(restored.showUpcomingEvents);
        assertFalse(restored.showCurrentPercentage);
        assertFalse(restored.sendChatMessages);
        assertEquals(12345, restored.websocket.port);
        assertEquals(6200, restored.youtube.pollInterval);
    }

    @Test void invalidNestedConfigurationProducesErrorInsteadOfSilentDefaults() {
        assertTrue(EntropyIntegrationsSettings.CODEC.parse(JsonOps.INSTANCE,
            JsonParser.parseString("{\"twitch\":{\"enabled\":\"yes\"}}")).error().isPresent());
        assertTrue(EntropyIntegrationsSettings.CODEC.parse(JsonOps.INSTANCE,
            JsonParser.parseString("{\"websocket\":{\"port\":0}}")).error().isPresent());
    }

    private EntropyIntegrationsSettings decode(String json) {
        return EntropyIntegrationsSettings.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }
}

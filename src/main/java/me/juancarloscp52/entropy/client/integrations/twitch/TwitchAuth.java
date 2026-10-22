package me.juancarloscp52.entropy.client.integrations.twitch;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Parsing and retry policy independent of Minecraft, IRC and credentials. */
final class TwitchAuth {
    record Identity(String login, boolean canWrite, long expiresIn) {}
    static final class AuthenticationException extends Exception {}

    static String normalizeToken(String token) {
        String trimmed = token == null ? "" : token.trim();
        return trimmed.startsWith("oauth:") ? trimmed.substring(6) : trimmed;
    }

    static Identity parseIdentity(String response) throws IOException, AuthenticationException {
        try {
            JsonObject json = JsonParser.parseString(response).getAsJsonObject();
            if (!json.has("login") || json.get("login").isJsonNull() ||
                    !json.has("user_id") || json.get("user_id").isJsonNull()) throw new AuthenticationException();
            String login = json.get("login").getAsString().toLowerCase(Locale.ROOT);
            String userId = json.get("user_id").getAsString();
            long expiry = json.get("expires_in").getAsLong();
            Set<String> scopes = new HashSet<>();
            for (var scope : json.getAsJsonArray("scopes")) scopes.add(scope.getAsString());
            if (!login.matches("[a-z0-9_]{1,25}") || !userId.matches("[0-9]+") || expiry <= 0 || !scopes.contains("chat:read"))
                throw new AuthenticationException();
            return new Identity(login, scopes.contains("chat:edit"), expiry);
        } catch (RuntimeException e) {
            // Do not propagate the JSON parser's message: a response can contain sensitive data.
            throw new IOException("Invalid validation response");
        }
    }

    static long retryDelayMillis(int failure, double jitter) {
        long base = 1000L << Math.min(5, Math.max(0, failure));
        return Math.min(30000, base + (long) (Math.max(0, Math.min(1, jitter)) * 500));
    }
}

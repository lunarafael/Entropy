package me.juancarloscp52.entropy.config;

public final class SettingsValidation {
    private SettingsValidation() {}

    public static long discordChannel(boolean enabled, String input, long previous) {
        try {
            long channel = Long.parseLong(input.trim());
            if (enabled && channel <= 0) throw new IllegalArgumentException("discord.channel");
            return channel;
        } catch (NumberFormatException exception) {
            if (enabled) throw new IllegalArgumentException("discord.channel");
            return previous;
        }
    }

    public static int port(boolean enabled, String input, int previous) {
        return enabled ? port(input) : previous;
    }

    public static int port(String input) {
        try {
            int port = Integer.parseInt(input.trim());
            if (port < 1 || port > 65535) throw new IllegalArgumentException("overlay.port");
            return port;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("overlay.port");
        }
    }
}

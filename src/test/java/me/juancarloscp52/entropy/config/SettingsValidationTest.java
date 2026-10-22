package me.juancarloscp52.entropy.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettingsValidationTest {
    @Test void disabledDiscordAcceptsBlankOrMalformedChannel() {
        assertEquals(-1, SettingsValidation.discordChannel(false, "", -1));
        assertEquals(123, SettingsValidation.discordChannel(false, "not a channel", 123));
        assertEquals(-1, SettingsValidation.discordChannel(false, "9999999999999999999999", -1));
    }

    @Test void enabledDiscordRequiresPositiveLong() {
        assertEquals(123456789012345678L, SettingsValidation.discordChannel(true, " 123456789012345678 ", -1));
        for (String input : new String[]{"", "-1", "0", "text", "9999999999999999999999"})
            assertThrows(IllegalArgumentException.class, () -> SettingsValidation.discordChannel(true, input, -1));
    }

    @Test void disabledOverlayRetainsLastSavedPorts() {
        for (String input : new String[]{"", "abc", "0", "65536", "9191"})
            assertEquals(9091, SettingsValidation.port(false, input, 9091));
        assertEquals(9191, SettingsValidation.port(true, "9191", 9091));
        assertThrows(IllegalArgumentException.class, () -> SettingsValidation.port(true, "", 9091));
    }

    @Test void portsMatchCodecRange() {
        assertEquals(1, SettingsValidation.port("1"));
        assertEquals(1023, SettingsValidation.port("1023"));
        assertEquals(9091, SettingsValidation.port("9091"));
        assertEquals(65535, SettingsValidation.port("65535"));
        for (String input : new String[]{"", "abc", "0", "-1", "65536"})
            assertThrows(IllegalArgumentException.class, () -> SettingsValidation.port(input));
    }
}

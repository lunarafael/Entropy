package me.juancarloscp52.entropy.client.integrations.twitch;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class TwitchAuthTest {
    private String identity(String scopes, int expiry) {
        return "{\"login\":\"Bot_Account\",\"user_id\":\"12345\",\"scopes\":" + scopes + ",\"expires_in\":" + expiry + "}";
    }

    @Test void receiveOnlyTokenUsesAuthenticatedAccountAndDoesNotPermitWrites() throws Exception {
        var result = TwitchAuth.parseIdentity(identity("[\"chat:read\"]", 3600));
        assertEquals("bot_account", result.login());
        assertFalse(result.canWrite());
        assertEquals(3600, result.expiresIn());
    }

    @Test void outputRequiresExplicitChatEditScope() throws Exception {
        assertTrue(TwitchAuth.parseIdentity(identity("[\"chat:read\",\"chat:edit\"]", 100)).canWrite());
        assertThrows(TwitchAuth.AuthenticationException.class, () -> TwitchAuth.parseIdentity(identity("[\"chat:edit\"]", 100)));
    }

    @Test void rejectsAppTokensExpiredTokensAndMalformedResponsesWithoutLeakingBody() {
        assertThrows(TwitchAuth.AuthenticationException.class, () -> TwitchAuth.parseIdentity("{\"login\":null,\"user_id\":null}"));
        assertThrows(TwitchAuth.AuthenticationException.class, () -> TwitchAuth.parseIdentity(identity("[\"chat:read\"]", 0)));
        IOException error = assertThrows(IOException.class, () -> TwitchAuth.parseIdentity("sensitive token-like body"));
        assertEquals("Invalid validation response", error.getMessage());
        assertNull(error.getCause());
    }

    @Test void tokenNormalizationAcceptsIrcPrefixAndRetryPolicyIsBounded() {
        assertEquals("abc", TwitchAuth.normalizeToken(" oauth:abc "));
        assertEquals("abc", TwitchAuth.normalizeToken(" abc "));
        assertEquals("", TwitchAuth.normalizeToken(null));
        assertEquals(1000, TwitchAuth.retryDelayMillis(0, 0));
        assertEquals(2500, TwitchAuth.retryDelayMillis(1, 1));
        assertEquals(30000, TwitchAuth.retryDelayMillis(Integer.MAX_VALUE, 1));
        assertEquals(1000, TwitchAuth.retryDelayMillis(-10, -1));
    }
}

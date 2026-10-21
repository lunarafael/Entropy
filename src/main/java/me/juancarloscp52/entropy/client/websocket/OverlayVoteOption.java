package me.juancarloscp52.entropy.client.websocket;

import com.google.gson.JsonElement;

/** The legacy label is kept alongside a plain-text label for browser consumers. */
public record OverlayVoteOption(JsonElement label, String labelText, String[] matches, int value) {
    public OverlayVoteOption {
        label = label.deepCopy();
        matches = matches.clone();
    }
}

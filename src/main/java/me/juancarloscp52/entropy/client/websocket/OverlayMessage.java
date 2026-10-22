package me.juancarloscp52.entropy.client.websocket;

public record OverlayMessage(String request, int totalVotes, String votingMode,
                             OverlayVoteOption[] voteOptions, int protocolVersion,
                             String sessionId, long pollId, long sequence,
                             int remainingTicks, int durationTicks, boolean preview) {
    public OverlayMessage {
        voteOptions = voteOptions.clone();
    }
}

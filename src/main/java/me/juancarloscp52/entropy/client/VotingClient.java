/*
 * Copyright (c) 2021 juancarloscp52
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package me.juancarloscp52.entropy.client;

import me.juancarloscp52.entropy.client.integrations.Integration;
import me.juancarloscp52.entropy.client.voting.VoteState;
import me.juancarloscp52.entropy.client.websocket.OverlayServer;
import me.juancarloscp52.entropy.networking.ServerboundVotes;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

public class VotingClient {
    private final VoteState state = new VoteState();
    private final Minecraft client = Minecraft.getInstance();
    private final OverlayServer overlayServer = EntropyClient.getInstance().getOverlayServer();
    private List<Component> events = List.of();
    private List<Integration> integrations = List.of();
    private int pollWidth = 195;
    private volatile int pollId = -1;
    private volatile long integrationGeneration;
    volatile boolean enabled;

    public void enable() {
        enabled = true;
    }

    public void disable() {
        enabled = false;
        stopIntegrations();
        pollId = -1;
        state.end();
        events = List.of();
        overlayServer.onVoteEnd();
    }

    public void stopIntegrations() {
        integrationGeneration++;
        for (Integration integration : integrations) {
            try {
                integration.stop();
            } catch (RuntimeException failure) {
                EntropyClient.LOGGER.warn("Could not stop an integration ({})", failure.getClass().getSimpleName());
            }
        }
        integrations = List.of();
    }

    public void setIntegrations(List<Integration> integrations) {
        this.integrations = List.copyOf(integrations);
    }

    /** Route every provider callback through the client thread and reject stale sessions/polls. */
    private void enqueueVote(Runnable action) {
        enqueueVote(pollId, action);
    }

    private void enqueueVote(int expectedPoll, Runnable action) {
        long expectedGeneration = integrationGeneration;
        if (!enabled || expectedPoll < 0) return;
        client.execute(() -> {
            if (enabled && pollId == expectedPoll && integrationGeneration == expectedGeneration) action.run();
        });
    }

    /** Each connected provider owns a revocable ticket, including callbacks already in flight. */
    public final class VoteSource implements AutoCloseable {
        private volatile boolean active = true;

        public void processVote(int option, String userId) {
            processVote(option, userId, pollId);
        }

        public void processVote(int option, String userId, int expectedPoll) {
            if (active) enqueueVote(expectedPoll, () -> { if (active) state.vote(option, userId); });
        }

        public void processVote(String message, String userId) {
            processVote(message, userId, pollId);
        }

        public void processVote(String message, String userId, int expectedPoll) {
            if (active) enqueueVote(expectedPoll, () -> {
                if (active) state.vote(VoteState.parseOption(message, pollId,
                    EntropyClient.getInstance().integrationsSettings.shouldUseAlternateOffsets()), userId);
            });
        }

        public void removeVote(int option, String userId) {
            removeVote(option, userId, pollId);
        }

        public void removeVote(int option, String userId, int expectedPoll) {
            if (active) enqueueVote(expectedPoll, () -> { if (active) state.remove(option, userId); });
        }

        public int pollId() { return VotingClient.this.pollId; }

        @Override public void close() { active = false; }
    }

    public VoteSource newVoteSource() { return new VoteSource(); }

    public void removeVote(int option, String userId) {
        enqueueVote(() -> state.remove(option, userId));
    }

    public void processVote(int option, String userId) {
        enqueueVote(() -> state.vote(option, userId));
    }

    public void processVote(String message, String userId) {
        enqueueVote(() -> {
            int option = VoteState.parseOption(message, pollId,
                EntropyClient.getInstance().integrationsSettings.shouldUseAlternateOffsets());
            state.vote(option, userId);
        });
    }

    public void newPoll(int voteID, List<Component> options) {
        if (!enabled) return;
        ClientEventHandler handler = EntropyClient.getInstance().clientEventHandler;
        if (handler != null) handler.setServerIntegrations(voteID >= 0);
        if (pollId >= 0) overlayServer.onVoteEnd();
        state.end();
        pollId = -1;
        events = List.of();
        if (voteID < 0 || options == null || options.size() != VoteState.OPTION_COUNT - 1) return;
        List<Component> next = new ArrayList<>();
        options.forEach(option -> next.add(option.copy()));
        next.add(Component.translatable("entropy.voting.randomEvent"));
        events = List.copyOf(next);
        pollId = voteID;
        state.begin(voteID);
        sendPoll(voteID, events);
        pollWidth = 200;
        for (Component label : events) {
            pollWidth = Math.max(pollWidth, client.font.width(Component.translatableEscape("entropy.votes.display", 5, label)));
        }
    }

    public void updatePollStatus(int voteID, int[] totalVotes, int totalVotesCount) {
        if (!enabled || pollId != voteID) return;
        state.confirm(voteID, totalVotes, totalVotesCount);
        overlayServer.updateVote(pollId, events, state.totals());
    }

    public void render(GuiGraphics graphics) {
        EntropyIntegrationsSettings settings = EntropyClient.getInstance().integrationsSettings;
        if (!enabled || pollId < 0 || !settings.overlay.minecraftHudEnabled || !settings.showUpcomingEvents) return;
        if (settings.overlay.showPollStatus) {
            graphics.drawString(client.font, Component.translatable("entropy.voting.total", state.total()), 10, 20,
                ARGB.color(255, 255, 255, 255));
        }
        for (int i = 0; i < events.size(); i++) renderPollElement(graphics, i);
    }

    public void renderPollElement(GuiGraphics graphics, int index) {
        if (index < 0 || index >= events.size()) return;
        EntropyIntegrationsSettings settings = EntropyClient.getInstance().integrationsSettings;
        if (!settings.overlay.minecraftHudEnabled || !settings.showUpcomingEvents) return;
        double ratio = state.total() > 0 ? (double) state.totals()[index] / state.total() : 0;
        int offset = pollId % 2 == 0 && settings.shouldUseAlternateOffsets() ? 4 : 0;
        graphics.fill(10, 31 + index * 18, pollWidth + 55, 45 + index * 18, ARGB.color(150, 0, 0, 0));
        if (settings.showCurrentPercentage) {
            graphics.fill(10, 31 + index * 18, 10 + Mth.floor((pollWidth + 45) * ratio), 45 + index * 18, getColor(150));
        }
        graphics.drawString(client.font, Component.translatableEscape("entropy.votes.display", 1 + index + offset,
            events.get(index)), 15, 34 + index * 18, ARGB.color(255, 255, 255, 255));
        if (settings.showCurrentPercentage) {
            Component percentage = Component.translatableEscape("entropy.votes.percentage", Mth.floor(ratio * 100));
            graphics.drawString(client.font, percentage, pollWidth + 52 - client.font.width(percentage),
                34 + index * 18, ARGB.color(255, 255, 255, 255));
        }
    }

    public void sendPoll(int voteID, List<Component> options) {
        publishToIntegrations(integrations, voteID, options);
        overlayServer.onNewVote(voteID, options);
    }

    private void publishToIntegrations(List<Integration> recipients, int voteID, List<Component> options) {
        if (EntropyClient.getInstance().integrationsSettings.sendChatMessages) {
            for (Integration integration : recipients) {
                try {
                    integration.sendPoll(voteID, options);
                } catch (RuntimeException failure) {
                    EntropyClient.LOGGER.warn("Could not publish poll to an integration ({})", failure.getClass().getSimpleName());
                }
            }
        }
    }

    public void sendMessage(String message) {
        if (EntropyClient.getInstance().integrationsSettings.sendChatMessages) {
            for (Integration integration : integrations) {
                try {
                    integration.sendMessage(message);
                } catch (RuntimeException failure) {
                    EntropyClient.LOGGER.warn("Could not send integration message ({})", failure.getClass().getSimpleName());
                }
            }
        }
    }

    public void sendVotes() {
        if (!enabled || pollId < 0) return;
        ClientPlayNetworking.send(new ServerboundVotes(pollId, state.drainDeltas()));
        overlayServer.updateVote(pollId, events, state.totals());
    }

    public void republishPoll() {
        republishPoll(integrations);
    }

    public void republishPoll(List<Integration> recipients) {
        if (enabled && pollId >= 0) {
            publishToIntegrations(recipients, pollId, events);
            overlayServer.updateVote(pollId, events, state.totals());
        }
    }

    public void sendCurrentPoll(Integration recipient) {
        client.execute(() -> {
            if (enabled && pollId >= 0 && integrations.contains(recipient))
                publishToIntegrations(List.of(recipient), pollId, events);
        });
    }

    public int getColor(int alpha) {
        return integrations.isEmpty() ? ARGB.color(alpha, 128, 192, 64) : integrations.getFirst().getColor(alpha);
    }
}

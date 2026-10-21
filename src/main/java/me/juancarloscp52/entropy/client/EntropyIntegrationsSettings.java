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

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import me.juancarloscp52.entropy.client.integrations.discord.DiscordIntegrationSettings;
import me.juancarloscp52.entropy.client.integrations.twitch.TwitchIntegrationSettings;
import me.juancarloscp52.entropy.client.integrations.youtube.YouTubeIntegrationSettings;

import java.util.Optional;
import java.util.function.Supplier;

public class EntropyIntegrationsSettings {
    public static final Codec<EntropyIntegrationsSettings> CODEC = RecordCodecBuilder.create(i -> i.group(
        defaultField("discord", DiscordIntegrationSettings.CODEC, DiscordIntegrationSettings::new).forGetter(s -> s.discord),
        defaultField("twitch", TwitchIntegrationSettings.CODEC, TwitchIntegrationSettings::new).forGetter(s -> s.twitch),
        defaultField("youtube", YouTubeIntegrationSettings.CODEC, YouTubeIntegrationSettings::new).forGetter(s -> s.youtube),
        Codec.BOOL.optionalFieldOf("send_chat_messages", true).forGetter(s -> s.sendChatMessages),
        Codec.BOOL.optionalFieldOf("show_current_percentage", true).forGetter(s -> s.showCurrentPercentage),
        Codec.BOOL.optionalFieldOf("show_upcoming_events", true).forGetter(s -> s.showUpcomingEvents),
        defaultField("overlay", OverlaySettings.CODEC, OverlaySettings::new).forGetter(s -> s.overlay),
        defaultField("websocket", WebsocketSettings.CODEC, WebsocketSettings::new).forGetter(s -> s.websocket)
    ).apply(i, EntropyIntegrationsSettings::new));

    private static <T> MapCodec<T> defaultField(String name, Codec<T> codec, Supplier<T> factory) {
        return codec.optionalFieldOf(name).xmap(value -> value.orElseGet(factory), Optional::of);
    }

    public EntropyIntegrationsSettings(final DiscordIntegrationSettings discord, final TwitchIntegrationSettings twitch, final YouTubeIntegrationSettings youtube, final boolean sendChatMessages, final boolean showCurrentPercentage, final boolean showUpcomingEvents) {
        this(discord, twitch, youtube, sendChatMessages, showCurrentPercentage, showUpcomingEvents, new OverlaySettings(), new WebsocketSettings());
    }

    public EntropyIntegrationsSettings(final DiscordIntegrationSettings discord, final TwitchIntegrationSettings twitch, final YouTubeIntegrationSettings youtube, final boolean sendChatMessages, final boolean showCurrentPercentage, final boolean showUpcomingEvents, OverlaySettings overlay, WebsocketSettings websocket) {
        this.discord = discord;
        this.twitch = twitch;
        this.youtube = youtube;
        this.sendChatMessages = sendChatMessages;
        this.showCurrentPercentage = showCurrentPercentage;
        this.showUpcomingEvents = showUpcomingEvents;
        this.overlay = overlay;
        this.websocket = websocket;
    }

    public EntropyIntegrationsSettings() {
    }

    public DiscordIntegrationSettings discord = new DiscordIntegrationSettings();
    public TwitchIntegrationSettings twitch = new TwitchIntegrationSettings();
    public YouTubeIntegrationSettings youtube = new YouTubeIntegrationSettings();
    public OverlaySettings overlay = new OverlaySettings();
    public WebsocketSettings websocket = new WebsocketSettings();

    public boolean sendChatMessages = true;
    public boolean showCurrentPercentage = true;
    public boolean showUpcomingEvents = true;

    public boolean shouldUseAlternateOffsets() {
        return twitch.enabled || youtube.enabled;
    }
}

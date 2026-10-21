package me.juancarloscp52.entropy.client;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** Client-only presentation. These flags never change voting or event execution. */
public class OverlaySettings {
    public static final Codec<OverlaySettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.BOOL.optionalFieldOf("minecraft_hud_enabled", true).forGetter(s -> s.minecraftHudEnabled),
        Codec.BOOL.optionalFieldOf("show_timer", true).forGetter(s -> s.showTimer),
        Codec.BOOL.optionalFieldOf("show_event_queue", true).forGetter(s -> s.showEventQueue),
        Codec.BOOL.optionalFieldOf("show_poll_status", true).forGetter(s -> s.showPollStatus)
    ).apply(instance, OverlaySettings::new));

    public boolean minecraftHudEnabled = true;
    public boolean showTimer = true;
    public boolean showEventQueue = true;
    public boolean showPollStatus = true;

    public OverlaySettings() {}

    public OverlaySettings(boolean minecraftHudEnabled, boolean showTimer, boolean showEventQueue, boolean showPollStatus) {
        this.minecraftHudEnabled = minecraftHudEnabled;
        this.showTimer = showTimer;
        this.showEventQueue = showEventQueue;
        this.showPollStatus = showPollStatus;
    }
}

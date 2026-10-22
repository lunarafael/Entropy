package me.juancarloscp52.entropy.client;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** Loopback services used by OBS. No credentials are exposed by these services. */
public class WebsocketSettings {
    public static final Codec<WebsocketSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.BOOL.optionalFieldOf("enabled", true).forGetter(s -> s.enabled),
        Codec.intRange(1, 65535).optionalFieldOf("port", 9091).forGetter(s -> s.port),
        Codec.intRange(1, 65535).optionalFieldOf("http_port", 9092).forGetter(s -> s.httpPort)
    ).apply(instance, WebsocketSettings::new));

    public boolean enabled = true;
    public int port = 9091;
    public int httpPort = 9092;

    public WebsocketSettings() {}

    public WebsocketSettings(boolean enabled, int port, int httpPort) {
        this.enabled = enabled;
        this.port = port;
        this.httpPort = httpPort;
    }
}

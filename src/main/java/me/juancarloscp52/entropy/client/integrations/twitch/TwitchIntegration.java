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

package me.juancarloscp52.entropy.client.integrations.twitch;

import me.juancarloscp52.entropy.client.EntropyClient;
import me.juancarloscp52.entropy.client.EntropyIntegrationsSettings;
import me.juancarloscp52.entropy.client.VotingClient;
import me.juancarloscp52.entropy.client.integrations.Integration;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import org.pircbotx.Configuration;
import org.pircbotx.PircBotX;
import org.pircbotx.cap.EnableCapHandler;
import org.pircbotx.hooks.ListenerAdapter;
import org.pircbotx.hooks.events.JoinEvent;
import org.pircbotx.hooks.events.MessageEvent;
import org.pircbotx.hooks.events.NoticeEvent;
import org.pircbotx.hooks.managers.GenericListenerManager;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/** One instance owns one token, channel, worker and retry loop. Settings changes recreate it. */
public class TwitchIntegration extends ListenerAdapter implements Integration {
    public enum Status { DISCONNECTED, CONNECTING, CONNECTED, AUTH_ERROR, ERROR }

    private final EntropyIntegrationsSettings settings = EntropyClient.getInstance().integrationsSettings;
    private final VotingClient votingClient;
    private final VotingClient.VoteSource voteSource;
    private final String token;
    private final String channel;
    private final Object validationLock = new Object();
    private volatile Status status = Status.DISCONNECTED;
    private volatile PircBotX ircChatBot;
    private volatile HttpsURLConnection validationConnection;
    private volatile boolean stopped;
    private volatile boolean canWrite;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> tokenCheck;
    private volatile String login;

    public TwitchIntegration(VotingClient votingClient) {
        this.votingClient = votingClient;
        this.voteSource = votingClient.newVoteSource();
        token = TwitchAuth.normalizeToken(settings.twitch.token);
        channel = settings.twitch.channel.trim().toLowerCase(Locale.ROOT);
        start();
    }

    public Status getStatus() { return status; }

    public String getStatusTranslationKey() {
        return "entropy.options.integrations.twitch.status." + status.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public synchronized void start() {
        if (executor != null || stopped) return;
        executor = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "entropy-twitch");
            thread.setDaemon(true);
            return thread;
        });
        status = Status.CONNECTING;
        executor.execute(this::connectionLoop);
    }

    private void connectionLoop() {
        int failures = 0;
        while (!stopped && status != Status.AUTH_ERROR && !Thread.currentThread().isInterrupted()) {
            PircBotX bot = null;
            try {
                status = Status.CONNECTING;
                TwitchAuth.Identity identity = validateToken();
                if (stopped) return;
                login = identity.login();
                canWrite = identity.canWrite();
                Configuration config = new Configuration.Builder()
                        .setAutoNickChange(false)
                        .setOnJoinWhoEnabled(false)
                        .setCapEnabled(true)
                        .addCapHandler(new EnableCapHandler("twitch.tv/tags"))
                        .addCapHandler(new EnableCapHandler("twitch.tv/commands"))
                        .addCapHandler(new EnableCapHandler("twitch.tv/membership"))
                        .setEncoding(StandardCharsets.UTF_8)
                        .addServer("irc.chat.twitch.tv", 6697)
                        .setSocketFactory(SSLSocketFactory.getDefault())
                        .setSocketConnectTimeout(10000)
                        .setSocketTimeout(300000)
                        .setName(identity.login())
                        .setLogin(identity.login())
                        .setServerPassword("oauth:" + token)
                        .addAutoJoinChannel("#" + channel)
                        .setListenerManager(new GenericListenerManager())
                        .addListener(this)
                        .setAutoReconnect(false)
                        .setShutdownHookEnabled(false)
                        .setAutoSplitMessage(false)
                        .buildConfiguration();
                bot = new PircBotX(config) {
                    @Override public void close() {
                        if (getSocket() != null) super.close();
                    }

                    @Override protected void changeSocket(Socket socket) throws IOException {
                        if (stopped || ircChatBot != this) {
                            socket.close();
                            throw new IOException("Integration stopped");
                        }
                        super.changeSocket(socket);
                        if (stopped) close();
                    }
                };
                synchronized (this) {
                    if (stopped) { bot.close(); return; }
                    ircChatBot = bot;
                    scheduleValidation(identity.expiresIn(), bot);
                }
                if (!stopped && !Thread.currentThread().isInterrupted()) bot.startBot();
                if (status == Status.CONNECTED) failures = 0;
            } catch (TwitchAuth.AuthenticationException e) {
                if (!stopped) status = Status.AUTH_ERROR;
            } catch (Exception e) {
                // Never log exception messages: IRC/HTTP libraries may embed credentials or raw traffic.
                if (!stopped && status != Status.AUTH_ERROR) status = Status.ERROR;
            } finally {
                synchronized (this) {
                    if (tokenCheck != null) tokenCheck.cancel(false);
                    tokenCheck = null;
                    if (ircChatBot == bot) ircChatBot = null;
                }
                closeBot(bot);
                canWrite = false;
            }
            if (stopped || status == Status.AUTH_ERROR) break;
            status = Status.ERROR;
            try { Thread.sleep(TwitchAuth.retryDelayMillis(failures++, ThreadLocalRandom.current().nextDouble())); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        if (stopped) status = Status.DISCONNECTED;
    }

    private TwitchAuth.Identity validateToken() throws IOException, TwitchAuth.AuthenticationException {
        synchronized (validationLock) {
            if (stopped) throw new IOException("Integration stopped");
            if (token.isBlank() || !channel.matches("[a-z0-9_]{1,25}"))
                throw new TwitchAuth.AuthenticationException();
            HttpsURLConnection connection = (HttpsURLConnection) URI.create("https://id.twitch.tv/oauth2/validate").toURL().openConnection();
            validationConnection = connection;
            try {
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Authorization", "OAuth " + token);
                if (stopped) throw new IOException("Integration stopped");
                int code = connection.getResponseCode();
                if (code == 401 || code == 403) throw new TwitchAuth.AuthenticationException();
                if (code != 200) throw new IOException("Validation unavailable");
                try (InputStream body = connection.getInputStream()) {
                    byte[] bytes = body.readNBytes(65537);
                    if (bytes.length > 65536) throw new IOException("Validation response too large");
                    return TwitchAuth.parseIdentity(new String(bytes, StandardCharsets.UTF_8));
                }
            } finally {
                validationConnection = null;
                connection.disconnect();
            }
        }
    }

    // Twitch requires validation at startup and at least hourly; earlier expiry gets an earlier check.
    private synchronized void scheduleValidation(long expiresIn, PircBotX bot) {
        if (stopped || executor.isShutdown() || ircChatBot != bot) return;
        tokenCheck = executor.schedule(() -> {
            if (stopped || ircChatBot != bot) return;
            try {
                TwitchAuth.Identity identity = validateToken();
                if (stopped || ircChatBot != bot) return;
                if (!identity.login().equals(login)) throw new TwitchAuth.AuthenticationException();
                canWrite = identity.canWrite();
                scheduleValidation(identity.expiresIn(), bot);
            } catch (TwitchAuth.AuthenticationException e) {
                if (!stopped && ircChatBot == bot) {
                    status = Status.AUTH_ERROR;
                    canWrite = false;
                    closeBot(bot);
                }
            } catch (IOException e) {
                if (!stopped && ircChatBot == bot) {
                    status = Status.ERROR;
                    canWrite = false;
                    closeBot(bot); // The sole connection loop retries and validates again.
                }
            }
        }, Math.max(1, Math.min(3600, expiresIn)), TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        voteSource.close();
        PircBotX bot;
        ScheduledExecutorService worker;
        synchronized (this) {
            stopped = true;
            status = Status.DISCONNECTED;
            canWrite = false;
            bot = ircChatBot;
            ircChatBot = null;
            worker = executor;
            if (tokenCheck != null) tokenCheck.cancel(true);
        }
        HttpsURLConnection connection = validationConnection;
        if (connection != null) connection.disconnect();
        if (worker != null) worker.shutdownNow();
        closeBot(bot);
    }

    private static void closeBot(PircBotX bot) {
        if (bot == null) return;
        bot.stopBotReconnect();
        bot.close();
    }

    @Override
    public void onMessage(MessageEvent event) {
        if (stopped || event.getBot() != ircChatBot || status != Status.CONNECTED ||
                event.getChannel() == null || !event.getChannel().getName().equalsIgnoreCase("#" + channel)) return;
        String id = event.getTags().get("user-id");
        if (id == null || id.isBlank()) id = event.getUser().getNick().toLowerCase(Locale.ROOT);
        voteSource.processVote(event.getMessage(), "twitch:" + id);
    }

    @Override
    public void onJoin(JoinEvent event) {
        if (stopped || event.getBot() != ircChatBot || event.getUser() == null ||
                !event.getUser().getNick().equalsIgnoreCase(login) ||
                !event.getChannel().getName().equalsIgnoreCase("#" + channel)) return;
        status = Status.CONNECTED;
        votingClient.sendCurrentPoll(this);
    }

    @Override
    public void onNotice(NoticeEvent event) {
        if (stopped || event.getBot() != ircChatBot) return;
        String notice = event.getMessage().toLowerCase(Locale.ROOT);
        if (notice.contains("login authentication failed") || notice.contains("improperly formatted auth") ||
                notice.contains("login unsuccessful")) {
            status = Status.AUTH_ERROR;
            canWrite = false;
            closeBot(ircChatBot);
        }
    }

    @Override
    public void sendPoll(int voteID, List<Component> events) {
        int offset = voteID % 2 == 0 ? 4 : 0;
        StringBuilder message = new StringBuilder("Current poll:");
        for (int i = 0; i < events.size(); i++)
            message.append(String.format("[ %d - %s ] ", i + 1 + offset, events.get(i).getString()));
        sendMessage(message.toString());
    }

    @Override
    public void sendMessage(String message) {
        PircBotX bot = ircChatBot;
        if (stopped || status != Status.CONNECTED || !EntropyClient.getInstance().integrationsSettings.sendChatMessages || !canWrite || bot == null) return;
        try {
            executor.execute(() -> {
                if (stopped || ircChatBot != bot || status != Status.CONNECTED || !EntropyClient.getInstance().integrationsSettings.sendChatMessages || !canWrite) return;
                try {
                    bot.sendIRC().message("#" + channel, "/me [Entropy Bot] " + message.replace('\r', ' ').replace('\n', ' '));
                } catch (RuntimeException e) {
                    if (!stopped && ircChatBot == bot && status != Status.AUTH_ERROR) {
                        status = Status.ERROR;
                        closeBot(bot);
                    }
                }
            });
        } catch (RejectedExecutionException ignored) { /* stop() won the race with a queued notification. */ }
    }

    @Override
    public int getColor(int alpha) { return ARGB.color(alpha, 145, 70, 255); }
}

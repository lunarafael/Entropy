/**
 * @author Kanawanagasaki
 */

package me.juancarloscp52.entropy.client.integrations.youtube;

import me.juancarloscp52.entropy.client.ClientEventHandler;
import me.juancarloscp52.entropy.client.EntropyClient;
import me.juancarloscp52.entropy.client.EntropyIntegrationsSettings;
import me.juancarloscp52.entropy.client.VotingClient;
import me.juancarloscp52.entropy.client.integrations.Integration;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class YoutubeIntegration implements Integration {
    public static final Logger LOGGER = LogManager.getLogger();

    private static final int THRESHOLD_INTERVAL = 3000;
    private static final int END_POLLING_OFFSET = 300;

    private ClientEventHandler _clientEventHandler;
    private VotingClient _votingClient;
    private final VotingClient.VoteSource _voteSource;
    private ExecutorService _executor;
    private EntropyIntegrationsSettings settings() { return EntropyClient.getInstance().integrationsSettings; }
    private boolean _isAccessTokenValid = false;
    private String _liveChatId = "";
    private String _nextPageToken = null;
    private volatile boolean _isRunning = false;
    private final Queue<String> _messagesToSend = new ConcurrentLinkedQueue<>();

    public YoutubeIntegration(ClientEventHandler clientEventHandler, VotingClient votingClient) {
        _clientEventHandler = clientEventHandler;
        _votingClient = votingClient;
        _voteSource = votingClient.newVoteSource();
        _executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "Entropy YouTube");
            thread.setDaemon(true);
            return thread;
        });

        this.start();
    }

    @Override
    public void start() {
        _isRunning = true;
        _executor.execute(() -> {
            _isAccessTokenValid = YoutubeApi.validateAccessToken(settings().youtube.accessToken);
            if (!_isAccessTokenValid)
                _isAccessTokenValid = YoutubeApi.refreshAccessToken(settings().youtube.clientId, settings().youtube.secret,
                        settings().youtube.refreshToken);
            if (!_isAccessTokenValid)
                return;

            var broadcasts = YoutubeApi.getLiveBroadcasts(settings().youtube.accessToken);
            if (broadcasts == null) {
                LOGGER.warn("[Youtube integration] Failed to fetch live broadcasts");
                return;
            }
            if (broadcasts.items.length == 0) {
                LOGGER.warn("[Youtube integration] Failed to find any live broadcasts");
                return;
            }
            var broadcast = broadcasts.items[broadcasts.items.length - 1];
            _liveChatId = broadcast.snippet.liveChatId;
            LOGGER.info("[Youtube integration] Started listening for chat messages for broadcast with the title \""
                    + broadcast.snippet.title + "\" on the channel \"" + broadcast.snippet.channelId + "\"");

            _nextPageToken = YoutubeApi.getChatMessagesLastPage(settings().youtube.accessToken, _liveChatId);

            if (_isRunning) _votingClient.sendCurrentPoll(this);
            while (_isRunning && _isAccessTokenValid) {
                try {
                    int expectedPoll = _voteSource.pollId();
                    var messages = YoutubeApi.getChatMessages(settings().youtube.accessToken, _liveChatId,
                            _nextPageToken);
                    if (!_isRunning) break;
                    if (messages == null) {
                        int failCounter = 0;
                        do {
                            failCounter++;
                            _isAccessTokenValid = YoutubeApi.refreshAccessToken(settings().youtube.clientId,
                                    settings().youtube.secret, settings().youtube.refreshToken);
                            Thread.sleep(1000);
                        } while (_isRunning && failCounter < 4 && !_isAccessTokenValid);
                    } else {
                        if (messages.items.length != 0) {
                            _nextPageToken = messages.nextPageToken;
                            for (var message : messages.items) {
                                _voteSource.processVote(message.snippet.displayMessage,
                                        "youtube:" + message.snippet.authorChannelId, expectedPoll);
                            }
                        }

                        String outgoing;
                        while (_isRunning && (outgoing = _messagesToSend.poll()) != null) {
                            YoutubeApi.sendChatMessage(settings().youtube.accessToken, _liveChatId, outgoing);
                        }

                        int sleep = settings().youtube.pollInterval;
                        int timeBeforeEvent = _clientEventHandler.eventCountDown * 50;
                        if (THRESHOLD_INTERVAL < timeBeforeEvent && timeBeforeEvent < sleep)
                            sleep = timeBeforeEvent - END_POLLING_OFFSET;
                        Thread.sleep(Math.max(messages.pollingIntervalMillis + 100, sleep));
                    }

                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception ex) {
                    LOGGER.warn("YouTube integration request failed ({}).", ex.getClass().getSimpleName());
                }
            }

            LOGGER.info("[Youtube integration] Stopped listening for chat messages");
        });
    }

    @Override
    public void stop() {
        _voteSource.close();
        _isRunning = false;
        _executor.shutdownNow();
        _messagesToSend.clear();
    }

    @Override
    public void sendPoll(int voteID, List<Component> events) {
        int altOffset = voteID % 2 == 0 ? 4 : 0;
        StringBuilder stringBuilder = new StringBuilder("Current poll:");
        for (int i = 0; i < events.size(); i++)
            stringBuilder.append(String.format("[ %d - %s ] ", 1 + i + altOffset, events.get(i).getString()));

        sendMessage(stringBuilder.toString());
    }

    @Override
    public void sendMessage(String message) {
        _messagesToSend.add("[Entropy Bot] " + message);
    }

    @Override
    public int getColor(int alpha) {
        return ARGB.color(alpha,255, 0, 0);
    }

}

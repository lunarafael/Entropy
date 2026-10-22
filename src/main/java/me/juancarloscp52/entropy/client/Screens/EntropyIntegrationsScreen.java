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

package me.juancarloscp52.entropy.client.Screens;

import me.juancarloscp52.entropy.Entropy;
import com.mojang.serialization.JsonOps;
import me.juancarloscp52.entropy.config.SettingsValidation;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Util;
import me.juancarloscp52.entropy.client.EntropyClient;
import me.juancarloscp52.entropy.client.EntropyIntegrationsSettings;
import me.juancarloscp52.entropy.client.Screens.Widgets.EntropySliderWidget;
import me.juancarloscp52.entropy.client.integrations.youtube.YoutubeApi;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.layouts.CommonLayouts;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EntropyIntegrationsScreen extends Screen {
    public static final Logger LOGGER = LogManager.getLogger();
    EntropyIntegrationsSettings settings = copy(EntropyClient.getInstance().integrationsSettings);
    private Component feedback = Component.empty();
    private boolean disposed;

    private static EntropyIntegrationsSettings copy(EntropyIntegrationsSettings source) {
        return EntropyIntegrationsSettings.CODEC.parse(JsonOps.INSTANCE,
            EntropyIntegrationsSettings.CODEC.encodeStart(JsonOps.INSTANCE, source).getOrThrow()).getOrThrow();
    }

    private TabManager tabManager;
    private TabNavigationBar tabNavigationBar;
    private HeaderAndFooterLayout layout;

    private GeneralTab general;
    private TwitchTab twitch;
    private DiscordTab discord;
    private YouTubeTab youtube;
    private OverlayTab overlay;

    Screen parent;

    public EntropyIntegrationsScreen(Screen parent) {
        super(Component.translatable("entropy.options.integrations.title"));
        this.parent = parent;
    }

    protected void init() {
        int selectedTab = tabNavigationBar == null ? 0 : tabNavigationBar.getTabs().indexOf(tabManager.getCurrentTab());
        tabManager = new TabManager(this::addRenderableWidget, this::removeWidget);
        layout = new HeaderAndFooterLayout(this);
        // Tabs own the editable widgets: retaining them also retains unsaved input across resize.
        if (general == null) {
            general = new GeneralTab();
            twitch = new TwitchTab();
            discord = new DiscordTab();
            youtube = new YouTubeTab();
            overlay = new OverlayTab();
        }
        tabNavigationBar = TabNavigationBar.builder(this.tabManager, this.width).addTabs(general, twitch, discord, youtube, overlay).build();
        addRenderableWidget(tabNavigationBar);

        LinearLayout linearLayout = layout.addToFooter(LinearLayout.horizontal().spacing(8));
        linearLayout.addChild(Button.builder(CommonComponents.GUI_DONE, (button) -> onDone()).build());
        linearLayout.addChild(Button.builder(CommonComponents.GUI_CANCEL, (button) -> onClose()).build());

        layout.visitWidgets((abstractWidget) -> {
            abstractWidget.setTabOrderGroup(1);
            addRenderableWidget(abstractWidget);
        });

        tabNavigationBar.selectTab(Math.max(0, selectedTab), false);
        repositionElements();
    }

    public void repositionElements() {
        if (tabNavigationBar != null) {
            tabNavigationBar.setWidth(width);
            tabNavigationBar.arrangeElements();
            final int bottom = tabNavigationBar.getRectangle().bottom();
            ScreenRectangle screenRectangle = new ScreenRectangle(0, bottom, width, height - layout.getFooterHeight() - bottom);
            tabManager.setTabArea(screenRectangle);
            layout.setHeaderHeight(bottom);
            layout.arrangeElements();
        }
    }

    private void onDone() {
        final long discordChannel;
        final int websocketPort;
        final int httpPort;
        try {
            discordChannel = SettingsValidation.discordChannel(discord.enabled.selected(), discord.channel.getValue(), settings.discord.channel);
            websocketPort = SettingsValidation.port(overlay.enabled.selected(), overlay.websocketPort.getValue(), settings.websocket.port);
            httpPort = SettingsValidation.port(overlay.enabled.selected(), overlay.httpPort.getValue(), settings.websocket.httpPort);
            if (overlay.enabled.selected() && websocketPort == httpPort) throw new IllegalArgumentException("overlay.port");
            if (twitch.enabled.selected() && (twitch.token.getValue().isBlank() || twitch.channel.getValue().isBlank()))
                throw new IllegalArgumentException("twitch.credentials");
            if (discord.enabled.selected() && discord.token.getValue().isBlank())
                throw new IllegalArgumentException("discord.credentials");
            if (youtube.enabled.selected() && (youtube.clientId.getValue().isBlank() || youtube.secret.getValue().isBlank()))
                throw new IllegalArgumentException("youtube.credentials");
        } catch (IllegalArgumentException exception) {
            feedback = Component.translatable("entropy.options.validation." + exception.getMessage()).withStyle(ChatFormatting.RED);
            return;
        }

        settings.twitch.enabled = twitch.enabled.selected();
        settings.twitch.token = twitch.token.getValue().trim();
        settings.twitch.channel = twitch.channel.getValue().trim();
        settings.discord.enabled = discord.enabled.selected();
        settings.discord.token = discord.token.getValue().trim();
        settings.discord.channel = discordChannel;
        settings.youtube.enabled = youtube.enabled.selected();
        settings.youtube.clientId = youtube.clientId.getValue().trim();
        settings.youtube.secret = youtube.secret.getValue().trim();
        settings.youtube.pollInterval = (int) (youtube.pollInterval.getValue() * 1000);
        settings.sendChatMessages = general.sendChatMessages.selected();
        settings.showCurrentPercentage = general.showPollStatus.selected();
        settings.showUpcomingEvents = general.showUpcomingEvents.selected();
        settings.overlay.minecraftHudEnabled = overlay.minecraftHud.selected();
        settings.overlay.showTimer = overlay.timer.selected();
        settings.overlay.showEventQueue = overlay.events.selected();
        settings.overlay.showPollStatus = overlay.poll.selected();
        settings.websocket.enabled = overlay.enabled.selected();
        settings.websocket.port = websocketPort;
        settings.websocket.httpPort = httpPort;

        EntropyClient client = EntropyClient.getInstance();
        EntropyIntegrationsSettings previous = client.integrationsSettings;
        // OAuth callbacks refresh the live credentials independently of this screen's draft.
        settings.youtube.accessToken = previous.youtube.accessToken;
        settings.youtube.refreshToken = previous.youtube.refreshToken;
        client.integrationsSettings = settings;
        boolean previousIntegrations = Entropy.getInstance().settings.integrations;
        Entropy.getInstance().settings.integrations = settings.twitch.enabled || settings.discord.enabled || settings.youtube.enabled;
        boolean integrationSaved = client.saveSettings();
        if (!integrationSaved || !Entropy.getInstance().saveSettings()) {
            client.integrationsSettings = previous;
            Entropy.getInstance().settings.integrations = previousIntegrations;
            if (integrationSaved) client.saveSettings(); // Restore the first file if saving the second one failed.
            feedback = Component.translatable("entropy.options.saveFailed").withStyle(ChatFormatting.RED);
            return;
        }
        client.applySettings(previous);
        if (parent instanceof EntropyConfigurationScreen configuration)
            configuration.integrationsSaved(Entropy.getInstance().settings.integrations);
        onClose();
    }

    @Override
    public void tick() {
        super.tick();
        if (overlay != null) {
            overlay.status.setMessage(Component.translatable("entropy.options.overlay.status." + EntropyClient.getInstance().getOverlayServer().getStatus().toLowerCase(Locale.ROOT)));
            overlay.url.setMessage(Component.literal(EntropyClient.getInstance().getOverlayServer().getOverlayUrl()));
        }
        if (twitch != null) {
            var handler = EntropyClient.getInstance().clientEventHandler;
            String key = handler == null ? "entropy.options.integrations.twitch.status.disconnected" : handler.getTwitchStatusTranslationKey();
            twitch.status.setMessage(Component.translatable(key));
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(font, feedback, width / 2, height - 44, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        disposed = true;
        YoutubeApi.stopHttpServer();
        if (youtube != null) youtube.executor.shutdownNow();
        minecraft.setScreen(parent);
    }

    @Environment(EnvType.CLIENT)
    class OverlayTab extends GridLayoutTab {
        private final Checkbox minecraftHud, timer, events, poll, enabled;
        private final EditBox websocketPort, httpPort;
        private final StringWidget status, url;

        OverlayTab() {
            super(Component.translatable("entropy.options.overlay.title"));
            GridLayout.RowHelper rows = layout.columnSpacing(12).rowSpacing(4).createRowHelper(2);
            minecraftHud = check(rows, "minecraftHud", settings.overlay.minecraftHudEnabled);
            enabled = check(rows, "enabled", settings.websocket.enabled);
            timer = check(rows, "timer", settings.overlay.showTimer);
            events = check(rows, "events", settings.overlay.showEventQueue);
            poll = check(rows, "poll", settings.overlay.showPollStatus);
            rows.addChild(new StringWidget(Component.translatable("entropy.options.overlay.instructions"), font).setMaxWidth(180));
            websocketPort = port(rows, "websocketPort", settings.websocket.port);
            httpPort = port(rows, "httpPort", settings.websocket.httpPort);
            rows.addChild(Button.builder(Component.translatable("entropy.options.overlay.copyUrl"), button -> {
                minecraft.keyboardHandler.setClipboard(EntropyClient.getInstance().getOverlayServer().getOverlayUrl());
                feedback = Component.translatable("entropy.options.overlay.copied").withStyle(ChatFormatting.GREEN);
            }).width(150).build());
            rows.addChild(Button.builder(Component.translatable("entropy.options.overlay.open"), button ->
                Util.getPlatform().openUri(EntropyClient.getInstance().getOverlayServer().getOverlayUrl())).width(150).build());
            rows.addChild(Button.builder(Component.translatable("entropy.options.overlay.preview"), button -> {
                EntropyClient.getInstance().getOverlayServer().preview();
                feedback = Component.translatable("entropy.options.overlay.status." + EntropyClient.getInstance().getOverlayServer().getStatus().toLowerCase(Locale.ROOT));
            }).width(150).build());
            status = rows.addChild(new StringWidget(Component.translatable("entropy.options.overlay.status." + EntropyClient.getInstance().getOverlayServer().getStatus().toLowerCase(Locale.ROOT)), font));
            url = rows.addChild(new StringWidget(Component.literal(EntropyClient.getInstance().getOverlayServer().getOverlayUrl()), font), 2);
        }

        private Checkbox check(GridLayout.RowHelper rows, String key, boolean selected) {
            Checkbox box = Checkbox.builder(Component.translatable("entropy.options.overlay." + key), font).selected(selected).build();
            rows.addChild(box);
            return box;
        }

        private EditBox port(GridLayout.RowHelper rows, String key, int value) {
            EditBox field = new EditBox(font, 150, 20, Component.translatable("entropy.options.overlay." + key));
            field.setValue(Integer.toString(value));
            field.setMaxLength(5);
            rows.addChild(CommonLayouts.labeledElement(font, field, Component.translatable("entropy.options.overlay." + key)));
            return field;
        }
    }

    @Environment(EnvType.CLIENT)
    class GeneralTab extends GridLayoutTab {
        private final Checkbox sendChatMessages;
        private final Checkbox showPollStatus;
        private final Checkbox showUpcomingEvents;

        public GeneralTab() {
            super(Component.translatable("entropy.options.integrations.general"));

            GridLayout.RowHelper rowHelper = layout.columnSpacing(10).rowSpacing(8).createRowHelper(1);

            showPollStatus = Checkbox.builder(Component.translatable("entropy.options.integrations.showPollStatus"), font)
                .selected(settings.showCurrentPercentage)
                .build();
            rowHelper.addChild(showPollStatus);

            showUpcomingEvents = Checkbox.builder(Component.translatable("entropy.options.integrations.showUpcomingEvents"), font)
                .selected(settings.showUpcomingEvents)
                .build();
            rowHelper.addChild(showUpcomingEvents);

            sendChatMessages = Checkbox.builder(Component.translatable("entropy.options.integrations.sendChatFeedBack"), font)
                .selected(settings.sendChatMessages)
                .build();
            rowHelper.addChild(sendChatMessages);
        }
    }

    @Environment(EnvType.CLIENT)
    class TwitchTab extends GridLayoutTab {
        private final StringWidget status;
        private final Checkbox enabled;
        private final EditBox token;
        private final EditBox channel;

        public TwitchTab() {
            super(Component.translatable("entropy.options.integrations.twitch"));

            GridLayout.RowHelper rowHelper = layout.columnSpacing(10).rowSpacing(8).createRowHelper(1);

            enabled = Checkbox.builder(Component.translatable("entropy.options.enabled"), font)
                .tooltip(Tooltip.create(Component.translatable("entropy.options.integrations.twitch.help")))
                .selected(settings.twitch.enabled)
                .build();
            rowHelper.addChild(enabled);

            token = new EditBox(font, 200, 20, Component.translatable("entropy.options.integrations.twitch.OAuthToken"));
            token.setMaxLength(64);
            token.setValue(settings.twitch.token);
            token.addFormatter((s, integer) -> FormattedCharSequence.forward("*".repeat(s.length()), Style.EMPTY));
            rowHelper.addChild(
                CommonLayouts.labeledElement(font, token, Component.translatable("entropy.options.integrations.twitch.OAuthToken")),
                rowHelper.newCellSettings().alignHorizontallyCenter()
            );

            channel = new EditBox(font, 200, 20, Component.translatable("entropy.options.integrations.twitch.channelName"));
            channel.setValue(settings.twitch.channel);
            rowHelper.addChild(
                CommonLayouts.labeledElement(font, channel, Component.translatable("entropy.options.integrations.twitch.channelName")),
                rowHelper.newCellSettings().alignHorizontallyCenter()
            );
            status = rowHelper.addChild(new StringWidget(Component.translatable("entropy.options.integrations.twitch.status.disconnected"), font));
        }
    }

    @Environment(EnvType.CLIENT)
    class DiscordTab extends GridLayoutTab {
        private final Checkbox enabled;
        private final EditBox token;
        private final EditBox channel;

        public DiscordTab() {
            super(Component.translatable("entropy.options.integrations.discord"));

            GridLayout.RowHelper rowHelper = layout.columnSpacing(10).rowSpacing(8).createRowHelper(1);

            enabled = Checkbox.builder(Component.translatable("entropy.options.enabled"), font)
                .tooltip(Tooltip.create(Component.translatable("entropy.options.integrations.discord.help")))
                .selected(settings.discord.enabled)
                .build();
            rowHelper.addChild(enabled);

            token = new EditBox(font, 200, 20, Component.translatable("entropy.options.integrations.discord.token"));
            token.setMaxLength(128);
            token.setValue(settings.discord.token);
            token.addFormatter((s, integer) -> FormattedCharSequence.forward("*".repeat(s.length()), Style.EMPTY));
            rowHelper.addChild(
                CommonLayouts.labeledElement(font, token, Component.translatable("entropy.options.integrations.discord.token")),
                rowHelper.newCellSettings().alignHorizontallyCenter()
            );
            channel = new EditBox(font, 200, 20, Component.translatable("entropy.options.integrations.discord.channelId"));
            channel.setValue(String.valueOf(settings.discord.channel));
            channel.setMaxLength(128);
            rowHelper.addChild(
                CommonLayouts.labeledElement(font, channel, Component.translatable("entropy.options.integrations.discord.channelId")),
                rowHelper.newCellSettings().alignHorizontallyCenter()
            );
        }
    }

    @Environment(EnvType.CLIENT)
    class YouTubeTab extends GridLayoutTab {
        private final Checkbox enabled;
        private final EditBox clientId;
        private final EditBox secret;
        private final StringWidget authStatus;
        private Button youtubeAuth = null;
        private final ExecutorService executor;
        private final EntropySliderWidget pollInterval;

        private void setAuthStatus(Component message) {
            minecraft.execute(() -> {
                if (!disposed) authStatus.setMessage(message);
            });
        }

        public YouTubeTab() {
            super(Component.translatable("entropy.options.integrations.youtube"));

            GridLayout.RowHelper rowHelper = layout.columnSpacing(10).rowSpacing(4).createRowHelper(1);

            enabled = Checkbox.builder(Component.translatable("entropy.options.enabled"), font)
                .tooltip(Tooltip.create(Component.translatable("entropy.options.integrations.youtube.help")))
                .selected(settings.youtube.enabled)
                .build();
            rowHelper.addChild(enabled);

            clientId = new EditBox(font, 200, 20, Component.translatable("entropy.options.integrations.youtube.clientId"));
            clientId.setMaxLength(128);
            clientId.setValue(settings.youtube.clientId);
            rowHelper.addChild(
                CommonLayouts.labeledElement(font, clientId, Component.translatable("entropy.options.integrations.youtube.clientId")),
                rowHelper.newCellSettings().alignHorizontallyCenter()
            );

            secret = new EditBox(font, 200, 20, Component.translatable("entropy.options.integrations.youtube.secret"));
            secret.setMaxLength(64);
            secret.setValue(settings.youtube.secret);
            secret.addFormatter((s, integer) -> FormattedCharSequence.forward("*".repeat(s.length()), Style.EMPTY));
            rowHelper.addChild(
                CommonLayouts.labeledElement(font, secret, Component.translatable("entropy.options.integrations.youtube.secret")),
                rowHelper.newCellSettings().alignHorizontallyCenter()
            );

            authStatus = new StringWidget(200, 20, Component.translatable("entropy.options.integrations.youtube.checkingAccessToken").withStyle(ChatFormatting.GOLD), font);
            rowHelper.addChild(authStatus);

            youtubeAuth = Button.builder(Component.translatable("entropy.options.integrations.youtube.authorize"), onPress -> {
                youtubeAuth.setMessage(Component.translatable("entropy.options.integrations.youtube.authorizing"));
                setAuthStatus(Component.translatable("entropy.options.integrations.youtube.authorizing"));

                YoutubeApi.authorize(clientId.getValue(), secret.getValue(), (isSuccessful, message) -> {
                    minecraft.execute(() -> {
                        if (!disposed) youtubeAuth.setMessage(Component.translatable("entropy.options.integrations.youtube.authorize"));
                    });

                    if (isSuccessful) {
                        // Checking if we can get broadcasts, if not, then we may have exceeded the quota
                        var broadcasts = YoutubeApi.getLiveBroadcasts(EntropyClient.getInstance().integrationsSettings.youtube.accessToken);
                        if (broadcasts == null) {
                            setAuthStatus(Component.translatable("entropy.options.integrations.youtube.quotaExceeded").withStyle(ChatFormatting.DARK_RED));
                        }
                        else {
                            setAuthStatus(Component.translatable("entropy.options.integrations.youtube.accessTokenValid").withStyle(ChatFormatting.DARK_GREEN));
                        }
                    }
                    else {
                        setAuthStatus(Component.translatable("entropy.options.integrations.youtube.accessTokenInvalid").withStyle(ChatFormatting.DARK_RED));
                    }
                });
            }).width(200).build();
            rowHelper.addChild(youtubeAuth);

            executor = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "entropy-youtube-settings");
                thread.setDaemon(true);
                return thread;
            });
            executor.execute(() -> {
                boolean isAccessTokenValid = !settings.youtube.accessToken.isBlank() && YoutubeApi.validateAccessToken(settings.youtube.accessToken);
                if (!isAccessTokenValid && !settings.youtube.refreshToken.isBlank())
                    isAccessTokenValid = YoutubeApi.refreshAccessToken(settings.youtube.clientId, settings.youtube.secret, settings.youtube.refreshToken);

                if (isAccessTokenValid) {
                    // Checking if we can get broadcasts, if not, then we may have exceeded the quota
                    var broadcasts = YoutubeApi.getLiveBroadcasts(EntropyClient.getInstance().integrationsSettings.youtube.accessToken);
                    if (broadcasts == null) {
                        setAuthStatus(Component.translatable("entropy.options.integrations.youtube.quotaExceeded").withStyle(ChatFormatting.DARK_RED));
                    }
                    else {
                        setAuthStatus(Component.translatable("entropy.options.integrations.youtube.accessTokenValid").withStyle(ChatFormatting.DARK_GREEN));
                    }
                }
                else {
                    setAuthStatus(Component.translatable("entropy.options.integrations.youtube.accessTokenInvalid").withStyle(ChatFormatting.DARK_RED));
                }
            });

            pollInterval = new EntropySliderWidget(0, 0, 200, 20, "entropy.options.integrations.youtube.pollInterval", settings.youtube.pollInterval / 1000.0, 2, 10, value -> Component.literal(String.format(Locale.ROOT, "%1.1f", value)), value -> {});
            pollInterval.setTooltip(Tooltip.create(Component.translatable("entropy.options.integrations.youtube.pollInterval.tooltip")));
            rowHelper.addChild(pollInterval);
        }
    }
}

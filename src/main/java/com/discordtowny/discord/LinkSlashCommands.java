package com.discordtowny.discord;

import com.discordtowny.config.Messages;
import com.discordtowny.config.PluginConfig;
import com.discordtowny.link.LinkService;
import com.discordtowny.model.AccountLink;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;

/**
 * Discord adapter for the linking slash commands (/link and /unlink).
 *
 * <p>Linking responses are mandatory ephemeral so that the
 * link code is not publicly exposed in guild channels.
 */
public final class LinkSlashCommands extends ListenerAdapter {

    private final LinkService linkService;
    private final PluginConfig config;
    private final Messages messages;
    private final Clock clock;
    private final Consumer<String> warning;
    private final ConcurrentHashMap<String, Instant> userCooldowns = new ConcurrentHashMap<>();
    private final AtomicBoolean channelWarningLogged = new AtomicBoolean(false);

    public LinkSlashCommands(
            LinkService linkService,
            PluginConfig config,
            Messages messages,
            Clock clock,
            Consumer<String> warning) {
        this.linkService = Objects.requireNonNull(linkService, "linkService");
        this.config = Objects.requireNonNull(config, "config");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.warning = warning != null ? warning : (msg -> Logger.getLogger("DiscordTowny").warning(msg));
    }

    public LinkSlashCommands(LinkService linkService, PluginConfig config, Messages messages, Clock clock) {
        this(linkService, config, messages, clock, null);
    }

    public LinkSlashCommands(LinkService linkService, PluginConfig config, Messages messages) {
        this(linkService, config, messages, Clock.systemUTC(), null);
    }

    public LinkSlashCommands(LinkService linkService, PluginConfig config, Messages messages, Consumer<String> warning) {
        this(linkService, config, messages, Clock.systemUTC(), warning);
    }

    /**
     * Slash command definitions to register in the Discord guild,
     * filtering those disabled by configuration.
     */
    public static List<SlashCommandData> getCommandData(PluginConfig config) {
        List<SlashCommandData> data = new ArrayList<>();
        if (config == null || config.commands().byName("link").map(PluginConfig.DiscordCommand::enabled).orElse(true)) {
            data.add(Commands.slash("link", "Link your Minecraft account with Discord")
                    .addOption(OptionType.STRING, "code",
                            "6-character code generated in-game with /dt link", true));
        }
        if (config == null || config.commands().byName("unlink").map(PluginConfig.DiscordCommand::enabled).orElse(true)) {
            data.add(Commands.slash("unlink", "Unlink your Minecraft account from Discord"));
        }
        return data;
    }

    /**
     * Complete slash command definitions.
     */
    public static List<SlashCommandData> getCommandData() {
        return getCommandData(null);
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        String name = event.getName().toLowerCase(Locale.ROOT);
        if (!"link".equals(name) && !"unlink".equals(name)) {
            return;
        }

        // Check if the command is enabled according to configuration
        Optional<PluginConfig.DiscordCommand> cmdOpt = config.commands().byName(name);
        if (cmdOpt.isPresent() && !cmdOpt.get().enabled()) {
            event.reply(messages.plain("general.no-permission", Map.of())).setEphemeral(true).queue();
            return;
        }

        // Check if linking commands are restricted to a specific channel
        Optional<String> linkChannelId = config.discord().linkChannelId();
        if (linkChannelId.isPresent()) {
            String allowedId = linkChannelId.get();
            checkConfiguredChannel(event.getGuild(), allowedId);

            String currentChannelId = event.getChannelId();
            if (!allowedId.equals(currentChannelId)) {
                String channelMention = "<#" + allowedId + ">";
                String msg = messages.plain("linking.wrong-channel", Map.of("channel", channelMention));
                event.reply(msg).setEphemeral(true).queue();
                return;
            }
        }

        // Check per-user cooldown
        String userId = event.getUser().getId();
        Instant now = clock.instant();
        Duration cooldown = config.commands().cooldown();
        if (cooldown != null && !cooldown.isZero() && !cooldown.isNegative()) {
            Instant lastExecution = userCooldowns.get(userId);
            if (lastExecution != null) {
                Duration elapsed = Duration.between(lastExecution, now);
                if (elapsed.compareTo(cooldown) < 0) {
                    long remainingSeconds = Math.max(1, cooldown.minus(elapsed).toSeconds());
                    String msg = messages.plain("space.cooldown", Map.of("seconds", String.valueOf(remainingSeconds)));
                    event.reply(msg).setEphemeral(true).queue();
                    return;
                }
            }
            userCooldowns.put(userId, now);
        }

        if ("link".equals(name)) {
            handleLink(event);
        } else {
            handleUnlink(event);
        }
    }

    private void handleLink(SlashCommandInteractionEvent event) {
        // Mandatory ephemeral reply: the code must never remain visible in the public channel
        event.deferReply(true).queue(hook -> {
            OptionMapping option = event.getOption("code");
            if (option == null) {
                option = event.getOption("codigo");
            }

            if (option == null || option.getAsString().isBlank()) {
                hook.editOriginal(messages.plain("linking.code-invalid", Map.of())).queue();
                return;
            }

            String code = option.getAsString().trim();
            String discordId = event.getUser().getId();

            linkService.redeem(code, discordId).thenAccept(result -> {
                String reply = switch (result) {
                    case SUCCESS -> messages.plain("linking.link-success", Map.of());
                    case CODE_INVALID -> messages.plain("linking.code-invalid", Map.of());
                    case CODE_EXPIRED -> messages.plain("linking.code-expired", Map.of());
                    case DISCORD_ALREADY_LINKED -> messages.plain("linking.discord-already-linked", Map.of());
                    case PLAYER_ALREADY_LINKED -> messages.plain("linking.already-linked", Map.of());
                    case TOO_MANY_ATTEMPTS -> {
                        long minutes = config.linking().attemptLockout().toMinutes();
                        yield messages.plain("linking.too-many-attempts", Map.of(
                                "minutes", String.valueOf(minutes)
                        ));
                    }
                };
                hook.editOriginal(reply).queue();
            }).exceptionally(ex -> {
                hook.editOriginal(messages.plain("general.database-unavailable", Map.of())).queue();
                return null;
            });
        });
    }

    private void handleUnlink(SlashCommandInteractionEvent event) {
        boolean ephemeral = config.commands().byName("unlink")
                .map(PluginConfig.DiscordCommand::ephemeral)
                .orElse(true);

        event.deferReply(ephemeral).queue(hook -> {
            String discordId = event.getUser().getId();

            linkService.findByDiscordId(discordId).thenCompose(optLink -> {
                if (optLink.isEmpty()) {
                    return CompletableFuture.completedFuture(false);
                }
                AccountLink link = optLink.get();
                // Conditional unlinking propagating the version read from authorization
                return linkService.unlink(link.uuid(), discordId, link.linkedAt());
            }).thenAccept(unlinked -> {
                String reply = unlinked
                        ? messages.plain("linking.unlink-success", Map.of())
                        : messages.plain("linking.not-linked", Map.of());
                hook.editOriginal(reply).queue();
            }).exceptionally(ex -> {
                hook.editOriginal(messages.plain("general.database-unavailable", Map.of())).queue();
                return null;
            });
        });
    }

    private void checkConfiguredChannel(Guild guild, String allowedId) {
        if (channelWarningLogged.get() || guild == null) {
            return;
        }
        GuildChannel channel = guild.getGuildChannelById(allowedId);
        if (channel == null) {
            if (channelWarningLogged.compareAndSet(false, true)) {
                warning.accept("discord.link-channel-id: configured channel " + allowedId
                        + " was not found in guild '" + guild.getName() + "' (" + guild.getId()
                        + "); channel may not exist in this guild or is not visible to the bot");
            }
            return;
        }
        Member self = guild.getSelfMember();
        if (self != null) {
            if (!self.hasAccess(channel)) {
                if (channelWarningLogged.compareAndSet(false, true)) {
                    warning.accept("discord.link-channel-id: bot lacks access to view configured channel "
                            + allowedId + " in guild '" + guild.getName() + "' (" + guild.getId() + ")");
                }
            } else if (!self.hasPermission(channel, Permission.MESSAGE_SEND)) {
                if (channelWarningLogged.compareAndSet(false, true)) {
                    warning.accept("discord.link-channel-id: bot lacks MESSAGE_SEND permission in configured channel "
                            + allowedId + " in guild '" + guild.getName() + "' (" + guild.getId() + ")");
                }
            }
        }
    }
}

package com.discordtowny.discord;

import com.discordtowny.config.Messages;
import com.discordtowny.config.PluginConfig;
import com.discordtowny.link.LinkService;
import com.discordtowny.model.AccountLink;
import com.discordtowny.model.ResidentSnapshot;
import com.discordtowny.model.TownSnapshot;
import com.discordtowny.towny.TownyFacade;
import com.discordtowny.towny.TownyReadException;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;

import java.awt.Color;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;

/**
 * Discord slash commands for querying Towny data (/town, /mytown, /res, /residents, /townlist, /help).
 *
 * <p>Principles:
 * <ul>
 *   <li><b>P1: Towny wins.</b> Live Towny data is read at the time of responding.</li>
 *   <li><b>P4: Sacred main thread.</b> Towny is read only on the server's main thread via the injected
 *       {@link #mainThreadExecutor}. Plain immutable copies are returned to the JDA thread.</li>
 *   <li><b>Reply deadline.</b> Responses are deferred immediately before hopping to avoid hitting
 *       Discord's 3-second timeout.</li>
 *   <li><b>Definitive answers.</b> A non-existent entity receives a clear error message, and a failed read
 *       receives an error message. Never render an empty embed.</li>
 *   <li><b>Mandatory linking.</b> Information commands do not require linking, except identity-dependent forms
 *       (/mytown, and /town and /res without an argument).</li>
 * </ul>
 */
public final class TownySlashCommands extends ListenerAdapter {

    public static final int PAGE_SIZE = 10;

    public static final Set<String> SUPPORTED_COMMANDS = Set.of(
            "town", "mytown", "res", "residents", "townlist", "help"
    );

    public record TownCardData(TownSnapshot town, String mayorName) {}
    public record ResidentEntry(UUID uuid, String name, boolean isMayor, boolean isOnline) {}
    public record TownSummary(String name, int residentCount, Optional<String> nationName, String mayorName, boolean ruined) {}
    public record ResidentsListData(UUID townUuid, String townName, List<ResidentEntry> residents, int page) {
        public ResidentsListData(String townName, List<ResidentEntry> residents, int page) {
            this(null, townName, residents, page);
        }
    }

    private final TownyFacade townyFacade;
    private final LinkService linkService;
    private volatile PluginConfig config;
    private final Messages messages;
    private final Executor mainThreadExecutor;
    private final Executor asyncExecutor;
    private final Clock clock;
    private final ConcurrentHashMap<String, Instant> userCooldowns = new ConcurrentHashMap<>();

    public TownySlashCommands(
            TownyFacade townyFacade,
            LinkService linkService,
            PluginConfig config,
            Messages messages,
            Executor mainThreadExecutor,
            Executor asyncExecutor,
            Clock clock) {
        this.townyFacade = Objects.requireNonNull(townyFacade, "townyFacade");
        this.linkService = Objects.requireNonNull(linkService, "linkService");
        this.config = Objects.requireNonNull(config, "config");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.mainThreadExecutor = Objects.requireNonNull(mainThreadExecutor, "mainThreadExecutor");
        this.asyncExecutor = asyncExecutor != null ? asyncExecutor : ForkJoinPool.commonPool();
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public TownySlashCommands(
            TownyFacade townyFacade,
            LinkService linkService,
            PluginConfig config,
            Messages messages,
            Executor mainThreadExecutor,
            Clock clock) {
        this(townyFacade, linkService, config, messages, mainThreadExecutor, ForkJoinPool.commonPool(), clock);
    }

    public TownySlashCommands(
            TownyFacade townyFacade,
            LinkService linkService,
            PluginConfig config,
            Messages messages,
            Executor mainThreadExecutor,
            Executor asyncExecutor) {
        this(townyFacade, linkService, config, messages, mainThreadExecutor, asyncExecutor, Clock.systemUTC());
    }

    public TownySlashCommands(
            TownyFacade townyFacade,
            LinkService linkService,
            PluginConfig config,
            Messages messages,
            Executor mainThreadExecutor) {
        this(townyFacade, linkService, config, messages, mainThreadExecutor, ForkJoinPool.commonPool(), Clock.systemUTC());
    }

    void updateConfig(PluginConfig config) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
    }

    public PluginConfig getConfig() {
        return config;
    }

    /**
     * Slash command definitions to register in the Discord guild,
     * filtering those disabled by configuration.
     */
    public static List<SlashCommandData> getCommandData(PluginConfig config) {
        List<SlashCommandData> data = new ArrayList<>();
        if (isEnabled(config, "town")) {
            data.add(Commands.slash("town", "Show town information")
                    .setDescriptionLocalization(DiscordLocale.SPANISH, "Muestra información de la town")
                    .setDescriptionLocalization(DiscordLocale.SPANISH_LATAM, "Muestra información de la town")
                    .addOptions(new OptionData(OptionType.STRING, "name", "Town name (leave empty for your own town)", false)
                            .setNameLocalization(DiscordLocale.SPANISH, "nombre")
                            .setNameLocalization(DiscordLocale.SPANISH_LATAM, "nombre")
                            .setDescriptionLocalization(DiscordLocale.SPANISH, "Nombre de la town (dejar vacío para tu propia town)")
                            .setDescriptionLocalization(DiscordLocale.SPANISH_LATAM, "Nombre de la town (dejar vacío para tu propia town)")));
        }
        if (isEnabled(config, "mytown")) {
            data.add(Commands.slash("mytown", "View your own town card")
                    .setDescriptionLocalization(DiscordLocale.SPANISH, "Ver la ficha de tu propia town")
                    .setDescriptionLocalization(DiscordLocale.SPANISH_LATAM, "Ver la ficha de tu propia town"));
        }
        if (isEnabled(config, "res")) {
            data.add(Commands.slash("res", "Show resident information")
                    .setDescriptionLocalization(DiscordLocale.SPANISH, "Muestra información del resident")
                    .setDescriptionLocalization(DiscordLocale.SPANISH_LATAM, "Muestra información del resident")
                    .addOptions(new OptionData(OptionType.STRING, "resident", "Resident name (leave empty for yourself)", false)
                            .setDescriptionLocalization(DiscordLocale.SPANISH, "Nombre del resident (dejar vacío para ti mismo)")
                            .setDescriptionLocalization(DiscordLocale.SPANISH_LATAM, "Nombre del resident (dejar vacío para ti mismo)")));
        }
        if (isEnabled(config, "residents")) {
            data.add(Commands.slash("residents", "List residents of a town")
                    .setDescriptionLocalization(DiscordLocale.SPANISH, "Lista los residents de una town")
                    .setDescriptionLocalization(DiscordLocale.SPANISH_LATAM, "Lista los residents de una town")
                    .addOptions(
                            new OptionData(OptionType.STRING, "town", "Town name (leave empty for your own town)", false)
                                    .setDescriptionLocalization(DiscordLocale.SPANISH, "Nombre de la town (dejar vacío para tu propia town)")
                                    .setDescriptionLocalization(DiscordLocale.SPANISH_LATAM, "Nombre de la town (dejar vacío para tu propia town)"),
                            new OptionData(OptionType.INTEGER, "page", "Page number (default 1)", false)
                                    .setNameLocalization(DiscordLocale.SPANISH, "pagina")
                                    .setNameLocalization(DiscordLocale.SPANISH_LATAM, "pagina")
                                    .setDescriptionLocalization(DiscordLocale.SPANISH, "Número de página (por defecto 1)")
                                    .setDescriptionLocalization(DiscordLocale.SPANISH_LATAM, "Número de página (por defecto 1)")));
        }
        if (isEnabled(config, "townlist")) {
            data.add(Commands.slash("townlist", "List all towns on the server")
                    .setDescriptionLocalization(DiscordLocale.SPANISH, "Lista todas las towns del servidor")
                    .setDescriptionLocalization(DiscordLocale.SPANISH_LATAM, "Lista todas las towns del servidor")
                    .addOptions(new OptionData(OptionType.INTEGER, "page", "Page number (default 1)", false)
                            .setNameLocalization(DiscordLocale.SPANISH, "pagina")
                            .setNameLocalization(DiscordLocale.SPANISH_LATAM, "pagina")
                            .setDescriptionLocalization(DiscordLocale.SPANISH, "Número de página (por defecto 1)")
                            .setDescriptionLocalization(DiscordLocale.SPANISH_LATAM, "Número de página (por defecto 1)")));
        }
        if (isEnabled(config, "help")) {
            data.add(Commands.slash("help", "List available Discord commands")
                    .setDescriptionLocalization(DiscordLocale.SPANISH, "Muestra los comandos de Discord disponibles")
                    .setDescriptionLocalization(DiscordLocale.SPANISH_LATAM, "Muestra los comandos de Discord disponibles"));
        }
        return data;
    }

    /**
     * Complete slash command definitions without filtering.
     */
    public static List<SlashCommandData> getCommandData() {
        return getCommandData(null);
    }

    private static boolean isEnabled(PluginConfig config, String name) {
        if (config == null) {
            return true;
        }
        return config.commands().byName(name)
                .map(PluginConfig.DiscordCommand::enabled)
                .orElse(true);
    }

    private static boolean isDefaultEphemeral(String name) {
        return "mytown".equals(name) || "help".equals(name);
    }

    private boolean isGuildAllowed(IReplyCallback event) {
        Guild guild = event.getGuild();
        if (guild == null) {
            String msg = messages.plain("discord.server-only", Map.of());
            event.reply(msg).setEphemeral(true).queue();
            return false;
        }

        String configuredGuildId = config.discord().guildId();
        return configuredGuildId != null && configuredGuildId.equals(guild.getId());
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        String name = event.getName().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_COMMANDS.contains(name)) {
            return;
        }

        if (!isGuildAllowed(event)) {
            return;
        }

        // Check if the command is enabled according to configuration
        Optional<PluginConfig.DiscordCommand> cmdOpt = config.commands().byName(name);
        if (cmdOpt.isPresent() && !cmdOpt.get().enabled()) {
            event.reply(messages.plain("general.command-disabled", Map.of())).setEphemeral(true).queue();
            return;
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
                    String msg = messages.plain("general.cooldown", Map.of("seconds", String.valueOf(remainingSeconds)));
                    event.reply(msg).setEphemeral(true).queue();
                    return;
                }
            }
            userCooldowns.put(userId, now);
        }

        boolean ephemeral = cmdOpt.map(PluginConfig.DiscordCommand::ephemeral)
                .orElse(isDefaultEphemeral(name));

        // Defer reply immediately to satisfy Discord's 3-second deadline
        event.deferReply(ephemeral).queue(hook -> {
            switch (name) {
                case "town" -> handleTown(event, hook, userId);
                case "mytown" -> handleMyTown(event, hook, userId);
                case "res" -> handleRes(event, hook, userId);
                case "residents" -> handleResidents(event, hook, userId);
                case "townlist" -> handleTownlist(event, hook, userId);
                case "help" -> handleHelp(hook);
                default -> hook.editOriginal(messages.plain("general.unknown-command", Map.of())).queue();
            }
        });
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        String commandName;
        if (id.startsWith("dt:townlist:")) {
            commandName = "townlist";
        } else if (id.startsWith("dt:residents:")) {
            commandName = "residents";
        } else {
            return;
        }

        if (!isGuildAllowed(event)) {
            return;
        }

        // Check if the command is enabled according to configuration
        Optional<PluginConfig.DiscordCommand> cmdOpt = config.commands().byName(commandName);
        if (cmdOpt.isPresent() && !cmdOpt.get().enabled()) {
            event.reply(messages.plain("general.command-disabled", Map.of())).setEphemeral(true).queue();
            return;
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
                    String msg = messages.plain("general.cooldown", Map.of("seconds", String.valueOf(remainingSeconds)));
                    event.reply(msg).setEphemeral(true).queue();
                    return;
                }
            }
            userCooldowns.put(userId, now);
        }

        if ("townlist".equals(commandName)) {
            int page;
            try {
                page = Integer.parseInt(id.substring("dt:townlist:".length()));
            } catch (NumberFormatException e) {
                return;
            }
            event.deferEdit().queue(hook -> handleTownlistButton(hook, page));
        } else {
            // format: dt:residents:<page>:<townUuid>
            String rest = id.substring("dt:residents:".length());
            int colon = rest.indexOf(':');
            if (colon == -1) {
                return;
            }
            int page;
            try {
                page = Integer.parseInt(rest.substring(0, colon));
            } catch (NumberFormatException e) {
                return;
            }
            UUID townUuid;
            try {
                townUuid = UUID.fromString(rest.substring(colon + 1));
            } catch (IllegalArgumentException e) {
                return;
            }
            event.deferEdit().queue(hook -> handleResidentsButton(hook, townUuid, page));
        }
    }

    private <T> CompletableFuture<T> callTowny(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            if (!townyFacade.isAvailable()) {
                throw new TownyReadException("Towny is not available");
            }
            return supplier.get();
        }, mainThreadExecutor);
    }

    private void handleTown(SlashCommandInteractionEvent event, InteractionHook hook, String userId) {
        String targetName = getOptionalString(event, "name", "town", "nombre");
        if (targetName != null && !targetName.isBlank()) {
            callTowny(() -> {
                Optional<TownSnapshot> townOpt = townyFacade.townByName(targetName.trim());
                if (townOpt.isEmpty()) {
                    return Optional.<TownCardData>empty();
                }
                TownSnapshot town = townOpt.get();
                String mayorName = townyFacade.resident(town.mayorUuid())
                        .map(ResidentSnapshot::name)
                        .orElse(town.mayorUuid().toString());
                return Optional.of(new TownCardData(town, mayorName));
            }).thenAcceptAsync(optCard -> {
                if (optCard.isEmpty()) {
                    hook.editOriginal(messages.plain("general.town-not-found", Map.of("town", targetName.trim()))).queue();
                    return;
                }
                TownCardData card = optCard.get();
                MessageEmbed embed = buildTownEmbed(card.town(), card.mayorName());
                hook.editOriginalEmbeds(embed).queue();
            }, asyncExecutor).exceptionallyAsync(ex -> {
                hook.editOriginal(messages.plain("general.towny-read-failed", Map.of())).queue();
                return null;
            }, asyncExecutor);
            return;
        }

        linkService.findByDiscordId(userId).thenAcceptAsync(optLink -> {
            if (optLink.isEmpty()) {
                hook.editOriginal(messages.plain("linking.link-required", Map.of())).queue();
                return;
            }
            AccountLink link = optLink.get();

            callTowny(() -> {
                Optional<TownSnapshot> townOpt = townyFacade.townOf(link.uuid());
                if (townOpt.isEmpty()) {
                    return Optional.<TownCardData>empty();
                }
                TownSnapshot town = townOpt.get();
                String mayorName = townyFacade.resident(town.mayorUuid())
                        .map(ResidentSnapshot::name)
                        .orElse(town.mayorUuid().toString());
                return Optional.of(new TownCardData(town, mayorName));
            }).thenAcceptAsync(optCard -> {
                if (optCard.isEmpty()) {
                    hook.editOriginal(messages.plain("general.not-in-town", Map.of())).queue();
                    return;
                }
                TownCardData card = optCard.get();
                MessageEmbed embed = buildTownEmbed(card.town(), card.mayorName());
                hook.editOriginalEmbeds(embed).queue();
            }, asyncExecutor).exceptionallyAsync(ex -> {
                hook.editOriginal(messages.plain("general.towny-read-failed", Map.of())).queue();
                return null;
            }, asyncExecutor);
        }, asyncExecutor).exceptionallyAsync(ex -> {
            hook.editOriginal(messages.plain("general.database-unavailable", Map.of())).queue();
            return null;
        }, asyncExecutor);
    }

    private void handleMyTown(SlashCommandInteractionEvent event, InteractionHook hook, String userId) {
        linkService.findByDiscordId(userId).thenAcceptAsync(optLink -> {
            if (optLink.isEmpty()) {
                hook.editOriginal(messages.plain("linking.link-required", Map.of())).queue();
                return;
            }
            AccountLink link = optLink.get();

            callTowny(() -> {
                Optional<TownSnapshot> townOpt = townyFacade.townOf(link.uuid());
                if (townOpt.isEmpty()) {
                    return Optional.<TownCardData>empty();
                }
                TownSnapshot town = townOpt.get();
                String mayorName = townyFacade.resident(town.mayorUuid())
                        .map(ResidentSnapshot::name)
                        .orElse(town.mayorUuid().toString());
                return Optional.of(new TownCardData(town, mayorName));
            }).thenAcceptAsync(optCard -> {
                if (optCard.isEmpty()) {
                    hook.editOriginal(messages.plain("general.not-in-town", Map.of())).queue();
                    return;
                }
                TownCardData card = optCard.get();
                MessageEmbed embed = buildTownEmbed(card.town(), card.mayorName());
                hook.editOriginalEmbeds(embed).queue();
            }, asyncExecutor).exceptionallyAsync(ex -> {
                hook.editOriginal(messages.plain("general.towny-read-failed", Map.of())).queue();
                return null;
            }, asyncExecutor);
        }, asyncExecutor).exceptionallyAsync(ex -> {
            hook.editOriginal(messages.plain("general.database-unavailable", Map.of())).queue();
            return null;
        }, asyncExecutor);
    }

    private void handleRes(SlashCommandInteractionEvent event, InteractionHook hook, String userId) {
        String targetName = getOptionalString(event, "resident", "name", "player", "jugador");
        if (targetName != null && !targetName.isBlank()) {
            callTowny(() -> townyFacade.residentByName(targetName.trim()))
                    .thenAcceptAsync(optRes -> {
                        if (optRes.isEmpty()) {
                            hook.editOriginal(messages.plain("general.resident-not-found", Map.of("resident", targetName.trim()))).queue();
                            return;
                        }
                        MessageEmbed embed = buildResidentEmbed(optRes.get());
                        hook.editOriginalEmbeds(embed).queue();
                    }, asyncExecutor).exceptionallyAsync(ex -> {
                        hook.editOriginal(messages.plain("general.towny-read-failed", Map.of())).queue();
                        return null;
                    }, asyncExecutor);
            return;
        }

        linkService.findByDiscordId(userId).thenAcceptAsync(optLink -> {
            if (optLink.isEmpty()) {
                hook.editOriginal(messages.plain("linking.link-required", Map.of())).queue();
                return;
            }
            AccountLink link = optLink.get();

            callTowny(() -> townyFacade.resident(link.uuid()))
                    .thenAcceptAsync(optRes -> {
                        if (optRes.isEmpty()) {
                            String missingName = (link.lastKnownName() != null && !link.lastKnownName().isBlank())
                                    ? link.lastKnownName()
                                    : link.uuid().toString();
                            hook.editOriginal(messages.plain("general.resident-not-found", Map.of("resident", missingName))).queue();
                            return;
                        }
                        MessageEmbed embed = buildResidentEmbed(optRes.get());
                        hook.editOriginalEmbeds(embed).queue();
                    }, asyncExecutor).exceptionallyAsync(ex -> {
                        hook.editOriginal(messages.plain("general.towny-read-failed", Map.of())).queue();
                        return null;
                    }, asyncExecutor);
        }, asyncExecutor).exceptionallyAsync(ex -> {
            hook.editOriginal(messages.plain("general.database-unavailable", Map.of())).queue();
            return null;
        }, asyncExecutor);
    }

    private void handleResidents(SlashCommandInteractionEvent event, InteractionHook hook, String userId) {
        String targetTown = getOptionalString(event, "town", "name", "nombre");
        int requestedPage = getOptionalInt(event, 1, "page", "pagina");

        if (targetTown != null && !targetTown.isBlank()) {
            callTowny(() -> {
                Optional<TownSnapshot> townOpt = townyFacade.townByName(targetTown.trim());
                if (townOpt.isEmpty()) {
                    return Optional.<ResidentsListData>empty();
                }
                TownSnapshot town = townOpt.get();
                List<UUID> residentUuids = town.residentUuids();
                List<ResidentEntry> entries = new ArrayList<>(residentUuids.size());
                for (UUID uuid : residentUuids) {
                    Optional<ResidentSnapshot> resOpt = townyFacade.resident(uuid);
                    String name = resOpt.map(ResidentSnapshot::name).orElse(uuid.toString());
                    boolean isMayor = town.isMayor(uuid);
                    boolean isOnline = resOpt.map(ResidentSnapshot::online).orElse(false);
                    entries.add(new ResidentEntry(uuid, name, isMayor, isOnline));
                }
                entries.sort((a, b) -> {
                    if (a.isMayor() != b.isMayor()) {
                        return a.isMayor() ? -1 : 1;
                    }
                    if (a.isOnline() != b.isOnline()) {
                        return a.isOnline() ? -1 : 1;
                    }
                    return String.CASE_INSENSITIVE_ORDER.compare(a.name(), b.name());
                });
                return Optional.of(new ResidentsListData(town.uuid(), town.name(), entries, requestedPage));
            }).thenAcceptAsync(optData -> {
                if (optData.isEmpty()) {
                    hook.editOriginal(messages.plain("general.town-not-found", Map.of("town", targetTown.trim()))).queue();
                    return;
                }
                ResidentsListData data = optData.get();
                sendPaginatedResidents(hook, data.townUuid(), data.townName(), data.residents(), data.page());
            }, asyncExecutor).exceptionallyAsync(ex -> {
                hook.editOriginal(messages.plain("general.towny-read-failed", Map.of())).queue();
                return null;
            }, asyncExecutor);
            return;
        }

        linkService.findByDiscordId(userId).thenAcceptAsync(optLink -> {
            if (optLink.isEmpty()) {
                hook.editOriginal(messages.plain("linking.link-required", Map.of())).queue();
                return;
            }
            AccountLink link = optLink.get();

            callTowny(() -> {
                Optional<TownSnapshot> townOpt = townyFacade.townOf(link.uuid());
                if (townOpt.isEmpty()) {
                    return Optional.<ResidentsListData>empty();
                }
                TownSnapshot town = townOpt.get();
                List<UUID> residentUuids = town.residentUuids();
                List<ResidentEntry> entries = new ArrayList<>(residentUuids.size());
                for (UUID uuid : residentUuids) {
                    Optional<ResidentSnapshot> resOpt = townyFacade.resident(uuid);
                    String name = resOpt.map(ResidentSnapshot::name).orElse(uuid.toString());
                    boolean isMayor = town.isMayor(uuid);
                    boolean isOnline = resOpt.map(ResidentSnapshot::online).orElse(false);
                    entries.add(new ResidentEntry(uuid, name, isMayor, isOnline));
                }
                entries.sort((a, b) -> {
                    if (a.isMayor() != b.isMayor()) {
                        return a.isMayor() ? -1 : 1;
                    }
                    if (a.isOnline() != b.isOnline()) {
                        return a.isOnline() ? -1 : 1;
                    }
                    return String.CASE_INSENSITIVE_ORDER.compare(a.name(), b.name());
                });
                return Optional.of(new ResidentsListData(town.uuid(), town.name(), entries, requestedPage));
            }).thenAcceptAsync(optData -> {
                if (optData.isEmpty()) {
                    hook.editOriginal(messages.plain("general.not-in-town", Map.of())).queue();
                    return;
                }
                ResidentsListData data = optData.get();
                sendPaginatedResidents(hook, data.townUuid(), data.townName(), data.residents(), data.page());
            }, asyncExecutor).exceptionallyAsync(ex -> {
                hook.editOriginal(messages.plain("general.towny-read-failed", Map.of())).queue();
                return null;
            }, asyncExecutor);
        }, asyncExecutor).exceptionallyAsync(ex -> {
            hook.editOriginal(messages.plain("general.database-unavailable", Map.of())).queue();
            return null;
        }, asyncExecutor);
    }

    private void handleTownlist(SlashCommandInteractionEvent event, InteractionHook hook, String userId) {
        int requestedPage = getOptionalInt(event, 1, "page", "pagina");

        callTowny(() -> {
            List<TownSnapshot> towns = townyFacade.allTowns();
            List<TownSummary> summaries = new ArrayList<>(towns.size());
            for (TownSnapshot t : towns) {
                String mayor = townyFacade.resident(t.mayorUuid())
                        .map(ResidentSnapshot::name)
                        .orElse(messages.label("embed.unknown", Map.of()));
                summaries.add(new TownSummary(t.name(), t.residentCount(), t.nationName(), mayor, t.ruined()));
            }
            summaries.sort(Comparator.comparing(TownSummary::name, String.CASE_INSENSITIVE_ORDER));
            return summaries;
        }).thenAcceptAsync(summaries -> {
            if (summaries.isEmpty()) {
                hook.editOriginal(messages.plain("general.no-towns-found", Map.of())).queue();
                return;
            }
            sendPaginatedTownlist(hook, summaries, requestedPage);
        }, asyncExecutor).exceptionallyAsync(ex -> {
            hook.editOriginal(messages.plain("general.towny-read-failed", Map.of())).queue();
            return null;
        }, asyncExecutor);
    }

    private void handleHelp(InteractionHook hook) {
        MessageEmbed embed = buildHelpEmbed(config, messages);
        hook.editOriginalEmbeds(embed).queue();
    }

    private void handleTownlistButton(InteractionHook hook, int page) {
        callTowny(() -> {
            List<TownSnapshot> towns = townyFacade.allTowns();
            List<TownSummary> summaries = new ArrayList<>(towns.size());
            for (TownSnapshot t : towns) {
                String mayor = townyFacade.resident(t.mayorUuid())
                        .map(ResidentSnapshot::name)
                        .orElse(messages.label("embed.unknown", Map.of()));
                summaries.add(new TownSummary(t.name(), t.residentCount(), t.nationName(), mayor, t.ruined()));
            }
            summaries.sort(Comparator.comparing(TownSummary::name, String.CASE_INSENSITIVE_ORDER));
            return summaries;
        }).thenAcceptAsync(summaries -> {
            if (summaries.isEmpty()) {
                hook.editOriginal(messages.plain("general.no-towns-found", Map.of()))
                        .setComponents(Collections.emptyList())
                        .queue();
                return;
            }
            sendPaginatedTownlist(hook, summaries, page);
        }, asyncExecutor).exceptionallyAsync(ex -> {
            hook.editOriginal(messages.plain("general.towny-read-failed", Map.of()))
                    .setComponents(Collections.emptyList())
                    .queue();
            return null;
        }, asyncExecutor);
    }

    private void handleResidentsButton(InteractionHook hook, UUID townUuid, int page) {
        callTowny(() -> {
            Optional<TownSnapshot> townOpt = townyFacade.town(townUuid);
            if (townOpt.isEmpty()) {
                return Optional.<ResidentsListData>empty();
            }
            TownSnapshot town = townOpt.get();
            List<UUID> residentUuids = town.residentUuids();
            List<ResidentEntry> entries = new ArrayList<>(residentUuids.size());
            for (UUID uuid : residentUuids) {
                Optional<ResidentSnapshot> resOpt = townyFacade.resident(uuid);
                String name = resOpt.map(ResidentSnapshot::name).orElse(uuid.toString());
                boolean isMayor = town.isMayor(uuid);
                boolean isOnline = resOpt.map(ResidentSnapshot::online).orElse(false);
                entries.add(new ResidentEntry(uuid, name, isMayor, isOnline));
            }
            entries.sort((a, b) -> {
                if (a.isMayor() != b.isMayor()) {
                    return a.isMayor() ? -1 : 1;
                }
                if (a.isOnline() != b.isOnline()) {
                    return a.isOnline() ? -1 : 1;
                }
                return String.CASE_INSENSITIVE_ORDER.compare(a.name(), b.name());
            });
            return Optional.of(new ResidentsListData(town.uuid(), town.name(), entries, page));
        }).thenAcceptAsync(optData -> {
            if (optData.isEmpty()) {
                hook.editOriginal(messages.plain("general.town-not-found", Map.of("town", townUuid.toString())))
                        .setComponents(Collections.emptyList())
                        .queue();
                return;
            }
            ResidentsListData data = optData.get();
            sendPaginatedResidents(hook, data.townUuid(), data.townName(), data.residents(), data.page());
        }, asyncExecutor).exceptionallyAsync(ex -> {
            hook.editOriginal(messages.plain("general.towny-read-failed", Map.of()))
                    .setComponents(Collections.emptyList())
                    .queue();
            return null;
        }, asyncExecutor);
    }

    private void sendPaginatedResidents(InteractionHook hook, UUID townUuid, String townName, List<ResidentEntry> residents, int requestedPage) {
        int totalResidents = residents.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) totalResidents / PAGE_SIZE));
        int page = Math.max(1, Math.min(requestedPage, totalPages));

        int start = (page - 1) * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, totalResidents);
        List<ResidentEntry> pageItems = totalResidents > 0 ? residents.subList(start, end) : Collections.emptyList();

        MessageEmbed embed = buildResidentsPageEmbed(townName, pageItems, page, totalPages, totalResidents);
        var action = hook.editOriginalEmbeds(embed);
        if (totalPages > 1) {
            Button prev = Button.secondary("dt:residents:" + (page - 1) + ":" + townUuid, messages.label("embed.previous", Map.of())).withDisabled(page <= 1);
            Button next = Button.secondary("dt:residents:" + (page + 1) + ":" + townUuid, messages.label("embed.next", Map.of())).withDisabled(page >= totalPages);
            action = action.setComponents(ActionRow.of(prev, next));
        } else {
            action = action.setComponents(Collections.emptyList());
        }
        action.queue();
    }

    private void sendPaginatedTownlist(InteractionHook hook, List<TownSummary> towns, int requestedPage) {
        int totalTowns = towns.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) totalTowns / PAGE_SIZE));
        int page = Math.max(1, Math.min(requestedPage, totalPages));

        int start = (page - 1) * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, totalTowns);
        List<TownSummary> pageItems = totalTowns > 0 ? towns.subList(start, end) : Collections.emptyList();

        MessageEmbed embed = buildTownlistPageEmbed(pageItems, page, totalPages, totalTowns);
        var action = hook.editOriginalEmbeds(embed);
        if (totalPages > 1) {
            Button prev = Button.secondary("dt:townlist:" + (page - 1), messages.label("embed.previous", Map.of())).withDisabled(page <= 1);
            Button next = Button.secondary("dt:townlist:" + (page + 1), messages.label("embed.next", Map.of())).withDisabled(page >= totalPages);
            action = action.setComponents(ActionRow.of(prev, next));
        } else {
            action = action.setComponents(Collections.emptyList());
        }
        action.queue();
    }

    public MessageEmbed buildTownEmbed(TownSnapshot town, String mayorName) {
        return buildTownEmbed(town, mayorName, this.messages);
    }

    public static MessageEmbed buildTownEmbed(TownSnapshot town, String mayorName, Messages messages) {
        EmbedBuilder embed = new EmbedBuilder();
        String title = messages.label("embed.town", Map.of()) + ": " + town.name();
        if (town.ruined()) {
            title += " [" + messages.label("embed.ruined", Map.of()).toUpperCase(Locale.ROOT) + "]";
        }
        embed.setTitle(title);
        embed.setColor(town.ruined() ? Color.RED : new Color(0x2ECC71));

        embed.addField(messages.label("embed.mayor", Map.of()), mayorName, true);
        embed.addField(messages.label("embed.residents", Map.of()), String.valueOf(town.residentCount()), true);
        embed.addField(messages.label("embed.nation", Map.of()), town.nationName().orElse(messages.label("embed.none", Map.of())), true);
        embed.addField(messages.label("embed.plots", Map.of()), String.valueOf(town.townBlocks()), true);
        embed.addField(messages.label("embed.bank", Map.of()), String.format(Locale.US, "%.2f", town.bankBalance()), true);
        embed.addField(messages.label("embed.ruined", Map.of()), town.ruined() ? messages.label("embed.yes", Map.of()) : messages.label("embed.no", Map.of()), true);
        if (town.registeredMillis() > 0) {
            embed.addField(messages.label("embed.founded", Map.of()), "<t:" + (town.registeredMillis() / 1000) + ":D>", true);
        }
        return embed.build();
    }

    public MessageEmbed buildResidentEmbed(ResidentSnapshot resident) {
        return buildResidentEmbed(resident, this.messages);
    }

    public static MessageEmbed buildResidentEmbed(ResidentSnapshot resident, Messages messages) {
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle(messages.label("embed.resident", Map.of()) + ": " + resident.name());
        embed.setColor(resident.online() ? new Color(0x2ECC71) : Color.GRAY);

        embed.addField(messages.label("embed.town", Map.of()), resident.townName().orElse(messages.label("embed.none", Map.of())), true);
        embed.addField(messages.label("embed.rank", Map.of()), resident.mayor() ? messages.label("embed.mayor", Map.of()) : messages.label("embed.resident", Map.of()), true);
        embed.addField(messages.label("embed.status", Map.of()), resident.online() ? messages.label("embed.online", Map.of()) : messages.label("embed.offline", Map.of()), true);
        embed.addField(messages.label("embed.balance", Map.of()), String.format(Locale.US, "%.2f", resident.balance()), true);
        if (!resident.online() && resident.lastOnlineMillis() > 0) {
            embed.addField(messages.label("embed.last-online", Map.of()), "<t:" + (resident.lastOnlineMillis() / 1000) + ":R>", true);
        }
        return embed.build();
    }

    public MessageEmbed buildResidentsPageEmbed(
            String townName, List<ResidentEntry> pageResidents, int page, int totalPages, int totalResidents) {
        return buildResidentsPageEmbed(townName, pageResidents, page, totalPages, totalResidents, this.messages);
    }

    public static MessageEmbed buildResidentsPageEmbed(
            String townName, List<ResidentEntry> pageResidents, int page, int totalPages, int totalResidents, Messages messages) {
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle(messages.label("embed.residents", Map.of()) + ": " + townName);
        embed.setColor(new Color(0x3498DB));

        if (pageResidents.isEmpty()) {
            embed.setDescription(messages.label("embed.no-residents", Map.of()));
        } else {
            StringBuilder sb = new StringBuilder();
            int startIndex = (page - 1) * PAGE_SIZE;
            for (int i = 0; i < pageResidents.size(); i++) {
                ResidentEntry r = pageResidents.get(i);
                int number = startIndex + i + 1;
                sb.append("**").append(number).append(". ").append(r.name()).append("**");
                if (r.isMayor()) {
                    sb.append(" *(").append(messages.label("embed.mayor", Map.of())).append(")*");
                }
                sb.append(" — ").append(r.isOnline() ? "🟢 " + messages.label("embed.online", Map.of()) : "⚪ " + messages.label("embed.offline", Map.of()));
                sb.append("\n");
            }
            embed.setDescription(sb.toString().trim());
        }
        embed.setFooter(messages.label("embed.page", Map.of("current", String.valueOf(page), "total", String.valueOf(totalPages))));
        return embed.build();
    }

    public MessageEmbed buildTownlistPageEmbed(
            List<TownSummary> pageTowns, int page, int totalPages, int totalTowns) {
        return buildTownlistPageEmbed(pageTowns, page, totalPages, totalTowns, this.messages);
    }

    public static MessageEmbed buildTownlistPageEmbed(
            List<TownSummary> pageTowns, int page, int totalPages, int totalTowns, Messages messages) {
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle(messages.label("embed.town-list-title", Map.of()));
        embed.setColor(new Color(0x3498DB));

        if (pageTowns.isEmpty()) {
            embed.setDescription(messages.label("general.no-towns-found", Map.of()));
        } else {
            StringBuilder sb = new StringBuilder();
            int startIndex = (page - 1) * PAGE_SIZE;
            for (int i = 0; i < pageTowns.size(); i++) {
                TownSummary t = pageTowns.get(i);
                int number = startIndex + i + 1;
                sb.append("**").append(number).append(". ").append(t.name()).append("**");
                if (t.ruined()) {
                    sb.append(" *[").append(messages.label("embed.ruined", Map.of()).toUpperCase(Locale.ROOT)).append("]*");
                }
                sb.append(" — ").append(t.residentCount()).append(" ").append(messages.label("embed.residents", Map.of()).toLowerCase(Locale.ROOT));
                sb.append(" | ").append(messages.label("embed.mayor", Map.of())).append(": ").append(t.mayorName());
                if (t.nationName().isPresent()) {
                    sb.append(" | ").append(messages.label("embed.nation", Map.of())).append(": ").append(t.nationName().get());
                }
                sb.append("\n");
            }
            embed.setDescription(sb.toString().trim());
        }
        embed.setFooter(messages.label("embed.page", Map.of("current", String.valueOf(page), "total", String.valueOf(totalPages))));
        return embed.build();
    }

    public MessageEmbed buildHelpEmbed(PluginConfig config) {
        return buildHelpEmbed(config, this.messages);
    }

    public static MessageEmbed buildHelpEmbed(PluginConfig config, Messages messages) {
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle(messages != null ? messages.label("embed.help-title", Map.of()) : "DiscordTowny — Help");
        embed.setColor(new Color(0x3498DB));

        StringBuilder sb = new StringBuilder();
        sb.append(messages != null ? messages.label("embed.help-intro", Map.of()) : "Available Discord commands:").append("\n\n");
        if (isEnabled(config, "town")) {
            sb.append(messages != null ? messages.label("embed.help-town", Map.of()) : "• **/town [name]** — View town card (mayor, residents, bank...)").append("\n");
        }
        if (isEnabled(config, "mytown")) {
            sb.append(messages != null ? messages.label("embed.help-mytown", Map.of()) : "• **/mytown** — Shortcut to view your own town card").append("\n");
        }
        if (isEnabled(config, "res")) {
            sb.append(messages != null ? messages.label("embed.help-res", Map.of()) : "• **/res [resident]** — View resident card (town, rank, status...)").append("\n");
        }
        if (isEnabled(config, "residents")) {
            sb.append(messages != null ? messages.label("embed.help-residents", Map.of()) : "• **/residents [town] [page]** — List residents and their status").append("\n");
        }
        if (isEnabled(config, "townlist")) {
            sb.append(messages != null ? messages.label("embed.help-townlist", Map.of()) : "• **/townlist [page]** — Ordered list of all towns, paginated").append("\n");
        }
        if (isEnabled(config, "link")) {
            sb.append(messages != null ? messages.label("embed.help-link", Map.of()) : "• **/link <code>** — Link your Minecraft account with Discord").append("\n");
        }
        if (isEnabled(config, "unlink")) {
            sb.append(messages != null ? messages.label("embed.help-unlink", Map.of()) : "• **/unlink** — Unlink your Minecraft account from Discord").append("\n");
        }
        if (isEnabled(config, "help")) {
            sb.append(messages != null ? messages.label("embed.help-help", Map.of()) : "• **/help** — List available Discord commands").append("\n");
        }
        embed.setDescription(sb.toString().trim());
        return embed.build();
    }

    private static String getOptionalString(SlashCommandInteractionEvent event, String... optionNames) {
        for (String optName : optionNames) {
            OptionMapping opt = event.getOption(optName);
            if (opt != null && !opt.getAsString().isBlank()) {
                return opt.getAsString().trim();
            }
        }
        return null;
    }

    private static int getOptionalInt(SlashCommandInteractionEvent event, int defaultValue, String... optionNames) {
        for (String optName : optionNames) {
            OptionMapping opt = event.getOption(optName);
            if (opt != null) {
                try {
                    return opt.getAsInt();
                } catch (Exception ignored) {
                    // fall back to default
                }
            }
        }
        return defaultValue;
    }
}

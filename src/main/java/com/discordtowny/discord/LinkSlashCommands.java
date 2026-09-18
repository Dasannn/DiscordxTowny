package com.discordtowny.discord;

import com.discordtowny.config.Messages;
import com.discordtowny.config.PluginConfig;
import com.discordtowny.link.LinkService;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Adaptador de Discord para los comandos slash de vinculacion (/link y /unlink).
 *
 * <p>Las respuestas son efimeras para que el codigo de vinculacion no quede
 * expuesto publicamente en los canales del guild.
 */
public final class LinkSlashCommands extends ListenerAdapter {

    private final LinkService linkService;
    private final PluginConfig config;
    private final Messages messages;

    public LinkSlashCommands(LinkService linkService, PluginConfig config, Messages messages) {
        this.linkService = Objects.requireNonNull(linkService, "linkService");
        this.config = Objects.requireNonNull(config, "config");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    /**
     * Definiciones de los comandos slash para registrar en el guild de Discord.
     */
    public static List<SlashCommandData> getCommandData() {
        return List.of(
                Commands.slash("link", "Vincula tu cuenta de Minecraft con Discord")
                        .addOption(OptionType.STRING, "codigo",
                                "Codigo de 6 caracteres generado en el juego con /dt link", true),
                Commands.slash("unlink", "Desvincula tu cuenta de Minecraft de Discord")
        );
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        String name = event.getName();
        if ("link".equalsIgnoreCase(name)) {
            handleLink(event);
        } else if ("unlink".equalsIgnoreCase(name)) {
            handleUnlink(event);
        }
    }

    private void handleLink(SlashCommandInteractionEvent event) {
        // Respuesta efimera obligatoria: el codigo no debe quedar visible en el canal
        boolean ephemeral = config.commands().byName("link")
                .map(PluginConfig.DiscordCommand::ephemeral)
                .orElse(true);

        event.deferReply(ephemeral).queue(hook -> {
            OptionMapping option = event.getOption("codigo");
            if (option == null) {
                option = event.getOption("code");
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
                return linkService.unlink(optLink.get().uuid());
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
}

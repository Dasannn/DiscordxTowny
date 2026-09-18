package com.discordtowny.discord;

import com.discordtowny.config.Messages;
import com.discordtowny.config.PluginConfig;
import com.discordtowny.link.LinkService;
import com.discordtowny.model.AccountLink;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Pruebas unitarias para {@link LinkSlashCommands}.
 */
class LinkSlashCommandsTest {

    private LinkService linkService;
    private PluginConfig config;
    private Messages messages;
    private LinkSlashCommands commands;

    private SlashCommandInteractionEvent event;
    private ReplyCallbackAction replyAction;
    private InteractionHook hook;
    private User user;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        linkService = mock(LinkService.class);
        messages = mock(Messages.class);

        PluginConfig.Linking linkingConfig = new PluginConfig.Linking(
                Duration.ofMinutes(10), 3, Duration.ofMinutes(15), true);

        config = new PluginConfig(
                new PluginConfig.Discord("token", "guild", Optional.empty()),
                new PluginConfig.Database(PluginConfig.Database.Type.SQLITE, "localhost", 3306, "db", "", "", "dt_", 1, 1, Duration.ofSeconds(5)),
                new PluginConfig.Structure("Cat", "Arch", true, true, "{town}", "{town}"),
                new PluginConfig.Roles("Alcalde", "{town}", Optional.empty(), false),
                new PluginConfig.Limits(200, 2, Duration.ofSeconds(60)),
                new PluginConfig.Lifecycle(PluginConfig.Lifecycle.Action.ARCHIVE, PluginConfig.Lifecycle.Action.ARCHIVE, 30),
                new PluginConfig.Sync(Duration.ofMinutes(30), PluginConfig.Sync.Mode.REPAIR, 20, Duration.ofSeconds(5)),
                linkingConfig,
                new PluginConfig.Logging(Duration.ofSeconds(10), 100, PluginConfig.Logging.Detail.FULL),
                new PluginConfig.Updates(false, Duration.ofHours(24), false, false),
                new PluginConfig.Commands(Duration.ofSeconds(5), List.of(
                        new PluginConfig.DiscordCommand("link", true, true),
                        new PluginConfig.DiscordCommand("unlink", true, true)
                ))
        );

        commands = new LinkSlashCommands(linkService, config, messages);

        event = mock(SlashCommandInteractionEvent.class);
        replyAction = mock(ReplyCallbackAction.class);
        hook = mock(InteractionHook.class);
        user = mock(User.class);

        when(event.getUser()).thenReturn(user);
        when(user.getId()).thenReturn("123456789012345678");

        when(event.deferReply(anyBoolean())).thenReturn(replyAction);
        doAnswer(invocation -> {
            Consumer<InteractionHook> callback = invocation.getArgument(0);
            callback.accept(hook);
            return null;
        }).when(replyAction).queue(any());

        WebhookMessageEditAction editAction = mock(WebhookMessageEditAction.class);
        when(hook.editOriginal(anyString())).thenReturn(editAction);
    }

    @Test
    void getCommandDataDefineComandosCorrectos() {
        List<SlashCommandData> data = LinkSlashCommands.getCommandData();
        assertEquals(2, data.size());

        SlashCommandData linkData = data.stream().filter(d -> d.getName().equals("link")).findFirst().orElseThrow();
        assertEquals("link", linkData.getName());
        assertEquals(1, linkData.getOptions().size());
        assertEquals("codigo", linkData.getOptions().getFirst().getName());
        assertEquals(OptionType.STRING, linkData.getOptions().getFirst().getType());
        assertTrue(linkData.getOptions().getFirst().isRequired());

        SlashCommandData unlinkData = data.stream().filter(d -> d.getName().equals("unlink")).findFirst().orElseThrow();
        assertEquals("unlink", unlinkData.getName());
        assertTrue(unlinkData.getOptions().isEmpty());
    }

    @Test
    void handleLinkCanjeExitoso() {
        when(event.getName()).thenReturn("link");
        OptionMapping opt = mock(OptionMapping.class);
        when(opt.getAsString()).thenReturn("ABC234");
        when(event.getOption("codigo")).thenReturn(opt);

        when(linkService.redeem("ABC234", "123456789012345678"))
                .thenReturn(CompletableFuture.completedFuture(LinkService.LinkResult.SUCCESS));
        when(messages.plain(eq("linking.link-success"), any())).thenReturn("Cuenta vinculada con exito");

        commands.onSlashCommandInteraction(event);

        verify(event).deferReply(true); // Efimera
        verify(hook).editOriginal("Cuenta vinculada con exito");
    }

    @Test
    void handleLinkCodigoInvalido() {
        when(event.getName()).thenReturn("link");
        OptionMapping opt = mock(OptionMapping.class);
        when(opt.getAsString()).thenReturn("MAL123");
        when(event.getOption("codigo")).thenReturn(opt);

        when(linkService.redeem("MAL123", "123456789012345678"))
                .thenReturn(CompletableFuture.completedFuture(LinkService.LinkResult.CODE_INVALID));
        when(messages.plain(eq("linking.code-invalid"), any())).thenReturn("Codigo invalido");

        commands.onSlashCommandInteraction(event);

        verify(hook).editOriginal("Codigo invalido");
    }

    @Test
    void handleLinkDemasiadosIntentos() {
        when(event.getName()).thenReturn("link");
        OptionMapping opt = mock(OptionMapping.class);
        when(opt.getAsString()).thenReturn("MAL123");
        when(event.getOption("codigo")).thenReturn(opt);

        when(linkService.redeem("MAL123", "123456789012345678"))
                .thenReturn(CompletableFuture.completedFuture(LinkService.LinkResult.TOO_MANY_ATTEMPTS));
        when(messages.plain(eq("linking.too-many-attempts"), any())).thenReturn("Demasiados intentos");

        commands.onSlashCommandInteraction(event);

        verify(hook).editOriginal("Demasiados intentos");
    }

    @Test
    void handleUnlinkUsuarioVinculado() {
        when(event.getName()).thenReturn("unlink");
        UUID uuid = UUID.randomUUID();
        AccountLink link = new AccountLink(uuid, "123456789012345678", Instant.now(), "Jugador");

        when(linkService.findByDiscordId("123456789012345678"))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(link)));
        when(linkService.unlink(uuid, "123456789012345678")).thenReturn(CompletableFuture.completedFuture(true));
        when(messages.plain(eq("linking.unlink-success"), any())).thenReturn("Vinculo eliminado");

        commands.onSlashCommandInteraction(event);

        verify(event).deferReply(true);
        verify(linkService).unlink(uuid, "123456789012345678");
        verify(hook).editOriginal("Vinculo eliminado");
    }

    @Test
    void handleUnlinkUsuarioNoVinculado() {
        when(event.getName()).thenReturn("unlink");

        when(linkService.findByDiscordId("123456789012345678"))
                .thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        when(messages.plain(eq("linking.not-linked"), any())).thenReturn("No tienes cuenta vinculada");

        commands.onSlashCommandInteraction(event);

        verify(hook).editOriginal("No tienes cuenta vinculada");
    }

    @Test
    void handleLinkRespuestaSiempreEfimeraInclusoSiConfiguracionDiceFalso() {
        // Configuracion con ephemeral = false para link
        PluginConfig nonEphemeralConfig = new PluginConfig(
                config.discord(), config.database(), config.structure(), config.roles(),
                config.limits(), config.lifecycle(), config.sync(), config.linking(),
                config.logging(), config.updates(),
                new PluginConfig.Commands(Duration.ofSeconds(5), List.of(
                        new PluginConfig.DiscordCommand("link", true, false),
                        new PluginConfig.DiscordCommand("unlink", true, false)
                ))
        );
        LinkSlashCommands nonEphemeralCommands = new LinkSlashCommands(linkService, nonEphemeralConfig, messages);

        when(event.getName()).thenReturn("link");
        OptionMapping opt = mock(OptionMapping.class);
        when(opt.getAsString()).thenReturn("ABC234");
        when(event.getOption("codigo")).thenReturn(opt);

        when(linkService.redeem("ABC234", "123456789012345678"))
                .thenReturn(CompletableFuture.completedFuture(LinkService.LinkResult.SUCCESS));
        when(messages.plain(eq("linking.link-success"), any())).thenReturn("Cuenta vinculada con exito");

        nonEphemeralCommands.onSlashCommandInteraction(event);

        // A pesar de que ephemeral=false en config, deferReply DEBE ser true
        verify(event).deferReply(true);
    }

    @Test
    void comandoDesactivadoSeRechazaSinInvocarServicio() {
        PluginConfig disabledConfig = new PluginConfig(
                config.discord(), config.database(), config.structure(), config.roles(),
                config.limits(), config.lifecycle(), config.sync(), config.linking(),
                config.logging(), config.updates(),
                new PluginConfig.Commands(Duration.ofSeconds(5), List.of(
                        new PluginConfig.DiscordCommand("link", false, true),
                        new PluginConfig.DiscordCommand("unlink", true, true)
                ))
        );
        LinkSlashCommands disabledCommands = new LinkSlashCommands(linkService, disabledConfig, messages);

        when(event.getName()).thenReturn("link");
        ReplyCallbackAction directReply = mock(ReplyCallbackAction.class);
        when(event.reply(anyString())).thenReturn(directReply);
        when(directReply.setEphemeral(true)).thenReturn(directReply);
        when(messages.plain(eq("general.no-permission"), any())).thenReturn("No tienes permiso");

        disabledCommands.onSlashCommandInteraction(event);

        verify(event).reply("No tienes permiso");
        verify(directReply).setEphemeral(true);
        verify(linkService, never()).redeem(any(), any());
    }

    @Test
    void cooldownPorUsuarioRechazaPeticionRepetidaSinInvocarServicio() {
        when(event.getName()).thenReturn("link");
        OptionMapping opt = mock(OptionMapping.class);
        when(opt.getAsString()).thenReturn("ABC234");
        when(event.getOption("codigo")).thenReturn(opt);

        when(linkService.redeem("ABC234", "123456789012345678"))
                .thenReturn(CompletableFuture.completedFuture(LinkService.LinkResult.SUCCESS));
        when(messages.plain(eq("linking.link-success"), any())).thenReturn("Cuenta vinculada");
        when(messages.plain(eq("space.cooldown"), any())).thenReturn("Espera cooldown");

        ReplyCallbackAction cooldownReply = mock(ReplyCallbackAction.class);
        when(event.reply(anyString())).thenReturn(cooldownReply);
        when(cooldownReply.setEphemeral(true)).thenReturn(cooldownReply);

        // Primer intento dentro del cooldown: pasa y se procesa
        commands.onSlashCommandInteraction(event);
        verify(linkService, times(1)).redeem("ABC234", "123456789012345678");

        // Segundo intento inmediato del mismo usuario: se bloquea por cooldown sin invocar el servicio
        commands.onSlashCommandInteraction(event);
        verify(linkService, times(1)).redeem(any(), any());
        verify(event).reply("Espera cooldown");
    }

    @Test
    void getCommandDataFiltraComandosDesactivadosSegunConfiguracion() {
        PluginConfig disabledConfig = new PluginConfig(
                config.discord(), config.database(), config.structure(), config.roles(),
                config.limits(), config.lifecycle(), config.sync(), config.linking(),
                config.logging(), config.updates(),
                new PluginConfig.Commands(Duration.ofSeconds(5), List.of(
                        new PluginConfig.DiscordCommand("link", false, true),
                        new PluginConfig.DiscordCommand("unlink", true, true)
                ))
        );

        List<SlashCommandData> filtered = LinkSlashCommands.getCommandData(disabledConfig);
        assertEquals(1, filtered.size());
        assertEquals("unlink", filtered.getFirst().getName());
    }
}

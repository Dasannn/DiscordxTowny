package com.discordtowny.minecraft;

import com.discordtowny.config.Messages;
import com.discordtowny.config.PluginConfig;
import com.discordtowny.link.LinkService;
import com.discordtowny.towny.TownyFacade;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Pruebas unitarias de la definicion de comandos Brigadier en {@link LinkMinecraftCommands}.
 */
class LinkMinecraftCommandsTest {

    private LinkService linkService;
    private PluginConfig config;
    private Messages messages;
    private TownyFacade townyFacade;

    @BeforeEach
    void setUp() {
        linkService = mock(LinkService.class);
        messages = mock(Messages.class);
        townyFacade = mock(TownyFacade.class);

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
                new PluginConfig.Commands(Duration.ofSeconds(5), List.of())
        );
    }

    @Test
    void arbolDeComandosContieneEstructuraCorrecta() {
        LiteralCommandNode<CommandSourceStack> root = LinkMinecraftCommands.createCommandNode(
                linkService, config, messages, townyFacade);

        assertEquals("dt", root.getName());

        // Comprueba subcomandos /dt link y /dt unlink
        CommandNode<CommandSourceStack> linkNode = root.getChild("link");
        assertNotNull(linkNode, "Debe existir el subcomando link");

        CommandNode<CommandSourceStack> unlinkNode = root.getChild("unlink");
        assertNotNull(unlinkNode, "Debe existir el subcomando unlink");

        // Comprueba /dt admin unlink <jugador>
        CommandNode<CommandSourceStack> adminNode = root.getChild("admin");
        assertNotNull(adminNode, "Debe existir el subcomando admin");

        CommandNode<CommandSourceStack> adminUnlink = adminNode.getChild("unlink");
        assertNotNull(adminUnlink, "Debe existir admin unlink");

        CommandNode<CommandSourceStack> jugadorArg = adminUnlink.getChild("jugador");
        assertNotNull(jugadorArg, "Debe existir el argumento <jugador>");
    }
}

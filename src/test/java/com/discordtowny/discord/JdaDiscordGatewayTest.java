package com.discordtowny.discord;

import com.discordtowny.config.PluginConfig;
import com.discordtowny.storage.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Pruebas unitarias de JdaDiscordGateway sin conexion de red.
 */
class JdaDiscordGatewayTest {

    private static final Logger LOGGER = Logger.getLogger("test");

    private PluginConfig config;
    private SpaceRepository spaces;
    private PluginConfig.Discord discordConfig;

    @BeforeEach
    void setUp() {
        config = mock(PluginConfig.class);
        spaces = mock(SpaceRepository.class);
        discordConfig = mock(PluginConfig.Discord.class);

        when(config.discord()).thenReturn(discordConfig);
        when(discordConfig.token()).thenReturn("mi-token-super-secreto-999");
        MayorRoleRegistry.clear();
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        MayorRoleRegistry.clear();
    }

    @Test
    @DisplayName("isAvailable es falso antes de conectar")
    void isAvailableFalseBeforeConnect() {
        var gateway = new JdaDiscordGateway(config, spaces, LOGGER);
        assertFalse(gateway.isAvailable(), "El gateway no debe estar disponible antes de conectar");
    }

    @Test
    @DisplayName("submit() con gateway desconectado devuelve fallo transitorio sin lanzar excepcion")
    void submitWhenDisconnectedDoesNotThrow() {
        var gateway = new JdaDiscordGateway(config, spaces, LOGGER);
        var op = new GuildOperation.DeleteSpace(UUID.randomUUID(), "town");

        CompletableFuture<OperationOutcome> future = gateway.submit(op);

        assertNotNull(future);
        assertFalse(future.isCompletedExceptionally(), "El futuro nunca debe completarse excepcionalmente");
        OperationOutcome outcome = future.join();
        assertFalse(outcome.succeeded());
        assertEquals(OperationOutcome.Status.TRANSIENT_FAILURE, outcome.status());
        assertTrue(outcome.reason().orElse("").contains("Discord no esta disponible"));
    }

    @Test
    @DisplayName("verifyPermissions() con gateway desconectado devuelve aviso")
    void verifyPermissionsWhenDisconnected() {
        var gateway = new JdaDiscordGateway(config, spaces, LOGGER);
        Optional<String> error = gateway.verifyPermissions();

        assertTrue(error.isPresent());
        assertTrue(error.get().contains("Discord no esta conectado"));
    }

    @Test
    @DisplayName("sanitizeMessage oculta el token si aparece en el mensaje")
    void sanitizeMessageHidesToken() {
        var gateway = new JdaDiscordGateway(config, spaces, LOGGER);
        String raw = "Error en login con token mi-token-super-secreto-999 en servidor";
        String sanitized = gateway.sanitizeMessage(raw);

        assertFalse(sanitized.contains("mi-token-super-secreto-999"),
                "El token no debe aparecer en el mensaje saneado");
        assertTrue(sanitized.contains("[TOKEN_OCULTO]"),
                "El token debe ser reemplazado por la mascara");
    }

    @Test
    @DisplayName("mayorRoleId() lanza excepcion si el gateway no esta conectado")
    void mayorRoleIdThrowsWhenDisconnected() {
        var gateway = new JdaDiscordGateway(config, spaces, LOGGER);
        assertThrows(IllegalStateException.class, gateway::mayorRoleId,
                "No debe devolver vacio silenciosamente si no pudo resolver el rol por desconexion");
    }
}

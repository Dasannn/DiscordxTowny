package com.discordtowny.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests de {@link SettingsRepository}: estado que el plugin se escribe a si mismo. */
class SettingsRepositoryTest extends StorageTestBase {

    @Test
    void guardaYRecuperaUnAjuste() {
        storage.settings().put(SettingsRepository.KEY_MAYOR_ROLE_ID, "123456789");
        assertEquals("123456789",
                storage.settings().get(SettingsRepository.KEY_MAYOR_ROLE_ID).orElseThrow());
    }

    @Test
    void ajusteInexistenteDevuelveVacio() {
        assertTrue(storage.settings().get("no_existe").isEmpty());
    }

    @Test
    void guardarDosVecesReemplazaEnVezDeFallar() {
        storage.settings().put(SettingsRepository.KEY_MAYOR_ROLE_ID, "111");
        storage.settings().put(SettingsRepository.KEY_MAYOR_ROLE_ID, "222");
        assertEquals("222",
                storage.settings().get(SettingsRepository.KEY_MAYOR_ROLE_ID).orElseThrow());
    }

    @Test
    void borraUnAjuste() {
        storage.settings().put("temporal", "valor");
        storage.settings().delete("temporal");
        assertTrue(storage.settings().get("temporal").isEmpty());
    }
}

package com.discordtowny.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for {@link SettingsRepository}: state that the plugin writes to itself. */
class SettingsRepositoryTest extends StorageTestBase {

    @Test
    void savesAndRetrievesASetting() {
        storage.settings().put(SettingsRepository.KEY_MAYOR_ROLE_ID, "123456789");
        assertEquals("123456789",
                storage.settings().get(SettingsRepository.KEY_MAYOR_ROLE_ID).orElseThrow());
    }

    @Test
    void nonexistentSettingReturnsEmpty() {
        assertTrue(storage.settings().get("no_existe").isEmpty());
    }

    @Test
    void savingTwiceReplacesInsteadOfFailing() {
        storage.settings().put(SettingsRepository.KEY_MAYOR_ROLE_ID, "111");
        storage.settings().put(SettingsRepository.KEY_MAYOR_ROLE_ID, "222");
        assertEquals("222",
                storage.settings().get(SettingsRepository.KEY_MAYOR_ROLE_ID).orElseThrow());
    }

    @Test
    void deletesASetting() {
        storage.settings().put("temporal", "valor");
        storage.settings().delete("temporal");
        assertTrue(storage.settings().get("temporal").isEmpty());
    }
}

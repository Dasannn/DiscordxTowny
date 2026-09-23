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

    @Test
    void savesAndRetrievesChatPrefix() {
        storage.settings().put(SettingsRepository.KEY_CHAT_PREFIX, "&8[&cServer&8] &r");
        assertEquals("&8[&cServer&8] &r",
                storage.settings().get(SettingsRepository.KEY_CHAT_PREFIX).orElseThrow());
    }

    @Test
    void savingEmptyChatPrefixIsPreservedAndDistinctFromNonexistent() {
        storage.settings().put(SettingsRepository.KEY_CHAT_PREFIX, "");
        var opt = storage.settings().get(SettingsRepository.KEY_CHAT_PREFIX);
        assertTrue(opt.isPresent(), "Empty string setting must be present");
        assertEquals("", opt.get(), "Value must be the empty string, not null or missing");
    }

    @Test
    void deletingChatPrefixRestoresEmptyOptional() {
        storage.settings().put(SettingsRepository.KEY_CHAT_PREFIX, "&6[Custom] ");
        storage.settings().delete(SettingsRepository.KEY_CHAT_PREFIX);
        assertTrue(storage.settings().get(SettingsRepository.KEY_CHAT_PREFIX).isEmpty());
    }
}

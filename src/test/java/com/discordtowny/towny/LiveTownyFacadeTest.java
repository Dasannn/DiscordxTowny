package com.discordtowny.towny;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.TownyEconomyHandler;
import com.palmergames.bukkit.towny.TownySettings;
import com.palmergames.bukkit.towny.db.TownyDataSource;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

class LiveTownyFacadeTest {
    private TownyAPI api;
    private LiveTownyFacade facade;
    private Town town;
    private Resident resident;
    private MockedStatic<TownySettings> settings;
    private final UUID townId = UUID.randomUUID();
    private final UUID residentId = UUID.randomUUID();
    private final List<String> warnings = new ArrayList<>();

    @BeforeEach
    void prepare() {
        // Town and Nation query economy prefixes when initializing their classes.
        settings = mockStatic(TownySettings.class);
        api = mock(TownyAPI.class);
        town = mock(Town.class);
        resident = mock(Resident.class);
        facade = new LiveTownyFacade(() -> api, () -> true, () -> true, warnings::add);
        when(api.getDataSource()).thenReturn(mock(TownyDataSource.class));
        when(api.getTown(townId)).thenReturn(town);
        when(api.getTown("Roma")).thenReturn(town);
        when(api.getResident(residentId)).thenReturn(resident);
        when(api.getResident("Ana")).thenReturn(resident);
        when(api.getTowns()).thenReturn(List.of(town));
        when(town.getUUID()).thenReturn(townId);
        when(town.getName()).thenReturn("Roma");
        when(town.getMayor()).thenReturn(resident);
        when(town.getResidents()).thenReturn(new ArrayList<>(List.of(resident)));
        when(town.getNumTownBlocks()).thenReturn(12);
        when(town.getRegistered()).thenReturn(123456L);
        when(resident.getUUID()).thenReturn(residentId);
        when(resident.getName()).thenReturn("Ana");
        when(resident.getTownOrNull()).thenReturn(town);
        when(resident.isMayor()).thenReturn(true);
        when(resident.isOnline()).thenReturn(true);
        when(resident.getLastOnline()).thenReturn(654321L);
    }

    @AfterEach
    void closeSettings() {
        if (settings != null) settings.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void copiesAllFieldsWithAndWithoutEconomy(boolean economy) {
        try (var state = mockStatic(TownyEconomyHandler.class)) {
            state.when(TownyEconomyHandler::isActive).thenReturn(economy);
            // Towny accounts require a server; substitute only their reading.
            facade = new LiveTownyFacade(() -> api, () -> true, () -> true, warnings::add,
                    entidad -> {
                        assertTrue(economy, "Do not read balance without economy");
                        assertSame(town, entidad);
                        return 42.5;
                    }, entidad -> {
                        assertTrue(economy, "Do not read balance without economy");
                        assertSame(resident, entidad);
                        return 7.5;
                    });
            Nation nation = mock(Nation.class);
            when(nation.getName()).thenReturn("Italia");
            when(town.getNationOrNull()).thenReturn(nation);
            when(town.isRuined()).thenReturn(true);

            assertTrue(facade.isAvailable());
            var copy = facade.town(townId).orElseThrow();
            assertEquals(townId, copy.uuid());
            assertEquals("Roma", copy.name());
            assertEquals(residentId, copy.mayorUuid());
            assertEquals(List.of(residentId), copy.residentUuids());
            assertTrue(copy.ruined());
            assertEquals("Italia", copy.nationName().orElseThrow());
            assertEquals(12, copy.townBlocks());
            assertEquals(123456L, copy.registeredMillis());
            assertEquals(economy ? 42.5 : 0, copy.bankBalance());
            assertEquals(copy, facade.townByName("Roma").orElseThrow());
            assertEquals(copy, facade.townOf(residentId).orElseThrow());
            assertEquals(List.of(copy), facade.allTowns());
            assertEquals(1, facade.townCount());

            var person = facade.resident(residentId).orElseThrow();
            assertEquals(residentId, person.uuid());
            assertEquals("Ana", person.name());
            assertEquals(townId, person.townUuid().orElseThrow());
            assertEquals("Roma", person.townName().orElseThrow());
            assertTrue(person.mayor());
            assertTrue(person.online());
            assertEquals(654321L, person.lastOnlineMillis());
            assertEquals(economy ? 7.5 : 0, person.balance());
            assertEquals(person, facade.residentByName("Ana").orElseThrow());
            if (!economy) {
                verify(town, never()).getAccount();
                verify(resident, never()).getAccount();
            }
        }
    }

    @Test
    void noCacheAndNoMutableReferences() {
        try (var state = mockStatic(TownyEconomyHandler.class)) {
            var copy = facade.town(townId).orElseThrow();
            assertTrue(copy.nationName().isEmpty());
            town.getResidents().clear();
            when(town.getName()).thenReturn("NuevaRoma");
            assertEquals(1, copy.residentCount());
            assertEquals("Roma", copy.name());
            assertEquals("NuevaRoma", facade.town(townId).orElseThrow().name());
            assertEquals(0, facade.town(townId).orElseThrow().residentCount());
            assertThrows(UnsupportedOperationException.class, () -> copy.residentUuids().clear());
            assertThrows(UnsupportedOperationException.class, () -> facade.allTowns().clear());
        }
    }

    @Test
    void missingEntitiesAndResidentWithoutTown() {
        try (var state = mockStatic(TownyEconomyHandler.class)) {
            assertTrue(facade.town(UUID.randomUUID()).isEmpty());
            assertTrue(facade.townByName("otra").isEmpty());
            assertTrue(facade.resident(UUID.randomUUID()).isEmpty());
            assertTrue(facade.residentByName("otro").isEmpty());
            assertTrue(facade.townOf(UUID.randomUUID()).isEmpty());
            when(resident.getTownOrNull()).thenReturn(null);
            when(resident.isMayor()).thenReturn(false);
            assertTrue(facade.townOf(residentId).isEmpty());
            var copy = facade.resident(residentId).orElseThrow();
            assertTrue(copy.townName().isEmpty());
            assertTrue(copy.townUuid().isEmpty());
            assertFalse(copy.mayor());
        }
    }

    private void verifyEmpty(LiveTownyFacade object) {
        assertFalse(object.isAvailable());
        assertTrue(object.town(townId).isEmpty());
        assertTrue(object.townByName("Roma").isEmpty());
        assertTrue(object.townOf(residentId).isEmpty());
        assertTrue(object.resident(residentId).isEmpty());
        assertTrue(object.residentByName("Ana").isEmpty());
        assertTrue(object.allTowns().isEmpty());
        assertEquals(0, object.townCount());
    }

    @Test
    void missingDisabledOrFailedDependencyReturnsEmpty() {
        verifyEmpty(new LiveTownyFacade(() -> api, () -> false, () -> true, warnings::add));
        verifyEmpty(new LiveTownyFacade(() -> null, () -> true, () -> true, warnings::add));
        verifyEmpty(new LiveTownyFacade(() -> { throw new NoClassDefFoundError("Towny"); },
                () -> true, () -> true, warnings::add));
        verifyEmpty(new LiveTownyFacade(() -> { throw new IllegalStateException("fallo"); },
                () -> true, () -> true, warnings::add));
        verifyNoInteractions(api);
        assertEquals(2, warnings.size());
    }

    @Test
    void visibleApiFailuresAndRecoveryWithoutCache() {
        when(api.getTown(townId)).thenThrow(new IllegalStateException("fallo"));
        assertTrue(facade.town(townId).isEmpty());
        assertTrue(facade.town(townId).isEmpty());
        assertEquals(1, warnings.size());
        when(api.getTowns()).thenThrow(new IllegalStateException("fallo"));
        assertTrue(facade.allTowns().isEmpty());
        assertEquals(0, facade.townCount());
        doReturn(null).when(api).getTown(townId);
        assertTrue(facade.town(townId).isEmpty());
        assertTrue(facade.isAvailable());
    }

    @Test
    void failureDuringSnapshotDoesNotDeliverPartialList() {
        try (var state = mockStatic(TownyEconomyHandler.class)) {
            Town broken = mock(Town.class);
            when(broken.getMayor()).thenThrow(new IllegalStateException("fallo"));
            when(api.getTowns()).thenReturn(List.of(town, broken));
            assertTrue(facade.allTowns().isEmpty());
            assertEquals(1, warnings.size());
            assertTrue(facade.isAvailable());
        }
    }

    @Test
    void availabilityRecoversAfterTransientReadFailure() {
        when(api.getTown(townId)).thenThrow(new IllegalStateException("fallo pasajero"));
        assertTrue(facade.town(townId).isEmpty());
        doReturn(town).when(api).getTown(townId);

        assertTrue(facade.isAvailable());
        assertTrue(facade.isAvailable());
        assertEquals(1, warnings.size());
        verify(api, times(2)).getDataSource();
    }

    @Test
    void availabilityRetriesAfterCheckFailure() {
        when(api.getDataSource()).thenThrow(new IllegalStateException("fallo pasajero"))
                .thenReturn(null, mock(TownyDataSource.class));
        assertFalse(facade.isAvailable());
        assertFalse(facade.isAvailable());
        assertTrue(facade.isAvailable());
        assertEquals(1, warnings.size());
    }

    @Test
    void townWithoutMayorDoesNotInvalidateTheOthers() {
        try (var state = mockStatic(TownyEconomyHandler.class)) {
            var valid = facade.town(townId).orElseThrow();
            Town withoutMayor = mock(Town.class);
            UUID withoutMayorId = UUID.randomUUID();
            when(api.getTown(withoutMayorId)).thenReturn(withoutMayor);
            when(api.getTown("Administrativa")).thenReturn(withoutMayor);
            when(resident.getTownOrNull()).thenReturn(withoutMayor);
            when(api.getTowns()).thenReturn(List.of(withoutMayor, town));

            assertEquals(List.of(valid), facade.allTowns());
            assertTrue(facade.town(withoutMayorId).isEmpty());
            assertTrue(facade.townByName("Administrativa").isEmpty());
            assertTrue(facade.townOf(residentId).isEmpty());
            assertTrue(facade.isAvailable());
            assertTrue(warnings.isEmpty());
            assertEquals(2, facade.townCount());
        }
    }

    @Test
    void everyMethodRejectsIncorrectThreadBeforeTouchingTowny() {
        facade = new LiveTownyFacade(() -> api, () -> { fail("Do not query Towny"); return true; },
                () -> false, warnings::add);
        List<Runnable> calls = List.of(() -> facade.isAvailable(), () -> facade.town(townId),
                () -> facade.townByName("Roma"), () -> facade.townOf(residentId),
                () -> facade.resident(residentId), () -> facade.residentByName("Ana"),
                () -> facade.allTowns(), () -> facade.townCount());
        for (Runnable call : calls) assertThrows(IllegalStateException.class, call::run);
        verifyNoInteractions(api);
    }
}

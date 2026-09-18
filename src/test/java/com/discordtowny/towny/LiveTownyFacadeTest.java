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
    private LiveTownyFacade fachada;
    private Town town;
    private Resident residente;
    private MockedStatic<TownySettings> ajustes;
    private final UUID townId = UUID.randomUUID();
    private final UUID residenteId = UUID.randomUUID();
    private final List<String> avisos = new ArrayList<>();

    @BeforeEach
    void preparar() {
        // Town y Nation consultan los prefijos de economia al inicializar sus clases.
        ajustes = mockStatic(TownySettings.class);
        api = mock(TownyAPI.class);
        town = mock(Town.class);
        residente = mock(Resident.class);
        fachada = new LiveTownyFacade(() -> api, () -> true, () -> true, avisos::add);
        when(api.getDataSource()).thenReturn(mock(TownyDataSource.class));
        when(api.getTown(townId)).thenReturn(town);
        when(api.getTown("Roma")).thenReturn(town);
        when(api.getResident(residenteId)).thenReturn(residente);
        when(api.getResident("Ana")).thenReturn(residente);
        when(api.getTowns()).thenReturn(List.of(town));
        when(town.getUUID()).thenReturn(townId);
        when(town.getName()).thenReturn("Roma");
        when(town.getMayor()).thenReturn(residente);
        when(town.getResidents()).thenReturn(new ArrayList<>(List.of(residente)));
        when(town.getNumTownBlocks()).thenReturn(12);
        when(town.getRegistered()).thenReturn(123456L);
        when(residente.getUUID()).thenReturn(residenteId);
        when(residente.getName()).thenReturn("Ana");
        when(residente.getTownOrNull()).thenReturn(town);
        when(residente.isMayor()).thenReturn(true);
        when(residente.isOnline()).thenReturn(true);
        when(residente.getLastOnline()).thenReturn(654321L);
    }

    @AfterEach
    void cerrarAjustes() {
        if (ajustes != null) ajustes.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void copiaTodosLosCamposConYSinEconomia(boolean economia) {
        try (var estado = mockStatic(TownyEconomyHandler.class)) {
            estado.when(TownyEconomyHandler::isActive).thenReturn(economia);
            // Las cuentas de Towny requieren un servidor; sustituir solo su lectura.
            fachada = new LiveTownyFacade(() -> api, () -> true, () -> true, avisos::add,
                    entidad -> {
                        assertTrue(economia, "No leer saldo sin economia");
                        assertSame(town, entidad);
                        return 42.5;
                    }, entidad -> {
                        assertTrue(economia, "No leer saldo sin economia");
                        assertSame(residente, entidad);
                        return 7.5;
                    });
            Nation nacion = mock(Nation.class);
            when(nacion.getName()).thenReturn("Italia");
            when(town.getNationOrNull()).thenReturn(nacion);
            when(town.isRuined()).thenReturn(true);

            assertTrue(fachada.isAvailable());
            var copia = fachada.town(townId).orElseThrow();
            assertEquals(townId, copia.uuid());
            assertEquals("Roma", copia.name());
            assertEquals(residenteId, copia.mayorUuid());
            assertEquals(List.of(residenteId), copia.residentUuids());
            assertTrue(copia.ruined());
            assertEquals("Italia", copia.nationName().orElseThrow());
            assertEquals(12, copia.townBlocks());
            assertEquals(123456L, copia.registeredMillis());
            assertEquals(economia ? 42.5 : 0, copia.bankBalance());
            assertEquals(copia, fachada.townByName("Roma").orElseThrow());
            assertEquals(copia, fachada.townOf(residenteId).orElseThrow());
            assertEquals(List.of(copia), fachada.allTowns());
            assertEquals(1, fachada.townCount());

            var persona = fachada.resident(residenteId).orElseThrow();
            assertEquals(residenteId, persona.uuid());
            assertEquals("Ana", persona.name());
            assertEquals(townId, persona.townUuid().orElseThrow());
            assertEquals("Roma", persona.townName().orElseThrow());
            assertTrue(persona.mayor());
            assertTrue(persona.online());
            assertEquals(654321L, persona.lastOnlineMillis());
            assertEquals(economia ? 7.5 : 0, persona.balance());
            assertEquals(persona, fachada.residentByName("Ana").orElseThrow());
            if (!economia) {
                verify(town, never()).getAccount();
                verify(residente, never()).getAccount();
            }
        }
    }

    @Test
    void sinCacheYSinReferenciasMutables() {
        try (var estado = mockStatic(TownyEconomyHandler.class)) {
            var copia = fachada.town(townId).orElseThrow();
            assertTrue(copia.nationName().isEmpty());
            town.getResidents().clear();
            when(town.getName()).thenReturn("NuevaRoma");
            assertEquals(1, copia.residentCount());
            assertEquals("Roma", copia.name());
            assertEquals("NuevaRoma", fachada.town(townId).orElseThrow().name());
            assertEquals(0, fachada.town(townId).orElseThrow().residentCount());
            assertThrows(UnsupportedOperationException.class, () -> copia.residentUuids().clear());
            assertThrows(UnsupportedOperationException.class, () -> fachada.allTowns().clear());
        }
    }

    @Test
    void entidadesAusentesYResidenteSinTown() {
        try (var estado = mockStatic(TownyEconomyHandler.class)) {
            assertTrue(fachada.town(UUID.randomUUID()).isEmpty());
            assertTrue(fachada.townByName("otra").isEmpty());
            assertTrue(fachada.resident(UUID.randomUUID()).isEmpty());
            assertTrue(fachada.residentByName("otro").isEmpty());
            assertTrue(fachada.townOf(UUID.randomUUID()).isEmpty());
            when(residente.getTownOrNull()).thenReturn(null);
            when(residente.isMayor()).thenReturn(false);
            assertTrue(fachada.townOf(residenteId).isEmpty());
            var copia = fachada.resident(residenteId).orElseThrow();
            assertTrue(copia.townName().isEmpty());
            assertTrue(copia.townUuid().isEmpty());
            assertFalse(copia.mayor());
        }
    }

    private void comprobarVacios(LiveTownyFacade objeto) {
        assertFalse(objeto.isAvailable());
        assertTrue(objeto.town(townId).isEmpty());
        assertTrue(objeto.townByName("Roma").isEmpty());
        assertTrue(objeto.townOf(residenteId).isEmpty());
        assertTrue(objeto.resident(residenteId).isEmpty());
        assertTrue(objeto.residentByName("Ana").isEmpty());
        assertTrue(objeto.allTowns().isEmpty());
        assertEquals(0, objeto.townCount());
    }

    @Test
    void dependenciaAusenteDeshabilitadaOFallidaDevuelveVacio() {
        comprobarVacios(new LiveTownyFacade(() -> api, () -> false, () -> true, avisos::add));
        comprobarVacios(new LiveTownyFacade(() -> null, () -> true, () -> true, avisos::add));
        comprobarVacios(new LiveTownyFacade(() -> { throw new NoClassDefFoundError("Towny"); },
                () -> true, () -> true, avisos::add));
        comprobarVacios(new LiveTownyFacade(() -> { throw new IllegalStateException("fallo"); },
                () -> true, () -> true, avisos::add));
        verifyNoInteractions(api);
        assertEquals(2, avisos.size());
    }

    @Test
    void fallosDeApiVisiblesYRecuperacionSinCache() {
        when(api.getTown(townId)).thenThrow(new IllegalStateException("fallo"));
        assertTrue(fachada.town(townId).isEmpty());
        assertFalse(fachada.isAvailable());
        assertFalse(fachada.isAvailable());
        assertTrue(fachada.town(townId).isEmpty());
        assertEquals(1, avisos.size());
        when(api.getTowns()).thenThrow(new IllegalStateException("fallo"));
        assertTrue(fachada.allTowns().isEmpty());
        assertEquals(0, fachada.townCount());
        doReturn(null).when(api).getTown(townId);
        assertTrue(fachada.town(townId).isEmpty());
        assertTrue(fachada.isAvailable());
    }

    @Test
    void falloDuranteSnapshotNoEntregaListaParcial() {
        try (var estado = mockStatic(TownyEconomyHandler.class)) {
            Town rota = mock(Town.class);
            when(rota.getMayor()).thenThrow(new IllegalStateException("fallo"));
            when(api.getTowns()).thenReturn(List.of(town, rota));
            assertTrue(fachada.allTowns().isEmpty());
            assertFalse(fachada.isAvailable());
        }
    }

    @Test
    void cadaMetodoRechazaHiloIncorrectoAntesDeTocarTowny() {
        fachada = new LiveTownyFacade(() -> api, () -> { fail("No consultar Towny"); return true; },
                () -> false, avisos::add);
        List<Runnable> llamadas = List.of(() -> fachada.isAvailable(), () -> fachada.town(townId),
                () -> fachada.townByName("Roma"), () -> fachada.townOf(residenteId),
                () -> fachada.resident(residenteId), () -> fachada.residentByName("Ana"),
                () -> fachada.allTowns(), () -> fachada.townCount());
        for (Runnable llamada : llamadas) assertThrows(IllegalStateException.class, llamada::run);
        verifyNoInteractions(api);
    }
}

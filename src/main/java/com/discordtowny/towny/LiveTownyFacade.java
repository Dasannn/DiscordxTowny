package com.discordtowny.towny;

import com.discordtowny.model.ResidentSnapshot;
import com.discordtowny.model.TownSnapshot;
import com.palmergames.bukkit.towny.Towny;
import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.TownyEconomyHandler;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import org.bukkit.Bukkit;

/** Lecturas en vivo; ningun objeto mutable de Towny cruza esta frontera. */
public final class LiveTownyFacade implements TownyFacade {
    private final Supplier<?> api;
    private final BooleanSupplier disponible;
    private final BooleanSupplier hiloPrincipal;
    private final Consumer<String> aviso;
    private final ToDoubleFunction<Town> saldoTown;
    private final ToDoubleFunction<Resident> saldoResidente;
    private boolean avisado;

    public LiveTownyFacade(Consumer<String> aviso) {
        this(() -> TownyAPI.getInstance(), () -> {
            var plugin = Bukkit.getPluginManager().getPlugin("Towny");
            return plugin instanceof Towny towny && towny.isEnabled() && !towny.isError();
        }, Bukkit::isPrimaryThread, aviso);
    }

    LiveTownyFacade(Supplier<?> api, BooleanSupplier disponible,
                    BooleanSupplier hiloPrincipal, Consumer<String> aviso) {
        this(api, disponible, hiloPrincipal, aviso,
                town -> town.getAccount().getHoldingBalance(),
                residente -> residente.getAccount().getHoldingBalance());
    }

    LiveTownyFacade(Supplier<?> api, BooleanSupplier disponible,
                    BooleanSupplier hiloPrincipal, Consumer<String> aviso,
                    ToDoubleFunction<Town> saldoTown, ToDoubleFunction<Resident> saldoResidente) {
        this.api = api;
        this.disponible = disponible;
        this.hiloPrincipal = hiloPrincipal;
        this.aviso = aviso;
        this.saldoTown = saldoTown;
        this.saldoResidente = saldoResidente;
    }

    private <T> T leer(Function<TownyAPI, T> lectura, T vacio) {
        // Rechazar antes de consultar incluso la disponibilidad de Towny.
        if (!hiloPrincipal.getAsBoolean()) {
            throw new IllegalStateException("TownyFacade: se requiere el hilo principal del servidor");
        }
        try {
            if (!disponible.getAsBoolean()) return vacio;
            TownyAPI actual = (TownyAPI) api.get();
            if (actual == null) return vacio;
            T resultado = lectura.apply(actual);
            avisado = false;
            return resultado;
        } catch (RuntimeException | LinkageError fallo) {
            if (!avisado) {
                avisado = true;
                aviso.accept("Towny: no se pudo completar la lectura; se devuelve un resultado vacio");
            }
            return vacio;
        }
    }

    @Override
    public boolean isAvailable() {
        return leer(actual -> actual.getDataSource() != null, false);
    }

    @Override
    public Optional<TownSnapshot> town(UUID townUuid) {
        return leer(actual -> Optional.ofNullable(actual.getTown(townUuid)).map(this::townSnapshot), Optional.empty());
    }

    @Override
    public Optional<TownSnapshot> townByName(String name) {
        return leer(actual -> Optional.ofNullable(actual.getTown(name)).map(this::townSnapshot), Optional.empty());
    }

    @Override
    public Optional<TownSnapshot> townOf(UUID playerUuid) {
        return leer(actual -> Optional.ofNullable(actual.getResident(playerUuid))
                .map(Resident::getTownOrNull).map(this::townSnapshot), Optional.empty());
    }

    @Override
    public Optional<ResidentSnapshot> resident(UUID playerUuid) {
        return leer(actual -> Optional.ofNullable(actual.getResident(playerUuid)).map(this::residentSnapshot), Optional.empty());
    }

    @Override
    public Optional<ResidentSnapshot> residentByName(String name) {
        return leer(actual -> Optional.ofNullable(actual.getResident(name)).map(this::residentSnapshot), Optional.empty());
    }

    @Override
    public List<TownSnapshot> allTowns() {
        return leer(actual -> actual.getTowns().stream().map(this::townSnapshot).filter(Objects::nonNull).toList(), List.of());
    }

    @Override
    public int townCount() {
        return leer(actual -> actual.getTowns().size(), 0);
    }

    private TownSnapshot townSnapshot(Town town) {
        Resident alcalde = town.getMayor();
        // Sin alcalde no hay snapshot valido; las otras towns siguen disponibles.
        if (alcalde == null) return null;
        return new TownSnapshot(town.getUUID(), town.getName(), alcalde.getUUID(),
                town.getResidents().stream().map(Resident::getUUID).toList(), town.isRuined(),
                Optional.ofNullable(town.getNationOrNull()).map(nacion -> nacion.getName()),
                town.getNumTownBlocks(), TownyEconomyHandler.isActive() ? saldoTown.applyAsDouble(town) : 0,
                town.getRegistered());
    }

    private ResidentSnapshot residentSnapshot(Resident residente) {
        Town town = residente.getTownOrNull();
        return new ResidentSnapshot(residente.getUUID(), residente.getName(),
                Optional.ofNullable(town).map(Town::getName), Optional.ofNullable(town).map(Town::getUUID),
                residente.isMayor(), residente.isOnline(), residente.getLastOnline(),
                TownyEconomyHandler.isActive() ? saldoResidente.applyAsDouble(residente) : 0);
    }
}

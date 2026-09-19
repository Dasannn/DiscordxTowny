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

/** Live reads; no mutable Towny object crosses this boundary. */
public final class LiveTownyFacade implements TownyFacade {
    private final Supplier<?> api;
    private final BooleanSupplier available;
    private final BooleanSupplier mainThread;
    private final Consumer<String> warning;
    private final ToDoubleFunction<Town> townBalance;
    private final ToDoubleFunction<Resident> residentBalance;
    private boolean warned;

    public LiveTownyFacade(Consumer<String> warning) {
        this(() -> TownyAPI.getInstance(), () -> {
            var plugin = Bukkit.getPluginManager().getPlugin("Towny");
            return plugin instanceof Towny towny && towny.isEnabled() && !towny.isError();
        }, Bukkit::isPrimaryThread, warning);
    }

    LiveTownyFacade(Supplier<?> api, BooleanSupplier available,
                    BooleanSupplier mainThread, Consumer<String> warning) {
        this(api, available, mainThread, warning,
                town -> town.getAccount().getHoldingBalance(),
                resident -> resident.getAccount().getHoldingBalance());
    }

    LiveTownyFacade(Supplier<?> api, BooleanSupplier available,
                    BooleanSupplier mainThread, Consumer<String> warning,
                    ToDoubleFunction<Town> townBalance, ToDoubleFunction<Resident> residentBalance) {
        this.api = api;
        this.available = available;
        this.mainThread = mainThread;
        this.warning = warning;
        this.townBalance = townBalance;
        this.residentBalance = residentBalance;
    }

    private <T> T read(Function<TownyAPI, T> readOperation, T empty) {
        // Reject before even checking Towny availability.
        if (!mainThread.getAsBoolean()) {
            throw new IllegalStateException("TownyFacade: server main thread is required");
        }
        try {
            if (!available.getAsBoolean()) return empty;
            TownyAPI current = (TownyAPI) api.get();
            if (current == null) return empty;
            T result = readOperation.apply(current);
            warned = false;
            return result;
        } catch (RuntimeException | LinkageError failure) {
            if (!warned) {
                warned = true;
                warning.accept("Towny: read could not be completed");
            }
            // Never answer a failed read with an empty result: the caller cannot
            // tell that apart from a confirmed absence, and acting on it removes
            // access from a town that is alive.
            throw new TownyReadException("Towny: read could not be completed", failure);
        }
    }

    @Override
    public boolean isAvailable() {
        // The probe itself must never throw: answering "not available" is the
        // whole point of asking.
        try {
            return read(current -> current.getDataSource() != null, false);
        } catch (TownyReadException failure) {
            return false;
        }
    }

    @Override
    public Optional<TownSnapshot> town(UUID townUuid) {
        return read(current -> Optional.ofNullable(current.getTown(townUuid)).map(this::townSnapshot), Optional.empty());
    }

    @Override
    public Optional<TownSnapshot> townByName(String name) {
        return read(current -> Optional.ofNullable(current.getTown(name)).map(this::townSnapshot), Optional.empty());
    }

    @Override
    public Optional<TownSnapshot> townOf(UUID playerUuid) {
        return read(current -> Optional.ofNullable(current.getResident(playerUuid))
                .map(Resident::getTownOrNull).map(this::townSnapshot), Optional.empty());
    }

    @Override
    public Optional<ResidentSnapshot> resident(UUID playerUuid) {
        return read(current -> Optional.ofNullable(current.getResident(playerUuid)).map(this::residentSnapshot), Optional.empty());
    }

    @Override
    public Optional<ResidentSnapshot> residentByName(String name) {
        return read(current -> Optional.ofNullable(current.getResident(name)).map(this::residentSnapshot), Optional.empty());
    }

    @Override
    public List<TownSnapshot> allTowns() {
        return read(current -> current.getTowns().stream().map(this::townSnapshot).filter(Objects::nonNull).toList(), List.of());
    }

    @Override
    public int townCount() {
        return read(current -> current.getTowns().size(), 0);
    }

    private TownSnapshot townSnapshot(Town town) {
        Resident mayor = town.getMayor();
        // Without a mayor there is no valid snapshot; other towns remain available.
        if (mayor == null) return null;
        return new TownSnapshot(town.getUUID(), town.getName(), mayor.getUUID(),
                town.getResidents().stream().map(Resident::getUUID).toList(), town.isRuined(),
                Optional.ofNullable(town.getNationOrNull()).map(nation -> nation.getName()),
                town.getNumTownBlocks(), TownyEconomyHandler.isActive() ? townBalance.applyAsDouble(town) : 0,
                town.getRegistered());
    }

    private ResidentSnapshot residentSnapshot(Resident resident) {
        Town town = resident.getTownOrNull();
        return new ResidentSnapshot(resident.getUUID(), resident.getName(),
                Optional.ofNullable(town).map(Town::getName), Optional.ofNullable(town).map(Town::getUUID),
                resident.isMayor(), resident.isOnline(), resident.getLastOnline(),
                TownyEconomyHandler.isActive() ? residentBalance.applyAsDouble(resident) : 0);
    }
}

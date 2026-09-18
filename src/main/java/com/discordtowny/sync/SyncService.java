package com.discordtowny.sync;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Reconciliacion entre Towny y Discord.
 *
 * <p><b>Ante una discrepancia, Towny gana.</b> Si alguien recibio a mano el rol
 * de una town a la que no pertenece, la sincronizacion se lo quita.
 *
 * <p>Solo se tocan los roles gestionados por el plugin. Los demas roles de un
 * usuario de Discord no se miran ni se modifican.
 */
public interface SyncService {

    /** Ajusta los roles de un jugador a lo que dice Towny. */
    CompletableFuture<Void> syncPlayer(UUID uuid);

    /** Ajusta los roles de todos los residentes de una town. */
    CompletableFuture<SyncReport> syncTown(UUID townUuid);

    /**
     * Recorre todo: canales desaparecidos, roles borrados, espacios registrados
     * sin canales, miembros con roles que no les tocan y espacios
     * inconsistentes.
     *
     * <p>Va por lotes con pausas, para no saturar el limite de peticiones.
     * Segun la configuracion repara o solo informa.
     */
    CompletableFuture<SyncReport> reconcileAll();

    /** Que se encontro y que se hizo. */
    record SyncReport(
            int spacesChecked,
            int rolesGranted,
            int rolesRevoked,
            int inconsistenciesFound,
            int inconsistenciesRepaired,
            java.util.List<String> problems) {}
}

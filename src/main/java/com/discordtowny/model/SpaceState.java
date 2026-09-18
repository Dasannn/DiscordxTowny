package com.discordtowny.model;

/** Estado del espacio de Discord de una town. */
public enum SpaceState {
    /** Canales y rol existen, y se sincronizan. */
    ACTIVE,
    /** Canales en solo lectura, movidos al archivo, rol eliminado. */
    ARCHIVED,
    /**
     * Una operacion fallo a medias. La reconciliacion debe retomarlo.
     * Nunca implica permisos abiertos: ante la duda, no se concede acceso.
     */
    INCONSISTENT
}

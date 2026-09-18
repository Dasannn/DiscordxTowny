package com.discordtowny.storage;

import com.discordtowny.model.AuditEvent;
import java.time.Instant;
import java.util.List;

/** Registro de lo que hizo el plugin. Bloquea: ver {@link Storage}. */
public interface AuditRepository {

    void record(AuditEvent event);

    /** Los mas recientes primero. Alimenta {@code /dt admin info}. */
    List<AuditEvent> recent(String target, int limit);

    /** Borra lo anterior a la fecha dada, para que la tabla no crezca sin fin. */
    int purgeBefore(Instant cutoff);
}

package com.discordtowny.storage;

import com.discordtowny.model.AuditEvent;
import java.time.Instant;
import java.util.List;

/** Record of what the plugin did. Blocks: see {@link Storage}. */
public interface AuditRepository {

    void record(AuditEvent event);

    /** Most recent first. Powers {@code /dt admin info}. */
    List<AuditEvent> recent(String target, int limit);

    /** Deletes entries before the given date so the table does not grow endlessly. */
    int purgeBefore(Instant cutoff);
}

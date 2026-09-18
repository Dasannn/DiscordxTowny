package com.discordtowny.link;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Clock mutable para pruebas de tiempo (caducidad de codigos, bloqueos).
 */
final class MutableClock extends Clock {

    private Instant instant;
    private final ZoneId zone;

    MutableClock(Instant initial) {
        this.instant = initial;
        this.zone = ZoneId.of("UTC");
    }

    void advance(Duration duration) {
        this.instant = this.instant.plus(duration);
    }

    void setInstant(Instant newInstant) {
        this.instant = newInstant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return instant;
    }
}

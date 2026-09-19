package com.discordtowny.towny;

/**
 * A read against Towny could not be completed.
 *
 * <p>Exists so that a failure is never mistaken for an answer. Before this,
 * every read returned an empty result both when the entity genuinely did not
 * exist and when Towny threw, which meant reconciliation could read "this town
 * was deleted" out of "Towny was momentarily unavailable" and archive a living
 * town.
 *
 * <p>An empty result from the facade now means one thing only: the entity is
 * confirmed not to exist. Anything else arrives as this exception, and the
 * caller decides — but never by removing access.
 */
public class TownyReadException extends RuntimeException {

    public TownyReadException(String message, Throwable cause) {
        super(message, cause);
    }

    /** For a failure with no underlying exception to attach. */
    public TownyReadException(String message) {
        super(message);
    }
}

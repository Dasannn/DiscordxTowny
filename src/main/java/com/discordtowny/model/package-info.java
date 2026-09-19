/**
 * Immutable types that cross boundaries between packages.
 *
 * <p><b>Dependency rule:</b> depends on nothing. Neither Bukkit, nor JDA,
 * nor JDBC, nor any other internal package. Everything else may depend on
 * it, and therefore can do so without creating cycles.
 *
 * <p>See {@code ARCHITECTURE.md}, section 2.
 */
package com.discordtowny.model;

/**
 * Sole access to the database: schema, migrations, and DAOs.
 *
 * <p><b>Dependency rule:</b> does not know JDA or Bukkit. Only used off the main thread.
 *
 * <p>See {@code ARCHITECTURE.md}, section 2.
 */
package com.discordtowny.storage;

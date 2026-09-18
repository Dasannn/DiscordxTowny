/**
 * Tipos inmutables que cruzan fronteras entre paquetes.
 *
 * <p><b>Regla de dependencias:</b> no depende de nada. Ni de Bukkit, ni de JDA,
 * ni de JDBC, ni de ningun otro paquete propio. Todo lo demas puede depender de
 * el, y por eso puede hacerlo sin crear ciclos.
 *
 * <p>Ver {@code ARCHITECTURE.md}, seccion 2.
 */
package com.discordtowny.model;

# Revisión: docs/publica

Revisado: 2 archivos (`README.md`, `docs/guia-de-uso.md`), 243 líneas (+241 diff)
Build: No aplica (documentación pública)

## Hallazgos

| # | Severidad | Archivo:línea | Problema | Corrección propuesta |
|---|---|---|---|---|
| 1 | bloqueante | `README.md:19-20` | La sección "Crear e invitar al bot" omite indicar la activación obligatoria de **Server Members Intent** en el portal de desarrolladores de Discord. El plugin requiere el intent `GUILD_MEMBERS` para operar; si el usuario no activa manualmente este intent privilegiado en la pestaña **Bot**, Discord rechaza la conexión con `DisallowedIntentsException` y el bot no puede conectarse ni funcionar. | Añadir en el paso 2 de "Crear e invitar al bot" la indicación explícita de bajar a la sección **Privileged Gateway Intents** en la pestaña **Bot** y activar la casilla **Server Members Intent**. |
| 2 | bloqueante | `README.md:22` | La lista de permisos a marcar en la URL de invitación omite el permiso **Conectar** (`VOICE_CONNECT`). `PermissionVerifier.java` exige obligatoriamente `Permission.VOICE_CONNECT` para que el bot pueda operar. Si el bot se invita siguiendo estas instrucciones, la verificación de permisos en el arranque fallará y el plugin se negará a funcionar. | Añadir **Conectar** (`Connect`) a la lista de permisos que deben marcarse en el paso 4 de la configuración de instalación. |
| 3 | importante | `README.md:35` | En el paso 4 de instalación se indica copiar `src/main/resources/config.yml` y crear carpetas manualmente. Un administrador de servidor que descarga el `.jar` compilado no dispone del árbol de código fuente. Además, Paper genera automáticamente `plugins/DiscordTowny/config.yml` en el primer arranque. | Indicar que se inicie el servidor una primera vez tras colocar el JAR para que Paper genere la carpeta `plugins/DiscordTowny/` y el archivo `config.yml` por defecto, o proveer enlace de descarga directo al archivo de configuración de la release. |
| 4 | menor | `README.md:5, 114, 120` y `docs/guia-de-uso.md:3, 70, 72, 93` | La documentación incluye comentarios internos y dudas sobre la especificación técnica ("La spec permite...", "La especificación no aclara...", "No acredita que las funciones estén implementadas"). Esto contradice la ficha T11 y `docs/spec.md` 13 (*"documentación escrita para jugadores y administradores de servidor, no para desarrolladores: sin detalles de código ni de arquitectura"*). | Reformular las secciones desde la perspectiva del usuario final, explicando qué hace el plugin de forma afirmativa y práctica, eliminando los meta-comentarios sobre la redacción interna de la spec. |

Veredicto: requiere correcciones

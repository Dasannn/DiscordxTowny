# DiscordTowny
DiscordTowny conecta Towny Advanced con Discord desde un servidor Paper.
Cada town necesita canales privados: crearlos y mantener sus permisos a mano no escala. El alcalde pide su espacio y el bot mantiene los accesos según Towny.

**En desarrollo.** Este documento describe el funcionamiento previsto en la especificación. No acredita que las funciones estén implementadas ni que exista una versión instalable.

## Requisitos

- Paper para Minecraft **26.2**.
- **Java 25**.
- **Towny Advanced 0.103.2.7 o superior**.
- Un servidor de Discord y un bot propio, que crearás en el paso siguiente.
- SQLite, incluida como opción predeterminada, o una base de datos MySQL/MariaDB con sus credenciales.

Cada instalación conecta un servidor de Minecraft con un solo servidor de Discord.

## Crear e invitar al bot

1. Abre el [portal de aplicaciones de Discord](https://discord.com/developers/applications). Pulsa **New Application**, escribe `DiscordTowny` y crea la aplicación.
2. En **Bot**, genera el token con **Reset Token** y guárdalo. Lo usarás en `discord.token`. No lo compartas.
3. En **Installation**, habilita **Guild Install**. Selecciona **Discord Provided Link** como enlace de instalación.
4. En los ajustes de **Guild Install**, selecciona `bot` y `applications.commands`. Marca **Gestionar canales**, **Gestionar roles**, **Ver canales** y **Enviar mensajes**.
5. Abre el enlace de instalación, elige **Añadir al servidor**, selecciona tu servidor y autoriza al bot. Necesitas permiso para gestionar ese servidor.
6. En Discord, abre **Ajustes del servidor → Roles**. **Coloca el rol del bot por encima de todos los roles que gestionará: los de las towns y `Alcalde`.** Si queda por debajo, no podrá asignarlos. El plugin avisa en consola al arrancar y no opera con esa jerarquía incorrecta.

Los pasos del portal se pueden consultar en la [guía oficial de Discord](https://docs.discord.com/developers/quick-start/getting-started).

## Instalación

Cuando esté disponible una versión publicada:

1. Prepara Paper y Java con las versiones indicadas. Instala Towny Advanced y comprueba que funciona.
2. Obtén el archivo JAR de DiscordTowny de la publicación oficial. La documentación del proyecto todavía no indica su dirección de descarga.
3. Detén Minecraft y coloca el JAR en la carpeta `plugins` del servidor.
4. Prepara `plugins/DiscordTowny/config.yml` copiando el [archivo de configuración incluido](src/main/resources/config.yml). Crea la carpeta si hace falta.
5. Completa el token, el ID del servidor de Discord y la base de datos como se explica abajo.
6. Arranca el servidor y revisa la consola. Corrige cualquier aviso sobre conexión o permisos del bot.
7. Entra al juego y ejecuta `/dt help`. Sigue la [guía de uso](docs/guia-de-uso.md) para vincularte y crear el primer espacio.

## Configuración mínima

Edita estos valores en `plugins/DiscordTowny/config.yml`; conserva los demás bloques del archivo de ejemplo.

| Clave | Qué poner |
|---|---|
| `discord.token` | El token que copiaste del portal. Sustituye `PON_AQUI_TU_TOKEN`. |
| `discord.guild-id` | El ID de tu servidor de Discord, entre comillas. Por ejemplo, `"123456789012345678"`; usa el tuyo. |
| `database.type` | `sqlite` para empezar sin un servidor de base de datos aparte. Es el valor predeterminado. |

Para copiar el ID, activa **Ajustes de usuario → Avanzado → Modo desarrollador** en Discord. Haz clic derecho sobre el icono del servidor y selecciona **Copiar ID del servidor**.

Con `sqlite`, se ignoran los demás valores del bloque `database`. Para usar MySQL o MariaDB, selecciona `mysql` o `mariadb` y completa `host`, `port`, `name`, `user` y `password` con los datos de una base disponible. El puerto predeterminado es `3306` y el nombre, `discordtowny`.

No compartas el archivo de configuración: contiene el token y, si las usas, las credenciales de la base de datos.

Otros valores iniciales que afectan a los jugadores:

| Opción | Valor predeterminado |
|---|---|
| Canales por town | Texto y voz, dentro de `Comunidades` |
| Categoría para espacios archivados | `Archivo` |
| Rol distintivo compartido por los alcaldes | `Alcalde` |
| `limits.min-residents` | 2 residentes |
| `limits.max-towns` | 200 towns |
| `limits.creation-cooldown-seconds` | 60 segundos |
| `linking.code-expiry-minutes` | 10 minutos |
| `commands.cooldown-seconds` | 5 segundos por usuario en Discord |

## Comandos del juego

Puedes sustituir `/dt` por `/discordtowny`. Los ejemplos usan la town `Robledal` y el jugador `AnaCraft`.
«Admin» significa tener el permiso `discordtowny.admin`.

| Comando o ejemplo | Quién puede usarlo | Qué hace |
|---|---|---|
| `/dt help` | Cualquier jugador | Muestra los comandos que puede usar y su explicación. |
| `/dt link` | Cualquier jugador | Genera un código para vincular su cuenta. |
| `/dt unlink` | Jugador vinculado | Desvincula su cuenta y retira los roles otorgados por el plugin. |
| `/dt status` | Cualquier jugador | Muestra la vinculación, la cuenta de Discord y si su town tiene espacio. |
| `/dt create` | Alcalde vinculado | Crea el espacio de su town si cumple los requisitos. |
| `/dt delete` | Alcalde | Archiva el espacio de su town, con confirmación. Conserva el historial. |
| `/dt sync` | Alcalde | Sincroniza los roles de los residentes de su town. |
| `/dt admin sync` | Admin | Revisa y sincroniza todos los espacios. |
| `/dt admin sync Robledal` | Admin | Revisa y sincroniza una town concreta. |
| `/dt admin unlink AnaCraft` | Admin | Desvincula a ese jugador. |
| `/dt admin reload` | Admin | Recarga configuración y textos. |
| `/dt admin list` | Admin | Lista towns, estados, canales, residentes con rol y última actividad. |
| `/dt admin info Robledal` | Admin | Muestra el detalle del espacio, su actividad y los problemas detectados. |
| `/dt admin purge` | Admin | Borra definitivamente los espacios archivados, con confirmación. |
| `/dt admin update` | Admin | Comprueba y descarga la última versión. |
| `/dt admin update status` | Admin | Muestra la versión actual, la disponible y si hay una descarga pendiente. |

Las actualizaciones se comprueban cada 12 horas y se descargan automáticamente por defecto. Se aplican al reiniciar el servidor. Puedes desactivar la comprobación con `updates.check-enabled` o la descarga automática con `updates.auto-download`.

## Comandos de Discord

Escribe `/` y selecciona el comando del bot. En los ejemplos, introduce el valor indicado en el campo que muestra Discord; `A7K9MX` es solo un código de ejemplo.

| Comando o ejemplo | Quién puede usarlo | Qué muestra o hace |
|---|---|---|
| `/link A7K9MX` | Jugador con un código válido del juego | Vincula su cuenta de Discord con Minecraft. |
| `/unlink` | Usuario vinculado | Desvincula su cuenta y retira los roles del plugin. |
| `/town Robledal` | Cualquier usuario, sin vincularse | Alcalde, residentes, fundación, parcelas, banco, nación y estado de ruina. |
| `/town` | Usuario vinculado | Ficha de su propia town. |
| `/residents Robledal` | Cualquier usuario, sin vincularse | Lista paginada de residentes y su estado. |
| `/res AnaCraft` | Cualquier usuario, sin vincularse | Town, cargo, conexión y saldo si hay economía. |
| `/res` | Usuario vinculado | Su propia ficha de residente. |
| `/townlist` o `/townlist 2` | Cualquier usuario, sin vincularse | Listado ordenado de towns, por páginas. |
| `/mytown` | Usuario vinculado | Ficha de su propia town. |
| `/help` | Cualquier usuario, sin vincularse | Comandos de Discord disponibles y su explicación. |

Las consultas leen los datos actuales de Towny. Los seis comandos de información se pueden desactivar en `commands`. Sus respuestas son públicas por defecto, salvo `/mytown` y `/help`, que solo ve quien los ejecuta. Cada uno tiene su opción `ephemeral`. La confirmación de vinculación también es privada.

La spec permite `/residents` sin argumento, pero no define qué town consulta en ese caso. Indica el nombre, como en el ejemplo.

## Límites de Discord

Discord admite **500 canales y 250 roles por servidor**. Con dos canales y un rol por town, el techo orientativo ronda las **240 towns**, antes de descontar otros canales, categorías, roles y espacios archivados. No es una capacidad garantizada. El límite propio del plugin empieza en 200.

Además, Discord limita cada categoría a **50 canales**. La especificación solo define una categoría activa y no explica cómo repartir espacios cuando se llena. Con texto y voz por town, esa categoría llega a 25 towns. No planifiques 240 espacios activos con la estructura actual sin resolver ese límite. Consulta los [límites oficiales de Discord](https://support.discord.com/hc/en-us/articles/33694251638295-Discord-Account-Caps-Server-Caps-and-More).

## Licencia

[MIT](LICENSE).

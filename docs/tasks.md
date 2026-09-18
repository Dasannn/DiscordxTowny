# DiscordTowny — Tareas

Unidad de trabajo del proyecto. Cada ficha es el encargo completo de un agente:
una rama, un worktree, una zona de archivos.

Rige `docs/constitution.md`. Implementa `docs/spec.md` según `ARCHITECTURE.md`,
en el orden de `docs/plan.md`.

Estado: **borrador v1** — pendiente de aprobación.

---

## Reglas para todo agente

Antes de escribir una línea, lee: `docs/constitution.md`, `docs/spec.md`,
`ARCHITECTURE.md` y tu ficha.

- Trabaja **solo** en los archivos de tu zona. Si necesitas tocar otra, para y
  avisa al arquitecto.
- No implementes nada que no esté en la spec. Si falta algo, avisa; no
  improvises.
- Respeta el modelo de hilos de la arquitectura. Es la fuente de la mitad de los
  bugs de este tipo de plugin.
- Deja pruebas de lo que escribes. La lógica sin prueba no está terminada.
- No integres a `main`. No revises tu propio trabajo.
- Cuando termines, declara la rama lista e indica qué quedó fuera y por qué.

Estado de cada tarea: `pendiente`, `en curso`, `en revisión`, `corrigiendo`,
`lista`, `integrada`.

---

## T0 — Esqueleto del proyecto

- **Rama**: `feat/esqueleto` · **Fase** 0 · **Depende de**: nada
- **Responsable**: arquitecto · **Estado**: pendiente
- **Zona**: raíz del proyecto, `build.gradle.kts`, recursos, CI

**Construye**

- Proyecto Gradle con Kotlin DSL, Java 25, plugin de shadow con relocalización
  de JDA y HikariCP.
- Clase principal que arranca y apaga limpio en Paper, con Towny como
  dependencia dura.
- `config.yml` y `messages.yml` de ejemplo, completos según la sección de
  configuración de la spec.
- Árbol de paquetes vacío de `ARCHITECTURE.md`, con la regla de dependencias
  escrita en un `package-info` o equivalente.
- CI que compila y corre tests en cada push.
- `.gitignore`, licencia.

**Aceptación**: el jar carga en un Paper limpio con Towny, arranca y se apaga
sin errores ni advertencias.

**No toques**: nada de lógica funcional.

---

## T1 — Contratos

- **Rama**: `feat/contratos` · **Fase** 1 · **Depende de**: T0
- **Responsable**: arquitecto · **Estado**: pendiente
- **Zona**: interfaces en cada paquete, sin implementaciones

**Construye** las firmas que separan las zonas:

- Almacenamiento: operaciones sobre vínculos, códigos, espacios y auditoría.
- Discord: encolar una operación sobre el guild, consultar su resultado,
  publicar en el canal de logs.
- Towny: lectura de town, residentes, alcalde, estado de ruina.
- Configuración: objetos tipados de cada bloque de `config.yml`.
- Dominio: los tipos que cruzan fronteras, inmutables.

**Aceptación**: las firmas compilan, están documentadas y revisadas. Ninguna
interfaz de dominio menciona tipos de JDA, Bukkit ni JDBC.

**Importante**: cambiar un contrato después obliga a coordinar varias ramas.
Piénsalo bien una vez.

---

## T2 — Almacenamiento

- **Rama**: `feat/storage` · **Fase** 2 · **Depende de**: T1
- **Paralela a**: T3, T4 · **Estado**: pendiente
- **Zona**: `storage/`

**Construye**

- Conexión con HikariCP, MariaDB/MySQL y SQLite con un único camino de código.
- Migraciones numeradas que corren al arrancar, con tabla de versión de esquema.
- Tablas `links`, `link_codes`, `spaces`, `audit_log` según la arquitectura, con
  prefijo configurable.
- Implementación de los contratos de almacenamiento, toda fuera del hilo
  principal.

**Aceptación**: tests con SQLite en memoria que cubren cada operación y la
cadena completa de migraciones. Las mismas pruebas pasan contra MariaDB.

**No toques**: dominio, Discord, Bukkit.

---

## T3 — Cliente de Discord y colas

- **Rama**: `feat/discord-core` · **Fase** 2 · **Depende de**: T1
- **Paralela a**: T2, T4 · **Estado**: pendiente
- **Zona**: `discord/`, sin los comandos slash

**Construye**

- Conexión JDA con los intents mínimos necesarios, arranque y apagado limpios.
- Cola serializada de mutaciones del guild: un consumidor, pasos idempotentes,
  reintentos con espera creciente, distinción entre fallo transitorio y
  permanente.
- Comprobación al arrancar de que el rol del bot está por encima de los roles
  que gestiona y de que tiene los permisos necesarios.
- Cola de logs: agrupa mensajes, envía por intervalo, tamaño máximo, descarta
  con recuento cuando se llena, nunca bloquea al productor.
- Modo degradado: si no hay conexión, el resto del plugin puede preguntarlo y
  seguir.

**Aceptación**: contra un guild de pruebas, crear y borrar canales y roles
funciona; interrumpir una operación a medias y reintentarla no duplica nada; el
canal de logs aguanta una ráfaga sin crecer sin límite.

**No toques**: dominio, almacenamiento, Bukkit.

---

## T4 — Configuración y fachada de Towny

- **Rama**: `feat/config-towny` · **Fase** 2 · **Depende de**: T1
- **Paralela a**: T2, T3 · **Estado**: pendiente
- **Zona**: `config/`, `towny/`

**Construye**

- Carga de `config.yml` y `messages.yml` a objetos tipados, una sola vez, con
  recarga.
- Validación al arrancar: IDs con formato válido, intervalos positivos,
  plantillas con marcadores conocidos. Configuración inválida se rechaza
  señalando qué está mal, y el plugin arranca degradado.
- El token y las credenciales nunca aparecen en logs ni en volcados.
- Fachada de lectura sobre Towny: town por UUID y por nombre, residentes,
  alcalde, estado de ruina, nación. Solo lectura, solo hilo principal.

**Aceptación**: tests de validación con configuraciones válidas e inválidas.
La fachada devuelve datos correctos contra un servidor local con Towny.

**No toques**: dominio, Discord, almacenamiento.

---

## T5 — Vinculación de cuentas

- **Rama**: `feat/vinculacion` · **Fase** 3 · **Depende de**: T2, T3, T4
- **Estado**: pendiente · **Zona**: `link/`, más sus comandos en `minecraft/` y
  `discord/`

**Construye**

- Generación de códigos de 6 caracteres sin caracteres ambiguos, con generador
  criptográficamente seguro, caducidad configurable y un código vivo por
  jugador.
- `/dt link`, `/dt unlink`, `/link`, `/unlink`, `/dt admin unlink <jugador>`.
- Un UUID a un Discord ID y viceversa; intento sobre cuenta ya vinculada se
  rechaza explicando cómo desvincular.
- Límite de intentos fallidos por usuario de Discord.
- Al vincular, se dispara la sincronización de esa cuenta.
- Desvincular retira todos los roles otorgados por el plugin.

**Aceptación**: criterio 1 de la spec. Un vínculo sobrevive a un reinicio. Un
código caducado o ya usado se rechaza.

**No toques**: `space/`, `sync/`.

---

## T6 — Ciclo de vida del espacio

- **Rama**: `feat/espacios` · **Fase** 4 · **Depende de**: T5
- **Secuencial con**: T7 · **Estado**: pendiente · **Zona**: `space/`

**Construye**

- `/dt create` con todas sus validaciones: es alcalde, está vinculado, no hay
  espacio ya, mínimo de residentes, `max_towns`, cooldown, bot disponible.
- Tarea de creación idempotente: categoría contenedora si falta, rol, canal de
  texto, canal de voz, permisos, asignación inicial de roles. Cada ID se
  persiste antes de seguir.
- Permisos exactos de la spec: `@everyone` sin ver, rol de town con acceso.
- Renombrado de town: renombra canales y rol.
- Archivado: canal a solo lectura, movido a la categoría de archivo, rol
  eliminado. Nada se borra solo.
- Restauración si la town revive estando archivada.
- `/dt delete` con confirmación.

**Aceptación**: criterios 2, 3, 10. Cortar el servidor a mitad de una creación y
reconciliar deja el espacio completo, sin duplicados.

**No toques**: `sync/`, comandos ajenos a los de esta ficha.

---

## T7 — Sincronización y reconciliación

- **Rama**: `feat/sincronizacion` · **Fase** 4 · **Depende de**: T6
- **Estado**: pendiente · **Zona**: `sync/`, listeners en `minecraft/`

**Construye**

- Listeners de Towny: entrada y salida de residentes, expulsión, cambio de
  alcalde, renombrado, eliminación y ruina. Cada uno lee y delega; nada pesado
  en el hilo principal.
- Cálculo de «roles que le corresponden a este jugador» como conjunto, y
  aplicación de la diferencia. Solo se tocan roles gestionados por el plugin.
- Sincronización al entrar al servidor y al vincular.
- Job periódico de reconciliación: canales desaparecidos, roles borrados,
  espacios registrados sin canales, miembros con roles que no les tocan,
  espacios `INCONSISTENTE`. Modo reparar o solo informar. Por lotes, con pausas.
- `/dt sync` y `/dt admin sync [town]`.

**Aceptación**: criterios 4, 5, 6, 8. Un rol dado a mano se retira en la
siguiente pasada. Un canal borrado a mano se detecta y se repara.

**No toques**: `space/` salvo consumirlo.

---

## T8 — Comandos del juego

- **Rama**: `feat/comandos-juego` · **Fase** 5 · **Depende de**: T7
- **Paralela a**: T9 · **Estado**: pendiente · **Zona**: `minecraft/`

**Construye**

- `/dt help`, filtrado por lo que puede usar quien lo ejecuta.
- `/dt status`.
- Bloque admin: `list`, `info <town>`, `purge` con confirmación, `reload`.
- Árbol de permisos completo y autocompletado de argumentos.
- Respuesta inmediata en operaciones asíncronas, con confirmación posterior.

**Aceptación**: cada comando responde correctamente con y sin permisos, con y
sin Discord disponible, dentro y fuera de una town.

**No toques**: dominio. Si necesitas algo que no expone, avisa.

---

## T9 — Comandos de Discord

- **Rama**: `feat/comandos-discord` · **Fase** 5 · **Depende de**: T7
- **Paralela a**: T8 · **Estado**: pendiente · **Zona**: comandos slash en
  `discord/`

**Construye**

- `/town`, `/res`, `/residents`, `/townlist`, `/mytown`, `/help`.
- Embeds ricos, paginación donde hace falta, datos leídos en vivo de Towny.
- Activación, cooldown y visibilidad efímera o pública configurables por
  comando.
- Errores claros ante town o jugador inexistente, y ante autor no vinculado en
  los comandos que lo requieren.

**Aceptación**: cada comando responde dentro del límite de tiempo de Discord,
incluso con muchas towns. Ningún embed vacío ante una entidad inexistente.

**No toques**: dominio, la cola de mutaciones.

---

## T10 — Actualizador

- **Rama**: `feat/actualizador` · **Fase** 6 · **Depende de**: T0
- **Paralela a**: casi todo · **Estado**: pendiente · **Zona**: `update/`

**Construye**

- Comprobación periódica contra los releases de GitHub, con el cliente HTTP del
  JDK. Sin dependencias nuevas.
- URL del repositorio **constante en el código**, no configurable.
- Comparación semántica de versiones. Caché de la respuesta, respeto del límite
  de peticiones, fuera del hilo principal, con tiempo máximo de espera.
- Descarga automática al detectar versión nueva, desactivable por
  configuración, y `/dt admin update` para forzarla.
- Descarga a temporal, SHA-256 verificado contra el checksum del release, y solo
  entonces mover a la carpeta `update` del servidor. Tope de tamaño.
- Avisos: consola al arrancar, admins al entrar, canal de logs una vez por
  versión. Y de nuevo al quedar descargada.
- `/dt admin update status`.

**Aceptación**: un checksum que no cuadra descarta la descarga sin dejar restos.
Sin red, el plugin funciona igual y anota el fallo una sola vez. El jar en uso
nunca se toca.

**No toques**: absolutamente nada fuera de `update/` y sus comandos.

---

## T11 — Documentación pública

- **Rama**: `docs/publica` · **Fase** 6 · **Depende de**: spec aprobada
- **Paralela a**: casi todo · **Estado**: pendiente · **Zona**: `README.md`,
  `docs/guia-de-uso.md`

**Construye**

- `README.md`: qué es, qué resuelve, requisitos, instalación, configuración
  mínima, lista de comandos, licencia.
- `docs/guia-de-uso.md`: recorrido en lenguaje de jugador — vincular la cuenta,
  crear el espacio de la town, qué ve cada quién, qué pasa al entrar, salir o
  desaparecer la town.
- Sin detalles de código ni de arquitectura.
- Instrucciones de creación del bot y permisos que necesita en Discord.

**Aceptación**: alguien que nunca vio el plugin lo instala y lo usa siguiendo
solo estos dos documentos.

**No toques**: código.

---

## T12 — Endurecimiento

- **Rama**: `chore/endurecimiento` · **Fase** 7 · **Depende de**: T8, T9, T10
- **Responsable**: arquitecto · **Estado**: pendiente

**Hace**

- Recorrer los once criterios de aceptación de la spec sobre un servidor real.
- Provocar fallos: bot caído, base de datos caída, canal borrado a mano, rol
  asignado a mano, corte a mitad de una creación, ráfaga de operaciones.
- Medir que el servidor no pierde ticks con el canal de logs saturado.
- Auditar que no se filtran secretos en ningún log ni mensaje de error.

**Aceptación**: los once criterios pasan y ningún fallo provocado deja permisos
abiertos ni estado irrecuperable.

---

## T13 — Release

- **Rama**: `chore/release` · **Fase** 8 · **Depende de**: T12
- **Responsable**: arquitecto · **Estado**: pendiente

**Hace**

- Versionado semántico y changelog.
- Pipeline que publica el jar **y su checksum SHA-256** en el release de GitHub.
  Sin checksum publicado, T10 no funciona.
- Guía de contribución y plantillas de issue.

**Aceptación**: un release publicado desde el pipeline es detectado y descargado
correctamente por el actualizador de una instancia con la versión anterior.

---

## Mapa rápido

| Tarea | Fase | Depende de | Paralela a | Zona |
|---|---|---|---|---|
| T0 Esqueleto | 0 | — | — | raíz |
| T1 Contratos | 1 | T0 | — | interfaces |
| T2 Almacenamiento | 2 | T1 | T3, T4 | `storage/` |
| T3 Discord y colas | 2 | T1 | T2, T4 | `discord/` |
| T4 Configuración y Towny | 2 | T1 | T2, T3 | `config/`, `towny/` |
| T5 Vinculación | 3 | T2, T3, T4 | T10, T11 | `link/` |
| T6 Espacios | 4 | T5 | T10, T11 | `space/` |
| T7 Sincronización | 4 | T6 | T10, T11 | `sync/` |
| T8 Comandos juego | 5 | T7 | T9 | `minecraft/` |
| T9 Comandos Discord | 5 | T7 | T8 | `discord/` slash |
| T10 Actualizador | 6 | T0 | casi todo | `update/` |
| T11 Documentación | 6 | spec | casi todo | `README`, guía |
| T12 Endurecimiento | 7 | T8, T9, T10 | — | todo |
| T13 Release | 8 | T12 | — | pipeline |

# DiscordTowny — Arquitectura

Cómo está construido el plugin por dentro. Rige sobre este documento
`docs/constitution.md`, y describe la implementación de `docs/spec.md`.

Estado: **borrador v1** — pendiente de aprobación.

---

## 1. Stack

| Elemento | Decisión | Porqué |
|---|---|---|
| Lenguaje | Java 25 | Versión del servidor objetivo |
| Servidor | Paper para Minecraft 26.2 | Requisito del proyecto |
| Build | Gradle con Kotlin DSL | Es lo que usan Paper y Towny; shadow para empaquetar |
| Discord | JDA | La biblioteca de referencia en el ecosistema de plugins, con gestión de rate limit incorporada |
| Base de datos | HikariCP + JDBC, MariaDB/MySQL o SQLite | Un solo camino de código para ambos motores |
| Tests | JUnit 5 + Mockito | Estándar, sin servidor de por medio |

Se empaqueta un único jar con JDA y HikariCP relocalizados, para no chocar con
otros plugins que carguen las mismas bibliotecas.

## 2. Estructura de módulos

Un solo módulo Gradle. Paquetes por responsabilidad, no por capa técnica:

```
com.discordtowny
├── DiscordTownyPlugin.java      arranque, apagado, cableado
├── model/                       tipos inmutables que cruzan fronteras
├── config/                      carga de config.yml y textos
├── storage/                     acceso a datos, migraciones, DAOs
├── link/                        vinculación de cuentas y códigos
├── space/                       ciclo de vida del espacio de una town
├── sync/                        reconciliación por eventos y periódica
├── discord/                     cliente JDA, cola de operaciones, comandos slash, logs
├── minecraft/                   comandos del juego, listeners de Towny
├── towny/                       fachada de lectura sobre la API de Towny
└── update/                      comprobación y descarga de nuevas versiones
```

**Regla de dependencias**: `minecraft` y `discord` son adaptadores de entrada y
salida; ambos dependen de `link`, `space` y `sync`. Nunca al revés. `towny` es
la única puerta a la API de Towny, y `storage` la única a la base de datos.
Ninguna clase de `space` o `sync` importa JDA ni Bukkit.

`model` no depende de nada, y todos los demás pueden depender de él. Existe
precisamente por eso: si los tipos inmutables vivieran en `link` o `space`,
`storage` dependería de ellos y ellos de `storage`, cerrando un ciclo de
paquetes. Cada paquete lleva su regla escrita en su `package-info`.

Esa regla es lo que permite que varios agentes trabajen a la vez sin pisarse: un
agente construye `discord/`, otro `storage/`, otro `sync/`, contra interfaces
acordadas.

## 3. Modelo de hilos

Tres mundos separados, con fronteras explícitas:

| Hilo | Qué corre |
|---|---|
| Hilo principal del servidor | Comandos del juego, listeners de Towny, toda lectura de la API de Towny |
| Pool del plugin | Base de datos, lógica de reconciliación |
| Hilos de JDA | Llamadas a Discord, respuesta a slash commands |

Reglas duras:

1. La API de Towny se lee **solo** en el hilo principal. Los listeners capturan
   lo que necesitan en un objeto inmutable y lo pasan al pool.
2. La base de datos y Discord se tocan **solo** fuera del hilo principal.
3. Volver al hilo principal se hace con el scheduler de Paper, y solo si hace
   falta hablar con Towny o con un jugador.
4. Nada bloquea esperando a Discord. El resultado se maneja de forma asíncrona.

Un comando del juego, por tanto, responde de inmediato con «trabajando en ello»
y confirma después.

## 4. Cola de operaciones de Discord

Crear y borrar canales y roles está fuertemente limitado por rate limit y, peor,
son operaciones no atómicas: crear un espacio son cuatro llamadas que pueden
fallar en la tercera.

Diseño:

- Toda mutación del guild pasa por una **cola serializada**. Un solo consumidor.
  Nada de crear diez espacios en paralelo.
- Cada operación se describe como una **tarea con pasos idempotentes**. Antes de
  crear algo, se comprueba si ya existe por su ID guardado. Reintentar una tarea
  a medias no duplica canales.
- Reintentos con espera creciente ante fallos transitorios. Fallo permanente
  (faltan permisos, se alcanzó un límite de Discord) corta la tarea y la marca.
- Una tarea que falla deja el espacio en estado `INCONSISTENTE`, y el job de
  reconciliación la retoma.

Las lecturas para responder a comandos de información no pasan por la cola.

## 5. Modelo de datos

Prefijo de tablas configurable. Esquema versionado con migraciones numeradas que
corren al arrancar.

**`links`** — identidad verificada
| Campo | Notas |
|---|---|
| `uuid` | Clave primaria |
| `discord_id` | Único |
| `linked_at` | |
| `last_known_name` | Solo para mostrar, nunca para identificar |

**`link_codes`** — códigos pendientes
| Campo | Notas |
|---|---|
| `code` | Clave primaria |
| `uuid` | Único: un código vivo por jugador |
| `expires_at` | Se purgan al caducar |
| `attempts` | Control de fuerza bruta |

**`spaces`** — espacio de Discord de una town
| Campo | Notas |
|---|---|
| `town_uuid` | Clave primaria. El UUID de Towny, no el nombre: el nombre cambia |
| `town_name` | Último nombre conocido, para mostrar y detectar renombrados |
| `category_id`, `text_channel_id`, `voice_channel_id`, `role_id` | IDs de Discord, nulos si no aplica |
| `state` | `ACTIVO`, `ARCHIVADO`, `INCONSISTENTE` |
| `created_at`, `archived_at`, `last_activity_at` | Para `/dt admin list` e `info` |

**`audit_log`** — qué hizo el bot
| Campo | Notas |
|---|---|
| `id`, `at`, `actor`, `action`, `target`, `result`, `detail` | Alimenta el canal de logs y el diagnóstico |

Se identifica por UUID de town y por ID de Discord. Nunca por nombre: los
nombres se renombran y rompen la correspondencia.

## 6. Flujos principales

### 6.1 Vinculación

Juego: se genera el código, se guarda con caducidad, se muestra al jugador.
Discord: `/link` busca el código, valida caducidad e intentos, escribe en
`links`, borra el código y encola una sincronización de esa cuenta.

Los códigos se generan con un generador criptográficamente seguro, no con
`Random`.

### 6.2 Creación del espacio

1. En el hilo principal se valida todo lo validable con Towny: es alcalde, la
   town existe, cumple el mínimo de residentes.
2. Se comprueban las condiciones del plugin: no hay espacio ya, hay cupo, no hay
   cooldown.
3. Se encola la tarea, que crea en orden: categoría si falta, rol, canal de
   texto, canal de voz, permisos, y por último asignación de roles a los
   residentes vinculados.
4. Cada paso que produce un ID lo persiste antes de seguir. Si el servidor cae
   entre pasos, la reconciliación termina el trabajo.

### 6.3 Sincronización de un jugador

Entra al servidor, vincula, o cambia de town: se calcula el conjunto de roles
que le corresponde según Towny y se compara con los que tiene en Discord.
Se aplica la diferencia. Se tocan únicamente los roles gestionados por el
plugin; los demás roles del usuario no se miran.

### 6.4 Reconciliación periódica

Recorre `spaces` y compara contra Discord y contra Towny: canales que ya no
existen, roles borrados, towns sin espacio pese a tenerlo registrado, miembros
con roles que no les tocan, espacios en estado `INCONSISTENTE`. Según la
configuración, repara o solo informa. Va en lotes, con pausas, para no saturar
el rate limit.

## 7. Configuración

`config.yml` y `messages.yml` separados. Se cargan en objetos tipados una sola
vez y se releen con `/dt admin reload`. Nada lee del `YamlConfiguration` en
caliente.

Los valores que llegan del archivo se validan al arrancar: IDs con formato
correcto, intervalos positivos, plantillas con marcadores conocidos. Una
configuración inválida se rechaza con un mensaje que dice qué línea está mal, y
el plugin arranca en modo degradado en lugar de operar con basura.

El token y las credenciales nunca se imprimen: se leen a memoria y se marcan
como sensibles en cualquier volcado de configuración.

## 8. Canal de logs

Los eventos de auditoría se escriben en la base de datos y, en paralelo, se
encolan para Discord. Un consumidor vacía la cola cada intervalo configurable y
envía los mensajes agrupados en un solo embed. La cola tiene tamaño máximo: si
se llena, se descartan los eventos menos relevantes y se anota cuántos se
perdieron. Nunca crece sin límite y nunca bloquea a quien lo produce.

## 9. Integración con Towny

Todo el contacto con Towny pasa por una fachada propia. El resto del plugin no
conoce las clases de Towny, solo los datos que necesita. Eso protege de los
cambios de API de Towny entre versiones: cuando cambie, se toca un solo paquete.

Los listeners se suscriben a los eventos de residentes, alcaldía, renombrado,
eliminación y ruina de towns. Cada listener hace lo mínimo en el hilo principal:
leer y delegar.

## 10. Errores

- Falla la conexión a Discord: el plugin queda en modo degradado. Los comandos
  del juego responden que Discord no está disponible, los del guild no existen,
  y el servidor no se entera.
- Falla la base de datos: los comandos que la necesitan se rechazan con mensaje
  claro. No se opera sobre Discord sin poder persistir el resultado.
- Falla una tarea a medias: el espacio queda `INCONSISTENTE` y la reconciliación
  lo retoma. Se prefiere dejar un canal sin crear antes que un canal visible
  para quien no debe.

## 10.1 Actualizador

Usa el cliente HTTP del propio JDK contra la API de releases de GitHub. Sin
dependencias nuevas.

- La URL del repositorio es **constante en el código**, no configurable. Que un
  administrador pueda apuntar el actualizador a otro origen convierte una
  configuración editable en ejecución de código arbitrario.
- La versión se compara de forma semántica, no por cadena de texto.
- La respuesta se cachea y se respeta el límite de peticiones de GitHub. La
  comprobación corre fuera del hilo principal, con tiempo máximo de espera.
- La descarga se escribe a un archivo temporal, se calcula su SHA-256 y se
  compara con el publicado en el release. Solo si coincide se mueve a la carpeta
  `update` del servidor. Si no, se borra el temporal.
- El tamaño de descarga tiene un tope, para no llenar el disco ante una
  respuesta inesperada.
- El actualizador no lee ni escribe nada del estado del plugin. Es independiente
  del resto: puede fallar entero sin afectar a nada más.

La descarga se dispara sola al detectar una versión nueva, y también a petición
con `/dt admin update`. Se puede desactivar por configuración.

Lo que **no** hace, deliberadamente: recargar clases en caliente ni aceptar un
origen distinto del oficial.

## 11. Pruebas

- La lógica de `link`, `space` y `sync` se prueba con JUnit sin servidor ni
  Discord, porque no depende de ninguno de los dos. Ahí está el valor real.
- `storage` se prueba contra SQLite en memoria, incluidas las migraciones.
- Los adaptadores de Bukkit y JDA se prueban a mano con una lista de
  verificación; no se montan servidores falsos.
- Cada criterio de aceptación de la spec tiene su comprobación, automática o
  manual documentada.

## 12. Decisiones deliberadas

| Decisión | Alternativa descartada | Porqué |
|---|---|---|
| Un solo módulo Gradle | Multi-módulo | No hay reutilización externa que lo justifique |
| Serializar las mutaciones de Discord | Paralelizar con control de rate limit | El rate limit lo hace inútil y multiplica los fallos parciales |
| Identificar por UUID de town | Por nombre | Los renombrados romperían la correspondencia |
| Archivar sin borrar | Borrado automático | El historial de conversación no se recupera |
| Un rol global de alcalde | Un rol de alcalde por town | Duplicaría el consumo del límite de 250 roles |
| Fachada propia sobre Towny | Usar su API por todo el código | Aísla los cambios de API a un solo paquete |
| Sin caché de datos de Towny | Caché con invalidación | Leer en vivo es barato y la caché desincronizada es la peor clase de bug aquí |
| Actualizar por la carpeta `update` de Paper | Reemplazo en caliente del jar | Recargar un plugin con conexiones vivas corrompe estado; el mecanismo de Paper ya resuelve esto |
| Origen de descarga fijo en el código | Origen configurable | Un origen editable convierte la configuración en ejecución de código arbitrario |

## 13. Preparado para naciones, sin construirlas

El modelo no impide añadir naciones después: `spaces` se identifica por el UUID
de la entidad dueña y podría admitir un tipo, y la sincronización ya calcula
«roles que le corresponden a este jugador» como un conjunto. Añadir naciones
sería sumar una fuente a ese cálculo, no reescribirlo. No se construye nada de
esto ahora.

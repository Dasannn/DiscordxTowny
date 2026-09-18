# DiscordTowny — Plan de desarrollo

Cómo se construye, en qué orden y qué se puede hacer en paralelo. Rige
`docs/constitution.md`; implementa `docs/spec.md` según `ARCHITECTURE.md`.

Estado: **borrador v1** — pendiente de aprobación.

---

## 1. Método

- Un worktree por tarea, rama con nombre propio, nadie toca `main`.
- Cada rama pasa revisión antes de integrarse. Revisa un agente distinto del
  autor, y la integración la hace el arquitecto.
- Ninguna rama se abre sin que exista el contrato que necesita (ver fase 1).
- Una tarea que necesita tocar archivos de otra tarea abierta no se lanza en
  paralelo: se espera o se reordena.

## 2. Reparto para trabajo en paralelo

La regla de dependencias de la arquitectura define las fronteras. Cada zona
pertenece a una sola tarea a la vez:

| Zona | Paquetes | Toca a otros |
|---|---|---|
| Datos | `storage/` | No |
| Discord | `discord/` | No |
| Juego | `minecraft/`, `towny/` | No |
| Dominio | `link/`, `space/`, `sync/` | Es el centro: se trabaja solo o coordinado |
| Actualizador | `update/` | No, es independiente |
| Configuración | `config/` | Lo consumen todos: se cierra pronto |
| Documentación | `README.md`, `docs/guia-de-uso.md` | No |

Zonas distintas, agentes distintos, sin conflictos de merge. El dominio es el
cuello de botella: se construye primero y luego se toca poco.

## 3. Fases

### Fase 0 — Esqueleto

Un solo agente. No se paraleliza nada hasta tenerlo.

- Proyecto Gradle, Java 25, dependencias, shadow con relocalización.
- Plugin que arranca y apaga en Paper sin hacer nada más.
- `config.yml` y `messages.yml` de ejemplo, completos según la spec.
- Estructura de paquetes vacía, con la regla de dependencias documentada.
- CI que compila y corre tests en cada push.

**Termina cuando**: el jar carga en un Paper limpio con Towny y no lanza
errores.

### Fase 1 — Contratos

Un solo agente, corto y decisivo. Define las interfaces que separan las zonas:
almacenamiento, operaciones sobre Discord, lectura de Towny, configuración
tipada. Sin implementaciones.

**Termina cuando**: las firmas están fijadas y revisadas. A partir de aquí,
varias tareas pueden avanzar sin verse.

**Es el documento vivo más importante del proyecto**: cambiar un contrato
después obliga a coordinar varias ramas. Se piensa bien una vez.

### Fase 2 — Cimientos, en paralelo

Tres agentes a la vez, tres zonas sin solape:

| Agente | Qué construye |
|---|---|
| A | `storage/`: esquema, migraciones, DAOs, soporte MariaDB y SQLite |
| B | `discord/`: conexión JDA, cola serializada de operaciones, cola de logs |
| C | `config/` y `towny/`: carga y validación de configuración, fachada de lectura de Towny |

**Termina cuando**: cada zona compila, tiene sus tests y se demuestra por
separado. `storage` contra SQLite en memoria; `discord` contra un guild de
pruebas; `towny` contra un servidor local.

### Fase 3 — Vinculación

Un agente, zona dominio. La primera funcionalidad de punta a punta: `/dt link`,
`/link`, `/unlink`, códigos con caducidad y control de intentos, persistencia.

**Termina cuando**: un jugador vincula su cuenta y el vínculo sobrevive a un
reinicio. Criterio de aceptación 1.

### Fase 4 — Espacios y sincronización

El corazón. Se hace en dos tareas, **en secuencia** porque comparten dominio:

1. Ciclo de vida del espacio: creación, renombrado, archivado, restauración, con
   tareas idempotentes sobre la cola.
2. Sincronización: listeners de Towny, cálculo de roles que corresponden,
   reconciliación periódica y bajo demanda.

**Termina cuando**: se cumplen los criterios de aceptación 2 a 6 y el 8.

### Fase 5 — Superficie de comandos, en paralelo

Dos agentes, zonas separadas:

| Agente | Qué construye |
|---|---|
| D | Comandos del juego: `help`, `status`, `delete`, `sync`, y todo el bloque `admin` |
| E | Comandos de Discord: `/town`, `/res`, `/residents`, `/townlist`, `/mytown`, `/help`, con sus embeds y paginación |

Ambos consumen dominio ya construido; no lo modifican.

### Fase 6 — Independientes, en paralelo

| Agente | Qué construye |
|---|---|
| F | `update/`: comprobación, descarga verificada, avisos, `/dt admin update` |
| G | `README.md` y `docs/guia-de-uso.md`, en lenguaje de jugador |

Ninguno toca el dominio. Pueden arrancar antes si hay agentes libres: el
actualizador solo necesita la fase 0, y la documentación solo necesita la spec.

### Fase 7 — Endurecimiento

Un agente, o el arquitecto:

- Recorrer los doce criterios de aceptación uno a uno sobre un servidor real.
- Provocar fallos a propósito: bot caído, base de datos caída, canal borrado a
  mano, rol asignado a mano, corte a mitad de una creación.
- Medir que el servidor no pierde ticks con el canal de logs a tope.
- Revisar que no se filtran secretos en ningún log ni mensaje.

### Fase 8 — Release

- Versionado semántico, changelog.
- Pipeline que publica el jar **y su checksum SHA-256** en el release de GitHub.
  Sin checksum publicado, el actualizador de la fase 6 no funciona.
- Licencia y guía de contribución.

## 3.1 Revisión

La revisión es una tarea más, con su propio agente, y no la hace quien escribió
el código.

### Roles

| Rol | Qué hace | Qué no hace |
|---|---|---|
| Agente autor | Escribe la tarea en su worktree | No revisa su propio trabajo ni integra |
| Agente revisor | Revisa la rama y escribe el informe en `docs/revisiones/` | **No corrige el código que revisa** |
| Arquitecto | Lee el informe, revisa la corrección, integra a `main` | No escribe las tareas |

### Ciclo

1. El agente autor termina su rama y la declara lista.
2. El agente revisor la revisa y escribe
   `docs/revisiones/<rama>.md`: qué revisó, qué encontró, severidad de cada
   hallazgo y qué hay que cambiar.
3. El agente autor corrige en su rama y responde al informe.
4. El revisor comprueba que cada hallazgo quedó resuelto y cierra el informe.
5. El arquitecto lee el informe cerrado, revisa los cambios de la corrección e
   integra.

El revisor no toca el código porque una corrección suya entraría sin revisar.
La única excepción son erratas evidentes en texto o comentarios, anotadas en el
informe.

### Qué revisa

- Cumple el criterio de aceptación de su tarea.
- No invade zonas de otras tareas ni rompe la regla de dependencias de la
  arquitectura.
- Respeta el modelo de hilos: ni Towny fuera del hilo principal, ni base de
  datos o Discord dentro de él.
- No implementa nada que no esté en la spec.
- No filtra secretos en logs ni en mensajes a usuarios.
- Los fallos parciales no dejan permisos abiertos.
- Tiene pruebas y la documentación al día.

### Formato del informe

Un hallazgo por línea, con archivo y línea, severidad (bloqueante, importante,
menor) y la corrección propuesta. Sin elogios ni resúmenes largos: el informe se
lee para actuar.

## 4. Orden y paralelismo

```
Fase 0 ──> Fase 1 ──┬──> Fase 2 (A, B, C en paralelo) ──> Fase 3 ──> Fase 4 ──┬──> Fase 5 (D, E en paralelo) ──> Fase 7 ──> Fase 8
                    └──> Fase 6 (F, G en paralelo, desde el principio) ────────┘
```

Pico de paralelismo: tres agentes en fase 2, más los dos independientes de la
fase 6. Cinco autores a la vez es el techo razonable, con un agente revisor
trabajando detrás sobre las ramas que van quedando listas.

## 5. Riesgos

| Riesgo | Qué hacemos |
|---|---|
| Cambio de contrato a mitad de fase 2 | Fase 1 cerrada y revisada antes de abrir ramas. Un cambio obliga a parar y coordinar |
| Rate limit de Discord en pruebas | Guild de pruebas dedicado, nunca el de producción |
| API de Towny distinta a la esperada | La fachada de `towny/` se valida en fase 2, antes de construir nada encima |
| Fuga de trabajo al dominio desde fases 5 y 6 | En revisión: una rama de comandos que modifica dominio se devuelve |
| Documentación que envejece | Fase 6 en paralelo, y la revisión comprueba que un cambio de comando actualiza la doc |

## 6. Definición de terminado

Una tarea está terminada cuando compila, tiene sus pruebas, cumple el criterio
de aceptación que le toca, no invade zonas ajenas, no rompe la regla de
dependencias, y su documentación está al día. No antes.

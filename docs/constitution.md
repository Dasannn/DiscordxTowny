# DiscordTowny — Constitution

Documento de principios. Define qué es el proyecto, qué no es, y las reglas que
ninguna decisión posterior (spec, arquitectura, plan, tareas) puede violar.

Estado: **borrador v1** — pendiente de aprobación.

---

## 1. Propósito

Plugin de Paper que conecta un servidor Minecraft con Towny Advanced a un
servidor de Discord, de forma que cada town tenga su espacio privado en Discord
(canal de texto y de voz) creado y mantenido automáticamente por un bot, y que
los miembros puedan consultar datos de su town desde Discord.

## 2. Producto en una frase

> El alcalde escribe un comando en el juego y su town tiene canales privados en
> Discord con los permisos correctos, para siempre, sin intervención de un admin.

## 3. Alcance v1

### Dentro

- **Vinculación verificada** de cuenta Minecraft ↔ cuenta Discord.
- **Gestión de canales**: creación, renombrado y borrado de los canales de una
  town, dentro de una categoría contenedora configurable (por defecto
  `Comunidades`).
- **Gestión de roles**: rol por town y rol de alcalde, asignados y revocados
  automáticamente por el bot.
- **Sincronización de pertenencia**: entrar, salir, ser expulsado de una town o
  cambiar de alcalde se refleja en Discord.
- **Comandos de información en Discord**: datos detallados de town, residente y
  listados.
- **Persistencia** en MySQL/MariaDB, con SQLite como alternativa automática.

### Fuera (v1)

Estas exclusiones son deliberadas. Añadir cualquiera de ellas requiere modificar
este documento primero.

- Chat bridge entre el chat de town y Discord.
- Canales y roles para naciones.
- Soporte multi-servidor / red BungeeCord o Velocity.
- Panel web o API HTTP.
- Integración con economía o con plugins de guerra (SiegeWar u otros).
- Internacionalización más allá de un único idioma configurable por archivo.

## 4. Plataforma

| Elemento | Decisión |
|---|---|
| Servidor | Paper para Minecraft 26.2 |
| Java | 25 |
| Dependencia dura | Towny Advanced |
| Discord | Un único guild por instancia del plugin |
| Licencia | Open source en GitHub |

El plugin no arranca si Towny no está presente. No se soporta más de un guild
por servidor de Minecraft en v1.

## 5. Principios

### P1 — Discord refleja Towny, nunca al revés

Towny es la única fuente de verdad sobre quién pertenece a qué town. Discord es
una proyección. Ante cualquier discrepancia, gana Towny. No existe ninguna
acción en Discord que modifique la pertenencia a una town.

### P2 — La pertenencia a un canal no es voluntaria

Ningún usuario puede unirse a un canal de town, ni auto-asignarse un rol, ni
solicitar acceso. El rol de town lo otorga exclusivamente el bot a partir de la
lista de residentes de Towny. Un usuario que no es residente no puede ver el
canal.

### P3 — Identidad verificada o nada

Ningún permiso se concede sobre una identidad no verificada. La vinculación se
prueba con un código de un solo uso generado dentro del juego. El rol de alcalde
se concede solo tras comprobar contra la API de Towny que esa cuenta vinculada
es efectivamente el alcalde de esa town.

### P4 — El hilo principal es sagrado

Ninguna llamada a la API de Discord ocurre en el hilo principal del servidor.
Ninguna consulta a la base de datos ocurre en el hilo principal. El rendimiento
del servidor de Minecraft nunca se degrada por culpa de Discord.

### P5 — El estado se reconcilia, no se asume

Un admin puede borrar un canal a mano, Discord puede fallar una petición, el
servidor puede caerse a medias. El sistema asume que el estado se desincroniza
y debe ser capaz de detectarlo y repararlo, tanto de forma periódica como bajo
demanda con un comando de administración.

### P6 — Las acciones destructivas se confirman

Borrar los canales de una town destruye historial de conversación. Toda
destrucción requiere confirmación explícita o un periodo de gracia configurable.

### P7 — Los secretos no se versionan

El token del bot y las credenciales de la base de datos viven en configuración
local, nunca en el repositorio, nunca en logs, nunca en mensajes de error
mostrados a usuarios.

### P8 — Configurable donde importa, opinado donde no

Nombres de categoría, plantillas de nombres de canal y de rol, y la política de
borrado son configurables. La arquitectura interna y el modelo de permisos no lo
son.

### P9 — Fallar de forma visible y segura

Si Discord no responde, el servidor de Minecraft sigue funcionando. Los errores
se registran con contexto suficiente para diagnosticarlos, y las operaciones que
fallan a medias no dejan permisos abiertos.

## 6. Límites conocidos

Un guild de Discord admite un máximo de 500 canales y 250 roles. Con dos canales
y un rol por town, el techo práctico ronda las 240 towns. Ese límite lo impone
Discord y no es negociable. Lo que sí es configurable es el límite propio del
plugin (`max_towns`) y los criterios para calificar, de modo que el
administrador decida qué towns reciben canal antes de chocar contra el techo de
Discord. Al alcanzar el límite se rechaza la creación con un mensaje claro, en
lugar de fallar de forma opaca.

Las operaciones de creación y borrado de canales y roles están fuertemente
limitadas por rate limit en Discord. El diseño debe serializar y reintentar esas
operaciones, no dispararlas en paralelo.

## 7. Reglas de desarrollo

- El proyecto se desarrolla con varios agentes en paralelo, un worktree por
  tarea, y nadie trabaja directamente sobre `main`.
- Toda rama pasa por revisión de código antes de integrarse.
- No se implementa nada que no esté documentado en la spec.
- Cambiar arquitectura o requisitos exige aprobación previa y actualizar estos
  documentos.

## 8. Criterio de éxito

Un alcalde ejecuta un comando en el juego y, en menos de un minuto, su town
tiene canales privados en Discord con los residentes correctos dentro y nadie
más. Un admin del servidor no tiene que tocar nada.

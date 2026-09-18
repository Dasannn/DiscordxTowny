# DiscordTowny — Especificación funcional

Qué hace el plugin, visto desde fuera. Sin decisiones de implementación: esas
van en `ARCHITECTURE.md`.

Estado: **borrador v1** — pendiente de aprobación.
Rige sobre este documento: `docs/constitution.md`.

---

## 1. Actores

| Actor | Quién es |
|---|---|
| Residente | Jugador que pertenece a una town |
| Alcalde | Residente que es mayor de su town según Towny |
| Administrador | Staff del servidor, con permiso `discordtowny.admin` |
| Bot | Aplicación de Discord que ejecuta las acciones en el guild |

## 2. Vinculación de cuentas

### 2.1 Flujo

1. El jugador ejecuta `/dt link` en el juego.
2. El plugin genera un código de un solo uso, de 6 caracteres alfanuméricos sin
   caracteres ambiguos, con caducidad configurable (por defecto 10 minutos).
3. El jugador ejecuta `/link <código>` en Discord.
4. El bot valida el código, guarda el par UUID ↔ Discord ID y responde de forma
   efímera.
5. Inmediatamente después, el plugin sincroniza los roles que le correspondan a
   esa cuenta.

### 2.2 Reglas

- Un UUID se vincula a un único Discord ID y viceversa. Un intento de vincular
  una cuenta ya vinculada se rechaza con un mensaje que explica cómo desvincular.
- El código caduca por tiempo y se invalida al usarse. Generar un código nuevo
  invalida el anterior.
- Los intentos fallidos se limitan por usuario de Discord para evitar fuerza
  bruta sobre el espacio de códigos.
- `/dt unlink` en el juego y `/unlink` en Discord rompen la vinculación y
  retiran todos los roles otorgados por el plugin.
- Un administrador puede desvincular a un tercero con
  `/dt admin unlink <jugador>`.

### 2.3 Verificación de alcaldía

Ser alcalde no se declara: se comprueba. En cada sincronización el plugin
consulta a Towny quién es el mayor de la town y concede o retira el rol de
alcalde en consecuencia. Un cambio de alcalde en el juego se refleja sin que
nadie ejecute nada.

## 3. Canales y roles

### 3.1 Estructura

- Existe una categoría contenedora, creada por el bot la primera vez que se
  necesita. Nombre por defecto: `Comunidades`.
- **Discord limita a 50 canales por categoría.** Cuando la categoría actual se
  llena, el bot crea la siguiente numerada (`Comunidades 2`, `Comunidades 3`) y
  coloca ahí los nuevos espacios. Es transparente para el usuario: el techo real
  pasa a ser el de 500 canales por servidor.
- Por cada town con espacio activo, dentro de esa categoría:
  - un canal de texto, nombre por plantilla (por defecto `{town}`),
  - un canal de voz, nombre por plantilla (por defecto `{town}`).
- Qué canales se crean (texto, voz o ambos) es configurable.

### 3.2 Roles

- Un rol por town, por plantilla (por defecto `{town}`).
- Un rol global de alcalde, nombre configurable (por defecto `Alcalde`),
  compartido por todos los alcaldes del servidor.
- El rol del bot debe estar por encima de todos los roles que gestiona. Si no lo
  está, el plugin lo detecta al arrancar y avisa en consola sin intentar operar.

### 3.3 Permisos de los canales

| Entidad | Permiso |
|---|---|
| `@everyone` | Ver canal: denegado |
| Rol de la town | Ver canal, escribir, conectar y hablar: permitido |
| Rol de alcalde | Sin permisos especiales a nivel de canal |
| Bot | Gestionar el canal |

El rol de alcalde es una distinción visible y una llave para ciertos comandos,
no una llave de acceso a canales de otras towns. Un alcalde solo ve el canal de
su propia town, porque solo tiene el rol de su propia town.

Ningún usuario puede obtener un rol de town por sí mismo. La única vía de
asignación es el bot, a partir de la lista de residentes de Towny.

## 4. Comandos en el juego

Prefijo `/dt`, alias `/discordtowny`.

| Comando | Quién | Qué hace |
|---|---|---|
| `/dt help` | Cualquier jugador | Lista los comandos disponibles para quien lo ejecuta, con una línea de explicación cada uno |
| `/dt link` | Cualquier jugador | Genera el código de vinculación |
| `/dt unlink` | Vinculado | Rompe su vinculación |
| `/dt status` | Cualquier jugador | Muestra si está vinculado, a qué cuenta, y si su town tiene espacio |
| `/dt create` | Alcalde | Crea la categoría, canales y rol de su town |
| `/dt delete` | Alcalde | Elimina el espacio de su town, con confirmación |
| `/dt sync` | Alcalde | Fuerza la sincronización de roles de los residentes de su town |
| `/dt admin sync [town]` | Admin | Reconcilia todo, o una town concreta |
| `/dt admin unlink <jugador>` | Admin | Desvincula a un tercero |
| `/dt admin reload` | Admin | Recarga configuración y textos |
| `/dt admin list` | Admin | Lista los espacios registrados: town, estado, canales, residentes con rol y última actividad |
| `/dt admin info <town>` | Admin | Detalle de un espacio concreto, incluida su actividad y las inconsistencias detectadas |
| `/dt admin purge` | Admin | Borra definitivamente los espacios archivados, con confirmación |
| `/dt admin update` | Admin | Comprueba y descarga la última versión ahora mismo, sin esperar al ciclo automático |
| `/dt admin update status` | Admin | Indica la versión actual, la disponible y si hay una descarga pendiente |

`/dt help` solo muestra los comandos que quien lo ejecuta puede usar: un jugador
sin town no ve los comandos de alcalde, y nadie sin permiso ve los de
administración.

### 4.1 Reglas de `/dt create`

Se rechaza, con mensaje explicativo, si:

- quien lo ejecuta no es el alcalde de la town,
- quien lo ejecuta no tiene cuenta vinculada,
- la town ya tiene espacio,
- la town no alcanza el mínimo de residentes configurado,
- se alcanzó `max_towns`,
- el cooldown de creación sigue activo,
- el bot no está conectado o le faltan permisos en el guild.

Al crear, el bot asigna el rol de town a todos los residentes ya vinculados y el
rol de alcalde a quien ejecutó el comando. Los residentes que se vinculen
después reciben su rol en ese momento.

## 5. Comandos en Discord

Slash commands. Cada uno se puede desactivar por configuración. Respuesta
efímera o pública, configurable por comando.

| Comando | Qué muestra |
|---|---|
| `/link <código>` | Vincula la cuenta |
| `/unlink` | Rompe la vinculación |
| `/town [nombre]` | Ficha de la town: alcalde, residentes, fundación, parcelas, banco, nación, estado de ruina. Sin argumento, la town del autor |
| `/residents [town]` | Lista de residentes con su estado, paginada |
| `/res [jugador]` | Ficha de residente: town, cargo, conexión, saldo si hay economía |
| `/townlist [página]` | Listado de towns ordenado, paginado |
| `/mytown` | Atajo a la ficha de la town propia |
| `/help` | Lista los comandos de Discord disponibles, con una línea cada uno |

### 5.1 Reglas

- Los datos se leen en vivo de Towny en el momento de responder.
- Un comando sobre una town inexistente responde con un error claro, no con un
  embed vacío.
- Los comandos de información no exigen vinculación, salvo los que dependen de
  saber quién eres (`/mytown`, y `/town` y `/res` sin argumento).
- Todo comando respeta un cooldown por usuario configurable.

### 5.2 Vinculación obligatoria para acceder

Ver o escribir en el canal de una town exige tener la cuenta vinculada. Un
residente no vinculado sigue siendo residente en el juego, pero no recibe rol ni
ve el canal hasta que vincule. El mensaje de rechazo explica cómo hacerlo.

## 6. Sincronización

### 6.1 Por eventos

El plugin reacciona a los eventos de Towny y actualiza Discord de inmediato:

| Evento | Efecto |
|---|---|
| Residente se une a una town | Recibe el rol de la town |
| Residente sale o es expulsado | Pierde el rol de la town |
| Cambio de alcalde | El rol de alcalde pasa de uno a otro |
| Town renombrada | Se renombran canales y rol |
| Town eliminada o en ruinas | Arranca la política de borrado |
| Jugador entra al servidor | Se reconcilian sus roles |

### 6.2 Periódica

Un job configurable recorre el estado y corrige diferencias: roles sobrantes,
roles faltantes, canales huérfanos y towns sin espacio pese a tenerlo
registrado. Puede operar en modo reparación o en modo aviso.

### 6.3 Bajo demanda

`/dt sync` para la propia town, `/dt admin sync` para todo.

### 6.4 Regla de oro

Ante una discrepancia, Towny gana. Si alguien recibió a mano el rol de una town
a la que no pertenece, la sincronización se lo quita.

## 7. Ciclo de vida del espacio de una town

1. **Creación** — a petición del alcalde, si cumple las condiciones.
2. **Activo** — se sincroniza por eventos y periódicamente.
3. **Archivado** — cuando la town desaparece, cae en ruinas o el alcalde ejecuta
   `/dt delete`. El canal se mueve a una categoría de archivo configurable, se
   elimina el rol de la town y el canal queda **visible solo para
   administradores**, en solo lectura. Nada se borra automáticamente.

   Los ex-residentes dejan de ver el canal: el rol desaparece, y es ese rol el
   que daba acceso. Se elige así porque los roles son el recurso escaso (250 por
   servidor) y conservarlos por cada town muerta agotaría el cupo. El historial
   se conserva íntegro y vuelve a sus residentes si la town revive.
4. **Borrado** — nunca automático. Un administrador borra los espacios
   archivados con `/dt admin purge`, con confirmación explícita.

Si la town revive o se recrea con el mismo nombre estando archivada, el espacio
se restaura con su historial intacto.

### 7.1 Visibilidad del estado

El plugin registra, por cada espacio, su estado, cuándo se creó, cuándo se
archivó, cuántos residentes tienen rol y cuándo hubo actividad por última vez en
sus canales. Esa información se consulta con `/dt admin list` y
`/dt admin info`, y sirve para decidir qué archivar o purgar.

## 8. Datos que se guardan

| Dato | Para qué |
|---|---|
| UUID, Discord ID, fecha de vinculación | Identidad verificada |
| Town, ID de categoría, canales y rol | Saber qué gestiona el plugin |
| Estado del espacio, fechas de creación y archivado, última actividad | Ciclo de vida y consultas de administración |
| Códigos de vinculación pendientes | Verificación, con caducidad |
| Registro de acciones del bot | Diagnóstico y auditoría |

El esquema concreto va en `ARCHITECTURE.md`.

## 8.1 Canal de logs en Discord

El bot publica en un canal de logs configurable lo que hace: creaciones,
archivados, borrados, cambios de rol, vinculaciones, inconsistencias detectadas
y errores. El nivel de detalle es configurable.

Para que esto no afecte al servidor, los mensajes se encolan y se envían
agrupados desde fuera del hilo principal, con un intervalo configurable. Si el
canal no existe o el bot no puede escribir en él, el plugin sigue funcionando y
registra el problema una sola vez en consola.

## 9. Configuración

Según la tabla acordada: Discord, base de datos, estructura, roles, límites,
ciclo de vida, sincronización, vinculación, comandos y textos. Los textos viven
en un archivo aparte del `config.yml`.

No son configurables: el modelo de permisos de los canales, el esquema de la
base de datos y el uso de un único rol por town.

## 10. Errores y fallos

- Si el bot no puede conectarse, el servidor de Minecraft funciona con
  normalidad y los comandos del plugin responden que Discord no está disponible.
- Si una operación falla a medias, el estado queda marcado como inconsistente y
  la siguiente reconciliación lo repara. Nunca se deja un canal accesible a
  quien no debería verlo.
- Los errores se registran con contexto: town, acción y causa.
- Ni el token ni las credenciales aparecen jamás en logs ni en mensajes a
  usuarios.

## 10.1 Actualizaciones

### Comprobación

El plugin consulta periódicamente los releases publicados en el repositorio
oficial de GitHub y compara con su propia versión. El intervalo es configurable
y la comprobación se puede desactivar por completo.

Cuando hay una versión nueva, se avisa:

- en la consola al arrancar,
- a los administradores al entrar al servidor,
- en el canal de logs de Discord, una sola vez por versión.

El aviso incluye la versión disponible, la actual y un resumen de los cambios.

### Descarga

La descarga es **automática**: al detectar una versión nueva, el plugin la baja
sin esperar a nadie. `/dt admin update` fuerza la comprobación y descarga en el
momento. El comportamiento automático se puede desactivar por configuración.

La descarga:

- viene solo del repositorio oficial, por HTTPS,
- se verifica contra el checksum publicado en el release, y se descarta si no
  coincide,
- se deposita en la carpeta `update` del servidor, sin tocar el jar en uso.

### Aplicación

La nueva versión entra **al reiniciar el servidor**, mediante el mecanismo
estándar de Paper. El plugin nunca se reemplaza en caliente: hacerlo con
conexiones abiertas a Discord y a la base de datos deja estado corrupto.

`/dt admin update status` indica si hay una actualización descargada y pendiente
de reinicio.

Descargada la actualización, se avisa de nuevo indicando que basta con reiniciar
para aplicarla.

### Reglas

- Si el release trae cambios que rompen la configuración o requieren migración,
  se indica en el aviso y se exige confirmación adicional.
- Un fallo de red o un checksum incorrecto no dejan nada a medias: se descarta
  la descarga y se informa.
- Sin conexión a GitHub, el plugin funciona con normalidad y anota el fallo una
  sola vez.

## 11. Fuera de esta versión

Chat bridge, naciones, multi-servidor, panel web, integraciones con economía o
plugins de guerra, y más de un guild.

## 12. Criterios de aceptación

1. Un jugador vincula su cuenta y el vínculo sobrevive a un reinicio.
2. Un alcalde ejecuta `/dt create` y aparecen categoría, canales y rol con los
   permisos correctos.
3. Un residente vinculado ve el canal de su town; un jugador de otra town no lo
   ve ni aparece en la lista de miembros del canal.
4. Expulsar a un residente le retira el rol sin que nadie ejecute nada.
5. Cambiar de alcalde mueve el rol de alcalde.
6. Borrar un canal a mano y lanzar `/dt admin sync` deja el estado coherente.
7. Con el bot apagado, el servidor arranca y opera sin errores en cascada.
8. Un usuario al que un admin le da a mano el rol de otra town lo pierde en la
   siguiente reconciliación.
9. Un residente sin vincular no ve el canal de su town; al vincular, lo ve sin
   ejecutar nada más.
10. Una town eliminada deja su canal archivado y legible para administradores,
    nunca borrado sin que un administrador lo ordene.
11. Con el canal de logs activo y muchas operaciones seguidas, el servidor no
    pierde ticks.

## 13. Documentación pública

El repositorio incluye documentación escrita para jugadores y administradores de
servidor, no para desarrolladores:

- `README.md`: qué es el plugin, qué resuelve, requisitos, instalación, y la
  lista de comandos.
- `docs/guia-de-uso.md`: recorrido en lenguaje llano — cómo vincular la cuenta,
  cómo crear el espacio de la town, qué ve cada quién y qué pasa cuando alguien
  entra, sale o la town desaparece. Sin detalles de código ni de arquitectura.

Ambos se mantienen al día como parte del trabajo, no al final.

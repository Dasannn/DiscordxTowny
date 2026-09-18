# Guía de uso de DiscordTowny

Imagina que juegas como `AnaCraft` y perteneces a `Robledal`. El dueño del servidor ya ha configurado el bot siguiendo el [README](../README.md).

## 1. Vincula tu cuenta

El bot necesita saber qué cuenta de Discord es tuya. Tener el mismo nombre en Minecraft y Discord no sustituye la vinculación. Sin ella, no puede darte el rol ni acceso a los canales de tu town.

1. En Minecraft, ejecuta `/dt link`.
2. Recibirás un código de seis caracteres. Por ejemplo, `A7K9MX`.
3. En el servidor de Discord, selecciona `/link` e introduce tu código. El ejemplo sería `/link A7K9MX`; usa el código que recibiste.
4. El bot confirma la vinculación con una respuesta que solo tú ves. Si tu town ya tiene espacio, recibes el rol que te corresponde y puedes ver sus canales.

El código dura 10 minutos por defecto y solo sirve una vez. Si ejecutas `/dt link` otra vez, el código anterior deja de valer. La vinculación se conserva al reiniciar Minecraft.

Cada cuenta de Minecraft se vincula con una sola cuenta de Discord, y viceversa. Para cambiarla, usa `/dt unlink` en Minecraft o `/unlink` en Discord y vuelve a vincularte. Desvincular retira todos los roles que te haya dado el plugin.

Puedes revisar tu cuenta y el estado del espacio con `/dt status`.

## 2. Crea el espacio de tu town

Si eres la alcaldesa de `Robledal`, ejecuta `/dt create` en Minecraft. Solo puede hacerlo quien figure como alcalde en Towny y tenga su cuenta vinculada.

Con la configuración inicial, aparecen un canal de texto y otro de voz con el nombre de la town, dentro de `Comunidades`. El bot crea esa categoría cuando se necesita por primera vez y añade categorías numeradas (`Comunidades 2`, `Comunidades 3`...) conforme se llena el límite de 50 canales por categoría. El dueño del servidor puede configurar solo texto, solo voz o ambos.

El bot crea un rol para `Robledal` y lo asigna a sus residentes vinculados. Tú también recibes el rol distintivo `Alcalde`, compartido por todos los alcaldes. Quien se vincule después recibe su rol en ese momento.

Para crear el espacio:

- Debes ser alcalde y tener la cuenta vinculada.
- La town no debe tener ya un espacio.
- Debe alcanzar el mínimo de residentes: 2 por defecto.
- Debe quedar cupo: el límite del plugin es 200 towns por defecto, sujeto a los límites de Discord.
- Debe haber terminado la espera entre creaciones: 60 segundos por defecto.
- El bot debe estar conectado y tener los permisos necesarios.

Si algo falla, el comando explica el motivo.

## 3. Quién puede ver los canales

El acceso que da el plugin es solo para residentes vinculados de esa town. Pueden ver y escribir en su canal de texto, y conectarse y hablar en el de voz. Un residente sin vincular sigue en la town dentro del juego, pero no recibe el rol de Discord.

No hay un botón para unirse por cuenta propia ni para asignarse el rol. El bot sigue la lista de residentes de Towny. Si alguien recibe a mano el rol de una town ajena, la siguiente sincronización se lo retira.

El rol `Alcalde` es una distinción. No da acceso a los canales de otras towns.

## 4. Cuando cambia la gente de la town

No hace falta ejecutar comandos para estos cambios:

| Qué ocurre en Minecraft | Qué ocurre en Discord |
|---|---|
| Entras en una town con espacio | Si estás vinculado, recibes su rol y acceso a sus canales. |
| Sales o te expulsan | Pierdes el rol de esa town y el acceso que daba. |
| Cambia el alcalde | El anterior pierde la distinción y el nuevo la recibe si está vinculado. |
| Te vinculas después de entrar | Recibes los roles que te corresponden en ese momento. |

El plugin también revisa tus roles al entrar al servidor de Minecraft. Por defecto, revisa los espacios cada 30 minutos para corregir diferencias. El administrador puede cambiar ese intervalo o configurar que solo se avisen los problemas.

Si el alcalde necesita forzar la revisión de su town, puede usar `/dt sync`.

## 5. Si la town cambia de nombre o desaparece

Si `Robledal` cambia de nombre, se renombran sus canales y su rol. Un cambio de nombre no archiva el espacio.

Si la town cae en ruinas o desaparece, su espacio se archiva. El alcalde también puede archivar el espacio con `/dt delete`, con confirmación.

Al archivarse:
- Los canales se trasladan a `Archivo` por defecto y quedan en solo lectura.
- Quedan visibles **solo para administradores**.
- El rol de la town se elimina (los roles son limitados en Discord), por lo que los ex-residentes dejan de ver los canales.
- **Los canales no se borran automáticamente.** El historial de mensajes se conserva íntegro.

Si la town revive o se recrea con el mismo nombre mientras el espacio sigue archivado, el espacio se restaura con su historial y los residentes recuperan el acceso a sus canales.

Un administrador puede borrar definitivamente los espacios archivados con `/dt admin purge`, con confirmación. La restauración con historial solo es posible mientras el espacio siga archivado, no después de purgarlo.

## 6. Consulta información desde Discord

Escribe `/`, elige el comando del bot y completa el campo con el valor del ejemplo.

| Ejemplo | Qué puedes consultar |
|---|---|
| `/town Robledal` | Alcalde, residentes, fecha de fundación, parcelas, banco, nación y si está en ruinas. |
| `/town` | La misma ficha, pero de tu town. Necesitas estar vinculado. |
| `/mytown` | Un atajo para consultar tu town. Necesitas estar vinculado. |
| `/residents Robledal` | La lista de residentes con su estado, dividida en páginas. |
| `/res AnaCraft` | Town, cargo y conexión de ese jugador; también saldo si hay economía. |
| `/res` | Tu ficha de residente. Necesitas estar vinculado. |
| `/townlist` o `/townlist 2` | Listado ordenado de towns; el segundo ejemplo pide la página 2. |
| `/help` | Los comandos disponibles, con una explicación de cada uno. |

Las consultas con nombre y los listados no exigen vincularse. En `/residents Robledal`, indica la town como en el ejemplo para consultar su lista de residentes.

Los datos se consultan en Towny al responder. Si buscas una town inexistente, recibes un error claro.

Por defecto, las respuestas de información son públicas, salvo `/mytown` y `/help`, que solo ves tú. El administrador puede cambiarlo o desactivar comandos. La espera entre comandos es de 5 segundos por usuario por defecto.

## 7. Problemas frecuentes

### No veo el canal de mi town

Ejecuta `/dt status`. ¿Vinculaste la cuenta de Discord que estás usando? Si no, sigue el primer paso de esta guía. Comprueba también que sigues siendo residente y que tu town tiene espacio activo. Si todo coincide, pide al alcalde que use `/dt sync`.

### No puedo crear el espacio

¿Eres el alcalde en Towny? ¿Estás vinculado? ¿La town tiene al menos 2 residentes con la configuración inicial? Revisa el mensaje del comando: también puede faltar cupo, seguir activa la espera entre creaciones, existir ya un espacio o faltar conexión o permisos del bot.

### El bot no asigna roles

Pide al dueño del servidor que abra **Ajustes del servidor → Roles**. El rol del bot debe estar **por encima de los roles de todas las towns y de `Alcalde`**. Tener «Gestionar roles» no basta si queda por debajo. El plugin detecta el problema al arrancar y avisa en consola.

### El código no funciona

Puede haber caducado, haberse usado o haber sido sustituido por otro. Genera uno nuevo con `/dt link` y úsalo en Discord. Si la cuenta ya está vinculada, sigue el mensaje para desvincularla antes. Los intentos fallidos están limitados.

### Discord no está disponible

El servidor de Minecraft puede seguir funcionando. Los comandos del plugin indican que Discord no está disponible. El administrador debe revisar la conexión del bot y los errores de consola.

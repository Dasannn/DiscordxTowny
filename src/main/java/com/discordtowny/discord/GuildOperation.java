package com.discordtowny.discord;

import com.discordtowny.model.SpaceRequest;
import java.util.List;
import java.util.UUID;

/**
 * Mutacion del guild, descrita como dato.
 *
 * <p>Cada implementacion debe ser <b>idempotente</b>: antes de crear algo se
 * comprueba si ya existe por su identificador guardado. Reintentar una
 * operacion a medias no puede duplicar canales ni roles.
 *
 * <p>Sellada a proposito: la cola sabe tratar exactamente estos casos, y anadir
 * uno nuevo obliga a decidir que hace con el.
 */
public sealed interface GuildOperation {

    /** Para el registro y los mensajes de error. */
    String describe();

    /** Crea lo que falte del espacio: categoria, rol, canales, permisos, roles. */
    record CreateSpace(SpaceRequest request) implements GuildOperation {
        @Override
        public String describe() {
            return "crear espacio de " + request.townName();
        }
    }

    /** Renombra canales y rol tras un renombrado de town. */
    record RenameSpace(UUID townUuid, String oldName, String newName) implements GuildOperation {
        @Override
        public String describe() {
            return "renombrar " + oldName + " a " + newName;
        }
    }

    /** Canales a solo lectura, movidos al archivo, rol eliminado. */
    record ArchiveSpace(UUID townUuid, String townName) implements GuildOperation {
        @Override
        public String describe() {
            return "archivar espacio de " + townName;
        }
    }

    /** Devuelve al activo un espacio archivado, con su historial. */
    record RestoreSpace(SpaceRequest request) implements GuildOperation {
        @Override
        public String describe() {
            return "restaurar espacio de " + request.townName();
        }
    }

    /** Borrado definitivo. Solo lo ordena un administrador. */
    record DeleteSpace(UUID townUuid, String townName) implements GuildOperation {
        @Override
        public String describe() {
            return "borrar definitivamente el espacio de " + townName;
        }
    }

    /**
     * Ajusta los roles de un miembro a los que le corresponden.
     *
     * <p>Solo se tocan roles gestionados por el plugin: los demas roles del
     * usuario no se miran ni se modifican.
     */
    record ApplyMemberRoles(String discordId, List<String> grantRoleIds, List<String> revokeRoleIds)
            implements GuildOperation {
        @Override
        public String describe() {
            return "ajustar roles de " + discordId;
        }
    }
}

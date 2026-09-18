package com.discordtowny.update;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Comprobacion y descarga de nuevas versiones publicadas en GitHub.
 *
 * <p>Independiente del resto del plugin: no lee ni escribe su estado y puede
 * fallar entero sin afectar a nada.
 *
 * <p>El origen de descarga es <b>constante en el codigo</b>, nunca
 * configurable: un origen editable convertiria el config.yml en ejecucion de
 * codigo arbitrario.
 *
 * <p>La nueva version se deja en la carpeta {@code update} del servidor y entra
 * al reiniciar. El jar en uso no se toca nunca: recargar un plugin con
 * conexiones vivas a Discord y a la base de datos corrompe estado.
 */
public interface UpdateService {

    CompletableFuture<Optional<Release>> checkForUpdate();

    /**
     * Descarga la version, verifica su SHA-256 contra el checksum publicado y
     * solo entonces la deja en la carpeta {@code update}.
     *
     * <p>Un checksum que no coincide descarta la descarga sin dejar restos.
     */
    CompletableFuture<DownloadResult> download(Release release);

    /** Cierto si ya hay una version descargada esperando al reinicio. */
    boolean isUpdatePending();

    String currentVersion();

    record Release(String version, String downloadUrl, String sha256, String notes) {}

    enum DownloadResult {
        SUCCESS,
        CHECKSUM_MISMATCH,
        NETWORK_ERROR,
        TOO_LARGE,
        IO_ERROR
    }
}

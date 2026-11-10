package com.davidadrianrg.atril.core.download

import com.davidadrianrg.atril.core.sync.SaveNames
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Extrae un zip en streaming dentro de una carpeta de plataforma.
 *
 * Es la única puerta de escritura de los dos motores de descarga: toda entrada
 * se resuelve con [SaveNames.resolveArchiveEntry] y las rechazadas se saltan
 * sin abortar el archivo. Un fallo o una cancelación deja la carpeta sin los
 * ficheros que este método creó.
 */
object ZipStreamExtractor {

    /**
     * Extrae [input] (los bytes de un zip, con longitud conocida o chunked)
     * dentro de [targetDir], creándola si no existe.
     *
     * Cada entrada se resuelve con [SaveNames.resolveArchiveEntry]; las que no
     * quedan estrictamente dentro de [targetDir] se saltan con `closeEntry`, de
     * modo que un miembro hostil nunca aborta el resto del archivo. Los
     * directorios se crean y no se devuelven; los ficheros se devuelven en el
     * orden en que aparecen en el zip.
     *
     * Si la copia falla a mitad, el fichero en curso ya está registrado y el
     * bloque de error borra todos los creados antes de relanzar la misma
     * excepción (incluida `CancellationException`, que nunca se envuelve).
     *
     * @param input flujo de bytes del zip. No se registra ningún nombre de
     *   entrada, porque puede ser dato del servidor.
     * @param targetDir carpeta de plataforma que recibe los ficheros.
     * @return los ficheros escritos, en orden de entrada.
     */
    fun extract(input: InputStream, targetDir: File): List<File> {
        targetDir.mkdirs()
        val createdFiles = mutableListOf<File>()
        try {
            ZipInputStream(input).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    val outFile = SaveNames.resolveArchiveEntry(targetDir, entry.name)
                    if (outFile == null) {
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                        continue
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        // Se registra ANTES de copiar: si la escritura falla a
                        // medias, el fichero parcial también entra en la limpieza.
                        createdFiles.add(outFile)
                        FileOutputStream(outFile).use { out -> zipIn.copyTo(out) }
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
        } catch (e: Exception) {
            createdFiles.forEach { if (it.exists()) it.delete() }
            throw e
        }
        return createdFiles
    }
}

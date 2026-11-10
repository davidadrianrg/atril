package com.davidadrianrg.atril.core.sync

import com.davidadrianrg.atril.core.util.RootShell
import java.io.File
import java.io.IOException

/**
 * Regla única de nombre de fichero derivado del sistema de ficheros que se
 * interpola en un comando de root.
 *
 * Los handlers de Android y PS2 obtienen nombres (paquete de la app, memory
 * card, carpeta del serial) del sistema de ficheros y los incrustan en un
 * comando ejecutado con `su`. El nombre es, por tanto, un token más: además de
 * que [RootShell.sq] escape el valor, se aplica esta regla antes de construir
 * el comando y se exige que el destino quede canónicamente bajo una base de
 * confianza. La regla no se aplica a nombres que vienen dentro de un archivo
 * (ahí la puerta es [SaveNames.isSafeSaveFileName]).
 *
 * El primer carácter debe ser alfanumérico: neutraliza metacaracteres de shell
 * (la barrera primaria sigue siendo [RootShell.sq]) e impide que el miembro del
 * tar empiece por `-`, que `tar` interpretaría como una opción.
 */
object RootCommandNames {

    /**
     * Longitud máxima aceptada, en caracteres ASCII.
     *
     * `NAME_MAX` en Linux/Android (ext4, f2fs) es 255 bytes por componente. El
     * temporal de Android añade el prefijo `android_root_` y el sufijo `.tar`
     * alrededor del nombre, de modo que 200 deja el componente resultante por
     * debajo del límite y cubre cualquier `applicationId` real y cualquier
     * card o carpeta de serial de PS2.
     */
    const val MAX_LENGTH: Int = 200

    /** Nombres aceptados: primer carácter alfanumérico y hasta [MAX_LENGTH]. */
    val RULE: Regex = Regex("^[A-Za-z0-9][A-Za-z0-9._ -]{0,199}$")

    /**
     * Indica si [name] es apto para interpolarse como un único token entre
     * comillas simples en un comando de root.
     *
     * Total: `null`, la cadena vacía, más de [MAX_LENGTH] caracteres o
     * cualquier carácter fuera del charset devuelve `false`.
     *
     * @param name nombre derivado del sistema de ficheros.
     * @return `true` solo si cumple [RULE].
     */
    fun isValid(name: String?): Boolean = name != null && RULE.matches(name)

    /**
     * Resuelve [name] bajo [base] aplicando la regla estricta y la contención
     * canónica antes de que el nombre llegue a un comando privilegiado.
     *
     * @param base base de confianza (p. ej. el padre de la carpeta de saves).
     * @param name nombre simple derivado del sistema de ficheros.
     * @return el hijo canónico, o `null` si el nombre no es válido, si escapa
     *   de [base] o si coincide con ella.
     */
    fun resolveUnder(base: File, name: String): File? {
        if (!isValid(name)) return null
        return SaveNames.resolveInsideDirectory(base, name)
    }

    /**
     * Construye el comando de empaquetado tar solo cuando cada nombre
     * interpolado es seguro y [member] queda estrictamente bajo [relativeTo].
     *
     * Acepta tanto el caso Android de un componente (carpeta de la app) como el
     * caso PS2 de dos (`card/serial`). Es puro: no lanza ni arranca procesos;
     * el handler solo llama a [RootShell.runToFile] cuando el resultado no es
     * `null`.
     *
     * @param archive fichero tar de salida.
     * @param relativeTo directorio de confianza usado con `-C`.
     * @param member miembro a archivar (directorio o fichero ya resuelto).
     * @return el comando con [RootShell.sq], o `null` si algo no es seguro.
     */
    fun tarCreateCommand(archive: File, relativeTo: File, member: File): String? {
        val base = canonicalOrNull(relativeTo) ?: return null
        val target = canonicalOrNull(member) ?: return null
        val basePrefix = base.path + File.separator
        if (target.path == base.path || !target.path.startsWith(basePrefix)) return null

        val relative = target.path.substring(basePrefix.length)
        if (relative.isEmpty()) return null
        if (relative.split(File.separatorChar).any { !isValid(it) }) return null

        return "tar -cf ${RootShell.sq(archive.absolutePath)} " +
            "-C ${RootShell.sq(base.path)} ${RootShell.sq(relative)}"
    }

    private fun canonicalOrNull(file: File): File? = try {
        file.canonicalFile
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}

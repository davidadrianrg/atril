package com.davidadrianrg.atril.core.sync.platform

import com.davidadrianrg.atril.core.sync.SaveNames
import com.davidadrianrg.atril.core.util.SafeDelete
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Root-free tar validation and extraction into an app-owned staging directory.
 *
 * Handlers that restore a tar created for a privileged path first stage the
 * archive as the app user: this parser validates every 512-byte ustar header
 * and writes only regular files and directories below the staging folder.
 * Root is only used afterwards, on the already validated tree.
 *
 * Rejected archives return `false` before any privileged operation: absolute
 * or traversal names, NUL and backslash characters, link/device/pax/long-name
 * entries, bad checksums, base-256 sizes, truncated members and archives whose
 * members do not share one safe top-level directory.
 */
internal object TarExtractor {

    /** Size of a ustar header block and of the data padding unit. */
    const val BLOCK_SIZE = 512

    private const val NAME_OFFSET = 0
    private const val NAME_LENGTH = 100
    private const val SIZE_OFFSET = 124
    private const val SIZE_LENGTH = 12
    private const val CHECKSUM_OFFSET = 148
    private const val CHECKSUM_LENGTH = 8
    private const val TYPE_OFFSET = 156
    private const val PREFIX_OFFSET = 345
    private const val PREFIX_LENGTH = 155

    private const val CHECKSUM_SPACE = 0x20
    private const val TYPE_REGULAR = '0'.code
    private const val TYPE_REGULAR_CONTIGUOUS = '7'.code
    private const val TYPE_DIRECTORY = '5'.code

    /**
     * Validates [tar] without writing anything.
     *
     * @param tar archive to parse.
     * @return `true` only when every header and every member passes the rules.
     */
    fun validate(tar: File): Boolean = try {
        tar.inputStream().buffered().use { parse(it, null) }
    } catch (_: Exception) {
        false
    }

    /**
     * Validates and extracts [tar] into [stagingDir] as the current user.
     *
     * [stagingDir] must exist and be empty. On any rejection or write failure
     * its contents are deleted and `false` is returned; on success it contains
     * exactly one top-level directory and `true` is returned.
     *
     * @param tar archive to extract.
     * @param stagingDir app-owned empty directory that receives the tree.
     * @return `true` only when the whole archive was validated and extracted.
     */
    fun extract(tar: File, stagingDir: File): Boolean {
        if (!stagingDir.isDirectory) return false
        if (stagingDir.list()?.isNotEmpty() == true) {
            clearStaging(stagingDir)
            return false
        }
        val ok = try {
            tar.inputStream().buffered().use { parse(it, stagingDir) }
        } catch (_: Exception) {
            false
        }
        val singleTopDir = ok && stagingDir.listFiles()?.let { it.size == 1 && it[0].isDirectory } == true
        if (!singleTopDir) clearStaging(stagingDir)
        return singleTopDir
    }

    /** Describes one validated member before its data is consumed. */
    private class Member(val path: String, val size: Long, val isDirectory: Boolean)

    private fun parse(input: InputStream, stagingDir: File?): Boolean {
        val block = ByteArray(BLOCK_SIZE)
        var topComponent: String? = null
        while (true) {
            val read = readFully(input, block)
            if (read == 0) break
            if (read < BLOCK_SIZE) return false
            // Two zero blocks mark the end; a single one is tolerated.
            if (block.all { it == 0.toByte() }) break
            val member = parseHeader(block) ?: return false
            val top = member.path.substringBefore('/')
            val expectedTop = topComponent
            if (expectedTop == null) {
                if (!SaveNames.isSafeSaveFileName(top)) return false
                topComponent = top
            } else if (top != expectedTop) {
                return false
            }
            if (!consumeMember(input, member, stagingDir)) return false
        }
        return topComponent != null
    }

    private fun parseHeader(block: ByteArray): Member? {
        val name = readTextField(block, NAME_OFFSET, NAME_LENGTH) ?: return null
        val prefix = readTextField(block, PREFIX_OFFSET, PREFIX_LENGTH) ?: return null
        val path = if (prefix.isEmpty()) name else "$prefix/$name"
        if (path.isEmpty()) return null
        if (!SaveNames.isSafeArchiveEntryPath(path)) return null

        val storedChecksum = parseOctal(block, CHECKSUM_OFFSET, CHECKSUM_LENGTH) ?: return null
        if (storedChecksum != checksum(block, signed = false) &&
            storedChecksum != checksum(block, signed = true)
        ) {
            return null
        }

        val size = parseOctal(block, SIZE_OFFSET, SIZE_LENGTH) ?: return null
        val isDirectory = when (block[TYPE_OFFSET].toInt() and 0xFF) {
            0, TYPE_REGULAR, TYPE_REGULAR_CONTIGUOUS -> false
            TYPE_DIRECTORY -> true
            else -> return null
        }
        return Member(path, size, isDirectory)
    }

    private fun consumeMember(input: InputStream, member: Member, stagingDir: File?): Boolean {
        val target = if (stagingDir == null) {
            null
        } else {
            SaveNames.resolveArchiveEntry(stagingDir, member.path) ?: return false
        }

        if (member.isDirectory) {
            if (target != null && !target.isDirectory && !target.mkdirs() && !target.isDirectory) {
                return false
            }
            return skipBytes(input, member.size) && skipBytes(input, padding(member.size))
        }

        var output: OutputStream? = null
        try {
            if (target != null) {
                target.parentFile?.mkdirs()
                output = FileOutputStream(target)
            }
            if (!copyBytes(input, member.size, output)) return false
            output?.flush()
        } catch (_: Exception) {
            return false
        } finally {
            try {
                output?.close()
            } catch (_: Exception) {
                // Nothing else to do: the staging tree is discarded on failure.
            }
        }
        return skipBytes(input, padding(member.size))
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            offset += read
        }
        return offset
    }

    private fun copyBytes(input: InputStream, size: Long, output: OutputStream?): Boolean {
        val buffer = ByteArray(8192)
        var remaining = size
        while (remaining > 0) {
            val chunk = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (chunk < 0) return false
            output?.write(buffer, 0, chunk)
            remaining -= chunk
        }
        return true
    }

    private fun skipBytes(input: InputStream, size: Long): Boolean {
        val buffer = ByteArray(8192)
        var remaining = size
        while (remaining > 0) {
            val chunk = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (chunk < 0) return false
            remaining -= chunk
        }
        return true
    }

    private fun padding(size: Long): Long = (BLOCK_SIZE - size % BLOCK_SIZE) % BLOCK_SIZE

    /**
     * Reads a NUL-padded header field, rejecting fields with data after the
     * terminator (an embedded NUL in the member name).
     */
    private fun readTextField(block: ByteArray, offset: Int, length: Int): String? {
        val fieldEnd = offset + length
        var terminator = fieldEnd
        for (i in offset until fieldEnd) {
            if (block[i] == 0.toByte()) {
                terminator = i
                break
            }
        }
        for (i in terminator until fieldEnd) {
            if (block[i] != 0.toByte()) return null
        }
        return String(block, offset, terminator - offset, Charsets.UTF_8)
    }

    /**
     * Parses an octal header field. A set high bit (GNU base-256) is rejected.
     */
    private fun parseOctal(block: ByteArray, offset: Int, length: Int): Long? {
        if (block[offset].toInt() and 0x80 != 0) return null
        var start = offset
        var end = offset + length
        while (start < end && (block[start].toInt() == CHECKSUM_SPACE || block[start] == 0.toByte())) start++
        while (end > start && (block[end - 1].toInt() == CHECKSUM_SPACE || block[end - 1] == 0.toByte())) end--
        if (start == end) return null
        var value = 0L
        for (i in start until end) {
            val digit = block[i].toInt() - '0'.code
            if (digit < 0 || digit > 7) return null
            if (value > (Long.MAX_VALUE - digit) / 8) return null
            value = value * 8 + digit
        }
        return value
    }

    private fun checksum(block: ByteArray, signed: Boolean): Long {
        var sum = 0L
        val checksumEnd = CHECKSUM_OFFSET + CHECKSUM_LENGTH
        for (i in block.indices) {
            sum += when {
                i >= CHECKSUM_OFFSET && i < checksumEnd -> CHECKSUM_SPACE
                signed -> block[i].toInt()
                else -> block[i].toInt() and 0xFF
            }
        }
        return sum
    }

    private fun clearStaging(stagingDir: File) {
        // El staging es app-owned: base y destino coinciden y se vacía con la
        // misma regla que el resto de restauraciones.
        SafeDelete.clearDirectoryContents(stagingDir, stagingDir)
    }
}

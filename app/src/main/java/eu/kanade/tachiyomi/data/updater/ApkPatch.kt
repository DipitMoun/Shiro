package eu.kanade.tachiyomi.data.updater

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Applies an update patch made by `tools/release/apk-patch.js` (format "SHPT" version 1).
 *
 * The new APK is rebuilt byte for byte from the installed one plus the downloaded changes, so its
 * signature stays valid. Both the base file and the result are checked against the hashes stored
 * in the patch: a wrong or damaged patch never produces a file.
 */
object ApkPatch {

    class PatchException(message: String) : IOException(message)

    private val MAGIC = byteArrayOf(0x53, 0x48, 0x50, 0x54, 0x01, 0x0a)
    private const val OP_END = 0
    private const val OP_COPY = 1
    private const val OP_INSERT = 2
    private const val BUFFER_SIZE = 64 * 1024
    private const val HASH_SIZE = 32

    /** What a patch says about the file it starts from and the file it produces. */
    class Header(
        val baseSize: Long,
        val baseSha256: ByteArray,
        val newSize: Long,
        val newSha256: ByteArray,
    ) {
        val newSha256Hex: String get() = newSha256.toHex()
    }

    fun sha256Hex(file: File): String = sha256(file).toHex()

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    /**
     * Rebuilds the new file at [output] from [oldFile] and [patch].
     *
     * @param onProgress bytes of the new file written so far, and its total size
     * @throws PatchException when the patch is not for [oldFile] or the result does not match
     */
    fun apply(
        oldFile: File,
        patch: InputStream,
        output: File,
        onProgress: ((written: Long, total: Long) -> Unit)? = null,
    ) {
        val partial = File(output.path + ".part")
        try {
            val input = DataInputStream(BufferedInputStream(patch, BUFFER_SIZE))
            val header = readHeader(input)

            if (oldFile.length() != header.baseSize || !MessageDigest.isEqual(sha256(oldFile), header.baseSha256)) {
                throw PatchException("patch is for another base file")
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            RandomAccessFile(oldFile, "r").use { old ->
                partial.outputStream().buffered(BUFFER_SIZE).use { out ->
                    val buffer = ByteArray(BUFFER_SIZE)

                    fun emit(bytes: ByteArray, count: Int) {
                        out.write(bytes, 0, count)
                        digest.update(bytes, 0, count)
                        written += count
                    }

                    while (true) {
                        when (val op = input.read()) {
                            -1 -> throw PatchException("patch is truncated")
                            OP_END -> break
                            OP_COPY -> {
                                val offset = readVarint(input)
                                var remaining = readVarint(input)
                                if (offset < 0 || remaining < 0 || offset + remaining > header.baseSize) {
                                    throw PatchException("copy out of range")
                                }
                                if (written + remaining > header.newSize) throw PatchException("patch overruns the new file")
                                old.seek(offset)
                                while (remaining > 0) {
                                    val chunk = minOf(remaining, BUFFER_SIZE.toLong()).toInt()
                                    old.readFully(buffer, 0, chunk)
                                    emit(buffer, chunk)
                                    remaining -= chunk
                                }
                            }
                            OP_INSERT -> {
                                var remaining = readVarint(input)
                                if (remaining < 0 || written + remaining > header.newSize) {
                                    throw PatchException("insert out of range")
                                }
                                while (remaining > 0) {
                                    val chunk = minOf(remaining, BUFFER_SIZE.toLong()).toInt()
                                    input.readFully(buffer, 0, chunk)
                                    emit(buffer, chunk)
                                    remaining -= chunk
                                }
                            }
                            else -> throw PatchException("unknown operation $op")
                        }
                        onProgress?.invoke(written, header.newSize)
                    }
                }
            }

            if (written != header.newSize || !MessageDigest.isEqual(digest.digest(), header.newSha256)) {
                throw PatchException("rebuilt file does not match the patch")
            }
            if (output.exists() && !output.delete()) throw IOException("cannot replace ${output.name}")
            if (!partial.renameTo(output)) throw IOException("cannot move the rebuilt file")
        } catch (e: Throwable) {
            partial.delete()
            if (e is EOFException) throw PatchException("patch is truncated")
            throw e
        }
    }

    fun readHeader(input: DataInputStream): Header {
        val magic = ByteArray(MAGIC.size)
        input.readFully(magic)
        if (!magic.contentEquals(MAGIC)) throw PatchException("not a Shiro patch")
        val baseSize = input.readLong()
        val baseSha = ByteArray(HASH_SIZE).also(input::readFully)
        val newSize = input.readLong()
        val newSha = ByteArray(HASH_SIZE).also(input::readFully)
        return Header(baseSize, baseSha, newSize, newSha)
    }

    private fun readVarint(input: DataInputStream): Long {
        var result = 0L
        var shift = 0
        while (shift < 63) {
            val byte = input.read()
            if (byte < 0) throw PatchException("patch is truncated")
            result = result or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
        }
        throw PatchException("number too large")
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

package eu.kanade.tachiyomi.data.updater

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

class ApkPatchTest {

    @TempDir
    lateinit var dir: File

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun bytes(size: Int, seed: Int): ByteArray {
        var x = seed
        return ByteArray(size) {
            x = x * 1664525 + 1013904223
            (x ushr 24).toByte()
        }
    }

    private fun varint(out: DataOutputStream, value: Long) {
        var n = value
        while (n >= 128) {
            out.write(((n % 128) or 128).toInt())
            n /= 128
        }
        out.write(n.toInt())
    }

    /** A patch written by hand: operations are `copy(offset, len)` or literal bytes. */
    private fun patch(
        old: ByteArray,
        new: ByteArray,
        newSha: ByteArray = sha(new),
        ops: DataOutputStream.() -> Unit,
    ): ByteArray {
        val raw = ByteArrayOutputStream()
        DataOutputStream(raw).apply {
            write(byteArrayOf(0x53, 0x48, 0x50, 0x54, 0x01, 0x0a))
            writeLong(old.size.toLong())
            write(sha(old))
            writeLong(new.size.toLong())
            write(newSha)
            ops()
            write(0)
        }
        return raw.toByteArray()
    }

    private fun DataOutputStream.copy(offset: Long, len: Long) {
        write(1)
        varint(this, offset)
        varint(this, len)
    }

    private fun DataOutputStream.insert(data: ByteArray) {
        write(2)
        varint(this, data.size.toLong())
        write(data)
    }

    private fun applyTo(old: ByteArray, patch: ByteArray): ByteArray {
        val oldFile = File(dir, "old.bin").apply { writeBytes(old) }
        val out = File(dir, "new.bin")
        ApkPatch.apply(oldFile, patch.inputStream(), out)
        return out.readBytes()
    }

    @Test
    fun `copies and inserts rebuild the new file`() {
        val old = bytes(10_000, 1)
        val extra = bytes(300, 2)
        val new = old.copyOfRange(0, 4_000) + extra + old.copyOfRange(6_000, 10_000)

        val result = applyTo(old, patch(old, new) {
            copy(0, 4_000)
            insert(extra)
            copy(6_000, 4_000)
        })

        assertArrayEquals(new, result)
    }

    @Test
    fun `a long copy crosses the internal buffer size`() {
        val old = bytes(300_000, 3)

        val result = applyTo(old, patch(old, old) { copy(0, 300_000) })

        assertArrayEquals(old, result)
    }

    @Test
    fun `a patch for another base file is refused`() {
        val old = bytes(1_000, 4)
        val other = bytes(1_000, 5)
        val p = patch(old, old) { copy(0, 1_000) }

        val error = assertThrows(ApkPatch.PatchException::class.java) { applyTo(other, p) }

        assertEquals("patch is for another base file", error.message)
    }

    @Test
    fun `a result that does not match its hash is refused and leaves nothing behind`() {
        val old = bytes(2_000, 6)
        val wrong = patch(old, old, newSha = sha(bytes(10, 7))) { copy(0, 2_000) }

        assertThrows(ApkPatch.PatchException::class.java) { applyTo(old, wrong) }

        assertFalse(File(dir, "new.bin").exists())
        assertFalse(File(dir, "new.bin.part").exists())
    }

    @Test
    fun `a copy outside the old file is refused`() {
        val old = bytes(1_000, 8)
        val p = patch(old, old) { copy(900, 500) }

        assertThrows(ApkPatch.PatchException::class.java) { applyTo(old, p) }
    }

    @Test
    fun `a truncated patch is refused`() {
        val old = bytes(1_000, 9)
        val p = patch(old, old) { copy(0, 1_000) }

        assertThrows(ApkPatch.PatchException::class.java) { applyTo(old, p.copyOf(p.size - 3)) }
    }

    @Test
    fun `something that is not a patch is refused`() {
        val old = bytes(100, 10)

        assertThrows(ApkPatch.PatchException::class.java) { applyTo(old, bytes(200, 11)) }
    }

    @Test
    fun `the header exposes the hash of the new file`() {
        val old = bytes(500, 12)
        val new = bytes(700, 13)
        val p = patch(old, new) { insert(new) }

        val header = ApkPatch.readHeader(java.io.DataInputStream(p.inputStream()))

        assertEquals(700L, header.newSize)
        assertEquals(sha(new).joinToString("") { "%02x".format(it) }, header.newSha256Hex)
    }

    /**
     * Cross-checks the Node tool and the app on real files. Set SHIRO_PATCH_OLD, SHIRO_PATCH_NEW
     * and SHIRO_PATCH_FILE (made by tools/release/apk-patch.js) to run it; skipped otherwise.
     */
    @Test
    fun `the app rebuilds a real apk from a patch made by the node tool`() {
        val oldPath = System.getenv("SHIRO_PATCH_OLD")
        val newPath = System.getenv("SHIRO_PATCH_NEW")
        val patchPath = System.getenv("SHIRO_PATCH_FILE")
        assumeTrue(oldPath != null && newPath != null && patchPath != null)

        val out = File(dir, "real.apk")
        File(patchPath!!).inputStream().use { ApkPatch.apply(File(oldPath!!), it, out) }

        assertEquals(ApkPatch.sha256Hex(File(newPath!!)), ApkPatch.sha256Hex(out))
    }
}

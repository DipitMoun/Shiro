package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.LegacyBackup
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackupDetectorTest {

    @Test
    fun `new backups are not treated as legacy`() {
        val backup = Backup(
            isLegacy = false,
            backupManga = listOf(BackupManga(source = 1, url = "manga")),
        )

        val bytes = ProtoBuf.encodeToByteArray(Backup.serializer(), backup)

        assertFalse(BackupDetector.isLegacyBackup(bytes))
    }

    @Test
    fun `legacy backups without anime sources are still detected`() {
        val backup = LegacyBackup(
            backupManga = listOf(BackupManga(source = 1, url = "manga")),
        )

        val bytes = ProtoBuf.encodeToByteArray(LegacyBackup.serializer(), backup)

        assertTrue(BackupDetector.isLegacyBackup(bytes))
    }
}

package at.daschmidt.backrestwidget

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsers of the history, snapshot and file screens against Backrest 1.14.1 responses. */
class BrowseTest {

    @Test
    fun parsesBackupHistory() {
        val records = BackupRecord.fromOperations(resource("history_1_14.json"))
        // Index operations are not backups.
        assertEquals(listOf(4L, 2L, 1L), records.map { it.id })

        val failed = records[0]
        assertEquals("kaputt", failed.planId)
        assertEquals(OpStatus.ERROR, failed.status)
        assertTrue(failed.message!!.contains("/gibt/es/nicht"))
        assertNull(failed.snapshotId)
        assertNull(failed.dataAdded)

        val second = records[1]
        assertEquals(OpStatus.SUCCESS, second.status)
        assertEquals("aaeb5ef154cfdef52117058e9b90b0f3f558032094c4f05953a70decc75299cc", second.snapshotId)
        assertEquals(5104L, second.dataAdded)
        assertEquals(1L, second.filesNew)
        assertEquals(6L, second.totalFiles)
        assertEquals(300036L, second.totalBytes)
        assertEquals(1341L, second.durationMs)
        assertEquals("local", second.repoId)
        assertEquals("android-test", second.instance)
    }

    @Test
    fun runningAndPendingBackups() {
        val ops = JSONObject(
            """
            {"operations": [
              {"id": "7", "planId": "p", "status": "STATUS_INPROGRESS", "unixTimeStartMs": "100",
               "operationBackup": {"lastStatus": {"status": {"percentDone": 0.25}}}},
              {"id": "8", "planId": "p", "status": "STATUS_PENDING", "unixTimeStartMs": "200", "operationBackup": {}},
              {"id": "9", "planId": "p", "status": "STATUS_WARNING", "unixTimeStartMs": "50", "unixTimeEndMs": "60",
               "displayMessage": "1 Datei nicht lesbar",
               "operationBackup": {"errors": [{"item": "/x"}], "lastStatus": {"summary": {"snapshotId": "abc"}}}}
            ]}
            """
        )
        val records = BackupRecord.fromOperations(ops)
        assertEquals(listOf(7L, 9L), records.map { it.id })
        assertEquals(0.25, records[0].percent!!, 0.0)
        assertNull(records[0].durationMs)
        assertEquals("abc", records[1].snapshotId)
        assertEquals(1, records[1].errorCount)
        assertEquals(0L, records[1].dataAdded) // summary present, zero omitted by protojson
    }

    @Test
    fun parsesSnapshots() {
        val snapshots = SnapshotInfo.fromList(resource("snapshots_1_14.json"))
        assertEquals(listOf("aaeb5ef1", "8e9e833f"), snapshots.map { it.id.take(8) })
        val newest = snapshots[0]
        assertEquals("docs", newest.planId)
        assertEquals(listOf("/srv/daten/docs"), newest.paths)
        assertEquals(1792127422513L, newest.timeMs)
        assertEquals(6L, newest.totalFiles)
    }

    @Test
    fun findsIndexOperation() {
        val (opId, snapshot) = SnapshotInfo.indexOp(resource("history_1_14.json"), "local")!!
        // The first index operation of the fixture; GetOperations is filtered by snapshot in the app.
        assertEquals(3L, opId)
        assertEquals(listOf("/srv/daten/docs"), snapshot.paths)
        assertNull(SnapshotInfo.indexOp(resource("history_1_14.json"), "anderes"))
    }

    @Test
    fun parsesDirectoryListing() {
        val entries = FileEntry.fromListing(resource("files_1_14.json"), "/srv/daten/docs")
        // The listed directory itself is dropped, directories come first.
        assertEquals(listOf("sub", "bild.bin", "datei1.txt", "datei2.txt", "datei3.txt", "neu.txt"), entries.map { it.name })
        assertTrue(entries[0].isDir)
        val bild = entries[1]
        assertFalse(bild.isDir)
        assertEquals(300000L, bild.size)
        assertEquals("/srv/daten/docs/bild.bin", bild.path)
        assertTrue(bild.mtimeMs!! > 1_700_000_000_000)
        // Same listing asked with a trailing slash.
        assertEquals(6, FileEntry.fromListing(resource("files_1_14.json"), "/srv/daten/docs/").size)
    }

    @Test
    fun parentPaths() {
        assertEquals("/srv/daten", parentPath("/srv/daten/docs"))
        assertEquals("/srv/daten", parentPath("/srv/daten/docs/"))
        assertEquals("/", parentPath("/srv"))
        assertEquals("/", parentPath("/"))
    }

    private fun resource(name: String) = JSONObject(javaClass.classLoader!!.getResource(name).readText())
}

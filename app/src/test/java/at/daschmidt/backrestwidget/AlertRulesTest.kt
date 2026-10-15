package at.daschmidt.backrestwidget

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertRulesTest {

    private val now = 1_760_400_000_000L
    private val hour = 3600_000L

    private fun plan(vararg entries: Pair<Long, OpStatus>, instance: String? = null) = PlanState(
        id = "nas",
        recent = entries.map { BackupEntry(it.first, 1000, it.second) },
        nextBackupMs = null, success30d = 0, warning30d = 0, failed30d = 0, bytesAdded30d = 0,
        instance = instance,
    )

    @Test
    fun newBackupReportsLatestFailureOnce() {
        val p = plan(now - 5 * hour to OpStatus.SUCCESS, now - hour to OpStatus.ERROR)
        assertEquals(now - hour, AlertRules.newBackup(p, OpStatus.ERROR, 0, now)?.startMs)
        assertNull(AlertRules.newBackup(p, OpStatus.ERROR, now - hour, now))
        assertNull(AlertRules.newBackup(p, OpStatus.WARNING, 0, now))
    }

    @Test
    fun newBackupIgnoresRunningAndOldEntries() {
        // The running backup does not hide the finished failure before it.
        val running = plan(now - 2 * hour to OpStatus.ERROR, now to OpStatus.RUNNING)
        assertEquals(now - 2 * hour, AlertRules.newBackup(running, OpStatus.ERROR, 0, now)?.startMs)
        val old = plan(now - 30 * hour to OpStatus.ERROR)
        assertNull(AlertRules.newBackup(old, OpStatus.ERROR, 0, now))
    }

    @Test
    fun planKeySeparatesRemoteInstances() {
        assertEquals("nas", plan().key)
        assertEquals("vps/nas", plan(instance = "vps").key)
    }

    @Test
    fun failedTasksFromOperations() {
        val ops = JSONObject(
            """
            {"operations": [
              {"id": "10", "status": "STATUS_ERROR", "planId": "_system_", "repoId": "b2",
               "unixTimeStartMs": "${now - hour}", "displayMessage": " prune failed: lock ", "operationPrune": {}},
              {"id": "11", "status": "STATUS_ERROR", "planId": "nas", "repoId": "b2",
               "unixTimeStartMs": "${now - hour}", "operationBackup": {}},
              {"id": "12", "status": "STATUS_SUCCESS", "planId": "nas", "repoId": "b2", "operationCheck": {}},
              {"id": "13", "status": "STATUS_ERROR", "planId": "nas", "repoId": "b2", "instanceId": "vps",
               "unixTimeStartMs": "${now - hour}", "operationRunHook": {}}
            ]}
            """
        )
        val tasks = FailedTask.fromOperations(ops, ops, null)
        assertEquals(listOf(10L, 13L), tasks.map { it.id })
        assertEquals("Prune · Repo b2", tasks[0].title)
        assertEquals("prune failed: lock", tasks[0].message)
        assertEquals("Hook · nas", tasks[1].title)
        assertEquals("vps", tasks[1].instance)
        assertNull(tasks[1].message)
    }

    @Test
    fun newTasksSkipsNotifiedAndOld() {
        fun task(id: Long, start: Long) = FailedTask(id, OpKind.PRUNE, null, "b2", "", start, null)
        val tasks = listOf(task(5, now - hour), task(6, now - 30 * hour), task(7, now))
        assertEquals(listOf(7L), AlertRules.newTasks(tasks, 6, now).map { it.id })
        assertEquals(listOf(5L, 7L), AlertRules.newTasks(tasks, 0, now).map { it.id })
    }

    @Test
    fun unreachableOncePerRun() {
        assertFalse(AlertRules.unreachable(2, 3, false))
        assertTrue(AlertRules.unreachable(3, 3, false))
        assertFalse(AlertRules.unreachable(7, 3, true))
    }

    @Test
    fun disconnectedNeedsLongSilence() {
        val remotes = listOf(
            RemoteState("ok", "connected", now - 2 * hour, emptyList()),
            RemoteState("blip", "retry_wait", now - 60_000, emptyList()),
            RemoteState("gone", "disconnected", now - hour, emptyList()),
            RemoteState("never", "unknown", null, emptyList()),
        )
        assertEquals(listOf("gone", "never"), AlertRules.disconnected(remotes, now).map { it.instance })
    }
}

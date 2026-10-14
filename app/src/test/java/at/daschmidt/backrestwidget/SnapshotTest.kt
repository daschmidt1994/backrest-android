package at.daschmidt.backrestwidget

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotTest {

    private val dashboard = JSONObject(
        """
        {
          "planSummaries": [
            {
              "id": "nas",
              "backupsSuccessLast30days": "28",
              "backupsFailed30days": "2",
              "bytesAddedLast30days": "1073741824",
              "nextBackupTimeMs": "1760400000000",
              "recentBackups": {
                "timestampMs": ["1760300000000", "1760200000000", "1760100000000"],
                "durationMs": ["60000", "120000", "30000"],
                "status": ["STATUS_ERROR", "STATUS_SUCCESS", "STATUS_SUCCESS"]
              }
            },
            {
              "id": "Docker",
              "backupsSuccessLast30days": "5",
              "recentBackups": {
                "timestampMs": ["1760000000000", "1760300000000"],
                "durationMs": ["1000", "0"],
                "status": ["STATUS_SUCCESS", "STATUS_INPROGRESS"]
              }
            },
            { "id": "leer" },
            { "backupsSuccessLast30days": "1" }
          ],
          "repoSummaries": [ { "id": "b2", "protectedBytes": "5000", "bytesAddedLast30days": "10" } ]
        }
        """
    )

    @Test
    fun parsesDashboard() {
        val s = Snapshot.fromDashboard(dashboard, 42)
        assertEquals(listOf("Docker", "leer", "nas"), s.plans.map { it.id })

        val nas = s.plans.first { it.id == "nas" }
        assertEquals(OpStatus.ERROR, nas.lastFinished?.status)
        assertEquals(1760300000000, nas.lastFinished?.startMs)
        assertEquals(Health.ERROR, nas.health)
        assertEquals(28, nas.success30d)
        assertEquals(2, nas.failed30d)
        assertEquals(0, nas.warning30d)
        assertEquals(1760400000000, nas.nextBackupMs)
        assertFalse(nas.running)

        val docker = s.plans.first { it.id == "Docker" }
        assertTrue(docker.running)
        assertEquals(OpStatus.SUCCESS, docker.lastFinished?.status)
        assertEquals(Health.RUNNING, docker.health)

        val empty = s.plans.first { it.id == "leer" }
        assertNull(empty.lastFinished)
        assertEquals(Health.NONE, empty.health)

        assertEquals(Health.ERROR, s.health)
        assertEquals(5000L, s.repos.single().protectedBytes)
    }

    @Test
    fun jsonRoundTrip() {
        val s = Snapshot.fromDashboard(dashboard, 42)
        assertEquals(s, Snapshot.fromJson(s.toJson()))
    }

    @Test
    fun numericStatuses() {
        assertEquals(OpStatus.WARNING, OpStatus.parse(7))
        assertEquals(OpStatus.CANCELLED, OpStatus.parse("STATUS_USER_CANCELLED"))
        assertEquals(OpStatus.UNKNOWN, OpStatus.parse(null))
    }
}

class RealResponseTest {
    @Test
    fun parsesBackrest114Response() {
        val text = javaClass.classLoader!!.getResource("dashboard_1_14.json").readText()
        val s = Snapshot.fromDashboard(JSONObject(text), 0)
        assertEquals(listOf("docs", "manuell"), s.plans.map { it.id })
        val docs = s.plans[0]
        assertEquals(OpStatus.SUCCESS, docs.lastFinished?.status)
        assertEquals(1791918995486, docs.lastFinished?.startMs)
        assertEquals(1791939600000, docs.nextBackupMs)
        assertEquals(1, docs.success30d)
        assertEquals(Health.OK, docs.health)
        val manual = s.plans[1]
        assertNull(manual.lastFinished)
        assertNull(manual.nextBackupMs)
        assertEquals(6L, s.repos.single().protectedBytes)
        assertEquals(Health.OK, s.health)
    }
}

class RunningOperationsTest {
    private fun resource(name: String) = JSONObject(javaClass.classLoader!!.getResource(name).readText())

    @Test
    fun parsesRunningBackupWithProgress() {
        val ops = RunningOp.fromOperations(resource("operations_running.json"))
        // finished, pending and index operations are ignored
        assertEquals(listOf(13L, 14L), ops.map { it.id })

        val backup = ops[0]
        assertEquals(OpKind.BACKUP, backup.kind)
        assertEquals("gross", backup.planId)
        assertEquals(0.8167, backup.percent!!, 0.001)
        assertEquals(688483652L, backup.bytesDone)
        assertEquals(842956800L, backup.totalBytes)
        assertEquals(200L, backup.filesDone)
        assertEquals(201L, backup.totalFiles)
        assertEquals("/var/tmp/brtest-src/big.bin", backup.currentFile)

        val prune = ops[1]
        assertEquals(OpKind.PRUNE, prune.kind)
        assertNull(prune.planId)
        assertNull(prune.percent)
        assertEquals("Prune · Repo local", prune.title)
    }

    @Test
    fun attachesRunningBackupToPlan() {
        val dashboard = JSONObject(
            """{"planSummaries":[{"id":"gross","recentBackups":{"timestampMs":["1"],"durationMs":["1"],"status":["STATUS_SUCCESS"]}},{"id":"docs"}]}"""
        )
        val s = Snapshot.fromDashboard(dashboard, 0, resource("operations_running.json"))
        val gross = s.plans.first { it.id == "gross" }
        assertEquals(13L, gross.activeBackup?.id)
        assertTrue(gross.running)
        assertEquals(Health.RUNNING, gross.health)
        assertNull(s.plans.first { it.id == "docs" }.activeBackup)
        // the prune has no plan row, the backup has
        assertEquals(listOf(14L), s.otherRunning.map { it.id })
        assertEquals("2 Aufgaben laufen", Format.overall(s))
        assertEquals(s, Snapshot.fromJson(s.toJson()))
    }

    @Test
    fun withoutOperationsNothingRuns() {
        val s = Snapshot.fromDashboard(JSONObject("""{"planSummaries":[{"id":"a"}]}"""), 0, null)
        assertTrue(s.running.isEmpty())
    }
}

class RemoteInstancesTest {
    private fun resource(name: String) = JSONObject(javaClass.classLoader!!.getResource(name).readText())

    private fun peer() = org.json.JSONArray(javaClass.classLoader!!.getResource("peer_states_1_14.json").readText()).getJSONObject(0)

    @Test
    fun parsesPeerWithoutKeepingSecrets() {
        val remote = RemoteState.from(peer())!!
        assertEquals("netbird-vps", remote.instance)
        assertTrue(remote.connected)
        assertEquals(listOf("netbird-system"), remote.planIds)
        assertTrue(remote.lastHeartbeatMs!! > 0)
        assertFalse(remote.toString().contains("fixture-repo-password"))
        assertFalse(remote.toString().contains("fixture-rest-password"))
    }

    @Test
    fun peerWithoutConfigUsesKnownPlans() {
        val remote = RemoteState.from(
            JSONObject("""{"peerInstanceId":"laptop","state":"CONNECTION_STATE_RETRY_WAIT","knownPlans":[{"id":"home"}]}""")
        )!!
        assertFalse(remote.connected)
        assertEquals("retry_wait", remote.state)
        assertEquals(listOf("home"), remote.planIds)
        assertNull(RemoteState.from(JSONObject("""{"peerInstanceId":""}""")))
    }

    @Test
    fun remotePlansFromSyncedOperations() {
        val remote = RemoteState.from(peer())!!
        val dashboard = JSONObject("""{"planSummaries":[{"id":"netbird-system","backupsSuccessLast30days":"9"}]}""")
        // "now" between the finished backup and the scheduled (pending) one
        val now = 1791972446639L + 60_000
        val s = Snapshot.fromDashboard(
            dashboard, now, null, listOf(remote),
            mapOf("netbird-vps/netbird-system" to resource("remote_operations_1_14.json")),
        )
        // A local plan with the same ID stays separate from the remote one.
        assertEquals(2, s.plans.size)
        val local = s.plans.first { !it.isRemote }
        assertEquals(9, local.success30d)
        val plan = s.plans.first { it.isRemote }
        assertEquals("netbird-vps", plan.instance)
        assertEquals(OpStatus.SUCCESS, plan.lastFinished?.status)
        assertEquals(1791972445924L, plan.lastFinished?.startMs)
        assertEquals(1792026000000L, plan.nextBackupMs)
        assertEquals(1, plan.success30d)
        assertEquals(Health.OK, plan.health)
        assertEquals(s, Snapshot.fromJson(s.toJson()))
    }

    @Test
    fun remoteRunningBackupBelongsToRemotePlan() {
        val remote = RemoteState("vps", "connected", null, listOf("system"))
        val ops = JSONObject(
            """{"operations":[{"id":"7","instanceId":"vps","planId":"system","repoId":"r","status":"STATUS_INPROGRESS",
               "unixTimeStartMs":"1000","operationBackup":{"lastStatus":{"status":{"percentDone":0.5}}}}]}"""
        )
        val s = Snapshot.fromDashboard(
            JSONObject("""{"planSummaries":[{"id":"system"}]}"""), 2000, ops, listOf(remote), mapOf("vps/system" to ops),
        )
        val remotePlan = s.plans.first { it.isRemote }
        val localPlan = s.plans.first { !it.isRemote }
        assertEquals(7L, remotePlan.activeBackup?.id)
        assertNull(localPlan.activeBackup)
        assertTrue(s.isRemote(s.running.single()))
        assertTrue(s.otherRunning.isEmpty())
    }
}

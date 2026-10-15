package at.daschmidt.backrestwidget

import org.json.JSONArray
import org.json.JSONObject

/** Status of a Backrest operation (v1.OperationStatus). */
enum class OpStatus {
    UNKNOWN, PENDING, RUNNING, SUCCESS, WARNING, ERROR, CANCELLED;

    val finished: Boolean get() = this == SUCCESS || this == WARNING || this == ERROR || this == CANCELLED

    companion object {
        fun parse(raw: Any?): OpStatus = when (raw) {
            "STATUS_PENDING", 1 -> PENDING
            "STATUS_INPROGRESS", 2 -> RUNNING
            "STATUS_SUCCESS", 3 -> SUCCESS
            "STATUS_ERROR", 4 -> ERROR
            "STATUS_SYSTEM_CANCELLED", "STATUS_USER_CANCELLED", 5, 6 -> CANCELLED
            "STATUS_WARNING", 7 -> WARNING
            else -> UNKNOWN
        }
    }
}

/** Traffic light shown for a plan and for the whole server. Ordered by severity. */
enum class Health { NONE, OK, RUNNING, WARNING, ERROR }

data class BackupEntry(val startMs: Long, val durationMs: Long, val status: OpStatus)

/** Kind of a Backrest operation, from the name of its oneof field. */
enum class OpKind(val field: String, val label: String) {
    BACKUP("operationBackup", "Backup"),
    CHECK("operationCheck", "Prüfung"),
    PRUNE("operationPrune", "Prune"),
    FORGET("operationForget", "Forget"),
    RESTORE("operationRestore", "Wiederherstellung"),
    STATS("operationStats", "Statistik"),
    INDEX("operationIndexSnapshot", "Indexierung"),
    HOOK("operationRunHook", "Hook"),
    COMMAND("operationRunCommand", "Befehl"),
    OTHER("", "Aufgabe");

    companion object {
        fun of(op: JSONObject): OpKind = entries.firstOrNull { it.field.isNotEmpty() && op.has(it.field) } ?: OTHER
    }
}

/** An operation that is currently in progress, with restic's progress where available. */
data class RunningOp(
    val id: Long,
    val kind: OpKind,
    /** Plan ID, null for repository tasks Backrest runs under its "_system_" pseudo plan. */
    val planId: String?,
    val repoId: String,
    val startMs: Long,
    /** 0.0–1.0, null when the operation reports no progress. */
    val percent: Double?,
    val bytesDone: Long,
    val totalBytes: Long,
    val filesDone: Long,
    val totalFiles: Long,
    val currentFile: String?,
    /** Backrest instance that runs the operation (multihost: may be a remote client). */
    val instance: String = "",
) {
    val title: String get() = if (planId != null) "${kind.label} · $planId" else "${kind.label} · Repo $repoId"

    companion object {
        fun from(op: JSONObject): RunningOp? {
            if (OpStatus.parse(op.opt("status")) != OpStatus.RUNNING) return null
            val kind = OpKind.of(op)
            if (kind == OpKind.INDEX) return null
            val id = op.longOrNull("id") ?: return null
            var progress: JSONObject? = null
            when (kind) {
                OpKind.BACKUP -> progress = op.optJSONObject(kind.field)?.optJSONObject("lastStatus")?.optJSONObject("status")
                OpKind.RESTORE -> progress = op.optJSONObject(kind.field)?.optJSONObject("lastStatus")
                else -> {}
            }
            val restore = kind == OpKind.RESTORE
            return RunningOp(
                id = id,
                kind = kind,
                planId = op.optString("planId").takeUnless { it.isEmpty() || it.startsWith("_") },
                repoId = op.optString("repoId"),
                startMs = op.longOrNull("unixTimeStartMs") ?: 0,
                percent = progress?.opt("percentDone")?.let { (it as? Number)?.toDouble() }?.coerceIn(0.0, 1.0),
                bytesDone = progress?.longOrNull(if (restore) "bytesRestored" else "bytesDone") ?: 0,
                totalBytes = progress?.longOrNull("totalBytes") ?: 0,
                filesDone = progress?.longOrNull(if (restore) "filesRestored" else "filesDone") ?: 0,
                totalFiles = progress?.longOrNull("totalFiles") ?: 0,
                currentFile = progress?.optJSONArray("currentFile")?.optString(0)?.ifEmpty { null },
                instance = op.optString("instanceId"),
            )
        }

        fun fromOperations(list: JSONObject?): List<RunningOp> =
            list?.optJSONArray("operations").objects().mapNotNull { from(it) }.sortedBy { it.startMs }
    }
}

/** A failed operation other than a backup, e.g. prune, check, forget or a hook. */
data class FailedTask(
    val id: Long,
    val kind: OpKind,
    /** Plan ID, null for repository tasks under Backrest's "_system_" pseudo plan. */
    val planId: String?,
    val repoId: String,
    val instance: String,
    val startMs: Long,
    /** Backrest's display message of the operation, usually the error text. */
    val message: String?,
) {
    val title: String get() = if (planId != null) "${kind.label} · $planId" else "${kind.label} · Repo $repoId"

    companion object {
        fun from(op: JSONObject): FailedTask? {
            if (OpStatus.parse(op.opt("status")) != OpStatus.ERROR) return null
            val kind = OpKind.of(op)
            if (kind == OpKind.BACKUP) return null // reported per plan
            return FailedTask(
                id = op.longOrNull("id") ?: return null,
                kind = kind,
                planId = op.optString("planId").takeUnless { it.isEmpty() || it.startsWith("_") },
                repoId = op.optString("repoId"),
                instance = op.optString("instanceId"),
                startMs = op.longOrNull("unixTimeStartMs") ?: 0,
                message = op.optString("displayMessage").trim().ifEmpty { null },
            )
        }

        fun fromOperations(vararg lists: JSONObject?): List<FailedTask> =
            lists.flatMap { it?.optJSONArray("operations").objects() }
                .mapNotNull { from(it) }.distinctBy { it.id }.sortedBy { it.id }
    }
}

data class PlanState(
    val id: String,
    val recent: List<BackupEntry>,
    val nextBackupMs: Long?,
    val success30d: Int,
    val warning30d: Int,
    val failed30d: Int,
    val bytesAdded30d: Long,
    /** The backup of this plan that is running right now, if known. */
    val activeBackup: RunningOp? = null,
    /** Remote (multihost client) instance owning the plan, null for local plans. */
    val instance: String? = null,
) {
    val isRemote: Boolean get() = instance != null

    /** Unique across instances; local plans keep their bare ID (compatible with stored state). */
    val key: String get() = if (instance != null) "$instance/$id" else id

    /** Newest backup that has finished. */
    val lastFinished: BackupEntry? get() = recent.lastOrNull { it.status.finished }
    val running: Boolean get() = activeBackup != null || recent.any { it.status == OpStatus.RUNNING }

    val health: Health
        get() {
            val last = lastFinished
            val base = when (last?.status) {
                null -> Health.NONE
                OpStatus.SUCCESS -> Health.OK
                OpStatus.ERROR -> Health.ERROR
                else -> Health.WARNING
            }
            return if (running && base < Health.WARNING) Health.RUNNING else base
        }
}

data class RepoState(val id: String, val protectedBytes: Long?, val bytesAdded30d: Long)

/** A Backrest client instance synced to the server (multihost). */
data class RemoteState(
    val instance: String,
    /** v1sync.ConnectionState without prefix, e.g. "connected", "retry_wait". */
    val state: String,
    val lastHeartbeatMs: Long?,
    val planIds: List<String>,
) {
    val connected: Boolean get() = state == "connected"

    companion object {
        /**
         * Parse a PeerState message. Only plan IDs are taken from the remote
         * config: its repositories carry plaintext passwords and are ignored.
         */
        fun from(peer: JSONObject): RemoteState? {
            val instance = peer.optString("peerInstanceId").trim().ifEmpty { return null }
            val ids = linkedSetOf<String>()
            peer.optJSONObject("remoteConfig")?.optJSONArray("plans").objects()
                .mapNotNullTo(ids) { it.optString("id").trim().ifEmpty { null } }
            peer.optJSONArray("knownPlans").objects()
                .mapNotNullTo(ids) { it.optString("id").trim().ifEmpty { null } }
            return RemoteState(
                instance = instance,
                state = peer.optString("state").removePrefix("CONNECTION_STATE_").lowercase().ifEmpty { "unknown" },
                lastHeartbeatMs = peer.longOrNull("lastHeartbeatMillis")?.takeIf { it > 0 },
                planIds = ids.sorted(),
            )
        }
    }
}

/** Everything the app and the widget show; cached between refreshes. */
data class Snapshot(
    val fetchedAtMs: Long,
    val plans: List<PlanState>,
    val repos: List<RepoState>,
    /** All operations in progress, oldest first. */
    val running: List<RunningOp> = emptyList(),
    /** Remote instances of a multihost server. Their plans are part of [plans]. */
    val remotes: List<RemoteState> = emptyList(),
) {
    /** Whether an operation runs on a remote instance rather than on the server itself. */
    fun isRemote(op: RunningOp): Boolean = remotes.any { it.instance == op.instance }

    /** Running operations that are not shown in a plan row (repository tasks, hooks, …). */
    val otherRunning: List<RunningOp>
        get() = running.filter { op ->
            op.kind != OpKind.BACKUP || plans.none { it.id == op.planId && it.instance == op.instance.takeIf { isRemote(op) } }
        }

    val health: Health get() = plans.maxOfOrNull { it.health } ?: Health.NONE

    fun toJson(): String = JSONObject().apply {
        put("fetchedAtMs", fetchedAtMs)
        put("plans", JSONArray(plans.map { p ->
            JSONObject().apply {
                put("id", p.id)
                p.nextBackupMs?.let { put("nextBackupMs", it) }
                put("success30d", p.success30d)
                put("warning30d", p.warning30d)
                put("failed30d", p.failed30d)
                put("bytesAdded30d", p.bytesAdded30d)
                p.instance?.let { put("instance", it) }
                p.activeBackup?.let { put("activeBackup", it.toJson()) }
                put("recent", JSONArray(p.recent.map { e ->
                    JSONArray().put(e.startMs).put(e.durationMs).put(e.status.name)
                }))
            }
        }))
        put("running", JSONArray(running.map { it.toJson() }))
        put("remotes", JSONArray(remotes.map { r ->
            JSONObject().apply {
                put("instance", r.instance)
                put("state", r.state)
                r.lastHeartbeatMs?.let { put("lastHeartbeatMs", it) }
                put("planIds", JSONArray(r.planIds))
            }
        }))
        put("repos", JSONArray(repos.map { r ->
            JSONObject().apply {
                put("id", r.id)
                r.protectedBytes?.let { put("protectedBytes", it) }
                put("bytesAdded30d", r.bytesAdded30d)
            }
        }))
    }.toString()

    companion object {
        fun fromJson(text: String): Snapshot {
            val o = JSONObject(text)
            val plans = o.optJSONArray("plans").objects().map { p ->
                PlanState(
                    id = p.getString("id"),
                    recent = p.optJSONArray("recent").arrays().map {
                        BackupEntry(it.getLong(0), it.getLong(1), OpStatus.valueOf(it.getString(2)))
                    },
                    nextBackupMs = if (p.has("nextBackupMs")) p.getLong("nextBackupMs") else null,
                    success30d = p.optInt("success30d"),
                    warning30d = p.optInt("warning30d"),
                    failed30d = p.optInt("failed30d"),
                    bytesAdded30d = p.optLong("bytesAdded30d"),
                    activeBackup = p.optJSONObject("activeBackup")?.let { runningFromJson(it) },
                    instance = p.optString("instance").ifEmpty { null },
                )
            }
            val running = o.optJSONArray("running").objects().map { runningFromJson(it) }
            val remotes = o.optJSONArray("remotes").objects().map { r ->
                val ids = r.optJSONArray("planIds")
                RemoteState(
                    instance = r.getString("instance"),
                    state = r.optString("state", "unknown"),
                    lastHeartbeatMs = if (r.has("lastHeartbeatMs")) r.getLong("lastHeartbeatMs") else null,
                    planIds = if (ids == null) emptyList() else (0 until ids.length()).map { ids.getString(it) },
                )
            }
            val repos = o.optJSONArray("repos").objects().map { r ->
                RepoState(
                    id = r.getString("id"),
                    protectedBytes = if (r.has("protectedBytes")) r.getLong("protectedBytes") else null,
                    bytesAdded30d = r.optLong("bytesAdded30d"),
                )
            }
            return Snapshot(o.getLong("fetchedAtMs"), plans, repos, running, remotes)
        }

        /**
         * Parse a GetSummaryDashboard response. int64 values arrive as strings,
         * zero values are omitted by protojson. Plan/repo paths are ignored.
         */
        fun fromDashboard(
            data: JSONObject,
            nowMs: Long,
            operations: JSONObject? = null,
            remotes: List<RemoteState> = emptyList(),
            /** GetOperations responses of remote plans, keyed by "<instance>/<plan>". */
            remoteOperations: Map<String, JSONObject> = emptyMap(),
        ): Snapshot {
            val running = RunningOp.fromOperations(operations)
            val remoteIds = remotes.map { it.instance }.toSet()
            val plans = data.optJSONArray("planSummaries").objects().mapNotNull { s ->
                val id = s.optString("id").ifEmpty { return@mapNotNull null }
                PlanState(
                    id = id,
                    recent = parseChart(s.optJSONObject("recentBackups")),
                    nextBackupMs = s.longOrNull("nextBackupTimeMs")?.takeIf { it > 0 },
                    success30d = s.longOrNull("backupsSuccessLast30days")?.toInt() ?: 0,
                    warning30d = s.longOrNull("backupsWarningLast30days")?.toInt() ?: 0,
                    failed30d = s.longOrNull("backupsFailed30days")?.toInt() ?: 0,
                    bytesAdded30d = s.longOrNull("bytesAddedLast30days") ?: 0,
                    activeBackup = running.lastOrNull {
                        it.kind == OpKind.BACKUP && it.planId == id && it.instance !in remoteIds
                    },
                )
            }.sortedBy { it.id.lowercase() }
            val remotePlans = remotes.flatMap { remote ->
                remote.planIds.map { planId ->
                    remotePlanState(remote.instance, planId, remoteOperations["${remote.instance}/$planId"], running, nowMs)
                }
            }
            val repos = data.optJSONArray("repoSummaries").objects().mapNotNull { s ->
                val id = s.optString("id").ifEmpty { return@mapNotNull null }
                RepoState(id, s.longOrNull("protectedBytes")?.takeIf { it > 0 }, s.longOrNull("bytesAddedLast30days") ?: 0)
            }.sortedBy { it.id.lowercase() }
            return Snapshot(nowMs, plans + remotePlans, repos, running, remotes)
        }

        /** Derive a remote plan's state from its synced operations (the summary dashboard only covers local plans). */
        private fun remotePlanState(
            instance: String,
            planId: String,
            operations: JSONObject?,
            running: List<RunningOp>,
            nowMs: Long,
        ): PlanState {
            val ops = operations?.optJSONArray("operations").objects()
                .filter { it.optString("instanceId") == instance && OpKind.of(it) == OpKind.BACKUP }
            val since = nowMs - 30L * 24 * 3600_000
            var success = 0; var warning = 0; var failed = 0; var added = 0L
            val recent = mutableListOf<BackupEntry>()
            var next: Long? = null
            for (op in ops) {
                val status = OpStatus.parse(op.opt("status"))
                val start = op.longOrNull("unixTimeStartMs") ?: continue
                if (status == OpStatus.PENDING) {
                    if (start > nowMs) next = minOf(next ?: start, start)
                    continue
                }
                val end = op.longOrNull("unixTimeEndMs")?.takeIf { it >= start }
                recent += BackupEntry(start, if (end != null) end - start else 0, status)
                if (start >= since && status.finished) {
                    when (status) {
                        OpStatus.SUCCESS -> success++
                        OpStatus.WARNING -> warning++
                        OpStatus.ERROR -> failed++
                        else -> {}
                    }
                    added += op.optJSONObject("operationBackup")?.optJSONObject("lastStatus")
                        ?.optJSONObject("summary")?.longOrNull("dataAdded") ?: 0
                }
            }
            val active = running.lastOrNull { it.kind == OpKind.BACKUP && it.planId == planId && it.instance == instance }
                ?: ops.mapNotNull { RunningOp.from(it) }.lastOrNull()
            return PlanState(
                id = planId,
                recent = recent.sortedBy { it.startMs },
                nextBackupMs = next,
                success30d = success,
                warning30d = warning,
                failed30d = failed,
                bytesAdded30d = added,
                activeBackup = active,
                instance = instance,
            )
        }

        private fun runningFromJson(o: JSONObject) = RunningOp(
            id = o.getLong("id"),
            kind = OpKind.valueOf(o.getString("kind")),
            planId = o.optString("planId").ifEmpty { null },
            repoId = o.optString("repoId"),
            startMs = o.optLong("startMs"),
            percent = if (o.has("percent")) o.getDouble("percent") else null,
            bytesDone = o.optLong("bytesDone"),
            totalBytes = o.optLong("totalBytes"),
            filesDone = o.optLong("filesDone"),
            totalFiles = o.optLong("totalFiles"),
            currentFile = o.optString("currentFile").ifEmpty { null },
            instance = o.optString("instance"),
        )

        private fun parseChart(chart: JSONObject?): List<BackupEntry> {
            if (chart == null) return emptyList()
            val starts = chart.optJSONArray("timestampMs") ?: return emptyList()
            val durations = chart.optJSONArray("durationMs") ?: return emptyList()
            val statuses = chart.optJSONArray("status") ?: return emptyList()
            val n = minOf(starts.length(), durations.length(), statuses.length())
            return (0 until n).mapNotNull { i ->
                val start = starts.opt(i).asLong() ?: return@mapNotNull null
                BackupEntry(start, durations.opt(i).asLong() ?: 0, OpStatus.parse(statuses.opt(i)))
            }.sortedBy { it.startMs }
        }
    }
}

private fun RunningOp.toJson() = JSONObject().apply {
    put("id", id)
    put("kind", kind.name)
    put("planId", planId ?: "")
    put("repoId", repoId)
    put("startMs", startMs)
    percent?.let { put("percent", it) }
    put("bytesDone", bytesDone)
    put("totalBytes", totalBytes)
    put("filesDone", filesDone)
    put("totalFiles", totalFiles)
    put("currentFile", currentFile ?: "")
    put("instance", instance)
}

private fun Any?.asLong(): Long? = when (this) {
    is Number -> toLong()
    is String -> toLongOrNull()
    else -> null
}

private fun JSONObject.longOrNull(key: String): Long? = opt(key).asLong()

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONArray?.arrays(): List<JSONArray> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONArray(it) }

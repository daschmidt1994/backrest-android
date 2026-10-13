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
            )
        }

        fun fromOperations(list: JSONObject?): List<RunningOp> =
            list?.optJSONArray("operations").objects().mapNotNull { from(it) }.sortedBy { it.startMs }
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
) {
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

/** Everything the app and the widget show; cached between refreshes. */
data class Snapshot(
    val fetchedAtMs: Long,
    val plans: List<PlanState>,
    val repos: List<RepoState>,
    /** All operations in progress, oldest first. */
    val running: List<RunningOp> = emptyList(),
) {
    /** Running operations that are not shown in a plan row (repository tasks, hooks, …). */
    val otherRunning: List<RunningOp> get() = running.filter { op -> op.kind != OpKind.BACKUP || plans.none { it.id == op.planId } }

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
                p.activeBackup?.let { put("activeBackup", it.toJson()) }
                put("recent", JSONArray(p.recent.map { e ->
                    JSONArray().put(e.startMs).put(e.durationMs).put(e.status.name)
                }))
            }
        }))
        put("running", JSONArray(running.map { it.toJson() }))
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
                )
            }
            val running = o.optJSONArray("running").objects().map { runningFromJson(it) }
            val repos = o.optJSONArray("repos").objects().map { r ->
                RepoState(
                    id = r.getString("id"),
                    protectedBytes = if (r.has("protectedBytes")) r.getLong("protectedBytes") else null,
                    bytesAdded30d = r.optLong("bytesAdded30d"),
                )
            }
            return Snapshot(o.getLong("fetchedAtMs"), plans, repos, running)
        }

        /**
         * Parse a GetSummaryDashboard response. int64 values arrive as strings,
         * zero values are omitted by protojson. Plan/repo paths are ignored.
         */
        fun fromDashboard(data: JSONObject, nowMs: Long, operations: JSONObject? = null): Snapshot {
            val running = RunningOp.fromOperations(operations)
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
                    activeBackup = running.lastOrNull { it.kind == OpKind.BACKUP && it.planId == id },
                )
            }.sortedBy { it.id.lowercase() }
            val repos = data.optJSONArray("repoSummaries").objects().mapNotNull { s ->
                val id = s.optString("id").ifEmpty { return@mapNotNull null }
                RepoState(id, s.longOrNull("protectedBytes")?.takeIf { it > 0 }, s.longOrNull("bytesAddedLast30days") ?: 0)
            }.sortedBy { it.id.lowercase() }
            return Snapshot(nowMs, plans, repos, running)
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

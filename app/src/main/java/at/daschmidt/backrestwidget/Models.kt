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

data class PlanState(
    val id: String,
    val recent: List<BackupEntry>,
    val nextBackupMs: Long?,
    val success30d: Int,
    val warning30d: Int,
    val failed30d: Int,
    val bytesAdded30d: Long,
) {
    /** Newest backup that has finished. */
    val lastFinished: BackupEntry? get() = recent.lastOrNull { it.status.finished }
    val running: Boolean get() = recent.any { it.status == OpStatus.RUNNING }

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
) {
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
                put("recent", JSONArray(p.recent.map { e ->
                    JSONArray().put(e.startMs).put(e.durationMs).put(e.status.name)
                }))
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
                )
            }
            val repos = o.optJSONArray("repos").objects().map { r ->
                RepoState(
                    id = r.getString("id"),
                    protectedBytes = if (r.has("protectedBytes")) r.getLong("protectedBytes") else null,
                    bytesAdded30d = r.optLong("bytesAdded30d"),
                )
            }
            return Snapshot(o.getLong("fetchedAtMs"), plans, repos)
        }

        /**
         * Parse a GetSummaryDashboard response. int64 values arrive as strings,
         * zero values are omitted by protojson. Plan/repo paths are ignored.
         */
        fun fromDashboard(data: JSONObject, nowMs: Long): Snapshot {
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
                )
            }.sortedBy { it.id.lowercase() }
            val repos = data.optJSONArray("repoSummaries").objects().mapNotNull { s ->
                val id = s.optString("id").ifEmpty { return@mapNotNull null }
                RepoState(id, s.longOrNull("protectedBytes")?.takeIf { it > 0 }, s.longOrNull("bytesAddedLast30days") ?: 0)
            }.sortedBy { it.id.lowercase() }
            return Snapshot(nowMs, plans, repos)
        }

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

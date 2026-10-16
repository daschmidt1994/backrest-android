package at.daschmidt.backrestwidget

import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime

/** A backup operation from Backrest's operation log, for the history screen. */
data class BackupRecord(
    val id: Long,
    val planId: String,
    val repoId: String,
    val instance: String,
    val startMs: Long,
    val endMs: Long?,
    val status: OpStatus,
    /** Backrest's display message, usually the error text of a failed backup. */
    val message: String?,
    val snapshotId: String?,
    val dataAdded: Long?,
    val filesNew: Long,
    val filesChanged: Long,
    val totalFiles: Long?,
    val totalBytes: Long?,
    /** Files restic could not read; the backup then ends with a warning. */
    val errorCount: Int,
    val percent: Double?,
) {
    val durationMs: Long? get() = endMs?.minus(startMs)

    companion object {
        fun from(op: JSONObject): BackupRecord? {
            if (OpKind.of(op) != OpKind.BACKUP) return null
            val status = OpStatus.parse(op.opt("status"))
            if (status == OpStatus.PENDING) return null // scheduled, not run yet
            val start = op.longOrNull("unixTimeStartMs") ?: return null
            val backup = op.optJSONObject("operationBackup")
            val summary = backup?.optJSONObject("lastStatus")?.optJSONObject("summary")
            val progress = backup?.optJSONObject("lastStatus")?.optJSONObject("status")
            return BackupRecord(
                id = op.longOrNull("id") ?: return null,
                planId = op.optString("planId"),
                repoId = op.optString("repoId"),
                instance = op.optString("instanceId"),
                startMs = start,
                endMs = op.longOrNull("unixTimeEndMs")?.takeIf { it >= start },
                status = status,
                message = op.optString("displayMessage").trim().ifEmpty { null },
                snapshotId = op.optString("snapshotId").ifEmpty { summary?.optString("snapshotId") }?.ifEmpty { null },
                dataAdded = summary?.longOrNull("dataAdded") ?: summary?.let { 0L },
                filesNew = summary?.longOrNull("filesNew") ?: 0,
                filesChanged = summary?.longOrNull("filesChanged") ?: 0,
                totalFiles = summary?.longOrNull("totalFilesProcessed"),
                totalBytes = summary?.longOrNull("totalBytesProcessed"),
                errorCount = backup?.optJSONArray("errors")?.length() ?: 0,
                percent = if (status == OpStatus.RUNNING) progress?.opt("percentDone")?.let { (it as? Number)?.toDouble() } else null,
            )
        }

        /** Backups of a GetOperations response, newest first. */
        fun fromOperations(list: JSONObject?): List<BackupRecord> =
            list?.optJSONArray("operations").objects().mapNotNull { from(it) }.sortedByDescending { it.startMs }
    }
}

/** A restic snapshot as returned by ListSnapshots. */
data class SnapshotInfo(
    val id: String,
    val timeMs: Long,
    val hostname: String,
    val paths: List<String>,
    /** From Backrest's "plan:<id>" tag; null for snapshots made outside Backrest. */
    val planId: String?,
    val dataAdded: Long?,
    val totalFiles: Long?,
    val totalBytes: Long?,
) {
    companion object {
        fun from(o: JSONObject): SnapshotInfo? {
            val id = o.optString("id").ifEmpty { return null }
            val summary = o.optJSONObject("summary")
            return SnapshotInfo(
                id = id,
                timeMs = o.longOrNull("unixTimeMs") ?: 0,
                hostname = o.optString("hostname"),
                paths = o.optJSONArray("paths").strings(),
                planId = o.optJSONArray("tags").strings().firstOrNull { it.startsWith("plan:") }?.removePrefix("plan:"),
                dataAdded = summary?.longOrNull("dataAdded"),
                totalFiles = summary?.longOrNull("totalFilesProcessed"),
                totalBytes = summary?.longOrNull("totalBytesProcessed"),
            )
        }

        /** Snapshots of a ListSnapshots response, newest first. */
        fun fromList(list: JSONObject?): List<SnapshotInfo> =
            list?.optJSONArray("snapshots").objects().mapNotNull { from(it) }.sortedByDescending { it.timeMs }

        /**
         * The snapshot from the index operation Backrest keeps for it. Its ID is
         * needed to download files; null when the snapshot is not indexed (yet).
         */
        fun indexOp(list: JSONObject?, repoId: String): Pair<Long, SnapshotInfo>? =
            list?.optJSONArray("operations").objects()
                .filter { it.optString("repoId") == repoId }
                .firstNotNullOfOrNull { op ->
                    val id = op.longOrNull("id") ?: return@firstNotNullOfOrNull null
                    op.optJSONObject("operationIndexSnapshot")?.optJSONObject("snapshot")?.let { from(it) }?.let { id to it }
                }
    }
}

/** An entry of a directory inside a snapshot (ListSnapshotFiles). */
data class FileEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val mtimeMs: Long?,
) {
    companion object {
        /**
         * Entries of a ListSnapshotFiles response for [path], directories first.
         * restic lists the directory itself first; it is dropped.
         */
        fun fromListing(list: JSONObject?, path: String): List<FileEntry> =
            list?.optJSONArray("entries").objects().mapNotNull { e ->
                val entryPath = e.optString("path").ifEmpty { return@mapNotNull null }
                if (entryPath.trimEnd('/') == path.trimEnd('/')) return@mapNotNull null
                FileEntry(
                    name = e.optString("name").ifEmpty { Format.fileName(entryPath) },
                    path = entryPath,
                    isDir = e.optString("type") == "dir",
                    size = e.longOrNull("size") ?: 0,
                    mtimeMs = e.optString("mtime").ifEmpty { null }?.let {
                        runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
                    },
                )
            }.sortedWith(compareBy<FileEntry> { !it.isDir }.thenBy { it.name.lowercase() })
    }
}

/** Parent directory of [path] in a snapshot, "/" for top-level paths. */
fun parentPath(path: String): String = path.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).ifEmpty { null } }

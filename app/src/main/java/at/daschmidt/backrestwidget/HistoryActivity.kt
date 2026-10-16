package at.daschmidt.backrestwidget

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle

/** Past backups of one plan, or of all plans. Tapping a backup opens its snapshot. */
class HistoryActivity : BrowseActivity() {

    private val planId: String? get() = intent.getStringExtra(EXTRA_PLAN)
    private val instance: String? get() = intent.getStringExtra(EXTRA_INSTANCE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        header(
            "Verlauf",
            when {
                planId == null -> "Alle Pläne"
                instance != null -> "Plan $planId · $instance"
                else -> "Plan $planId"
            },
        )
        reload()
    }

    override fun reload() {
        val plan = planId
        val instance = instance
        // Operations include index, forget and prune runs; ask for enough to keep many backups.
        load({ BackupRecord.fromOperations(it.history(plan, instance, if (plan == null) 600 else 300)) }) { records ->
            val remotes = settings.snapshot?.remotes.orEmpty().map { it.instance }.toSet()
            val shown = if (plan != null && instance == null) records.filter { it.instance !in remotes } else records
            showRows(shown.map { row(it, it.instance in remotes) }, "Keine Backups gefunden")
        }
    }

    private fun row(r: BackupRecord, remote: Boolean): Row {
        val title = if (planId == null) "${r.planId} · ${Format.dateTime(this, r.startMs)}" else Format.dateTime(this, r.startMs)
        val subtitle = listOfNotNull(
            Format.statusLabel(r.status),
            r.percent?.let { "${(it * 100).toInt()} %" },
            r.durationMs?.let { Format.duration(it) },
            r.instance.takeIf { remote },
        ).joinToString(" · ")
        val stats = listOfNotNull(
            r.dataAdded?.let { "+${Format.bytes(it)} neu" },
            if (r.dataAdded != null) "${r.filesNew} neu, ${r.filesChanged} geändert" else null,
            r.totalFiles?.let { files -> "$files Dateien" + (r.totalBytes?.let { " (${Format.bytes(it)})" } ?: "") },
            r.errorCount.takeIf { it > 0 }?.let { "$it nicht lesbar" },
        ).joinToString(" · ")
        val failed = r.status == OpStatus.ERROR || r.status == OpStatus.WARNING
        return Row(
            title = title,
            subtitle = subtitle,
            // Errors first: they are what one looks for in the history.
            detail = listOfNotNull(r.message?.takeIf { failed }?.let { Format.firstLine(it) }, stats.ifEmpty { null })
                .joinToString("\n").ifEmpty { null },
            iconColor = Format.statusColor(this, r.status),
            detailColor = if (r.status == OpStatus.ERROR) getColor(R.color.status_error) else null,
            // Snapshots of remote instances live in their repos, not on this server.
            opens = r.snapshotId != null && !remote,
            item = r,
        )
    }

    override fun onRowClick(row: Row) {
        val r = row.item as BackupRecord
        if (row.opens) {
            startActivity(FilesActivity.intent(this, r.repoId, r.snapshotId!!, null))
        } else if (r.message != null) {
            AlertDialog.Builder(this)
                .setTitle("${r.planId} · ${Format.statusLabel(r.status)}")
                .setMessage(r.message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    companion object {
        private const val EXTRA_PLAN = "plan"
        private const val EXTRA_INSTANCE = "instance"

        /** History of [planId] ([instance] for a remote plan), or of all plans when null. */
        fun intent(context: Context, planId: String?, instance: String?): Intent =
            Intent(context, HistoryActivity::class.java)
                .putExtra(EXTRA_PLAN, planId)
                .putExtra(EXTRA_INSTANCE, instance)
    }
}

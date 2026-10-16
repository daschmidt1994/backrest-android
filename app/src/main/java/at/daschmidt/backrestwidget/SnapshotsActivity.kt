package at.daschmidt.backrestwidget

import android.content.Context
import android.content.Intent
import android.os.Bundle

/** Snapshots in a repository, newest first. Tapping one browses its files. */
class SnapshotsActivity : BrowseActivity() {

    private val repoId: String get() = intent.getStringExtra(EXTRA_REPO).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        header("Snapshots", "Repository $repoId")
        reload()
    }

    override fun reload() {
        val repo = repoId
        load({ SnapshotInfo.fromList(it.snapshots(repo)) }) { snapshots ->
            header("Snapshots", "Repository $repo · ${snapshots.size} Snapshots")
            showRows(snapshots.map { row(it) }, "Keine Snapshots in diesem Repository")
        }
    }

    private fun row(s: SnapshotInfo) = Row(
        title = Format.dateTime(this, s.timeMs),
        subtitle = listOfNotNull(
            s.planId?.let { "Plan $it" },
            s.hostname.ifEmpty { null },
            Format.shortId(s.id),
        ).joinToString(" · "),
        detail = listOfNotNull(
            s.totalFiles?.let { "$it Dateien" },
            s.totalBytes?.let { Format.bytes(it) },
            s.dataAdded?.let { "+${Format.bytes(it)} neu" },
        ).joinToString(" · ").ifEmpty { s.paths.joinToString(", ") },
        icon = R.drawable.ic_snapshot,
        opens = true,
        item = s,
    )

    override fun onRowClick(row: Row) {
        val s = row.item as SnapshotInfo
        startActivity(FilesActivity.intent(this, repoId, s.id, s.paths))
    }

    companion object {
        private const val EXTRA_REPO = "repo"

        fun intent(context: Context, repoId: String): Intent =
            Intent(context, SnapshotsActivity::class.java).putExtra(EXTRA_REPO, repoId)
    }
}

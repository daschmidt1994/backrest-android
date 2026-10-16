package at.daschmidt.backrestwidget

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/**
 * Directories and files inside a snapshot. Starts at the backed-up paths;
 * files (and directories as tar) can be downloaded through the browser.
 */
class FilesActivity : BrowseActivity() {

    private val repoId: String get() = intent.getStringExtra(EXTRA_REPO).orEmpty()
    private val snapshotId: String get() = intent.getStringExtra(EXTRA_SNAPSHOT).orEmpty()

    /** Backed-up paths of the snapshot, null until known. */
    private var roots: List<String>? = null

    /** Directory shown; null for the list of [roots] when there are several. */
    private var path: String? = null

    /** Index operation of the snapshot, needed for download links. */
    private var indexOpId: Long? = null

    /** First visible row per directory, restored when coming back up. */
    private val scrollPositions = mutableMapOf<String?, Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        header("Snapshot ${Format.shortId(snapshotId)}", null)
        roots = intent.getStringArrayExtra(EXTRA_PATHS)?.toList()?.map { normalize(it) }?.ifEmpty { null }
        path = savedInstanceState?.getString(STATE_PATH)
        if (savedInstanceState?.getBoolean(STATE_AT_ROOTS) == true) path = null
        reload()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PATH, path)
        outState.putBoolean(STATE_AT_ROOTS, roots != null && path == null)
    }

    override fun reload() {
        val known = roots
        if (known == null) {
            // Opened from the history: the backed-up paths come from Backrest's index.
            val repo = repoId
            val snapshot = snapshotId
            load({ SnapshotInfo.indexOp(it.snapshotOperations(snapshot), repo) }) { found ->
                indexOpId = found?.first
                roots = found?.second?.paths?.map { normalize(it) }?.ifEmpty { null } ?: listOf("/")
                found?.second?.let { header("Snapshot ${Format.shortId(snapshot)}", Format.dateTime(this, it.timeMs)) }
                reload()
            }
            return
        }
        val current = path ?: known.singleOrNull()
        if (current == null) showRoots(known) else open(current)
    }

    private fun showRoots(roots: List<String>) {
        path = null
        updateBackHandling()
        header("Snapshot ${Format.shortId(snapshotId)}", "${roots.size} gesicherte Pfade")
        showRows(
            roots.map { Row(title = it, icon = R.drawable.ic_folder, opens = true, item = FileEntry(Format.fileName(it), it, true, 0, null)) },
            "Keine Pfade",
            scrollPositions[null] ?: 0,
        )
    }

    private fun open(dir: String) {
        val repo = repoId
        val snapshot = snapshotId
        path = dir
        updateBackHandling()
        header("Snapshot ${Format.shortId(snapshot)}", dir)
        load({ FileEntry.fromListing(it.listFiles(repo, snapshot, dir), dir) }) { entries ->
            showRows(entries.map { row(it) }, "Ordner ist leer", scrollPositions[dir] ?: 0)
        }
    }

    private fun row(e: FileEntry) = Row(
        title = e.name,
        subtitle = if (e.isDir) e.mtimeMs?.let { "Ordner · geändert ${Format.dateTime(this, it)}" } ?: "Ordner"
        else listOfNotNull(Format.bytes(e.size), e.mtimeMs?.let { "geändert ${Format.dateTime(this, it)}" }).joinToString(" · "),
        icon = if (e.isDir) R.drawable.ic_folder else R.drawable.ic_file,
        opens = e.isDir,
        item = e,
    )

    override fun onRowClick(row: Row) {
        val e = row.item as FileEntry
        if (e.isDir) {
            scrollPositions[path] = scrollPosition
            open(e.path)
        } else {
            AlertDialog.Builder(this)
                .setTitle(e.name)
                .setMessage(listOfNotNull(
                    "Größe: ${Format.bytes(e.size)}",
                    e.mtimeMs?.let { "Geändert: ${Format.dateTime(this, it)}" },
                    "Pfad: ${e.path}",
                ).joinToString("\n"))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Herunterladen") { _, _ -> download(e.path) }
                .show()
        }
    }

    override fun onRowLongClick(row: Row): Boolean {
        val e = row.item as FileEntry
        if (!e.isDir) return false
        AlertDialog.Builder(this)
            .setTitle(e.name)
            .setMessage("Ordner als Archiv (.tar) herunterladen?\n\n${e.path}")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Herunterladen") { _, _ -> download(e.path) }
            .show()
        return true
    }

    /** Get a signed download link and hand it to the browser, which saves the file. */
    private fun download(file: String) {
        val repo = repoId
        val snapshot = snapshotId
        val known = indexOpId
        Toast.makeText(this, "Download wird vorbereitet …", Toast.LENGTH_SHORT).show()
        background({ client ->
            val opId = known ?: SnapshotInfo.indexOp(client.snapshotOperations(snapshot), repo)?.first
                ?: throw BackrestException("Backrest hat den Snapshot noch nicht indexiert")
            opId to client.downloadUrl(opId, file)
        }, { (opId, url) ->
            indexOpId = opId
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                .onFailure { Toast.makeText(this, "Kein Browser zum Herunterladen gefunden", Toast.LENGTH_LONG).show() }
        }) { message ->
            Toast.makeText(this, "Download fehlgeschlagen: $message", Toast.LENGTH_LONG).show()
        }
    }

    override val canNavigateBack: Boolean
        get() {
            val current = path ?: return false
            val roots = roots ?: return false
            return current !in roots || roots.size > 1
        }

    override fun navigateBack(): Boolean {
        if (!canNavigateBack) return false
        val current = path!!
        val roots = roots!!
        if (current in roots) showRoots(roots) else open(parentPath(current))
        return true
    }

    private fun normalize(p: String): String = if (p == "/") p else p.trimEnd('/')

    companion object {
        private const val EXTRA_REPO = "repo"
        private const val EXTRA_SNAPSHOT = "snapshot"
        private const val EXTRA_PATHS = "paths"
        private const val STATE_PATH = "path"
        private const val STATE_AT_ROOTS = "atRoots"

        /** [paths] are the snapshot's backed-up paths if known; otherwise they are looked up. */
        fun intent(context: Context, repoId: String, snapshotId: String, paths: List<String>?): Intent =
            Intent(context, FilesActivity::class.java)
                .putExtra(EXTRA_REPO, repoId)
                .putExtra(EXTRA_SNAPSHOT, snapshotId)
                .putExtra(EXTRA_PATHS, paths?.toTypedArray())
    }
}

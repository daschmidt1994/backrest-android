package at.daschmidt.backrestwidget

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.net.SocketTimeoutException

/** One card in a [BrowseActivity] list. */
data class Row(
    val title: String,
    val subtitle: String? = null,
    val detail: String? = null,
    val icon: Int = R.drawable.dot,
    /** Tint of [icon]; null keeps the drawable's own tint. */
    val iconColor: Int? = null,
    val detailColor: Int? = null,
    /** Shows a chevron: tapping opens another screen. */
    val opens: Boolean = false,
    /** What the row stands for, handed back on clicks. */
    val item: Any? = null,
)

/**
 * A screen with a back button, a title and a list of cards loaded from the
 * server: the base of the history, snapshot and file screens.
 */
abstract class BrowseActivity : Activity() {

    protected lateinit var settings: Settings
    private val adapter = RowAdapter()
    private lateinit var list: ListView

    /** Results of loads started before the newest one are dropped. */
    private var generation = 0
    private var running = 0

    private val backCallback: Any? =
        if (Build.VERSION.SDK_INT >= 33) OnBackInvokedCallback { navigateBack() } else null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browse)
        settings = Settings(this)
        applySystemBarInsets(findViewById(R.id.root))

        findViewById<View>(R.id.action_back).setOnClickListener { if (!navigateBack()) finish() }
        findViewById<View>(R.id.action_refresh).setOnClickListener { reload() }
        list = findViewById(R.id.list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> onRowClick(adapter.rows[position]) }
        list.setOnItemLongClickListener { _, _, position, _ -> onRowLongClick(adapter.rows[position]) }
    }

    /** Load the screen's content (again). */
    abstract fun reload()

    open fun onRowClick(row: Row) {}

    open fun onRowLongClick(row: Row): Boolean = false

    /** Whether back steps within the screen (e.g. up a directory) instead of closing it. */
    open val canNavigateBack: Boolean get() = false

    /** Step back within the screen; false when there is nothing to go back to. */
    open fun navigateBack(): Boolean = false

    /** Call after [canNavigateBack] may have changed. */
    protected fun updateBackHandling() {
        if (Build.VERSION.SDK_INT < 33) return
        val callback = backCallback as OnBackInvokedCallback
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
        if (canNavigateBack) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        }
    }

    @Deprecated("Back on Android 12 and older; newer versions use the OnBackInvokedCallback")
    override fun onBackPressed() {
        if (!navigateBack()) @Suppress("DEPRECATION") super.onBackPressed()
    }

    protected fun header(title: String, subtitle: String?) {
        findViewById<TextView>(R.id.title).text = title
        findViewById<TextView>(R.id.subtitle).apply {
            text = subtitle
            visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
    }

    /** Index of the first visible row, to come back to the same place later. */
    protected val scrollPosition: Int get() = list.firstVisiblePosition

    /**
     * Run [task] off the main thread and hand its result to [show]. Failures
     * replace the list with the error message.
     */
    protected fun <T> load(task: (BackrestClient) -> T, show: (T) -> Unit) {
        val gen = ++generation
        background(task, { if (gen == generation) show(it) }) { message ->
            if (gen == generation) showMessage(message, error = true)
        }
    }

    /** Run [task] off the main thread; [onError] gets a readable message. */
    protected fun <T> background(task: (BackrestClient) -> T, onResult: (T) -> Unit, onError: (String) -> Unit) {
        setBusy(+1)
        val context = applicationContext
        Thread {
            val result = runCatching { task(BackrestClient(Settings(context))) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                setBusy(-1)
                result.fold(onResult) { onError(errorText(it)) }
            }
        }.start()
    }

    /** Replace the list; [empty] is shown when there are no rows. */
    protected fun showRows(rows: List<Row>, empty: String, scrollTo: Int = 0) {
        adapter.rows = rows
        adapter.notifyDataSetChanged()
        list.setSelection(scrollTo.coerceIn(0, maxOf(rows.size - 1, 0)))
        if (rows.isEmpty()) showMessage(empty, error = false) else findViewById<View>(R.id.message).visibility = View.GONE
    }

    private fun showMessage(text: String, error: Boolean) {
        if (error) {
            adapter.rows = emptyList()
            adapter.notifyDataSetChanged()
        }
        findViewById<TextView>(R.id.message).apply {
            this.text = text
            setTextColor(getColor(if (error) R.color.status_error else R.color.text_secondary))
            visibility = View.VISIBLE
        }
    }

    private fun setBusy(delta: Int) {
        running += delta
        findViewById<View>(R.id.progress).visibility = if (running > 0) View.VISIBLE else View.INVISIBLE
    }

    private fun errorText(e: Throwable): String = when (e) {
        is SocketTimeoutException -> "Zeitüberschreitung – der Server hat nicht rechtzeitig geantwortet"
        is BackrestException -> e.message ?: "Fehler"
        else -> "Fehler: ${e.message ?: e.javaClass.simpleName}"
    }

    private inner class RowAdapter : BaseAdapter() {
        var rows: List<Row> = emptyList()

        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false)
            val row = rows[position]
            view.findViewById<ImageView>(R.id.row_icon).apply {
                setImageResource(row.icon)
                if (row.iconColor != null) setColorFilter(row.iconColor) else clearColorFilter()
            }
            view.findViewById<TextView>(R.id.row_title).text = row.title
            view.findViewById<TextView>(R.id.row_subtitle).setTextOrHide(row.subtitle)
            view.findViewById<TextView>(R.id.row_detail).apply {
                setTextOrHide(row.detail)
                setTextColor(row.detailColor ?: getColor(R.color.text_secondary))
            }
            view.findViewById<View>(R.id.row_chevron).visibility = if (row.opens) View.VISIBLE else View.GONE
            return view
        }
    }
}

private fun TextView.setTextOrHide(value: String?) {
    text = value
    visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE
}

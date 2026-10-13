package at.daschmidt.backrestwidget

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var settings: Settings
    private var refreshing = false
    private var resumed = false
    private val handler = Handler(Looper.getMainLooper())
    private val poll = Runnable { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)
        applySystemBarInsets(findViewById(R.id.root))

        findViewById<View>(R.id.action_refresh).setOnClickListener { refresh() }
        findViewById<View>(R.id.action_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.action_web).setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(settings.serverUrl))) }
        }

        if (Build.VERSION.SDK_INT >= 33 && settings.notifyOnFailure &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!settings.configured) {
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
            return
        }
        resumed = true
        Refresher.schedule(this)
        render()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        handler.removeCallbacks(poll)
    }

    private fun refresh() {
        if (refreshing) return
        refreshing = true
        findViewById<View>(R.id.progress).visibility = View.VISIBLE
        Thread {
            Refresher.refresh(applicationContext)
            runOnUiThread {
                refreshing = false
                if (!isDestroyed) {
                    findViewById<View>(R.id.progress).visibility = View.INVISIBLE
                    render()
                    // Keep polling while in front; closely while something runs.
                    handler.removeCallbacks(poll)
                    if (resumed) {
                        val busy = settings.snapshot?.running?.isNotEmpty() == true
                        handler.postDelayed(poll, if (busy) POLL_RUNNING_MS else POLL_IDLE_MS)
                    }
                }
            }
        }.start()
    }

    private fun render() {
        val snapshot = settings.snapshot
        val error = settings.lastError

        findViewById<ImageView>(R.id.status_dot).setColorFilter(
            Format.healthColor(this, snapshot?.health ?: Health.NONE)
        )
        findViewById<TextView>(R.id.status_title).text =
            snapshot?.let { Format.overall(it) } ?: getString(R.string.no_data)
        findViewById<TextView>(R.id.status_subtitle).text = buildString {
            append(settings.serverUrl)
            snapshot?.let { append("\nStand ${Format.dateTime(this@MainActivity, it.fetchedAtMs)}") }
        }
        findViewById<TextView>(R.id.status_error).apply {
            visibility = if (error != null) View.VISIBLE else View.GONE
            text = error
        }

        val inflater = LayoutInflater.from(this)
        val running = findViewById<LinearLayout>(R.id.running)
        running.removeAllViews()
        snapshot?.running?.forEach { running.addView(runningView(inflater, running, it)) }
        findViewById<View>(R.id.running_label).visibility =
            if (snapshot?.running.isNullOrEmpty()) View.GONE else View.VISIBLE

        val plans = findViewById<LinearLayout>(R.id.plans)
        plans.removeAllViews()
        snapshot?.plans?.forEach { plans.addView(planView(inflater, plans, it)) }
        findViewById<View>(R.id.plans_label).visibility = if (snapshot?.plans.isNullOrEmpty()) View.GONE else View.VISIBLE

        val repos = findViewById<LinearLayout>(R.id.repos)
        repos.removeAllViews()
        snapshot?.repos?.forEach { repo ->
            val view = inflater.inflate(R.layout.item_repo, repos, false)
            view.findViewById<TextView>(R.id.repo_name).text = repo.id
            view.findViewById<TextView>(R.id.repo_info).text = buildString {
                repo.protectedBytes?.let { append("${Format.bytes(it)} gesichert · ") }
                append("+${Format.bytes(repo.bytesAdded30d)} in 30 Tagen")
            }
            repos.addView(view)
        }
        findViewById<View>(R.id.repos_label).visibility = if (snapshot?.repos.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun planView(inflater: LayoutInflater, parent: LinearLayout, plan: PlanState): View {
        val view = inflater.inflate(R.layout.item_plan, parent, false)
        view.findViewById<ImageView>(R.id.plan_dot).setColorFilter(Format.healthColor(this, plan.health))
        view.findViewById<TextView>(R.id.plan_name).text = plan.id

        val last = plan.lastFinished
        view.findViewById<TextView>(R.id.plan_last).text = when {
            last == null -> "Letztes Backup: keines in den letzten 30 Tagen"
            else -> "Letztes Backup: ${Format.dateTime(this, last.startMs)} · ${Format.statusLabel(last.status)} · ${Format.duration(last.durationMs)}"
        }
        view.findViewById<TextView>(R.id.plan_next).text = when {
            plan.activeBackup != null ->
                listOfNotNull("Backup läuft gerade", Format.percent(plan.activeBackup)).joinToString(" · ")
            plan.running -> "Backup läuft gerade"
            plan.nextBackupMs != null -> "Nächstes Backup: ${Format.dateTime(this, plan.nextBackupMs)}"
            else -> "Kein Zeitplan"
        }
        view.findViewById<TextView>(R.id.plan_stats).text =
            "30 Tage: ${plan.success30d} ✓ · ${plan.warning30d} ⚠ · ${plan.failed30d} ✗ · +${Format.bytes(plan.bytesAdded30d)}"

        val history = view.findViewById<LinearLayout>(R.id.plan_history)
        val density = resources.displayMetrics.density
        plan.recent.takeLast(HISTORY_BARS).forEach { entry ->
            history.addView(View(this).apply {
                setBackgroundResource(R.drawable.history_bar)
                background.setTint(Format.statusColor(this@MainActivity, entry.status))
                layoutParams = LinearLayout.LayoutParams((6 * density).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
                    .apply { marginEnd = (3 * density).toInt() }
                contentDescription = "${Format.dateTime(this@MainActivity, entry.startMs)}: ${Format.statusLabel(entry.status)}"
            })
        }

        view.findViewById<Button>(R.id.plan_backup).apply {
            isEnabled = !plan.running
            setOnClickListener { confirmBackup(plan.id) }
        }
        return view
    }

    private fun runningView(inflater: LayoutInflater, parent: LinearLayout, op: RunningOp): View {
        val view = inflater.inflate(R.layout.item_running, parent, false)
        view.findViewById<TextView>(R.id.op_title).text = op.title
        view.findViewById<TextView>(R.id.op_percent).text = Format.percent(op) ?: ""
        view.findViewById<ProgressBar>(R.id.op_progress).apply {
            isIndeterminate = op.percent == null
            progress = ((op.percent ?: 0.0) * 1000).toInt()
        }
        view.findViewById<TextView>(R.id.op_amounts).apply {
            text = Format.amounts(op)
            visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }
        view.findViewById<TextView>(R.id.op_file).apply {
            text = op.currentFile?.let { "Aktuell: $it" }
            visibility = if (op.currentFile == null) View.GONE else View.VISIBLE
        }
        view.findViewById<TextView>(R.id.op_since).text =
            "Gestartet ${Format.dateTime(this, op.startMs)} · läuft seit ${Format.duration(System.currentTimeMillis() - op.startMs)}"
        view.findViewById<Button>(R.id.op_cancel).setOnClickListener { confirmCancel(op) }
        return view
    }

    private fun confirmCancel(op: RunningOp) {
        AlertDialog.Builder(this)
            .setTitle("Abbrechen?")
            .setMessage("„${op.title}“ wirklich abbrechen?")
            .setNegativeButton("Weiterlaufen lassen", null)
            .setPositiveButton("Abbrechen") { _, _ ->
                val context = applicationContext
                Thread {
                    val message = try {
                        BackrestClient(Settings(context)).cancel(op.id)
                        "Abbruch angefordert"
                    } catch (e: Exception) {
                        "Abbrechen fehlgeschlagen: ${e.message}"
                    }
                    runOnUiThread {
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        if (!isDestroyed) refresh()
                    }
                }.start()
            }
            .show()
    }

    private fun confirmBackup(planId: String) {
        AlertDialog.Builder(this)
            .setTitle("Backup starten?")
            .setMessage("Plan „$planId“ jetzt sichern.")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Starten") { _, _ -> startBackup(planId) }
            .show()
    }

    private fun startBackup(planId: String) {
        Toast.makeText(this, "Backup „$planId“ wird gestartet …", Toast.LENGTH_SHORT).show()
        val context = applicationContext
        Thread {
            val message = try {
                if (BackrestClient(Settings(context)).startBackup(planId)) "Backup „$planId“ abgeschlossen"
                else "Backup „$planId“ läuft"
            } catch (e: Exception) {
                "Backup fehlgeschlagen: ${e.message}"
            }
            runOnUiThread {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                if (!isDestroyed) refresh() else Refresher.refreshSoon(context)
            }
        }.start()
    }

    private companion object {
        const val HISTORY_BARS = 30
        const val POLL_RUNNING_MS = 3_000L
        const val POLL_IDLE_MS = 30_000L
    }
}

/** targetSdk 35+ draws edge-to-edge; keep content clear of the system bars. */
fun applySystemBarInsets(root: View) {
    val left = root.paddingLeft
    val top = root.paddingTop
    val right = root.paddingRight
    val bottom = root.paddingBottom
    root.setOnApplyWindowInsetsListener { v, insets ->
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
            v.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
        } else {
            @Suppress("DEPRECATION")
            v.setPadding(
                left + insets.systemWindowInsetLeft, top + insets.systemWindowInsetTop,
                right + insets.systemWindowInsetRight, bottom + insets.systemWindowInsetBottom
            )
        }
        insets
    }
}

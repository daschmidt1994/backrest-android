package at.daschmidt.backrestwidget

import android.content.Context
import android.os.Build
import android.text.format.DateFormat
import android.text.format.DateUtils
import java.util.Calendar
import java.util.Locale

object Format {
    fun relative(timeMs: Long, nowMs: Long = System.currentTimeMillis()): String =
        if (kotlin.math.abs(nowMs - timeMs) < DateUtils.MINUTE_IN_MILLIS) "gerade eben"
        else DateUtils.getRelativeTimeSpanString(timeMs, nowMs, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

    fun time(context: Context, timeMs: Long): String = DateFormat.getTimeFormat(context).format(timeMs)

    /** "heute 02:00", "morgen 02:00", "Mo., 14.10. 02:00" or, in another year, "Mo., 14.10.2025 02:00". */
    fun dayAndTime(context: Context, timeMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val days = dayIndex(timeMs) - dayIndex(nowMs)
        val day = when (days) {
            0L -> "heute"
            1L -> "morgen"
            -1L -> "gestern"
            else -> {
                val skeleton = if (dayIndex(timeMs) / 400 == dayIndex(nowMs) / 400) "EEEdM" else "EEEdMy"
                DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton), timeMs).toString()
            }
        }
        return "$day ${time(context, timeMs)}"
    }

    fun dateTime(context: Context, timeMs: Long): String = dayAndTime(context, timeMs)

    fun duration(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "${s} s"
            s < 3600 -> "${s / 60} min"
            else -> "${s / 3600} h ${(s % 3600) / 60} min"
        }
    }

    fun bytes(value: Long): String {
        if (value < 1024) return "$value B"
        val units = listOf("KiB", "MiB", "GiB", "TiB", "PiB")
        var v = value.toDouble() / 1024
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return String.format(Locale.getDefault(), if (v < 10) "%.1f %s" else "%.0f %s", v, units[i])
    }

    fun statusLabel(status: OpStatus): String = when (status) {
        OpStatus.SUCCESS -> "erfolgreich"
        OpStatus.WARNING -> "mit Warnungen"
        OpStatus.ERROR -> "fehlgeschlagen"
        OpStatus.CANCELLED -> "abgebrochen"
        OpStatus.RUNNING -> "läuft"
        OpStatus.PENDING -> "geplant"
        OpStatus.UNKNOWN -> "unbekannt"
    }

    fun healthColor(context: Context, health: Health): Int = context.getColor(
        when (health) {
            Health.OK -> R.color.status_ok
            Health.RUNNING -> R.color.status_running
            Health.WARNING -> R.color.status_warning
            Health.ERROR -> R.color.status_error
            Health.NONE -> R.color.status_none
        }
    )

    fun statusColor(context: Context, status: OpStatus): Int = healthColor(
        context, when (status) {
            OpStatus.SUCCESS -> Health.OK
            OpStatus.RUNNING, OpStatus.PENDING -> Health.RUNNING
            OpStatus.WARNING, OpStatus.CANCELLED -> Health.WARNING
            OpStatus.ERROR -> Health.ERROR
            OpStatus.UNKNOWN -> Health.NONE
        }
    )

    /** One-line summary of the server state for headers. */
    fun overall(snapshot: Snapshot): String {
        val failed = snapshot.plans.count { it.health == Health.ERROR }
        val warned = snapshot.plans.count { it.health == Health.WARNING }
        val running = snapshot.running
        return when {
            snapshot.plans.isEmpty() && running.isEmpty() -> "Keine Pläne"
            failed > 0 -> "$failed fehlgeschlagen"
            warned > 0 -> if (warned == 1) "1 Warnung" else "$warned Warnungen"
            running.size == 1 -> "${running[0].kind.label} läuft"
            running.size > 1 -> "${running.size} Aufgaben laufen"
            snapshot.plans.any { it.running } -> "Backup läuft"
            else -> "Alles OK"
        }
    }

    fun connectionLabel(state: String): String = when (state) {
        "connected" -> "verbunden"
        "disconnected" -> "getrennt"
        "retry_wait" -> "wartet auf Verbindung"
        "pending" -> "verbindet …"
        "error_auth" -> "Anmeldefehler"
        "error_protocol", "error_internal" -> "Verbindungsfehler"
        else -> "Status unbekannt"
    }

    /** "Version 0.4.0 (5)" from the installed package. */
    fun appVersion(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
        return context.getString(R.string.version, info.versionName ?: "?", code)
    }

    fun percent(op: RunningOp): String? = op.percent?.let { "${(it * 100).toInt()} %" }

    /** "656 MiB / 804 MiB · 200/201 Dateien", parts omitted when unknown. */
    fun amounts(op: RunningOp): String = listOfNotNull(
        if (op.totalBytes > 0) "${bytes(op.bytesDone)} / ${bytes(op.totalBytes)}" else null,
        if (op.totalFiles > 0) "${op.filesDone}/${op.totalFiles} Dateien" else null,
    ).joinToString(" · ")

    /** First 8 characters of a snapshot ID, as restic shows them. */
    fun shortId(id: String): String = id.take(8)

    fun firstLine(text: String): String = text.lineSequence().first().let { if (it.length > 200) it.take(200) + " …" else it }

    fun fileName(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

    private fun dayIndex(timeMs: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = timeMs }
        return c.get(Calendar.YEAR) * 400L + c.get(Calendar.DAY_OF_YEAR)
    }
}

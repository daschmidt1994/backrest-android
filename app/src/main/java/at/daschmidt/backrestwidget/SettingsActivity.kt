package at.daschmidt.backrestwidget

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

class SettingsActivity : Activity() {

    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = Settings(this)
        applySystemBarInsets(findViewById(R.id.root))

        val url = findViewById<EditText>(R.id.input_url)
        val user = findViewById<EditText>(R.id.input_user)
        val password = findViewById<EditText>(R.id.input_password)
        val interval = findViewById<EditText>(R.id.input_interval)
        val threshold = findViewById<EditText>(R.id.input_unreachable_threshold)
        val result = findViewById<TextView>(R.id.result)
        val save = findViewById<Button>(R.id.save)
        val alertSwitches = addAlertSwitches(findViewById(R.id.alerts))
        findViewById<TextView>(R.id.version).text = Format.appVersion(this)

        url.setText(settings.serverUrl)
        user.setText(settings.username)
        if (settings.password.isNotEmpty()) password.hint = getString(R.string.password_saved)
        interval.setText(settings.intervalMinutes.toString())
        threshold.setText(settings.unreachableThreshold.toString())

        findViewById<Button>(R.id.notify_test).setOnClickListener {
            saveAlerts(alertSwitches, threshold)
            requestNotificationPermission()
            val sent = Notifier.sendTest(this, settings)
            result.visibility = View.VISIBLE
            result.text = if (sent > 0) getString(R.string.notify_test_sent, sent) else getString(R.string.notify_test_none)
        }
        findViewById<Button>(R.id.notify_system).setOnClickListener {
            startActivity(
                Intent(SystemSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(SystemSettings.EXTRA_APP_PACKAGE, packageName)
            )
        }

        save.setOnClickListener {
            var newUrl = url.text.toString().trim()
            if (newUrl.isEmpty()) {
                url.error = getString(R.string.url_required)
                return@setOnClickListener
            }
            if (!newUrl.startsWith("http://") && !newUrl.startsWith("https://")) newUrl = "http://$newUrl"
            val newPassword = password.text.toString()
            val credentialsChanged = newUrl.trimEnd('/') != settings.serverUrl ||
                user.text.toString().trim() != settings.username || newPassword.isNotEmpty()

            settings.serverUrl = newUrl
            settings.username = user.text.toString()
            if (newPassword.isNotEmpty()) settings.password = newPassword
            if (user.text.isBlank()) settings.password = ""
            if (credentialsChanged) settings.token = null
            settings.intervalMinutes = interval.text.toString().toIntOrNull() ?: 30
            saveAlerts(alertSwitches, threshold)
            if (settings.anyAlertEnabled) requestNotificationPermission()
            url.setText(settings.serverUrl)
            interval.setText(settings.intervalMinutes.toString())
            Refresher.schedule(this)

            save.isEnabled = false
            result.visibility = View.VISIBLE
            result.text = getString(R.string.testing)
            Thread {
                val outcome = Refresher.refresh(applicationContext)
                runOnUiThread {
                    save.isEnabled = true
                    outcome.onSuccess {
                        result.text = getString(R.string.test_ok, it.plans.size)
                        if (!isDestroyed) {
                            startActivity(
                                Intent(this, MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            )
                            finish()
                        }
                    }.onFailure {
                        result.text = getString(R.string.test_failed, it.message)
                    }
                }
            }.start()
        }
    }

    private fun addAlertSwitches(container: LinearLayout): Map<Alert, Switch> = Alert.entries.associateWith { alert ->
        Switch(this).apply {
            setText(alert.settingLabel)
            setTextColor(getColor(R.color.text_primary))
            textSize = 15f
            setPadding(0, 6, 0, 6)
            isChecked = settings.alertEnabled(alert)
            container.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun saveAlerts(switches: Map<Alert, Switch>, threshold: EditText) {
        switches.forEach { (alert, switch) -> settings.setAlertEnabled(alert, switch.isChecked) }
        settings.unreachableThreshold = threshold.text.toString().toIntOrNull() ?: 3
        threshold.setText(settings.unreachableThreshold.toString())
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !Notifier.canPost(this)) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}

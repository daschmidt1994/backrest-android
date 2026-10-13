package at.daschmidt.backrestwidget

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
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
        val notify = findViewById<Switch>(R.id.input_notify)
        val result = findViewById<TextView>(R.id.result)
        val save = findViewById<Button>(R.id.save)

        url.setText(settings.serverUrl)
        user.setText(settings.username)
        if (settings.password.isNotEmpty()) password.hint = getString(R.string.password_saved)
        interval.setText(settings.intervalMinutes.toString())
        notify.isChecked = settings.notifyOnFailure

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
            settings.notifyOnFailure = notify.isChecked
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
}

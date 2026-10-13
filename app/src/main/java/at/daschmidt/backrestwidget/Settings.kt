package at.daschmidt.backrestwidget

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Connection settings and cached state. The password never leaves the device unencrypted at rest. */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("backrest", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_URL, value.trim().trimEnd('/')).apply()

    var username: String
        get() = prefs.getString(KEY_USER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USER, value.trim()).apply()

    var password: String
        get() = prefs.getString(KEY_PASSWORD, null)?.let { runCatching { decrypt(it) }.getOrNull() } ?: ""
        set(value) = prefs.edit().apply {
            if (value.isEmpty()) remove(KEY_PASSWORD) else putString(KEY_PASSWORD, encrypt(value))
        }.apply()

    /** Session JWT from v1.Authentication/Login; dropped whenever credentials change. */
    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    /** Refresh interval of the widget in minutes (WorkManager minimum is 15). */
    var intervalMinutes: Int
        get() = prefs.getInt(KEY_INTERVAL, 30)
        set(value) = prefs.edit().putInt(KEY_INTERVAL, value.coerceAtLeast(15)).apply()

    var notifyOnFailure: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY, value).apply()

    val configured: Boolean get() = serverUrl.isNotEmpty()

    var snapshot: Snapshot?
        get() = prefs.getString(KEY_SNAPSHOT, null)?.let { runCatching { Snapshot.fromJson(it) }.getOrNull() }
        set(value) = prefs.edit().putString(KEY_SNAPSHOT, value?.toJson()).apply()

    /** Error of the most recent refresh, null when it succeeded. */
    var lastError: String?
        get() = prefs.getString(KEY_ERROR, null)
        set(value) = prefs.edit().putString(KEY_ERROR, value).apply()

    var lastAttemptMs: Long
        get() = prefs.getLong(KEY_ATTEMPT, 0)
        set(value) = prefs.edit().putLong(KEY_ATTEMPT, value).apply()

    /** Newest failed backup start per plan that was already notified, to avoid repeats. */
    fun notifiedFailure(planId: String): Long = prefs.getLong("$KEY_NOTIFIED$planId", 0)
    fun setNotifiedFailure(planId: String, startMs: Long) = prefs.edit().putLong("$KEY_NOTIFIED$planId", startMs).apply()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, IV_LENGTH))
        }
        return String(cipher.doFinal(data, IV_LENGTH, data.size - IV_LENGTH))
    }

    private companion object {
        const val KEY_URL = "url"
        const val KEY_USER = "user"
        const val KEY_PASSWORD = "password"
        const val KEY_TOKEN = "token"
        const val KEY_INTERVAL = "interval"
        const val KEY_NOTIFY = "notify"
        const val KEY_SNAPSHOT = "snapshot"
        const val KEY_ERROR = "error"
        const val KEY_ATTEMPT = "attempt"
        const val KEY_NOTIFIED = "notified_"
        const val KEY_ALIAS = "backrest_password"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
    }
}

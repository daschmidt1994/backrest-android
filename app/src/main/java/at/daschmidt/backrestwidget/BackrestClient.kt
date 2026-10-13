package at.daschmidt.backrestwidget

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.SSLException

open class BackrestException(message: String) : Exception(message)
class AuthException(message: String) : BackrestException(message)

/**
 * Minimal client for Backrest's Connect RPC API: unary calls are JSON POSTs to
 * /<service>/<method>. Must not be called on the main thread.
 */
class BackrestClient(private val settings: Settings) {

    private val base: String get() = settings.serverUrl

    fun login() {
        if (settings.username.isEmpty()) throw AuthException("Server verlangt Anmeldung – Benutzer fehlt")
        val result = post("v1.Authentication", "Login", JSONObject().apply {
            put("username", settings.username)
            put("password", settings.password)
        }, authenticated = false)
        val token = result.optString("token")
        if (token.isEmpty()) throw BackrestException("Login-Antwort ohne Token")
        settings.token = token
    }

    fun summaryDashboard(): JSONObject = call("GetSummaryDashboard", JSONObject())

    /**
     * Start a backup of [planId]. Backrest only answers once the backup has
     * finished, but its handler does not watch the request context, so the
     * backup keeps running after we give up waiting. A timeout therefore means
     * "started"; quick errors (unknown plan, auth) still surface.
     */
    fun startBackup(planId: String): Boolean = try {
        call("Backup", JSONObject().put("value", planId), readTimeoutMs = BACKUP_CONFIRM_MS)
        true // finished within the confirmation window
    } catch (e: SocketTimeoutException) {
        false
    }

    private fun call(method: String, body: JSONObject, readTimeoutMs: Int = READ_TIMEOUT_MS): JSONObject {
        if (settings.token == null && settings.username.isNotEmpty()) login()
        return try {
            post("v1.Backrest", method, body, readTimeoutMs = readTimeoutMs)
        } catch (e: AuthException) {
            if (settings.username.isEmpty()) throw e
            settings.token = null
            login()
            post("v1.Backrest", method, body, readTimeoutMs = readTimeoutMs)
        }
    }

    private fun post(
        service: String,
        method: String,
        body: JSONObject,
        authenticated: Boolean = true,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): JSONObject {
        if (base.isEmpty()) throw BackrestException("Kein Server konfiguriert")
        val conn = try {
            URL("$base/$service/$method").openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw BackrestException("Ungültige Server-URL")
        }
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = false
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Connect-Protocol-Version", "1")
            if (authenticated) settings.token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (status != 200) throw errorFrom(status, text)
            return try {
                if (text.isBlank()) JSONObject() else JSONObject(text)
            } catch (e: Exception) {
                throw BackrestException("Keine JSON-Antwort – ist das ein Backrest-Server?")
            }
        } catch (e: SocketTimeoutException) {
            throw e
        } catch (e: SSLException) {
            throw BackrestException("TLS-Fehler: ${e.message}")
        } catch (e: IOException) {
            throw BackrestException("Server nicht erreichbar (${e.javaClass.simpleName})")
        } finally {
            conn.disconnect()
        }
    }

    private fun errorFrom(status: Int, text: String): BackrestException {
        val json = runCatching { JSONObject(text) }.getOrNull()
        val code = json?.optString("code").orEmpty()
        val message = json?.optString("message")?.ifEmpty { null } ?: text.trim().take(200)
        return when {
            status == 401 || code == "unauthenticated" -> AuthException("Anmeldung fehlgeschlagen: $message")
            status == 403 || code == "permission_denied" -> AuthException("Keine Berechtigung: $message")
            status in 300..399 -> BackrestException("Weiterleitung (HTTP $status) – URL prüfen (http/https?)")
            code.isNotEmpty() -> BackrestException("$code: $message")
            else -> BackrestException("HTTP $status")
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000
        const val BACKUP_CONFIRM_MS = 10_000
    }
}

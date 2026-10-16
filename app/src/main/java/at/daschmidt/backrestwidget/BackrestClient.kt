package at.daschmidt.backrestwidget

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
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

    /** Operations of one plan of a remote (multihost client) instance. */
    fun planOperations(instance: String, planId: String, lastN: Int = 60): JSONObject =
        call(
            "GetOperations",
            JSONObject()
                .put("selector", JSONObject().put("instanceId", instance).put("planId", planId))
                .put("lastN", lastN.toString()),
        )

    /**
     * Peer states of a multihost server (its client instances). Empty when the
     * server has no multihost sync. The messages include each peer's remote
     * config with repository passwords – callers must keep only what they need.
     *
     * Backrest ends a stream requested without subscription by cancelling it
     * while peer states may still be queued, so peers go missing at random.
     * The stream is therefore subscribed and read until it stays quiet.
     */
    fun peerStates(): List<JSONObject> {
        if (settings.token == null && settings.username.isNotEmpty()) login()
        return try {
            streamPeerStates()
        } catch (e: AuthException) {
            if (settings.username.isEmpty()) throw e
            settings.token = null
            login()
            streamPeerStates()
        }
    }

    private fun streamPeerStates(): List<JSONObject> {
        val conn = try {
            URL("$base/v1sync.BackrestSyncStateService/GetPeerSyncStatesStream").openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw BackrestException("Ungültige Server-URL")
        }
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            // The subscribed stream never ends by itself: a quiet stream means
            // the initial burst of peer states is complete.
            conn.readTimeout = PEER_IDLE_MS
            conn.instanceFollowRedirects = false
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/connect+json")
            conn.setRequestProperty("Connect-Protocol-Version", "1")
            settings.token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            conn.outputStream.use { it.write(envelope(JSONObject().put("subscribe", true))) }

            val status = conn.responseCode
            if (status == 404) return emptyList() // Backrest without multihost sync
            if (status != 200) {
                throw errorFrom(status, conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "")
            }
            // Keyed by peer: a peer is sent again when its state changes.
            val peers = linkedMapOf<String, JSONObject>()
            DataInputStream(conn.inputStream.buffered()).use { input ->
                while (true) {
                    val flags = try {
                        input.readUnsignedByte()
                    } catch (e: EOFException) {
                        break
                    } catch (e: SocketTimeoutException) {
                        if (peers.isEmpty()) throw BackrestException("Keine Antwort zu Remote-Instanzen")
                        break
                    }
                    val length = input.readInt()
                    if (length < 0 || length > MAX_FRAME) throw BackrestException("Ungültige Antwort (Frame)")
                    val bytes = ByteArray(length).also { input.readFully(it) }
                    val message = if (length == 0) JSONObject() else JSONObject(String(bytes))
                    if (flags and END_STREAM != 0) {
                        val error = message.optJSONObject("error")
                        // Without a subscription the server ends the stream by cancelling it.
                        when (val code = error?.optString("code").orEmpty()) {
                            "", "canceled" -> {}
                            "unimplemented" -> return emptyList()
                            "unauthenticated", "permission_denied" -> throw AuthException(code)
                            else -> throw BackrestException("$code: ${error?.optString("message")}")
                        }
                        break
                    }
                    peers[message.optString("peerKeyid").ifEmpty { message.optString("peerInstanceId") }] = message
                }
            }
            return peers.values.toList()
        } catch (e: SocketTimeoutException) {
            throw BackrestException("Zeitüberschreitung")
        } catch (e: SSLException) {
            throw BackrestException("TLS-Fehler: ${e.message}")
        } catch (e: IOException) {
            throw BackrestException("Server nicht erreichbar (${e.javaClass.simpleName})")
        } finally {
            conn.disconnect()
        }
    }

    private fun envelope(message: JSONObject): ByteArray {
        val body = message.toString().toByteArray()
        return ByteArrayOutputStream().apply {
            write(0)
            write(body.size ushr 24); write(body.size ushr 16); write(body.size ushr 8); write(body.size)
            write(body)
        }.toByteArray()
    }

    /** The newest [lastN] operations of all plans and repositories (empty selector). */
    fun recentOperations(lastN: Int = 100): JSONObject =
        call("GetOperations", JSONObject().put("selector", JSONObject()).put("lastN", lastN.toString()))

    /**
     * The newest [lastN] operations of a plan (all kinds; callers filter the
     * backups), or of all plans when [planId] is null. [instance] selects a
     * remote instance's plan.
     */
    fun history(planId: String?, instance: String?, lastN: Int): JSONObject {
        val selector = JSONObject()
        planId?.let { selector.put("planId", it) }
        instance?.let { selector.put("instanceId", it) }
        return call("GetOperations", JSONObject().put("selector", selector).put("lastN", lastN.toString()))
    }

    /** All snapshots in a repository. Backrest asks restic, which can take a while for remote repos. */
    fun snapshots(repoId: String): JSONObject =
        call("ListSnapshots", JSONObject().put("repoId", repoId), readTimeoutMs = RESTIC_TIMEOUT_MS)

    /** Contents of directory [path] in a snapshot. */
    fun listFiles(repoId: String, snapshotId: String, path: String): JSONObject =
        call(
            "ListSnapshotFiles",
            JSONObject().put("repoId", repoId).put("snapshotId", snapshotId).put("path", path),
            readTimeoutMs = RESTIC_TIMEOUT_MS,
        )

    /** Operations belonging to a snapshot, including the index operation needed for downloads. */
    fun snapshotOperations(snapshotId: String): JSONObject =
        call("GetOperations", JSONObject().put("selector", JSONObject().put("snapshotId", snapshotId)))

    /**
     * A signed URL that downloads [path] from the snapshot of index operation
     * [indexOpId] (a directory arrives as tar). It needs no login, so a browser
     * can open it.
     */
    fun downloadUrl(indexOpId: Long, path: String): String {
        val relative = call(
            "GetDownloadURL",
            JSONObject().put("opId", indexOpId.toString()).put("filePath", path),
        ).optString("value")
        if (relative.isEmpty()) throw BackrestException("Server lieferte keinen Download-Link")
        return "$base/${relative.removePrefix("./").removePrefix("/")}"
    }

    /** Ask Backrest to cancel a running operation. Not guaranteed to succeed immediately. */
    fun cancel(operationId: Long) {
        call("Cancel", JSONObject().put("operationId", operationId.toString()))
    }

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
        const val RESTIC_TIMEOUT_MS = 90_000
        const val END_STREAM = 0x02
        const val PEER_IDLE_MS = 1_500
        const val MAX_FRAME = 32 * 1024 * 1024
    }
}

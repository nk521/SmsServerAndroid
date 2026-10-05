package dev.nkmason.smsserverandroid

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

const val DEFAULT_PORT = 8080

/** One attempted SMS send. Newest entries are appended at the end. */
data class LogEntry(
    val timeText: String,
    val to: String,
    val message: String,
    val ok: Boolean,
    val detail: String
)

data class ServerState(
    val running: Boolean = false,
    val port: Int = DEFAULT_PORT,
    val error: String? = null,
    val smsGranted: Boolean = false,
    val sentOk: Int = 0,
    val sentFailed: Int = 0,
    val log: List<LogEntry> = emptyList()
)

object ServerManager {
    private const val PREFS = "sms_server_prefs"
    private const val KEY_PORT = "port"
    private const val KEY_TOKEN = "token"
    private const val MAX_LOG = 100

    private val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var server: HttpApiServer? = null
    private val _state = MutableStateFlow(ServerState())
    val state: StateFlow<ServerState> = _state.asStateFlow()

    fun savedPort(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_PORT, DEFAULT_PORT)

    fun savedToken(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKEN, "").orEmpty()

    fun savePort(context: Context, port: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_PORT, port).apply()
    }

    fun saveToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKEN, token).apply()
    }

    fun smsGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
                PackageManager.PERMISSION_GRANTED

    fun updateSmsGranted(granted: Boolean) {
        _state.value = _state.value.copy(smsGranted = granted)
    }

    @Synchronized
    fun start(context: Context, port: Int, token: String) {
        stopInternal()
        val appContext = context.applicationContext
        val newServer = HttpApiServer(port) { method, path, body, auth, apiKey ->
            ServerManager.handleRequest(appContext, token, method, path, body, auth, apiKey)
        }
        try {
            newServer.start()
        } catch (e: Exception) {
            server = null
            _state.value = _state.value.copy(
                running = false,
                port = port,
                error = "Could not open port $port: ${e.message ?: e.javaClass.simpleName}"
            )
            return
        }
        server = newServer
        _state.value = _state.value.copy(
            running = true,
            port = port,
            error = null,
            smsGranted = smsGranted(appContext)
        )
    }

    @Synchronized
    fun stop() {
        stopInternal()
        _state.value = _state.value.copy(running = false, error = null)
    }

    private fun stopInternal() {
        server?.stop()
        server = null
    }

    fun localIpAddress(): String? {
        return try {
            for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in Collections.list(ni.inetAddresses)) {
                    if (ia is Inet4Address && !ia.isLoopbackAddress) return ia.hostAddress
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /** Called from the HTTP handler thread. */
    private fun handleRequest(
        context: Context,
        token: String,
        method: String,
        path: String,
        body: String,
        auth: String?,
        apiKey: String?
    ): Pair<Int, JSONObject> {
        if (method == "OPTIONS") return 200 to JSONObject().put("ok", true)
        if (!authorized(token, auth, apiKey)) {
            return 401 to errorJson("Unauthorized: set a valid Authorization: Bearer <token> or X-API-Key header")
        }
        return when (path) {
            "/", "" -> 200 to apiInfoJson()
            "/status" ->
                if (method == "GET") 200 to statusJson(context)
                else 405 to errorJson("Use GET for /status")
            "/log" ->
                if (method == "GET") 200 to logJson()
                else 405 to errorJson("Use GET for /log")
            "/send" ->
                if (method == "POST") sendSms(context, body)
                else 405 to errorJson("Use POST with a JSON body for /send")
            else -> 404 to errorJson("Unknown endpoint. GET / lists endpoints.")
        }
    }

    private fun authorized(token: String, auth: String?, apiKey: String?): Boolean {
        if (token.isEmpty()) return true
        return auth.equals("Bearer $token", ignoreCase = true) ||
                auth == token ||
                apiKey == token
    }

    private fun sendSms(context: Context, body: String): Pair<Int, JSONObject> {
        if (!smsGranted(context)) {
            return 403 to errorJson("SEND_SMS permission not granted on the device")
        }
        if (body.isBlank()) {
            return 400 to errorJson("JSON body required: {\"to\": \"+1234567890\", \"message\": \"Hello\"}")
        }
        val json = try {
            JSONObject(body)
        } catch (_: Exception) {
            return 400 to errorJson("Invalid JSON")
        }
        val to = json.optString("to").trim()
        val message = json.optString("message")
        if (to.isEmpty() || message.isEmpty()) {
            return 400 to errorJson("Fields \"to\" and \"message\" are required")
        }
        return try {
            val sms = smsManager(context)
            val parts = sms.divideMessage(message)
            if (parts.size <= 1) {
                sms.sendTextMessage(to, null, message, null, null)
            } else {
                sms.sendMultipartTextMessage(to, null, parts, null, null)
            }
            recordResult(to, message, true, "queued")
            200 to JSONObject().put("queued", true).put("to", to)
        } catch (e: SecurityException) {
            recordResult(to, message, false, e.message ?: "SecurityException")
            403 to errorJson("Failed to send: ${e.message ?: "permission denied"}")
        } catch (e: Exception) {
            recordResult(to, message, false, e.message ?: e.javaClass.simpleName)
            500 to errorJson("Failed to send: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun smsManager(context: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

    private fun recordResult(to: String, message: String, ok: Boolean, detail: String) {
        val entry = LogEntry(formatter.format(Date()), to, message, ok, detail)
        synchronized(this) {
            val s = _state.value
            val list = ArrayList(s.log)
            list.add(entry)
            while (list.size > MAX_LOG) list.removeAt(0)
            _state.value = s.copy(
                sentOk = s.sentOk + if (ok) 1 else 0,
                sentFailed = s.sentFailed + if (ok) 0 else 1,
                log = list
            )
        }
    }

    private fun statusJson(context: Context): JSONObject {
        val ip = localIpAddress() ?: "localhost"
        val s = _state.value
        return JSONObject()
            .put("running", s.running)
            .put("port", s.port)
            .put("device_ip", ip)
            .put("sms_permission", s.smsGranted)
            .put("sent", s.sentOk)
            .put("failed", s.sentFailed)
    }

    private fun logJson(): JSONObject {
        val arr = JSONArray()
        val s = _state.value
        for (i in s.log.indices.reversed()) {
            val e = s.log[i]
            arr.put(
                JSONObject()
                    .put("time", e.timeText)
                    .put("to", e.to)
                    .put("message", e.message)
                    .put("ok", e.ok)
                    .put("detail", e.detail)
            )
        }
        return JSONObject().put("entries", arr)
    }

    private fun apiInfoJson(): JSONObject = JSONObject()
        .put("name", "SMS Server")
        .put(
            "endpoints",
            JSONArray()
                .put("GET /status")
                .put("GET /log")
                .put("POST /send {\"to\": \"+1234567890\", \"message\": \"Hello\"}")
        )

    private fun errorJson(message: String): JSONObject = JSONObject().put("error", message)
}

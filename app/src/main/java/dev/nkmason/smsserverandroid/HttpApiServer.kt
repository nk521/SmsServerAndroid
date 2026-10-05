package dev.nkmason.smsserverandroid

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private typealias HttpHandler =
            (method: String, path: String, body: String, auth: String?, apiKey: String?) -> Pair<Int, JSONObject>

/**
 * Minimal HTTP/1.1 server on a raw ServerSocket: one thread for accepting,
 * a pooled thread per connection. Responses always close the connection.
 */
class HttpApiServer(
    private val port: Int,
    private val handler: HttpHandler
) {
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r).apply { isDaemon = true; name = "sms-http-conn" }
    }
    private val running = AtomicBoolean(false)

    fun start() {
        val socket = ServerSocket()
        socket.bind(InetSocketAddress(port)) // throws if the port is taken
        serverSocket = socket
        running.set(true)
        pool.execute { acceptLoop() }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        pool.shutdownNow()
    }

    private fun acceptLoop() {
        while (running.get()) {
            val client = try {
                serverSocket?.accept() ?: return
            } catch (_: SocketException) {
                return
            } catch (_: Exception) {
                continue
            }
            pool.execute { handleClient(client) }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 8000
            val reader = BufferedReader(InputStreamReader(client.inputStream, Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase(Locale.US)
            val path = parts[1].substringBefore('?')

            var contentLength = 0
            var auth: String? = null
            var apiKey: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) {
                    val name = line.substring(0, i).trim().lowercase(Locale.US)
                    val value = line.substring(i + 1).trim()
                    when (name) {
                        "content-length" -> contentLength = value.toIntOrNull() ?: 0
                        "authorization" -> auth = value
                        "x-api-key" -> apiKey = value
                    }
                }
            }

            var body = ""
            if (contentLength > 0) {
                if (contentLength > MAX_BODY) {
                    respond(client, 400, JSONObject().put("error", "Body too large"))
                    return
                }
                val chars = CharArray(contentLength)
                var off = 0
                while (off < contentLength) {
                    val n = reader.read(chars, off, contentLength - off)
                    if (n < 0) break
                    off += n
                }
                body = String(chars, 0, off)
            }

            val (code, json) = try {
                handler(method, path, body, auth, apiKey)
            } catch (e: Exception) {
                500 to JSONObject().put("error", "Internal error: ${e.message ?: e.javaClass.simpleName}")
            }
            respond(client, code, json)
        } catch (_: Exception) {
            // client hung up mid-request; nothing to answer to
        } finally {
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun respond(client: Socket, code: Int, json: JSONObject) {
        val body = json.toString().toByteArray(Charsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            500 -> "Internal Server Error"
            else -> "OK"
        }
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type, Authorization, X-API-Key\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        client.getOutputStream().use { out ->
            out.write(head.toByteArray(Charsets.US_ASCII))
            out.write(body)
            out.flush()
        }
    }

    companion object {
        private const val MAX_BODY = 65536
    }
}

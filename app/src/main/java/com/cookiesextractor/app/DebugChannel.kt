package com.cookiesextractor.app

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.ArrayDeque

/**
 * The debug channel's embedded HTTP server (COK-10): one accept thread, one connection at a
 * time, GET-only, no dependencies. Deliberately pure JVM (no android imports) so the pieces
 * that can be unit-tested stay testable; the routing lives with the activity. Security
 * shape: the channel is off unless the user enables it in-app, every request must carry the
 * per-enable random token, and the connection answers and closes.
 */
class DebugChannelServer(
    private val port: Int,
    val token: String,
    private val bindHost: String? = null,
    private val handler: (DebugHttp.Request) -> Response,
) {

    data class Response(
        val status: Int,
        val reason: String,
        val contentType: String,
        val body: ByteArray,
    ) {
        companion object {
            fun text(status: Int, reason: String, body: String) =
                Response(status, reason, "text/plain; charset=utf-8", body.toByteArray(Charsets.UTF_8))

            fun json(body: String) =
                Response(200, "OK", "application/json; charset=utf-8", body.toByteArray(Charsets.UTF_8))

            fun ok(body: String) = text(200, "OK", body)
        }
    }

    /** Bounded memory of page console lines; see MainActivity's WebChromeClient. */
    class ConsoleRing(private val capacity: Int) {
        private val lines = ArrayDeque<String>()

        @Synchronized
        fun add(line: String) {
            lines.addLast(if (line.length > MAX_LINE_CHARS) line.take(MAX_LINE_CHARS) + "…" else line)
            while (lines.size > capacity) lines.removeFirst()
        }

        @Synchronized
        fun latest(n: Int): List<String> = lines.toList().takeLast(n.coerceAtLeast(0))
    }

    val console = ConsoleRing(MAX_CONSOLE_LINES)

    @Volatile private var serverSocket: ServerSocket? = null

    /** The port actually bound, for display after start(); 0 before. */
    val boundPort: Int get() = serverSocket?.localPort ?: 0

    /**
     * Starts serving. Throws [IOException] when the port is taken; the caller surfaces it.
     * Idempotent: a second start on a live server is a no-op.
     */
    @Synchronized
    fun start() {
        if (serverSocket != null) return
        // Bound to the LAN address when the caller resolved one, so the socket is not also
        // exposed on every other interface (cellular included); null binds wildcard.
        val socket = ServerSocket(port, 50, bindHost?.let { InetAddress.getByName(it) })
        serverSocket = socket
        Thread({
            while (!socket.isClosed) {
                try {
                    socket.accept().use { connection -> serve(connection) }
                } catch (e: IOException) {
                    if (socket.isClosed) break // stop() closes the socket to unwind the loop
                } catch (e: Exception) {
                    if (socket.isClosed) break
                    // one broken connection must not kill the channel
                }
            }
        }, "debug-channel").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        serverSocket?.close()
        serverSocket = null
    }

    /** True iff the request carries the token as a bearer header or a `token` query param. */
    fun authorized(request: DebugHttp.Request): Boolean {
        val presented = request.headers["authorization"]
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)?.trim()
            ?: request.query["token"]
            ?: return false
        return MessageDigest.isEqual(
            presented.toByteArray(Charsets.UTF_8),
            token.toByteArray(Charsets.UTF_8),
        )
    }

    private fun serve(connection: Socket) {
        connection.soTimeout = 10_000
        val head = readHead(connection) ?: return
        val request = DebugHttp.parse(head) ?: run {
            write(connection, Response.text(400, "Bad Request", "not an HTTP/1.x GET\r\n"))
            return
        }
        if (!authorized(request)) {
            write(connection, Response.text(401, "Unauthorized", "bad or missing token\r\n"))
            return
        }
        write(connection, try {
            handler(request)
        } catch (e: Exception) {
            Response.text(500, "Internal Server Error", e.javaClass.simpleName + "\r\n")
        })
    }

    /** Reads until the end of the request head; null on EOF/timeout before a full head. */
    private fun readHead(connection: Socket): String? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        val head = connection.getInputStream()
        while (out.size() < MAX_HEAD_BYTES) {
            val n = try {
                head.read(buffer)
            } catch (e: IOException) {
                return null
            }
            if (n <= 0) return null
            out.write(buffer, 0, n)
            if (out.toString(Charsets.ISO_8859_1.name()).contains("\r\n\r\n")) break
        }
        return out.toString(Charsets.ISO_8859_1.name())
    }

    private fun write(connection: Socket, response: Response) {
        val head = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ').append(response.reason).append("\r\n")
            append("Content-Type: ").append(response.contentType).append("\r\n")
            append("Content-Length: ").append(response.body.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.ISO_8859_1)
        val output = connection.getOutputStream()
        output.write(head)
        output.write(response.body)
        output.flush()
    }

    companion object {
        private const val MAX_HEAD_BYTES = 16 * 1024
        private const val MAX_LINE_CHARS = 4 * 1024

        /** Cap of the console ring; /console clamps its n to the same bound. */
        const val MAX_CONSOLE_LINES = 300
    }
}

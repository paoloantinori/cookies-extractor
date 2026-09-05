package com.cookiesextractor.app

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-socket integration tests for the debug channel server: plain JVM, ephemeral port.
 * The routing/UI bridge lives in MainActivity and needs a device; everything below the
 * handler boundary is exercised here for real.
 */
class DebugChannelServerTest {

    private fun withServer(handler: (DebugHttp.Request) -> DebugChannelServer.Response, block: (Int) -> Unit) {
        val server = DebugChannelServer(0, "test-token", null, handler)
        server.start()
        try {
            block(server.boundPort)
        } finally {
            server.stop()
        }
    }

    private fun exchange(port: Int, request: String): Pair<String, String> {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 3000)
            socket.soTimeout = 3000
            socket.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
            socket.getOutputStream().flush()
            socket.shutdownOutput()
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
            val statusLine = reader.readLine() ?: ""
            val body = StringBuilder()
            var line: String?
            var inBody = false
            while (true) {
                line = reader.readLine() ?: break
                if (inBody) body.append(line)
                if (line.isEmpty()) inBody = true
            }
            return statusLine to body.toString()
        }
    }

    @Test
    fun authorizedRequestReachesHandlerAndReturnsBody() {
        withServer({ DebugChannelServer.Response.text(200, "OK", "hello\r\n") }) { port ->
            val (status, body) = exchange(
                port,
                "GET /status HTTP/1.1\r\nAuthorization: Bearer test-token\r\n\r\n",
            )
            assertTrue(status.startsWith("HTTP/1.1 200"))
            assertEquals("hello", body.trim())
        }
    }

    @Test
    fun tokenInQueryAlsoAuthorizes() {
        withServer({ DebugChannelServer.Response.text(200, "OK", "ok") }) { port ->
            val (status, _) = exchange(port, "GET /x?token=test-token HTTP/1.1\r\n\r\n")
            assertTrue(status.startsWith("HTTP/1.1 200"))
        }
    }

    @Test
    fun bearerPrefixIsCaseInsensitiveAtTheSocket() {
        withServer({ DebugChannelServer.Response.ok("x") }) { port ->
            val (status, _) = exchange(port, "GET / HTTP/1.1\r\nAuthorization: bearer test-token\r\n\r\n")
            assertTrue(status.startsWith("HTTP/1.1 200"))
        }
    }

    @Test
    fun secondRequestOnTheSameServerIsServed() {
        var count = 0
        withServer({ count++; DebugChannelServer.Response.ok("n$count") }) { port ->
            val auth = "Authorization: Bearer test-token\r\n"
            assertEquals("n1", exchange(port, "GET /x HTTP/1.1\r\n$auth\r\n").second.trim())
            assertEquals("n2", exchange(port, "GET /x HTTP/1.1\r\n$auth\r\n").second.trim())
        }
    }

    @Test
    fun headOverTheCapIsRejectedWithoutHanging() {
        withServer({ DebugChannelServer.Response.ok("x") }) { port ->
            val (status, _) = exchange(port, "A".repeat(20 * 1024))
            assertTrue(status.startsWith("HTTP/1.1 400"))
        }
    }

    @Test
    fun stoppedServerRefusesConnections() {
        val server = DebugChannelServer(0, "test-token") { DebugChannelServer.Response.ok("x") }
        server.start()
        val port = server.boundPort
        server.stop()
        Thread.sleep(100) // let the accept thread unwind
        try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1000) }
            throw AssertionError("connection unexpectedly accepted after stop()")
        } catch (expected: java.net.ConnectException) {
            // the socket is gone: exactly what a stopped channel should do
        }
    }

    @Test
    fun missingOrWrongTokenIsUnauthorizedWithoutCallingHandler() {
        var handlerCalled = false
        withServer({ handlerCalled = true; DebugChannelServer.Response.text(200, "OK", "x") }) { port ->
            assertTrue(exchange(port, "GET /status HTTP/1.1\r\n\r\n").first.startsWith("HTTP/1.1 401"))
            assertTrue(
                exchange(port, "GET /status?token=wrong HTTP/1.1\r\n\r\n").first.startsWith("HTTP/1.1 401")
            )
        }
        assertEquals(false, handlerCalled)
    }

    @Test
    fun nonGetRequestIsRejected() {
        withServer({ DebugChannelServer.Response.text(200, "OK", "x") }) { port ->
            assertTrue(exchange(port, "POST /status HTTP/1.1\r\n\r\n").first.startsWith("HTTP/1.1 400"))
        }
    }

    @Test
    fun handlerExceptionBecomes500WithNoMessageEcho() {
        withServer({ throw IllegalStateException("boom") }) { port ->
            val (status, body) = exchange(port, "GET /x HTTP/1.1\r\nAuthorization: Bearer test-token\r\n\r\n")
            assertTrue(status.startsWith("HTTP/1.1 500"))
            // exception class only: the message could echo page data
            assertEquals("IllegalStateException", body.trim())
        }
    }

    @Test
    fun consoleRingKeepsOnlyTheLastLines() {
        val ring = DebugChannelServer.ConsoleRing(3)
        (1..5).forEach { ring.add("line$it") }
        assertEquals(listOf("line3", "line4", "line5"), ring.latest(10))
        assertEquals(listOf("line4", "line5"), ring.latest(2))
        assertEquals(emptyList<String>(), ring.latest(0))
    }

    @Test
    fun consoleRingTruncatesHugeLines() {
        val ring = DebugChannelServer.ConsoleRing(3)
        ring.add("x".repeat(10_000))
        val stored = ring.latest(1).single()
        assertEquals(4 * 1024 + 1, stored.length)
        assertTrue(stored.endsWith("…"))
    }
}

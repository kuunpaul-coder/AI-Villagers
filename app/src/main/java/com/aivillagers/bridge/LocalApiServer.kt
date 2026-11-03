package com.aivillagers.bridge

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loopback-only HTTP API for the Stardew Valley SMAPI mod.
 * GET /health
 * POST /chat {"npc":"Abigail","message":"Привет","context":"..."}
 */
class LocalApiServer(
    private val handler: (JSONObject) -> JSONObject,
    private val isModelReady: () -> Boolean,
    private val port: Int = 8765
) {
    private val running = AtomicBoolean(false)
    @Volatile private var socket: ServerSocket? = null
    private var thread: Thread? = null

    @Synchronized
    fun start() {
        if (running.get()) return

        // Bind before reporting success to the UI. Previously binding happened in
        // a background thread and its exception was swallowed, producing a false
        // "server started" status even when the port was unavailable.
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(java.net.InetSocketAddress(InetAddress.getByName("0.0.0.0"), port), 32)
        }
        socket = server
        running.set(true)
        thread = Thread({
            try {
                while (running.get()) {
                    try {
                        val client = server.accept()
                        Thread({ handle(client) }, "ai-villagers-client").apply {
                            isDaemon = true
                            start()
                        }
                    } catch (t: Exception) {
                        if (running.get()) throw t
                    }
                }
            } catch (_: Throwable) {
                running.set(false)
            } finally {
                try { server.close() } catch (_: Throwable) {}
                socket = null
            }
        }, "ai-villagers-http").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        try { socket?.close() } catch (_: Throwable) {}
        socket = null
        thread?.interrupt()
        thread = null
    }

    private fun handle(client: Socket) {
        client.use { connection ->
            connection.soTimeout = 300_000
            val input = BufferedInputStream(connection.getInputStream())
            val output = connection.getOutputStream()
            try {
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: ""
                val path = parts.getOrNull(1) ?: ""
                var contentLength = 0
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", true)) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }
                if (method == "GET" && path == "/health") {
                    respond(output, 200, JSONObject()
                        .put("status", "ok")
                        .put("modelLoaded", isModelReady()))
                    return
                }
                if (method != "POST" || path != "/chat") {
                    respond(output, 404, JSONObject().put("error", "Not found"))
                    return
                }
                if (!isModelReady()) {
                    respond(output, 503, JSONObject().put("error", "Gemma model is not ready"))
                    return
                }
                if (contentLength !in 1..65536) {
                    respond(output, 400, JSONObject().put("error", "Invalid Content-Length"))
                    return
                }
                val body = ByteArray(contentLength)
                var offset = 0
                while (offset < contentLength) {
                    val count = input.read(body, offset, contentLength - offset)
                    if (count < 0) throw IllegalArgumentException("Incomplete request body")
                    offset += count
                }
                val request = JSONObject(String(body, StandardCharsets.UTF_8))
                val response = handler(request)
                respond(output, 200, response)
            } catch (t: Throwable) {
                try {
                    respond(output, 500, JSONObject().put("error", t.message ?: "Internal error"))
                } catch (_: Throwable) {}
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        var previous = -1
        while (true) {
            val current = input.read()
            if (current < 0) return if (bytes.size() == 0) null
                else String(bytes.toByteArray(), StandardCharsets.US_ASCII)
            if (previous == 13 && current == 10) {
                val data = bytes.toByteArray()
                return String(if (data.isNotEmpty()) data.copyOf(data.size - 1) else data,
                    StandardCharsets.US_ASCII)
            }
            bytes.write(current)
            previous = current
            if (bytes.size() > 8192) throw IllegalArgumentException("HTTP header too large")
        }
    }

    private fun respond(output: java.io.OutputStream, code: Int, body: JSONObject) {
        val payload = body.toString().toByteArray(StandardCharsets.UTF_8)
        val status = when (code) {
            200 -> "OK"
            404 -> "Not Found"
            503 -> "Service Unavailable"
            500 -> "Internal Server Error"
            else -> "Bad Request"
        }
        val header = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${payload.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.US_ASCII))
        output.write(payload)
        output.flush()
    }
}

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
 * Tiny loopback-only HTTP API for the Stardew Valley SMAPI mod.
 * GET /health
 * POST /chat  {"npc":"Abigail","message":"Привет","context":"..."}
 */
class LocalApiServer(
    private val handler: (JSONObject) -> JSONObject,
    private val port: Int = 8765
) {
    private val running = AtomicBoolean(false)
    @Volatile private var socket: ServerSocket? = null
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({
            try {
                val server = ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))
                socket = server
                while (running.get()) {
                    try {
                        val client = server.accept()
                        Thread({ handle(client) }, "ai-villagers-client").start()
                    } catch (t: Exception) {
                        if (running.get()) throw t
                    }
                }
            } catch (t: Throwable) {
                running.set(false)
            } finally {
                try { socket?.close() } catch (_: Throwable) {}
                socket = null
            }
        }, "ai-villagers-http").apply { isDaemon = true; start() }
    }

    fun stop() {
        running.set(false)
        try { socket?.close() } catch (_: Throwable) {}
        thread?.interrupt()
        thread = null
    }

    private fun handle(client: Socket) {
        client.use { connection ->
            connection.soTimeout = 120_000
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
                    respond(output, 200, JSONObject().put("status", "ok").put("modelLoaded", true))
                    return
                }
                if (method != "POST" || path != "/chat") {
                    respond(output, 404, JSONObject().put("error", "Not found"))
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
                respond(output, 500, JSONObject().put("error", t.message ?: "Internal error"))
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
        val status = if (code == 200) "OK" else if (code == 404) "Not Found" else "Bad Request"
        val header = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${payload.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.US_ASCII))
        output.write(payload)
        output.flush()
    }
}

package com.pingbooster.app

import android.os.SystemClock
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * The heartbeat prober.
 *
 * Deliberately dependency free and allocation free in the hot path: one cheap socket per
 * cycle, the HTTP request bytes are built once, streams are used directly and everything is
 * closed in a finally block. That keeps both the CPU cost and the memory footprint of a
 * probe at a minimum (no OkHttp, no coroutines, no per-cycle objects).
 */
class PingEngine {

    /** A parsed destination, prepared once whenever the settings change. */
    class Target(
        val host: String,
        val port: Int,
        val tls: Boolean,
        val label: String,
        private val request: ByteArray?,
        private val httpLike: Boolean
    ) {
        /** Sends the prebuilt request (when there is one) and waits for the first byte. */
        fun roundTrip(socket: Socket, timeoutMs: Int) {
            socket.tcpNoDelay = true
            socket.soTimeout = timeoutMs
            val payload = request
            if (!httpLike || payload == null) return

            val output = socket.getOutputStream()
            output.write(payload)
            output.flush()
            // A single byte is enough to know the server answered: no response buffering,
            // no string parsing, nothing to garbage collect afterwards.
            socket.getInputStream().read()
        }
    }

    class ProbeResult(val ok: Boolean, val latencyMs: Int, val error: String)

    companion object {
        const val NO_LATENCY = -1
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 4_000

        /** Parses "host", "host:port", "http://host/path" or "https://host:port/path". */
        fun parse(input: String): Target? {
            val raw = input.trim()
            if (raw.isEmpty()) return null

            val hasScheme = raw.contains("://")
            val scheme = if (hasScheme) raw.substringBefore("://").lowercase() else ""
            var rest = if (hasScheme) raw.substringAfter("://") else raw
            var tls = scheme.startsWith("https")
            var port = when (scheme) {
                "http" -> 80
                "https" -> 443
                else -> -1
            }
            var path = "/"

            val slash = rest.indexOf('/')
            if (slash >= 0) {
                path = rest.substring(slash)
                rest = rest.substring(0, slash)
            }

            if (rest.startsWith("[")) {
                // IPv6 literal, e.g. [2606:4700:4700::1111]:443
                val close = rest.indexOf(']')
                if (close < 0) return null
                val host = rest.substring(1, close)
                val tail = rest.substring(close + 1)
                if (tail.startsWith(":")) {
                    port = tail.substring(1).toIntOrNull() ?: return null
                    tls = port == 443
                } else if (port < 0) {
                    port = 443
                    tls = true
                }
                return buildTarget(host, port, tls, path)
            }

            val colon = rest.lastIndexOf(':')
            if (colon > 0 && rest.indexOf(':') == colon) {
                val parsed = rest.substring(colon + 1).toIntOrNull()
                if (parsed != null) {
                    port = parsed
                    if (!hasScheme) tls = parsed == 443
                    rest = rest.substring(0, colon)
                }
            }

            val host = rest.trim().trim('/')
            if (host.isEmpty()) return null
            if (port < 0) {
                port = 443
                tls = true
            }
            return buildTarget(host, port, tls, path)
        }

        private fun buildTarget(host: String, port: Int, tls: Boolean, path: String): Target {
            // Plain TCP connect is the cheapest possible keep-alive; HTTP(S) targets send a
            // minimal HEAD so the server sees real traffic.
            val httpLike = tls || port == 80
            val request = if (httpLike) {
                val hostHeader = if (tls && port == 443) host else "$host:$port"
                ("HEAD $path HTTP/1.1\r\n" +
                    "Host: $hostHeader\r\n" +
                    "User-Agent: PingBooster/2.0\r\n" +
                    "Accept: */*\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
            } else {
                null
            }
            val label = if (tls) host else "$host:$port"
            return Target(host, port, tls, label, request, httpLike)
        }
    }

    private val sslFactory: SSLSocketFactory by lazy { SSLSocketFactory.getDefault() as SSLSocketFactory }

    /** Runs one heartbeat and returns the round trip time. Never throws. */
    fun probe(target: Target, connectTimeoutMs: Int = CONNECT_TIMEOUT_MS): ProbeResult {
        val started = SystemClock.elapsedRealtime()
        var socket: Socket? = null
        return try {
            val plain = Socket()
            plain.connect(InetSocketAddress(target.host, target.port), connectTimeoutMs)
            val live: Socket = if (target.tls) {
                (sslFactory.createSocket(plain, target.host, target.port, true) as SSLSocket).apply {
                    soTimeout = READ_TIMEOUT_MS
                    startHandshake()
                }
            } else {
                plain
            }
            socket = live
            target.roundTrip(live, READ_TIMEOUT_MS)
            ProbeResult(true, (SystemClock.elapsedRealtime() - started).toInt(), "")
        } catch (error: Throwable) {
            ProbeResult(false, NO_LATENCY, describe(error))
        } finally {
            // Streams belong to the socket, so closing it releases everything without leaks.
            try {
                socket?.close()
            } catch (_: Throwable) {
                // The socket is being discarded either way.
            }
        }
    }

    private fun describe(error: Throwable): String = when (error) {
        is java.net.UnknownHostException -> "No DNS"
        is java.net.SocketTimeoutException -> "Timeout"
        is java.net.ConnectException -> "Refused"
        is javax.net.ssl.SSLException -> "TLS error"
        else -> error.javaClass.simpleName
    }
}

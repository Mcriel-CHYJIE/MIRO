package com.n0va.detection.server

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

class MjpegServer(
    initialPort: Int = 8080
) {
    @Volatile
    var port: Int = initialPort

    @Volatile
    var isRunning = false
        private set
    @Volatile
    var clientCount = 0
        private set

    private var serverSocket: ServerSocket? = null
    @Volatile
    private var latestFrame: ByteArray? = null

    private val frameLock = Any()

    fun setLatestFrame(jpeg: ByteArray) {
        synchronized(frameLock) {
            latestFrame = jpeg
        }
    }

    fun getLatestFrame(): ByteArray? {
        synchronized(frameLock) {
            return latestFrame
        }
    }

    /** Wait up to [timeoutMs] for the first frame to arrive */
    fun waitForFrame(timeoutMs: Long): ByteArray? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val f = getLatestFrame()
            if (f != null) return f
            Thread.sleep(10)
        }
        return null
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        Thread({ serverLoop() }, "mjpeg-server").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        synchronized(frameLock) { latestFrame = null }
    }

    private fun serverLoop() {
        try {
            serverSocket = ServerSocket(port)
        } catch (e: Exception) {
            isRunning = false
            return
        }

        while (isRunning && !serverSocket!!.isClosed) {
            try {
                val client = serverSocket!!.accept()
                clientCount++
                Thread({
                    handleClient(client)
                    try { client.close() } catch (_: Exception) {}
                    clientCount--
                }, "mjpeg-client").apply { isDaemon = true; start() }
            } catch (_: Exception) {
                if (isRunning) break
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = URLDecoder.decode(parts[1], "UTF-8")

            // Read headers (up to empty line)
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.isEmpty()) break
            }

            when (method) {
                "GET" -> when {
                    path == "/stream" || path == "/video" || path == "/mjpeg" -> serveMjpeg(client)
                    path == "/status" -> serveJson(client)
                    else -> serveMjpeg(client) // / or anything else → MJPEG for compatibility
                }
                else -> serveMjpeg(client)
            }
        } catch (_: Exception) {}
    }

    private fun serveMjpeg(client: Socket) {
        val boundary = "ipwebcam"
        val os = client.getOutputStream()

        // Send HTTP headers immediately so the client gets a response
        os.write("HTTP/1.1 200 OK\r\n".toByteArray())
        os.write("Server: IPWebcam/1.0\r\n".toByteArray())
        os.write("Cache-Control: no-cache, no-store, must-revalidate\r\n".toByteArray())
        os.write("Pragma: no-cache\r\n".toByteArray())
        os.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
        os.write("Content-Type: multipart/x-mixed-replace; boundary=$boundary\r\n".toByteArray())
        os.write("\r\n".toByteArray())
        os.flush()

        var lastFrameTime = System.currentTimeMillis()
        val frameInterval = 33L // ~30fps cap

        while (isRunning && !client.isClosed) {
            val frame = getLatestFrame()
            if (frame != null) {
                try {
                    os.write("--$boundary\r\n".toByteArray())
                    os.write("Content-Type: image/jpeg\r\n".toByteArray())
                    os.write("Content-Length: ${frame.size}\r\n".toByteArray())
                    os.write("\r\n".toByteArray())
                    os.write(frame)
                    os.write("\r\n".toByteArray())
                    os.flush()
                    lastFrameTime = System.currentTimeMillis()
                } catch (_: Exception) {
                    break
                }
            }
            val elapsed = System.currentTimeMillis() - lastFrameTime
            if (elapsed < frameInterval) {
                Thread.sleep(frameInterval - elapsed)
            }
        }
    }

    private fun serveJson(client: Socket) {
        val json = """{"clients":$clientCount,"streaming":$isRunning}"""
        val response = "HTTP/1.0 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${json.toByteArray().size}\r\n" +
                "Connection: close\r\n\r\n" +
                json
        client.getOutputStream().write(response.toByteArray())
        client.getOutputStream().flush()
    }
}

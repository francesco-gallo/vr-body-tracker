package com.vrproject.bodytracker

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

class MjpegServer(port: Int = 8080) : NanoHTTPD(port) {

    @Volatile
    private var latestJpeg: ByteArray? = null

    private val activeConnections = AtomicInteger(0)

    fun hasClients(): Boolean = activeConnections.get() > 0

    fun updateFrame(jpegBytes: ByteArray) {
        latestJpeg = jpegBytes
    }

    override fun serve(session: IHTTPSession): Response {
        val boundary = "mjpegboundary"
        activeConnections.incrementAndGet()

        val stream = object : InputStream() {
            private var currentStream: ByteArrayInputStream? = null

            // NanoHTTPD's chunked response reads via the bulk read(byte[], off, len) below,
            // but InputStream still requires the single-byte read() to be implemented.
            private fun ensureCurrentStream(): ByteArrayInputStream? {
                if (currentStream != null && currentStream!!.available() > 0) {
                    return currentStream
                }
                val frame = latestJpeg
                if (frame == null) {
                    try {
                        Thread.sleep(30)
                    } catch (_: Exception) {}
                    return null
                }
                val header = "--$boundary\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n"
                val footer = "\r\n"
                val payload = header.toByteArray() + frame + footer.toByteArray()
                currentStream = ByteArrayInputStream(payload)
                return currentStream
            }

            override fun read(): Int {
                val s = ensureCurrentStream() ?: return 0
                return s.read()
            }

            // Without this override, the default InputStream.read(byte[], int, int)
            // implementation calls read() once per byte, which is extremely slow for
            // multi-KB JPEG frames streamed at high FPS. Delegating directly to the
            // backing ByteArrayInputStream lets the whole buffered chunk be copied at once.
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val s = ensureCurrentStream() ?: return 0
                return s.read(b, off, len)
            }

            override fun close() {
                super.close()
                activeConnections.decrementAndGet()
            }
        }

        return newChunkedResponse(
            Response.Status.OK,
            "multipart/x-mixed-replace; boundary=$boundary",
            stream
        )
    }
}
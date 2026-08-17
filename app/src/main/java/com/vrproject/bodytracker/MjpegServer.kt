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

            override fun read(): Int {
                if (currentStream == null || currentStream?.available() == 0) {
                    val frame = latestJpeg
                    if (frame == null) {
                        try {
                            Thread.sleep(30)
                        } catch (_: Exception) {}
                        return 0
                    }
                    val header = "--$boundary\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n"
                    val footer = "\r\n"
                    val payload = header.toByteArray() + frame + footer.toByteArray()
                    currentStream = ByteArrayInputStream(payload)
                }
                return currentStream?.read() ?: -1
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
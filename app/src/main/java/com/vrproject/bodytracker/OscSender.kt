package com.vrproject.bodytracker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class OscMessageData(
    val address: String,
    val args: List<Any>
)

class OscSender {
    private val socket = DatagramSocket()

    suspend fun checkEndpoint(host: String, port: Int): Boolean {
        if (host.isBlank() || port !in 1..65535) {
            return false
        }

        return withContext(Dispatchers.IO) {    
            try {
                val address = InetAddress.getByName(host)
                val probe = DatagramSocket()
                val payload = "ping".toByteArray(Charsets.UTF_8)
                try {
                    probe.connect(address, port)
                    probe.send(DatagramPacket(payload, payload.size, address, port))
                    true
                } catch (e: Exception) {
                    false
                } finally {
                    probe.close()
                }
            } catch (e: Exception) {
                false
            }
        }
    }

    suspend fun send(host: String, port: Int, messages: List<OscMessageData>, bundle: Boolean) {
        if (messages.isEmpty()) {
            return
        }

        withContext(Dispatchers.IO) {
            val address = InetAddress.getByName(host)
            if (bundle) {
                val payload = OscEncoding.encodeBundle(messages)
                socket.send(DatagramPacket(payload, payload.size, address, port))
            } else {
                for (message in messages) {
                    val payload = OscEncoding.encodeMessage(message)
                    socket.send(DatagramPacket(payload, payload.size, address, port))
                }
            }
        }
    }

    fun close() {
        socket.close()
    }
}

private object OscEncoding {
    fun encodeBundle(messages: List<OscMessageData>): ByteArray {
        val stream = ByteArrayOutputStream()
        writePaddedString(stream, "#bundle")
        // 8-byte OSC timetag. 1 means "immediate".
        stream.write(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1))

        for (message in messages) {
            val payload = encodeMessage(message)
            writeInt(stream, payload.size)
            stream.write(payload)
        }

        return stream.toByteArray()
    }

    fun encodeMessage(message: OscMessageData): ByteArray {
        val stream = ByteArrayOutputStream()

        writePaddedString(stream, message.address)

        val typeTags = buildTypeTags(message.args)
        writePaddedString(stream, typeTags)

        for (arg in message.args) {
            when (arg) {
                is Int -> writeInt(stream, arg)
                is Float -> writeFloat(stream, arg)
                is Double -> writeFloat(stream, arg.toFloat())
                is String -> writePaddedString(stream, arg)
                else -> writePaddedString(stream, arg.toString())
            }
        }

        return stream.toByteArray()
    }

    private fun buildTypeTags(args: List<Any>): String {
        val tags = StringBuilder(",")
        for (arg in args) {
            tags.append(
                when (arg) {
                    is Int -> 'i'
                    is Float -> 'f'
                    is Double -> 'f'
                    is String -> 's'
                    else -> 's'
                }
            )
        }
        return tags.toString()
    }

    private fun writePaddedString(stream: ByteArrayOutputStream, value: String) {
        val data = value.toByteArray(Charsets.UTF_8)
        stream.write(data)
        stream.write(0)

        val padding = (4 - ((data.size + 1) % 4)) % 4
        repeat(padding) {
            stream.write(0)
        }
    }

    private fun writeInt(stream: ByteArrayOutputStream, value: Int) {
        val bytes = ByteBuffer.allocate(4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(value)
            .array()
        stream.write(bytes)
    }

    private fun writeFloat(stream: ByteArrayOutputStream, value: Float) {
        val bytes = ByteBuffer.allocate(4)
            .order(ByteOrder.BIG_ENDIAN)
            .putFloat(value)
            .array()
        stream.write(bytes)
    }
}

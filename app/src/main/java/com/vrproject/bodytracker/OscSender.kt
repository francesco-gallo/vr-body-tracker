package com.vrproject.bodytracker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val buffer = ByteBuffer.allocate(8192).order(ByteOrder.BIG_ENDIAN)

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
            synchronized(buffer) {
                buffer.clear()
                if (bundle) {
                    OscEncoding.encodeBundle(messages, buffer)
                    val length = buffer.position()
                    socket.send(DatagramPacket(buffer.array(), length, address, port))
                } else {
                    for (message in messages) {
                        buffer.clear()
                        OscEncoding.encodeMessage(message, buffer)
                        val length = buffer.position()
                        socket.send(DatagramPacket(buffer.array(), length, address, port))
                    }
                }
            }
        }
    }

    fun close() {
        socket.close()
    }
}

private object OscEncoding {

    fun encodeBundle(messages: List<OscMessageData>, buffer: ByteBuffer) {
        writePaddedString(buffer, "#bundle")
        // 8-byte OSC timetag. 1 = "immediate"
        buffer.putLong(1L)

        for (message in messages) {
            val sizePos = buffer.position()
            buffer.putInt(0) // Spazio riservato per la dimensione della payload
            val startPos = buffer.position()

            encodeMessage(message, buffer)

            val endPos = buffer.position()
            val payloadSize = endPos - startPos

            // Inseriamo la dimensione calcolata prima della payload
            buffer.putInt(sizePos, payloadSize)
        }
    }

    fun encodeMessage(message: OscMessageData, buffer: ByteBuffer) {
        writePaddedString(buffer, message.address)

        val typeTags = buildTypeTags(message.args)
        writePaddedString(buffer, typeTags)

        for (arg in message.args) {
            when (arg) {
                is Int -> buffer.putInt(arg)
                is Float -> buffer.putFloat(arg)
                is Double -> buffer.putFloat(arg.toFloat())
                is String -> writePaddedString(buffer, arg)
                else -> writePaddedString(buffer, arg.toString())
            }
        }
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

    private fun writePaddedString(buffer: ByteBuffer, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        buffer.put(bytes)
        buffer.put(0.toByte())

        val padding = (4 - ((bytes.size + 1) % 4)) % 4
        repeat(padding) {
            buffer.put(0.toByte())
        }
    }
}
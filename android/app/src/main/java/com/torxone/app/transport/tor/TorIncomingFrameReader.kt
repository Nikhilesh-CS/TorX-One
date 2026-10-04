package com.torxone.app.transport.tor

import java.io.DataInputStream
import java.io.PushbackInputStream
import java.net.Socket

/** First-frame admission and incomplete frame reads have absolute, socket-closing deadlines. */
internal class TorIncomingFrameReader(
    private val socket: Socket,
    private val stream: PushbackInputStream,
    private val frameTimeoutMs: Long
) {
    private val input = DataInputStream(stream)
    private var receivedFrame = false

    fun readFrame(): ByteArray? {
        if (receivedFrame) {
            // Between complete frames, SO_TIMEOUT provides the longer persistent-stream idle budget.
            val first = stream.read()
            if (first < 0) return null
            stream.unread(first)
        }
        val payload = TorIoDeadline.watch(socket, frameTimeoutMs).use { TorStreamFraming.readFrame(input) }
        if (payload != null) receivedFrame = true
        return payload
    }
}

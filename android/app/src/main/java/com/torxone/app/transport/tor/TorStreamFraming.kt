package com.torxone.app.transport.tor

import com.torxone.app.protocol.ProtocolLimits
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

/** One validated return-onion header, then repeated bounded length/payload frames. */
object TorStreamFraming {
    fun writeHeader(output: DataOutputStream, returnOnion: String) {
        require(returnOnion.matches(Regex("[a-z2-7]{56}\\.onion")))
        output.writeUTF(returnOnion)
    }

    fun readHeader(input: DataInputStream): String = input.readUTF().also {
        require(it.matches(Regex("[a-z2-7]{56}\\.onion")))
    }

    fun writeFrame(output: DataOutputStream, payload: ByteArray) {
        require(payload.size in 1..ProtocolLimits.MAX_TRANSPORT_ENVELOPE_BYTES)
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }

    fun readFrame(input: DataInputStream): ByteArray? {
        // Distinguish clean EOF between frames from a truncated length field.
        val first = input.read()
        if (first == -1) return null
        val remaining = ByteArray(3)
        input.readFully(remaining)
        val length = (first shl 24) or ((remaining[0].toInt() and 255) shl 16) or
            ((remaining[1].toInt() and 255) shl 8) or (remaining[2].toInt() and 255)
        require(length in 1..ProtocolLimits.MAX_TRANSPORT_ENVELOPE_BYTES)
        return ByteArray(length).also(input::readFully)
    }
}

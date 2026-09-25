package com.torxone.app.relationship

import com.torxone.app.identity.IdentityCrypto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Payload sent over the wire from Alice to Bob after Alice scans Bob's QR code (Section 4 & 5).
 * Enables Bob to compute matching 3DH responder keys and establish bilateral contact.
 */
data class ContactBootstrapPayload(
    val inviteId: String,
    val initiatorDisplayName: String,
    val initiatorSigningPublicKey: ByteArray,
    val initiatorEncryptionPublicKey: ByteArray,
    val initiatorEphemeralPublicKey: ByteArray,
    val signature: ByteArray
) {
    fun toByteArray(): ByteArray {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        dos.writeUTF(inviteId)
        dos.writeUTF(initiatorDisplayName)
        dos.writeInt(initiatorSigningPublicKey.size)
        dos.write(initiatorSigningPublicKey)
        dos.writeInt(initiatorEncryptionPublicKey.size)
        dos.write(initiatorEncryptionPublicKey)
        dos.writeInt(initiatorEphemeralPublicKey.size)
        dos.write(initiatorEphemeralPublicKey)
        dos.writeInt(signature.size)
        dos.write(signature)
        return bos.toByteArray()
    }

    companion object {
        fun fromByteArray(bytes: ByteArray): ContactBootstrapPayload {
            val dis = DataInputStream(ByteArrayInputStream(bytes))
            val inviteId = dis.readUTF()
            val name = dis.readUTF()
            val signPub = ByteArray(dis.readInt()).apply { dis.readFully(this) }
            val encPub = ByteArray(dis.readInt()).apply { dis.readFully(this) }
            val ephPub = ByteArray(dis.readInt()).apply { dis.readFully(this) }
            val sig = ByteArray(dis.readInt()).apply { dis.readFully(this) }
            return ContactBootstrapPayload(inviteId, name, signPub, encPub, ephPub, sig)
        }

        fun serializeForSigning(
            inviteId: String,
            displayName: String,
            signingPub: ByteArray,
            encryptionPub: ByteArray,
            ephemeralPub: ByteArray
        ): ByteArray {
            val bos = ByteArrayOutputStream()
            val dos = DataOutputStream(bos)
            dos.writeUTF(inviteId)
            dos.writeUTF(displayName)
            dos.write(signingPub)
            dos.write(encryptionPub)
            dos.write(ephemeralPub)
            return bos.toByteArray()
        }
    }
}

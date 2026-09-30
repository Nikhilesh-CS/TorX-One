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
    val initiatorIdentityId: String,
    val initiatorDisplayName: String,
    val initiatorSigningPublicKey: ByteArray,
    val initiatorEncryptionPublicKey: ByteArray,
    val initiatorEphemeralPublicKey: ByteArray,
    val signature: ByteArray,
    val initiatorTorOnionAddress: String? = null
) {
    fun toByteArray(): ByteArray {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        dos.writeUTF(inviteId)
        dos.writeUTF(initiatorIdentityId)
        dos.writeUTF(initiatorDisplayName)
        dos.writeInt(initiatorSigningPublicKey.size)
        dos.write(initiatorSigningPublicKey)
        dos.writeInt(initiatorEncryptionPublicKey.size)
        dos.write(initiatorEncryptionPublicKey)
        dos.writeInt(initiatorEphemeralPublicKey.size)
        dos.write(initiatorEphemeralPublicKey)
        dos.writeInt(signature.size)
        dos.write(signature)
        // Optional extension appended after the legacy payload so older persisted/bootstrap
        // frames remain decodable. New peers authenticate it as part of the signature.
        initiatorTorOnionAddress?.let { dos.writeUTF(it) }
        return bos.toByteArray()
    }

    companion object {
        private const val EXPECTED_KEY_SIZE = 32
        private const val EXPECTED_SIG_SIZE = 64

        fun fromByteArray(bytes: ByteArray): ContactBootstrapPayload {
            val dis = DataInputStream(ByteArrayInputStream(bytes))
            val inviteId = dis.readUTF()
            val initiatorIdentityId = dis.readUTF()
            val name = dis.readUTF()

            val signLen = dis.readInt()
            if (signLen != EXPECTED_KEY_SIZE) {
                throw IllegalArgumentException("Invalid initiator signing key length: $signLen")
            }
            val signPub = ByteArray(signLen).apply { dis.readFully(this) }

            val encLen = dis.readInt()
            if (encLen != EXPECTED_KEY_SIZE) {
                throw IllegalArgumentException("Invalid initiator encryption key length: $encLen")
            }
            val encPub = ByteArray(encLen).apply { dis.readFully(this) }

            val ephLen = dis.readInt()
            if (ephLen != EXPECTED_KEY_SIZE) {
                throw IllegalArgumentException("Invalid initiator ephemeral key length: $ephLen")
            }
            val ephPub = ByteArray(ephLen).apply { dis.readFully(this) }

            val sigLen = dis.readInt()
            if (sigLen != EXPECTED_SIG_SIZE) {
                throw IllegalArgumentException("Invalid signature length: $sigLen")
            }
            val sig = ByteArray(sigLen).apply { dis.readFully(this) }

            val onion = if (dis.available() > 0) dis.readUTF() else null
            if (dis.available() != 0) throw IllegalArgumentException("Trailing bytes in ContactBootstrapPayload: ${dis.available()}")
            if (onion != null && !onion.matches(Regex("[a-z2-7]{56}\\.onion"))) {
                throw IllegalArgumentException("Invalid initiator Tor onion address")
            }

            return ContactBootstrapPayload(inviteId, initiatorIdentityId, name, signPub, encPub, ephPub, sig, onion)
        }

        fun serializeForSigning(
            inviteId: String,
            initiatorIdentityId: String,
            displayName: String,
            signingPub: ByteArray,
            encryptionPub: ByteArray,
            ephemeralPub: ByteArray,
            initiatorTorOnionAddress: String? = null
        ): ByteArray {
            val bos = ByteArrayOutputStream()
            val dos = DataOutputStream(bos)
            dos.writeUTF(inviteId)
            dos.writeUTF(initiatorIdentityId)
            dos.writeUTF(displayName)
            dos.write(signingPub)
            dos.write(encryptionPub)
            dos.write(ephemeralPub)
            initiatorTorOnionAddress?.let { dos.writeUTF(it) }
            return bos.toByteArray()
        }
    }
}

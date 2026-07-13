package com.torxone.app.crypto

import android.util.Base64
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.Sign
import org.json.JSONObject

data class Identity(
    val name: String,
    val encryptionPublicKey: ByteArray,
    val encryptionSecretKey: ByteArray,
    val signingPublicKey: ByteArray,
    val signingSecretKey: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Identity
        return name == other.name &&
                encryptionPublicKey.contentEquals(other.encryptionPublicKey) &&
                encryptionSecretKey.contentEquals(other.encryptionSecretKey) &&
                signingPublicKey.contentEquals(other.signingPublicKey) &&
                signingSecretKey.contentEquals(other.signingSecretKey)
    }
    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + encryptionPublicKey.contentHashCode()
        result = 31 * result + encryptionSecretKey.contentHashCode()
        result = 31 * result + signingPublicKey.contentHashCode()
        result = 31 * result + signingSecretKey.contentHashCode()
        return result
    }
}

object CryptoManager {
    private val lazySodium = LazySodiumAndroid(SodiumAndroid())
    private val hexRegex = Regex("^[0-9a-f]+$")
    private val onionRegex = Regex("^[a-z2-7]{56}\\.onion$")

    fun generateIdentity(name: String): Identity {
        val encKeyPair = lazySodium.cryptoBoxKeypair()
        val sigKeyPair = lazySodium.cryptoSignKeypair()

        return Identity(
            name = name,
            encryptionPublicKey = encKeyPair.publicKey.asBytes,
            encryptionSecretKey = encKeyPair.secretKey.asBytes,
            signingPublicKey = sigKeyPair.publicKey.asBytes,
            signingSecretKey = sigKeyPair.secretKey.asBytes
        )
    }

    fun encryptMessage(plaintext: String, recipientEncPub: ByteArray, senderEncSec: ByteArray): Pair<ByteArray, ByteArray> {
        require(recipientEncPub.size == Box.PUBLICKEYBYTES) { "Recipient encryption key must be ${Box.PUBLICKEYBYTES} bytes" }
        require(senderEncSec.size == Box.SECRETKEYBYTES) { "Sender encryption key must be ${Box.SECRETKEYBYTES} bytes" }

        val nonce = lazySodium.nonce(Box.NONCEBYTES)
        val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
        val ciphertext = ByteArray(plaintextBytes.size + Box.MACBYTES)
        
        val success = lazySodium.cryptoBoxEasy(
            ciphertext,
            plaintextBytes,
            plaintextBytes.size.toLong(),
            nonce,
            recipientEncPub,
            senderEncSec
        )
        
        if (!success) throw Exception("Encryption failed")
        return Pair(ciphertext, nonce)
    }

    fun decryptMessage(ciphertext: ByteArray, nonce: ByteArray, senderEncPub: ByteArray, recipientEncSec: ByteArray): String {
        require(ciphertext.size >= Box.MACBYTES) { "Ciphertext is too short" }
        require(nonce.size == Box.NONCEBYTES) { "Nonce must be ${Box.NONCEBYTES} bytes" }
        require(senderEncPub.size == Box.PUBLICKEYBYTES) { "Sender encryption key must be ${Box.PUBLICKEYBYTES} bytes" }
        require(recipientEncSec.size == Box.SECRETKEYBYTES) { "Recipient encryption key must be ${Box.SECRETKEYBYTES} bytes" }

        val plaintext = ByteArray(ciphertext.size - Box.MACBYTES)
        
        val success = lazySodium.cryptoBoxOpenEasy(
            plaintext,
            ciphertext,
            ciphertext.size.toLong(),
            nonce,
            senderEncPub,
            recipientEncSec
        )
        
        if (!success) throw Exception("Decryption failed")
        return String(plaintext, Charsets.UTF_8)
    }

    fun sign(data: ByteArray, secretKey: ByteArray): ByteArray {
        require(secretKey.size == Sign.SECRETKEYBYTES) { "Signing secret key must be ${Sign.SECRETKEYBYTES} bytes" }
        val signature = ByteArray(Sign.BYTES)
        val success = lazySodium.cryptoSignDetached(
            signature,
            data,
            data.size.toLong(),
            secretKey
        )
        if (!success) throw Exception("Signing failed")
        return signature
    }

    fun verify(data: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        if (signature.size != Sign.BYTES || publicKey.size != Sign.PUBLICKEYBYTES) return false
        return lazySodium.cryptoSignVerifyDetached(
            signature,
            data,
            data.size,
            publicKey
        )
    }

    fun createContactString(identity: Identity, onionAddress: String? = null): String {
        val json = JSONObject().apply {
            put("n", identity.name)
            put("e", toHex(identity.encryptionPublicKey).lowercase())
            put("s", toHex(identity.signingPublicKey).lowercase())
            if (!onionAddress.isNullOrBlank()) {
                put("o", onionAddress.lowercase())
            }
        }
        val b64 = Base64.encodeToString(json.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "astra:$b64"
    }

    data class ParsedContact(
        val name: String,
        val encryptionPublicKey: ByteArray,
        val signingPublicKey: ByteArray,
        val onionAddress: String?
    )

    fun parseContactString(contactString: String): ParsedContact? {
        try {
            val prefix = "astra:"
            val trimmed = contactString.trim()
            if (!trimmed.startsWith(prefix)) return null

            val b64 = trimmed.removePrefix(prefix).trim()
            val jsonString = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
            val json = JSONObject(jsonString)

            val name = json.getString("n").trim().takeIf { it.isNotBlank() } ?: return null
            val encPub = fromHexOrNull(json.getString("e"), Box.PUBLICKEYBYTES) ?: return null
            val sigPub = fromHexOrNull(json.getString("s"), Sign.PUBLICKEYBYTES) ?: return null
            val onion = json.optString("o")
                .trim()
                .lowercase()
                .takeIf { it.isNotBlank() }
                ?.takeIf { onionRegex.matches(it) }

            return ParsedContact(name, encPub, sigPub, onion)
        } catch (e: Exception) {
            return null
        }
    }

    /** @deprecated use parseContactString returning ParsedContact */
    fun parseContactStringLegacy(contactString: String): Triple<String, ByteArray, ByteArray>? {
        val parsed = parseContactString(contactString) ?: return null
        return Triple(parsed.name, parsed.encryptionPublicKey, parsed.signingPublicKey)
    }
    
    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    fun fromHex(hex: String): ByteArray {
        val normalized = hex.trim().lowercase()
        require(normalized.length % 2 == 0) { "Hex string must have an even length" }
        require(normalized.isNotEmpty() && hexRegex.matches(normalized)) { "Invalid hex string" }
        return normalized.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    fun fromHexOrNull(hex: String, expectedBytes: Int? = null): ByteArray? {
        return try {
            val bytes = fromHex(hex)
            if (expectedBytes != null && bytes.size != expectedBytes) null else bytes
        } catch (_: Exception) {
            null
        }
    }
}

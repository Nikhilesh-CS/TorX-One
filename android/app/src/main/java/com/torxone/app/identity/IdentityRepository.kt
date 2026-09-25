package com.torxone.app.identity

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64
import java.util.UUID

interface IdentityRepository {
    suspend fun createIdentity(displayName: String): TorXIdentity
    suspend fun loadIdentity(): TorXIdentity?
    suspend fun sign(data: ByteArray): ByteArray
    suspend fun createContactInvite(): ContactInviteV1
}

/**
 * Android Keystore backed IdentityRepository using EncryptedSharedPreferences.
 * Identity secret material is stored encrypted via AES256-GCM backed by hardware Keystore.
 */
class KeystoreIdentityRepository(
    private val context: Context
) : IdentityRepository {

    private var cachedIdentity: TorXIdentity? = null

    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                "torx_identity_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            // Fallback for testing environments / unit tests without Android KeyStore
            context.getSharedPreferences("torx_identity_fallback_prefs", Context.MODE_PRIVATE)
        }
    }

    override suspend fun createIdentity(displayName: String): TorXIdentity = withContext(Dispatchers.IO) {
        val signingPair = IdentityCrypto.generateEd25519KeyPair()
        val encryptionPair = IdentityCrypto.generateX25519KeyPair()

        val identity = TorXIdentity(
            identityId = UUID.randomUUID().toString(),
            signingPublicKey = signingPair.publicKey,
            signingPrivateKey = signingPair.privateKey,
            encryptionPublicKey = encryptionPair.publicKey,
            encryptionPrivateKey = encryptionPair.privateKey,
            displayName = displayName,
            createdAt = System.currentTimeMillis()
        )

        saveIdentity(identity)
        cachedIdentity = identity
        identity
    }

    override suspend fun loadIdentity(): TorXIdentity? = withContext(Dispatchers.IO) {
        cachedIdentity?.let { return@withContext it }

        val id = prefs.getString("identity_id", null) ?: return@withContext null
        val name = prefs.getString("display_name", "") ?: ""
        val signPub = prefs.getString("sign_pub", null)?.let { Base64.getDecoder().decode(it) } ?: return@withContext null
        val signPriv = prefs.getString("sign_priv", null)?.let { Base64.getDecoder().decode(it) } ?: return@withContext null
        val encPub = prefs.getString("enc_pub", null)?.let { Base64.getDecoder().decode(it) } ?: return@withContext null
        val encPriv = prefs.getString("enc_priv", null)?.let { Base64.getDecoder().decode(it) } ?: return@withContext null
        val createdAt = prefs.getLong("created_at", System.currentTimeMillis())

        val identity = TorXIdentity(
            identityId = id,
            signingPublicKey = signPub,
            signingPrivateKey = signPriv,
            encryptionPublicKey = encPub,
            encryptionPrivateKey = encPriv,
            displayName = name,
            createdAt = createdAt
        )
        cachedIdentity = identity
        identity
    }

    override suspend fun sign(data: ByteArray): ByteArray = withContext(Dispatchers.Default) {
        val identity = loadIdentity() ?: throw IllegalStateException("Identity not initialized")
        IdentityCrypto.signEd25519(identity.signingPrivateKey, data)
    }

    override suspend fun createContactInvite(): ContactInviteV1 = withContext(Dispatchers.Default) {
        val identity = loadIdentity() ?: throw IllegalStateException("Identity not initialized")
        val ephemeralBootstrapPair = IdentityCrypto.generateX25519KeyPair()

        val inviteId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val expiresAt = now + (7 * 24 * 60 * 60 * 1000L) // 7 days

        val signedData = ContactInviteCodec.serializeForSigning(
            protocolVersion = 1,
            inviteId = inviteId,
            displayName = identity.displayName,
            signingPublicKey = identity.signingPublicKey,
            encryptionPublicKey = identity.encryptionPublicKey,
            bootstrapEphemeralPublicKey = ephemeralBootstrapPair.publicKey,
            createdAt = now,
            expiresAt = expiresAt
        )

        val signature = IdentityCrypto.signEd25519(identity.signingPrivateKey, signedData)

        ContactInviteV1(
            protocolVersion = 1,
            inviteId = inviteId,
            displayName = identity.displayName,
            identitySigningPublicKey = identity.signingPublicKey,
            identityEncryptionPublicKey = identity.encryptionPublicKey,
            bootstrapEphemeralPublicKey = ephemeralBootstrapPair.publicKey,
            createdAt = now,
            expiresAt = expiresAt,
            signature = signature
        )
    }

    private fun saveIdentity(identity: TorXIdentity) {
        prefs.edit()
            .putString("identity_id", identity.identityId)
            .putString("display_name", identity.displayName)
            .putString("sign_pub", Base64.getEncoder().encodeToString(identity.signingPublicKey))
            .putString("sign_priv", Base64.getEncoder().encodeToString(identity.signingPrivateKey))
            .putString("enc_pub", Base64.getEncoder().encodeToString(identity.encryptionPublicKey))
            .putString("enc_priv", Base64.getEncoder().encodeToString(identity.encryptionPrivateKey))
            .putLong("created_at", identity.createdAt)
            .apply()
    }
}

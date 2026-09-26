package com.torxone.app.identity

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64
import java.util.UUID

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface IdentityState {
    data object NoIdentity : IdentityState
    data object Loading : IdentityState
    data class Ready(val identity: TorXIdentity) : IdentityState
    data class Failed(val error: Throwable) : IdentityState
}

interface IdentityRepository {
    val identityState: StateFlow<IdentityState>
    suspend fun createIdentity(displayName: String): TorXIdentity
    suspend fun loadIdentity(): TorXIdentity?
    suspend fun ensureIdentity(displayName: String = "Me"): TorXIdentity =
        loadIdentity() ?: createAndPublishIdentity(displayName)
    suspend fun createAndPublishIdentity(displayName: String): TorXIdentity =
        createIdentity(displayName)
    fun requireLocalIdentityId(): String {
        return when (val state = identityState.value) {
            is IdentityState.Ready -> state.identity.identityId
            else -> throw IllegalStateException("Local identity is not initialized (state: $state)")
        }
    }
    fun getIdentityState(): IdentityState = identityState.value
    suspend fun sign(data: ByteArray): ByteArray
    suspend fun createContactInvite(): ContactInviteV1
    suspend fun getPendingInviteEphemeralPrivateKey(inviteId: String): ByteArray?
}

/**
 * Android Keystore backed IdentityRepository using EncryptedSharedPreferences.
 * Identity secret material is stored encrypted via AES256-GCM backed by hardware Keystore.
 */
class KeystoreIdentityRepository(
    private val context: Context,
    private val pendingInviteDao: com.torxone.app.data.dao.PendingInviteDao? = null,
    private val allowInsecureFallback: Boolean = false
) : IdentityRepository {

    private val _identityState = MutableStateFlow<IdentityState>(IdentityState.NoIdentity)
    override val identityState: StateFlow<IdentityState> = _identityState.asStateFlow()

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
        } catch (e: Exception) {
            if (allowInsecureFallback) {
                // Fallback ONLY allowed when explicitly opted in (e.g. test fakes)
                context.getSharedPreferences("torx_identity_fallback_prefs", Context.MODE_PRIVATE)
            } else {
                throw SecurityException("Hardware Keystore EncryptedSharedPreferences initialization failed. Plaintext fallback is rejected in production.", e)
            }
        }
    }

    override suspend fun createIdentity(displayName: String): TorXIdentity = withContext(Dispatchers.IO) {
        _identityState.value = IdentityState.Loading
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
        _identityState.value = IdentityState.Ready(identity)
        identity
    }

    override suspend fun createAndPublishIdentity(displayName: String): TorXIdentity {
        return createIdentity(displayName)
    }

    override suspend fun ensureIdentity(displayName: String): TorXIdentity {
        val existing = loadIdentity()
        return existing ?: createAndPublishIdentity(displayName)
    }

    override fun requireLocalIdentityId(): String {
        return when (val state = _identityState.value) {
            is IdentityState.Ready -> state.identity.identityId
            else -> cachedIdentity?.identityId
                ?: throw IllegalStateException("Local identity is not initialized")
        }
    }

    override fun getIdentityState(): IdentityState = _identityState.value

    override suspend fun loadIdentity(): TorXIdentity? = withContext(Dispatchers.IO) {
        cachedIdentity?.let {
            if (_identityState.value !is IdentityState.Ready) {
                _identityState.value = IdentityState.Ready(it)
            }
            return@withContext it
        }

        _identityState.value = IdentityState.Loading
        try {
            val id = prefs.getString("identity_id", null)
            if (id == null) {
                _identityState.value = IdentityState.NoIdentity
                return@withContext null
            }
            val name = prefs.getString("display_name", "") ?: ""
            val signPub = prefs.getString("sign_pub", null)?.let { Base64.getDecoder().decode(it) }
            val signPriv = prefs.getString("sign_priv", null)?.let { Base64.getDecoder().decode(it) }
            val encPub = prefs.getString("enc_pub", null)?.let { Base64.getDecoder().decode(it) }
            val encPriv = prefs.getString("enc_priv", null)?.let { Base64.getDecoder().decode(it) }
            val createdAt = prefs.getLong("created_at", System.currentTimeMillis())

            if (signPub == null || signPriv == null || encPub == null || encPriv == null) {
                _identityState.value = IdentityState.NoIdentity
                return@withContext null
            }

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
            _identityState.value = IdentityState.Ready(identity)
            identity
        } catch (e: Throwable) {
            _identityState.value = IdentityState.Failed(e)
            throw e
        }
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
            identityId = identity.identityId,
            displayName = identity.displayName,
            signingPublicKey = identity.signingPublicKey,
            encryptionPublicKey = identity.encryptionPublicKey,
            bootstrapEphemeralPublicKey = ephemeralBootstrapPair.publicKey,
            createdAt = now,
            expiresAt = expiresAt
        )

        val signature = IdentityCrypto.signEd25519(identity.signingPrivateKey, signedData)

        // Persist ephemeral bootstrap private key so Bob can compute 3DH responder keys (Section 4)
        pendingInviteDao?.insert(
            com.torxone.app.data.entity.PendingInviteEntity(
                inviteId = inviteId,
                ephemeralPublicKey = ephemeralBootstrapPair.publicKey,
                ephemeralPrivateKey = ephemeralBootstrapPair.privateKey,
                createdAt = now,
                expiresAt = expiresAt
            )
        )

        ContactInviteV1(
            protocolVersion = 1,
            inviteId = inviteId,
            identityId = identity.identityId,
            displayName = identity.displayName,
            identitySigningPublicKey = identity.signingPublicKey,
            identityEncryptionPublicKey = identity.encryptionPublicKey,
            bootstrapEphemeralPublicKey = ephemeralBootstrapPair.publicKey,
            createdAt = now,
            expiresAt = expiresAt,
            signature = signature
        )
    }

    override suspend fun getPendingInviteEphemeralPrivateKey(inviteId: String): ByteArray? = withContext(Dispatchers.IO) {
        val invite = pendingInviteDao?.getById(inviteId) ?: return@withContext null
        if (System.currentTimeMillis() > invite.expiresAt) return@withContext null
        invite.ephemeralPrivateKey
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

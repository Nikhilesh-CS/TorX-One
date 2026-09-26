package com.torxone.app.contacts

import com.torxone.app.data.dao.BootstrapStateDao
import com.torxone.app.data.dao.ConsumedInviteDao
import com.torxone.app.data.entity.BootstrapStateEntity
import com.torxone.app.data.entity.BootstrapStatus
import com.torxone.app.data.entity.ConsumedInviteEntity
import com.torxone.app.identity.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ConsumedInviteTest {

    private lateinit var consumedInviteDao: FakeConsumedInviteDao
    private lateinit var bootstrapStateDao: FakeBootstrapStateDao
    private lateinit var localIdentity: TorXIdentity
    private lateinit var peerIdentity: TorXIdentity

    @Before
    fun setUp() {
        consumedInviteDao = FakeConsumedInviteDao()
        bootstrapStateDao = FakeBootstrapStateDao()

        // Generate identities for Alice (local) and Bob (peer)
        val aliceSigning = IdentityCrypto.generateEd25519KeyPair()
        val aliceDh = IdentityCrypto.generateX25519KeyPair()
        localIdentity = TorXIdentity(
            identityId = "alice-id",
            displayName = "Alice",
            signingPublicKey = aliceSigning.publicKey,
            signingPrivateKey = aliceSigning.privateKey,
            encryptionPublicKey = aliceDh.publicKey,
            encryptionPrivateKey = aliceDh.privateKey,
            createdAt = System.currentTimeMillis()
        )

        val bobSigning = IdentityCrypto.generateEd25519KeyPair()
        val bobDh = IdentityCrypto.generateX25519KeyPair()
        peerIdentity = TorXIdentity(
            identityId = "bob-id",
            displayName = "Bob",
            signingPublicKey = bobSigning.publicKey,
            signingPrivateKey = bobSigning.privateKey,
            encryptionPublicKey = bobDh.publicKey,
            encryptionPrivateKey = bobDh.privateKey,
            createdAt = System.currentTimeMillis()
        )
    }

    private fun createSignedInvite(
        identity: TorXIdentity,
        inviteId: String = UUID.randomUUID().toString(),
        createdAt: Long = System.currentTimeMillis(),
        expiresAt: Long = System.currentTimeMillis() + 86400000L
    ): ContactInviteV1 {
        val ephemeralKeyPair = IdentityCrypto.generateX25519KeyPair()
        val dataToSign = ContactInviteCodec.serializeForSigning(
            protocolVersion = 1,
            inviteId = inviteId,
            identityId = identity.identityId,
            displayName = identity.displayName,
            signingPublicKey = identity.signingPublicKey,
            encryptionPublicKey = identity.encryptionPublicKey,
            bootstrapEphemeralPublicKey = ephemeralKeyPair.publicKey,
            createdAt = createdAt,
            expiresAt = expiresAt
        )
        val signature = IdentityCrypto.signEd25519(identity.signingPrivateKey, dataToSign)

        return ContactInviteV1(
            protocolVersion = 1,
            inviteId = inviteId,
            identityId = identity.identityId,
            displayName = identity.displayName,
            identitySigningPublicKey = identity.signingPublicKey,
            identityEncryptionPublicKey = identity.encryptionPublicKey,
            bootstrapEphemeralPublicKey = ephemeralKeyPair.publicKey,
            createdAt = createdAt,
            expiresAt = expiresAt,
            signature = signature
        )
    }

    @Test
    fun testValidNewInviteAcceptedAndTrackedAsConsumed() = runBlocking {
        val bobInvite = createSignedInvite(peerIdentity)
        val consumedIdsBefore = consumedInviteDao.getAllConsumedInviteIds().toSet()

        // 1. Validate fresh invite
        val result = ContactInviteCodec.validate(
            invite = bobInvite,
            localIdentity = localIdentity,
            consumedInviteIds = consumedIdsBefore
        )
        assertTrue("Valid invite must pass validation", result is InviteValidationResult.Valid)

        // 2. Consume and record in DAOs
        val relId = "rel-alice-bob-1"
        consumedInviteDao.insert(
            ConsumedInviteEntity(
                inviteId = bobInvite.inviteId,
                consumedAt = System.currentTimeMillis()
            )
        )
        bootstrapStateDao.upsert(
            BootstrapStateEntity(
                relationshipId = relId,
                inviteId = bobInvite.inviteId,
                status = BootstrapStatus.LOCAL_ESTABLISHED,
                isInitiator = true,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )

        // 3. Verify persistence
        val record = consumedInviteDao.getById(bobInvite.inviteId)
        assertNotNull(record)
        assertEquals(bobInvite.inviteId, record!!.inviteId)

        val bootstrap = bootstrapStateDao.getByRelationshipId(relId)
        assertNotNull(bootstrap)
        assertEquals(BootstrapStatus.LOCAL_ESTABLISHED, bootstrap!!.status)
    }

    @Test
    fun testReusedInviteRejected() = runBlocking {
        val bobInvite = createSignedInvite(peerIdentity)

        // Pre-consume the invite
        consumedInviteDao.insert(
            ConsumedInviteEntity(
                inviteId = bobInvite.inviteId,
                consumedAt = System.currentTimeMillis()
            )
        )

        val consumedIds = consumedInviteDao.getAllConsumedInviteIds().toSet()
        val result = ContactInviteCodec.validate(
            invite = bobInvite,
            localIdentity = localIdentity,
            consumedInviteIds = consumedIds
        )

        assertTrue("Reused invite must fail validation", result is InviteValidationResult.Invalid)
        assertEquals(
            InviteValidationError.ALREADY_CONSUMED,
            (result as InviteValidationResult.Invalid).error
        )
    }

    @Test
    fun testExpiredInviteRejected() {
        val now = System.currentTimeMillis()
        val expiredInvite = createSignedInvite(
            identity = peerIdentity,
            createdAt = now - 100000L,
            expiresAt = now - 5000L // Expired 5 seconds ago
        )

        val result = ContactInviteCodec.validate(
            invite = expiredInvite,
            localIdentity = localIdentity,
            consumedInviteIds = emptySet(),
            now = now
        )

        assertTrue(result is InviteValidationResult.Invalid)
        assertEquals(
            InviteValidationError.EXPIRED,
            (result as InviteValidationResult.Invalid).error
        )
    }

    @Test
    fun testTamperedSignatureRejected() {
        val validInvite = createSignedInvite(peerIdentity)
        val tamperedSignature = validInvite.signature.clone()
        tamperedSignature[0] = (tamperedSignature[0].toInt() xor 1).toByte()

        val tamperedInvite = validInvite.copy(signature = tamperedSignature)
        val result = ContactInviteCodec.validate(
            invite = tamperedInvite,
            localIdentity = localIdentity,
            consumedInviteIds = emptySet()
        )

        assertTrue(result is InviteValidationResult.Invalid)
        assertEquals(
            InviteValidationError.INVALID_SIGNATURE,
            (result as InviteValidationResult.Invalid).error
        )
    }

    @Test
    fun testSelfInviteRejected() {
        val selfInvite = createSignedInvite(localIdentity)
        val result = ContactInviteCodec.validate(
            invite = selfInvite,
            localIdentity = localIdentity,
            consumedInviteIds = emptySet()
        )

        assertTrue(result is InviteValidationResult.Invalid)
        assertEquals(
            InviteValidationError.SELF_INVITE,
            (result as InviteValidationResult.Invalid).error
        )
    }

    @Test
    fun testMalformedQrStringRejected() {
        assertNull(ContactInviteCodec.decodeFromQrString("not-a-torx-uri"))
        assertNull(ContactInviteCodec.decodeFromQrString("torx://contact/invalid-base64-&&&"))
    }

    class FakeConsumedInviteDao : ConsumedInviteDao {
        val records = ConcurrentHashMap<String, ConsumedInviteEntity>()
        override suspend fun getAllConsumedInviteIds(): List<String> = records.keys.toList()
        override suspend fun getById(inviteId: String): ConsumedInviteEntity? = records[inviteId]
        override suspend fun insert(entity: ConsumedInviteEntity) { records[entity.inviteId] = entity }
    }

    class FakeBootstrapStateDao : BootstrapStateDao {
        val states = ConcurrentHashMap<String, BootstrapStateEntity>()
        override suspend fun getByRelationshipId(relationshipId: String): BootstrapStateEntity? = states[relationshipId]
        override suspend fun getIncompleteBootstraps(): List<BootstrapStateEntity> =
            states.values.filter { it.status != BootstrapStatus.ACTIVE && it.status != BootstrapStatus.FAILED }
        override suspend fun upsert(entity: BootstrapStateEntity) { states[entity.relationshipId] = entity }
        override suspend fun updateStatus(relationshipId: String, status: BootstrapStatus, updatedAt: Long, error: String?) {
            states[relationshipId]?.let { states[relationshipId] = it.copy(status = status, updatedAt = updatedAt, errorMessage = error) }
        }
        override suspend fun delete(relationshipId: String) { states.remove(relationshipId) }
    }
}

package com.torxone.app.connection

import com.torxone.app.crypto.CryptoManager
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * Protocol Hardening Unit Tests:
 * 1. Cryptographic Authentication of Queue Rotation (Ed25519 Signatures)
 * 2. Deadlock-free two-way handshake over active queue
 * 3. Tamper detection and rejection of forged proposals
 */
@RunWith(RobolectricTestRunner::class)
class QueueRotationHandshakeTest {

    private lateinit var aliceDao: FakeConnectionQueueDao
    private lateinit var bobDao: FakeConnectionQueueDao
    private lateinit var aliceConnectionManager: ConnectionManager
    private lateinit var bobConnectionManager: ConnectionManager

    // Generate real cryptographic keypairs using CryptoManager
    private val aliceIdentity = CryptoManager.generateIdentity("Alice")
    private val bobIdentity = CryptoManager.generateIdentity("Bob")

    private val aliceKeyHex = CryptoManager.toHex(aliceIdentity.signingPublicKey)
    private val bobKeyHex = CryptoManager.toHex(bobIdentity.signingPublicKey)

    @Before
    fun setup() {
        aliceDao = FakeConnectionQueueDao()
        bobDao = FakeConnectionQueueDao()
        aliceConnectionManager = ConnectionManager(aliceDao)
        bobConnectionManager = ConnectionManager(bobDao)
    }

    @Test
    fun testSignedRotationProposal_generationAndVerification() = runBlocking {
        // Alice connection to Bob
        val conn = aliceConnectionManager.getOrCreateConnection(bobKeyHex, aliceKeyHex)
        val proposal = aliceConnectionManager.proposeQueueRotation(conn.connectionId)

        val proposalWire = aliceConnectionManager.createSignedRotationProposal(
            connectionId = conn.connectionId,
            oldQueueId = conn.sendQueueId,
            newQueueId = proposal.proposedSendQueueId,
            generation = 1L,
            signingSecretKey = aliceIdentity.signingSecretKey
        )

        val json = JSONObject(proposalWire)
        assertEquals("QUEUE_ROTATE_PROPOSE", json.getString("type"))
        assertEquals(conn.connectionId, json.getString("connectionId"))
        assertEquals(conn.sendQueueId, json.getString("oldQueueId"))
        assertEquals(proposal.proposedSendQueueId, json.getString("newQueueId"))
        assertTrue(json.has("signature"))
        assertTrue(json.getString("signature").isNotBlank())

        // Bob receives and validates proposal
        // Setup Bob's local connection object matching Alice's sendQueue as Bob's recvQueue
        val bobConn = bobConnectionManager.getOrCreateConnection(aliceKeyHex, bobKeyHex)
        bobDao.updateRecvQueueId(bobConn.connectionId, conn.sendQueueId)

        val result = bobConnectionManager.handleSignedRotationProposal(json, bobIdentity.signingSecretKey)
        assertTrue("Proposal should be successfully authenticated and handled: ${result.error}", result.success)
        assertNotNull("Should generate signed ROTATE_ACK", result.ackWireJson)

        // Verify Bob's receive queue transitioned to newQueueId (with old queue draining)
        val updatedBobConn = bobDao.getById(bobConn.connectionId)!!
        assertEquals(proposal.proposedSendQueueId, updatedBobConn.recvQueueId)
        assertEquals(ConnectionManager.ROTATION_OLD_QUEUE_DRAINING, updatedBobConn.rotationState)
    }

    @Test
    fun testSignedRotationProposal_rejectedWhenSignatureForged() = runBlocking {
        val conn = aliceConnectionManager.getOrCreateConnection(bobKeyHex, aliceKeyHex)
        val proposal = aliceConnectionManager.proposeQueueRotation(conn.connectionId)

        // Attacker creates proposal signed with Eve's key instead of Alice's
        val eveIdentity = CryptoManager.generateIdentity("Eve")
        val forgedWire = aliceConnectionManager.createSignedRotationProposal(
            connectionId = conn.connectionId,
            oldQueueId = conn.sendQueueId,
            newQueueId = proposal.proposedSendQueueId,
            generation = 1L,
            signingSecretKey = eveIdentity.signingSecretKey
        )

        val bobConn = bobConnectionManager.getOrCreateConnection(aliceKeyHex, bobKeyHex)
        bobDao.updateRecvQueueId(bobConn.connectionId, conn.sendQueueId)

        val result = bobConnectionManager.handleSignedRotationProposal(JSONObject(forgedWire), bobIdentity.signingSecretKey)
        assertFalse("Forged proposal MUST be rejected", result.success)
        assertEquals("Cryptographic signature verification failed", result.error)
    }

    @Test
    fun testCompleteDeadlockFreeHandshake() = runBlocking {
        // Step 1: Initial state
        // Alice sends to Q_A1, Bob receives on Q_A1
        val qA1 = "queue-alice-1"
        val qB1 = "queue-bob-1"

        val aliceConn = aliceConnectionManager.getOrCreateConnection(bobKeyHex, aliceKeyHex)
        aliceDao.updateSendQueueId(aliceConn.connectionId, qA1)
        aliceDao.updateRecvQueueId(aliceConn.connectionId, qB1)

        val bobConn = bobConnectionManager.getOrCreateConnection(aliceKeyHex, bobKeyHex)
        bobDao.updateSendQueueId(bobConn.connectionId, qB1)
        bobDao.updateRecvQueueId(bobConn.connectionId, qA1)

        // Step 2: Alice initiates rotation
        val aliceProposal = aliceConnectionManager.proposeQueueRotation(aliceConn.connectionId)
        val qA2 = aliceProposal.proposedSendQueueId

        // Critical protocol check: Alice's active sendQueueId MUST STILL BE Q_A1 (NOT Q_A2)
        val aliceMidConn = aliceDao.getById(aliceConn.connectionId)!!
        assertEquals("Active send queue must stay Q_A1 during proposal", qA1, aliceMidConn.sendQueueId)
        assertEquals(qA2, aliceMidConn.pendingSendQueueId)
        assertEquals(ConnectionManager.ROTATION_PROPOSED, aliceMidConn.rotationState)

        // Alice creates signed proposal
        val proposalWire = aliceConnectionManager.createSignedRotationProposal(
            connectionId = aliceConn.connectionId,
            oldQueueId = qA1,
            newQueueId = qA2,
            generation = 1L,
            signingSecretKey = aliceIdentity.signingSecretKey
        )

        // Step 3: Bob receives proposal over Q_A1
        val bobHandling = bobConnectionManager.handleSignedRotationProposal(
            JSONObject(proposalWire),
            bobIdentity.signingSecretKey
        )
        assertTrue("Bob should succeed: ${bobHandling.error}", bobHandling.success)
        assertNotNull(bobHandling.ackWireJson)

        // Bob has activated Q_A2 and is draining Q_A1
        val bobAfterProposal = bobDao.getById(bobConn.connectionId)!!
        assertEquals("Bob's recv queue is now Q_A2", qA2, bobAfterProposal.recvQueueId)
        assertEquals(ConnectionManager.ROTATION_OLD_QUEUE_DRAINING, bobAfterProposal.rotationState)

        // Step 4: Alice receives signed ROTATE_ACK from Bob
        val ackJson = JSONObject(bobHandling.ackWireJson!!)
        val aliceAckResult = aliceConnectionManager.handleSignedRotationAck(ackJson)
        assertTrue("Alice successfully handles signed ROTATE_ACK", aliceAckResult)

        // Step 5: Alice's send queue is now transitioned to Q_A2, draining Q_A1
        val aliceFinalConn = aliceDao.getById(aliceConn.connectionId)!!
        assertEquals("Alice's active send queue is now Q_A2", qA2, aliceFinalConn.sendQueueId)
        assertEquals(ConnectionManager.ROTATION_OLD_QUEUE_DRAINING, aliceFinalConn.rotationState)
    }
}

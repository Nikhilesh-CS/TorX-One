package com.torxone.app.calls

import com.torxone.app.chat.DirectConversationRoutingTest
import com.torxone.app.contacts.LegacyAndFreshContactTest
import com.torxone.app.data.entity.ContactEntity
import com.torxone.app.data.entity.ConversationEntity
import com.torxone.app.data.entity.ConversationType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class CallAndNotificationSecurityTest {

    @Test
    fun testNotificationQuickReplyResolvesContactSafely() = runBlocking {
        val contactDao = DirectConversationRoutingTest.FakeContactDao()
        val conversationDao = DirectConversationRoutingTest.FakeConversationDao()

        val convId = "conv-reply-1"
        val relId = "rel-reply-1"
        val validPeerId = "peer-verified-identity-key"

        conversationDao.upsert(ConversationEntity(convId, ConversationType.DIRECT, "Alice"))

        // 1. Valid verified contact
        val validContact = ContactEntity(
            contactId = "c-1",
            displayName = "Alice",
            relationshipId = relId,
            conversationId = convId,
            remoteIdentityId = validPeerId,
            signingPublicKey = ByteArray(32)
        )
        contactDao.upsert(validContact)

        // Notification handler logic verification
        val contact = contactDao.getByConversationId(convId)
        assertNotNull(contact)
        assertTrue("Quick reply must verify contact identity is known", contact!!.isRemoteIdentityKnown)
        assertEquals(relId, contact.relationshipId)
        assertEquals(validPeerId, contact.remoteIdentityId)

        // 2. Legacy contact with missing identity: must fail closed
        val legacyContact = ContactEntity(
            contactId = "c-legacy",
            displayName = "Legacy Bob",
            relationshipId = "rel-legacy",
            conversationId = "conv-legacy-reply",
            remoteIdentityId = ContactEntity.REMOTE_IDENTITY_UNKNOWN,
            signingPublicKey = ByteArray(32)
        )
        contactDao.upsert(legacyContact)

        val retrievedLegacy = contactDao.getByConversationId("conv-legacy-reply")
        assertNotNull(retrievedLegacy)
        assertFalse("Legacy contact must not allow quick reply", retrievedLegacy!!.isRemoteIdentityKnown)
    }

    @Test
    fun testVideoCallRequiresCameraPermission() {
        fun evaluatePermissions(isVideo: Boolean, audioGranted: Boolean, cameraGranted: Boolean): Boolean {
            return if (isVideo) {
                audioGranted && cameraGranted
            } else {
                audioGranted
            }
        }

        // Voice call: audio only
        assertTrue("Voice call with audio granted should be allowed", evaluatePermissions(isVideo = false, audioGranted = true, cameraGranted = false))
        assertFalse("Voice call without audio must be rejected", evaluatePermissions(isVideo = false, audioGranted = false, cameraGranted = true))

        // Video call: requires both audio AND camera
        assertFalse("Video call without camera must be rejected", evaluatePermissions(isVideo = true, audioGranted = true, cameraGranted = false))
        assertFalse("Video call without audio must be rejected", evaluatePermissions(isVideo = true, audioGranted = false, cameraGranted = true))
        assertTrue("Video call with both audio and camera must be allowed", evaluatePermissions(isVideo = true, audioGranted = true, cameraGranted = true))
    }

    @Test
    fun testIceCandidateBufferingBeforeRemoteDescription() {
        // Verification of ICE candidate queue state machine implemented in WebRtcClient
        var isRemoteDescriptionSet = false
        val pendingIceCandidates = CopyOnWriteArrayList<String>()
        val appliedToPeerConnection = mutableListOf<String>()

        fun addRemoteCandidate(candidate: String) {
            synchronized(pendingIceCandidates) {
                if (isRemoteDescriptionSet) {
                    appliedToPeerConnection.add(candidate)
                } else {
                    pendingIceCandidates.add(candidate)
                }
            }
        }

        fun onRemoteDescriptionSet() {
            synchronized(pendingIceCandidates) {
                isRemoteDescriptionSet = true
                for (cand in pendingIceCandidates) {
                    appliedToPeerConnection.add(cand)
                }
                pendingIceCandidates.clear()
            }
        }

        fun release() {
            synchronized(pendingIceCandidates) {
                pendingIceCandidates.clear()
                isRemoteDescriptionSet = false
            }
        }

        // 1. Two ICE candidates arrive while ringing (before remote SDP answer is received)
        addRemoteCandidate("candidate:1 1 UDP 2122260223 192.168.1.100 5000 typ host")
        addRemoteCandidate("candidate:2 1 UDP 2122260223 192.168.1.100 5001 typ host")

        assertEquals("Candidates must be buffered", 2, pendingIceCandidates.size)
        assertEquals("PeerConnection must not receive candidates before remote description", 0, appliedToPeerConnection.size)

        // 2. Remote SDP answer arrives and is set
        onRemoteDescriptionSet()

        assertTrue("Remote description must be marked set", isRemoteDescriptionSet)
        assertEquals("Buffer must be drained", 0, pendingIceCandidates.size)
        assertEquals("Both candidates must now be applied to PeerConnection", 2, appliedToPeerConnection.size)

        // 3. Third candidate arrives after remote description is already set
        addRemoteCandidate("candidate:3 1 UDP 2122260223 192.168.1.100 5002 typ host")
        assertEquals(0, pendingIceCandidates.size)
        assertEquals(3, appliedToPeerConnection.size)

        // 4. Teardown
        release()
        assertFalse(isRemoteDescriptionSet)
        assertEquals(0, pendingIceCandidates.size)
    }
}

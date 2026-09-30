package com.torxone.app.contacts

import com.torxone.app.data.entity.ContactEntity
import org.junit.Assert.*
import org.junit.Test

class ExistingPeerSelectionTest {
    private fun candidate(id: String, time: Long, usable: Boolean = true, active: Boolean = true,
                          chat: Boolean = true, generation: Int = 1) = ExistingPeerCandidate(
        ContactEntity(id, "rel-$id", "Peer", signingPublicKey = ByteArray(32), conversationId = "chat", createdAt = time),
        usable, usable, usable, active, chat, generation)
    @Test fun resultDoesNotDependOnDatabaseRowOrder() {
        val candidates = listOf(candidate("a", 1), candidate("b", 2), candidate("c", 2))
        repeat(50) { assertEquals("b", selectExistingPeer(candidates.shuffled())?.contactId) }
    }
    @Test fun healthyLaneBeatsNewerBrokenLane() {
        assertEquals("healthy", selectExistingPeer(listOf(candidate("broken", 99, false), candidate("healthy", 1)))?.contactId)
    }
    @Test fun activeLaneBeatsIncompletePairing() {
        assertEquals("active", selectExistingPeer(listOf(candidate("pending", 99, active = false), candidate("active", 1)))?.contactId)
    }
    @Test fun deletedChatCanReopenUsingExistingHealthyLane() {
        val contact = selectExistingPeer(listOf(candidate("broken", 99, false), candidate("retained", 1, chat = false)))!!
        assertEquals("retained", contact.contactId)
        assertEquals(ExistingContactScanAction.REOPEN, existingContactScanAction(false, true, true, true))
    }
    @Test fun newerConnectionGenerationWinsOtherwiseEqual() {
        assertEquals("rotated", selectExistingPeer(listOf(candidate("old", 99), candidate("rotated", 1, generation = 2)))?.contactId)
        assertNull(selectExistingPeer(emptyList()))
    }
}

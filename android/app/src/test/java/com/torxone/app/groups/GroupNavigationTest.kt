package com.torxone.app.groups

import com.torxone.app.conversations.ConversationUiModel
import com.torxone.app.data.entity.ContactEntity
import com.torxone.app.data.entity.ConversationEntity
import com.torxone.app.data.entity.ConversationType
import com.torxone.app.data.entity.GroupEntity
import com.torxone.app.data.entity.GroupMemberEntity
import com.torxone.app.data.entity.GroupMemberRole
import com.torxone.app.data.entity.GroupMemberState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GroupNavigationTest {

    private lateinit var groupDao: TestGroupDao
    private lateinit var groupMemberDao: TestGroupMemberDao
    private lateinit var conversationDao: TestConversationDao
    private lateinit var contactDao: TestContactDao

    @Before
    fun setUp() {
        groupDao = TestGroupDao()
        groupMemberDao = TestGroupMemberDao()
        conversationDao = TestConversationDao()
        contactDao = TestContactDao()
    }

    @Test
    fun `ConversationUiModel from accurately maps ConversationType`() {
        val now = System.currentTimeMillis()

        val directEntity = ConversationEntity(
            conversationId = "conv-direct-1",
            type = ConversationType.DIRECT,
            title = "Alice",
            createdAt = now
        )
        val groupEntity = ConversationEntity(
            conversationId = "conv-group-1",
            type = ConversationType.GROUP,
            title = "Alpha Squad",
            createdAt = now
        )

        val directUiModel = ConversationUiModel.from(directEntity)
        val groupUiModel = ConversationUiModel.from(groupEntity)

        assertEquals(ConversationType.DIRECT, directUiModel.type)
        assertEquals(ConversationType.GROUP, groupUiModel.type)
    }

    @Test
    fun `direct chat lookup strictly enforces conversationId match without arbitrary fallback`() = runTest {
        val now = System.currentTimeMillis()

        // Populate two contacts in the database
        val contactBob = ContactEntity(
            contactId = "bob-id",
            displayName = "Bob",
            relationshipId = "rel-bob",
            conversationId = "conv-bob",
            signingPublicKey = ByteArray(32)
        )
        val contactCharlie = ContactEntity(
            contactId = "charlie-id",
            displayName = "Charlie",
            relationshipId = "rel-charlie",
            conversationId = "conv-charlie",
            signingPublicKey = ByteArray(32)
        )
        contactDao.upsert(contactBob)
        contactDao.upsert(contactCharlie)

        // When navigating to a group conversation or unknown conversationId
        val targetGroupId = "group-xyz"
        val resolvedContact = contactDao.getByConversationId(targetGroupId)

        // It must be null, never returning Bob or Charlie as a silent dangerous fallback
        assertNull("Group conversation must not resolve to an arbitrary direct contact", resolvedContact)
    }

    @Test
    fun `local chat deletion deletes message history but preserves group roster and membership`() = runTest {
        val groupId = "group-persist-test"
        val now = System.currentTimeMillis()

        val groupEntity = GroupEntity(
            groupId = groupId,
            conversationId = groupId,
            title = "Core Team",
            creatorIdentityId = "user-1",
            epoch = 1L,
            createdAt = now,
            updatedAt = now
        )
        val memberEntity = GroupMemberEntity(
            groupId = groupId,
            memberIdentityId = "user-1",
            contactId = "self",
            relationshipId = "self",
            role = GroupMemberRole.OWNER.name,
            state = GroupMemberState.ACTIVE.name,
            joinedEpoch = 1L,
            joinedAt = now
        )

        groupDao.upsert(groupEntity)
        groupMemberDao.upsert(memberEntity)

        // Simulating local delete of conversation
        conversationDao.deleteById(groupId)

        // Invariant: The user is still a participant in the group and group state remains intact
        val persistedGroup = groupDao.getById(groupId)
        assertNotNull(persistedGroup)
        assertEquals("Core Team", persistedGroup?.title)

        val activeMembers = groupMemberDao.getActiveMembers(groupId)
        assertEquals(1, activeMembers.size)
        assertEquals(GroupMemberState.ACTIVE.name, activeMembers[0].state)
    }
}

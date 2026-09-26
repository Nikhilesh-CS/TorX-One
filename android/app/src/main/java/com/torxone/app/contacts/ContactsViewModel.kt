package com.torxone.app.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torxone.app.connection.Connection
import com.torxone.app.connection.ConnectionManager
import com.torxone.app.crypto.SessionCrypto
import com.torxone.app.data.TorXDatabase
import com.torxone.app.data.entity.*
import com.torxone.app.identity.ContactInviteCodec
import com.torxone.app.identity.IdentityCrypto
import com.torxone.app.identity.IdentityRepository
import com.torxone.app.identity.InviteValidationResult
import com.torxone.app.relationship.RelationshipService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

data class ContactsUiState(
    val contacts: List<ContactEntity> = emptyList(),
    val myInviteQrString: String? = null,
    val pendingInviteValidation: InviteValidationResult.Valid? = null,
    val error: String? = null
)

class ContactsViewModel(
    private val database: TorXDatabase,
    private val identityRepository: IdentityRepository,
    private val sessionCrypto: SessionCrypto,
    private val connectionManager: ConnectionManager,
    private val agent: com.torxone.app.agent.TorXAgent? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ContactsUiState())
    val uiState: StateFlow<ContactsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            database.contactDao().observeAll().collect { list ->
                _uiState.update { it.copy(contacts = list) }
            }
        }
    }

    /**
     * Generate contact invite QR string for sharing.
     */
    fun generateMyInviteQr() {
        viewModelScope.launch {
            try {
                val invite = identityRepository.createContactInvite()
                val qr = ContactInviteCodec.encodeToQrString(invite)
                _uiState.update { it.copy(myInviteQrString = qr) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Failed to generate invite") }
            }
        }
    }

    /**
     * Process scanned QR string.
     */
    fun onQrScanned(qrString: String) {
        val invite = ContactInviteCodec.decodeFromQrString(qrString)
        if (invite == null) {
            _uiState.update { it.copy(error = "Invalid QR code format") }
            return
        }

        viewModelScope.launch {
            val localIdentity = identityRepository.loadIdentity()
            val existingContacts = database.contactDao().getAll()
            if (existingContacts.any { it.signingPublicKey.contentEquals(invite.identitySigningPublicKey) }) {
                _uiState.update { it.copy(error = "Contact already exists with this peer") }
                return@launch
            }
            val consumedInviteIds = existingContacts.map { it.remoteIdentityId }.filter { it.isNotBlank() }.toSet()
            val result = ContactInviteCodec.validate(invite, localIdentity, consumedInviteIds)

            when (result) {
                is InviteValidationResult.Valid -> {
                    _uiState.update { it.copy(pendingInviteValidation = result, error = null) }
                }
                is InviteValidationResult.Invalid -> {
                    _uiState.update { it.copy(error = "Invalid invite: ${result.error}") }
                }
            }
        }
    }

    /**
     * Confirm adding contact after user inspects safety fingerprint.
     */
    fun confirmAddContact(onComplete: (conversationId: String) -> Unit) {
        val valid = _uiState.value.pendingInviteValidation ?: return

        viewModelScope.launch {
            try {
                val localIdentity = identityRepository.loadIdentity()
                    ?: throw IllegalStateException("Local identity not initialized")

                val contactId = UUID.randomUUID().toString()
                val conversationId = UUID.randomUUID().toString()

                // 1. Establish pairwise relationship (3DH)
                val bootstrap = RelationshipService.establishFromInvite(
                    localIdentity = localIdentity,
                    invite = valid.invite,
                    contactId = contactId
                )

                // 2. Persist Relationship & Contact
                val relationshipEntity = PairRelationshipEntity(
                    relationshipId = bootstrap.relationship.relationshipId,
                    localIdentityId = localIdentity.identityId,
                    contactId = contactId,
                    rootSecret = bootstrap.relationship.pairRootSecret,
                    state = "ACTIVE",
                    generation = 1,
                    verifiedAt = System.currentTimeMillis()
                )
                database.pairRelationshipDao().upsert(relationshipEntity)

                val contactEntity = ContactEntity(
                    contactId = contactId,
                    relationshipId = bootstrap.relationship.relationshipId,
                    displayName = valid.invite.displayName,
                    signingPublicKey = valid.invite.identitySigningPublicKey,
                    verificationState = "VERIFIED",
                    conversationId = conversationId,
                    remoteIdentityId = valid.invite.identityId
                )
                database.contactDao().upsert(contactEntity)

                // 3. Register Connection
                val connection = Connection(
                    relationshipId = bootstrap.relationship.relationshipId,
                    generation = 1,
                    sendQueueId = bootstrap.aliceToBobQueueId,
                    recvQueueId = bootstrap.bobToAliceQueueId,
                    sendAuth = bootstrap.aliceSendAuth,
                    recvAuth = bootstrap.bobSendAuth
                )
                connectionManager.registerConnection(connection)

                val connectionDbEntity = ConnectionDbEntity(
                    connectionId = connection.connectionId,
                    relationshipId = connection.relationshipId,
                    generation = connection.generation,
                    sendQueueId = connection.sendQueueId,
                    recvQueueId = connection.recvQueueId,
                    sendAuth = connection.sendAuth,
                    recvAuth = connection.recvAuth,
                    state = "ACTIVE"
                )
                database.connectionDao().upsert(connectionDbEntity)

                // 4. Initialize Double Ratchet session
                sessionCrypto.initializeSession(
                    relationshipId = bootstrap.relationship.relationshipId,
                    sessionInitializationSecret = bootstrap.secrets.sessionInitializationSecret,
                    isInitiator = true,
                    remoteRatchetPublicKey = valid.invite.bootstrapEphemeralPublicKey,
                    localRatchetPrivateKey = bootstrap.aliceEphemeralPrivateKey!!,
                    localRatchetPublicKey = bootstrap.aliceEphemeralPublicKey
                )

                // 5. Create Conversation
                val conversationEntity = ConversationEntity(
                    conversationId = conversationId,
                    type = ConversationType.DIRECT,
                    title = valid.invite.displayName,
                    unreadCount = 0
                )
                database.conversationDao().upsert(conversationEntity)

                // 6. Send wire ContactBootstrapPayload to Bob so Bob establishes matching responder keys (Phase 4 & 5)
                val bootstrapSignedData = com.torxone.app.relationship.ContactBootstrapPayload.serializeForSigning(
                    inviteId = valid.invite.inviteId,
                    initiatorIdentityId = localIdentity.identityId,
                    displayName = localIdentity.displayName,
                    signingPub = localIdentity.signingPublicKey,
                    encryptionPub = localIdentity.encryptionPublicKey,
                    ephemeralPub = bootstrap.aliceEphemeralPublicKey
                )
                val bootstrapSig = IdentityCrypto.signEd25519(localIdentity.signingPrivateKey, bootstrapSignedData)
                val bootstrapWire = com.torxone.app.relationship.ContactBootstrapPayload(
                    inviteId = valid.invite.inviteId,
                    initiatorIdentityId = localIdentity.identityId,
                    initiatorDisplayName = localIdentity.displayName,
                    initiatorSigningPublicKey = localIdentity.signingPublicKey,
                    initiatorEncryptionPublicKey = localIdentity.encryptionPublicKey,
                    initiatorEphemeralPublicKey = bootstrap.aliceEphemeralPublicKey,
                    signature = bootstrapSig
                )

                agent?.enqueue(
                    com.torxone.app.agent.DeliveryItem(
                        deliveryId = UUID.randomUUID().toString(),
                        logicalMessageId = UUID.randomUUID().toString(),
                        conversationId = conversationId,
                        connectionId = connection.connectionId,
                        queueAddress = "invite-${valid.invite.inviteId}",
                        ciphertext = bootstrapWire.toByteArray(),
                        queueAuthenticator = ByteArray(0),
                        status = com.torxone.app.agent.DeliveryStatus.QUEUED,
                        priority = com.torxone.app.agent.DeliveryPriority.HIGH
                    )
                )

                _uiState.update { it.copy(pendingInviteValidation = null) }
                onComplete(conversationId)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Failed to add contact") }
            }
        }
    }

    fun dismissValidation() {
        _uiState.update { it.copy(pendingInviteValidation = null) }
    }
}

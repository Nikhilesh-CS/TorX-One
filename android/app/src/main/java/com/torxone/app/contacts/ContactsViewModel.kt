package com.torxone.app.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.torxone.app.connection.Connection
import com.torxone.app.connection.ConnectionManager
import com.torxone.app.crypto.SessionCrypto
import com.torxone.app.data.TorXDatabase
import com.torxone.app.data.entity.*
import com.torxone.app.identity.ContactInviteCodec
import com.torxone.app.identity.IdentityRepository
import com.torxone.app.identity.InviteValidationResult
import com.torxone.app.relationship.RelationshipService
import com.torxone.app.transport.tor.TorRoute
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

data class ContactsUiState(
    val contacts: List<ContactEntity> = emptyList(),
    val myInviteQrString: String? = null,
    val generatingInvite: Boolean = false,
    val pendingInviteValidation: InviteValidationResult.Valid? = null,
    val error: String? = null,
    val reopenedContact: ContactEntity? = null,
    val pendingEndpointUpdate: PeerEndpointUpdate? = null,
    val updatingEndpoint: Boolean = false,
    val addingContact: Boolean = false
)

data class PeerEndpointUpdate(
    val contact: ContactEntity,
    val valid: InviteValidationResult.Valid,
    val expected: TorRoute?,
    val candidate: TorRoute,
    val reopen: Boolean
)

class ContactsViewModel(
    private val database: TorXDatabase,
    private val identityRepository: IdentityRepository,
    private val sessionCrypto: SessionCrypto,
    private val sessionStore: com.torxone.app.crypto.SessionStore,
    private val connectionManager: ConnectionManager,
    private val agent: com.torxone.app.agent.TorXAgent? = null,
    private val nearbyTransport: com.torxone.app.transport.nearby.NearbyTransport? = null,
    private val torRouteManager: com.torxone.app.transport.tor.TorRouteManager? = null,
    private val localOnionAddress: () -> String? = { null },
    private val keyProtector: com.torxone.app.crypto.KeyProtector,
    private val peerTorEndpoints: com.torxone.app.transport.tor.PeerTorEndpointRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ContactsUiState())
    val uiState: StateFlow<ContactsUiState> = _uiState.asStateFlow()
    private var inviteJob: Job? = null
    private var pairingJob: Job? = null
    private var scanJob: Job? = null
    private var endpointJob: Job? = null

    init {
        viewModelScope.launch {
            database.contactDao().observeAll().collect { list ->
                _uiState.update { it.copy(contacts = list.sortedBy { contact -> contact.relationshipId }.distinctBy { contact -> contact.signingPublicKey.toList() }) }
            }
        }
    }

    /**
     * Generate contact invite QR string for sharing.
     */
    fun generateMyInviteQr() {
        if (inviteJob?.isActive == true) return
        inviteJob = viewModelScope.launch {
            _uiState.update { it.copy(generatingInvite = true, error = null) }
            try {
                val onion = withContext(Dispatchers.IO) { withTimeoutOrNull(120_000) {
                    var address = localOnionAddress()
                    while (address == null) {
                        delay(500)
                        address = localOnionAddress()
                    }
                    address
                } } ?: throw IllegalStateException("Tor could not create your address. Check your connection and tap Retry.")
                val baseInvite = identityRepository.createContactInvite()
                val unsigned = baseInvite.copy(protocolVersion = 2, torOnionAddress = onion)
                val signed = ContactInviteCodec.serializeForSigning(
                    unsigned.protocolVersion, unsigned.inviteId, unsigned.identityId,
                    unsigned.displayName, unsigned.identitySigningPublicKey,
                    unsigned.identityEncryptionPublicKey, unsigned.bootstrapEphemeralPublicKey,
                    unsigned.createdAt, unsigned.expiresAt, onion
                )
                val invite = unsigned.copy(signature = identityRepository.sign(signed))
                nearbyTransport?.registerPendingInvite(invite.inviteId)
                val qr = ContactInviteCodec.encodeToQrString(invite)
                _uiState.update { it.copy(myInviteQrString = qr) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Failed to generate invite") }
            } finally {
                _uiState.update { it.copy(generatingInvite = false) }
            }
        }
    }

    /**
     * Process scanned QR string.
     */
    fun onQrScanned(qrString: String) {
        if (endpointJob?.isActive == true || pairingJob?.isActive == true) return
        scanJob?.cancel()
        _uiState.update { it.copy(pendingEndpointUpdate = null, pendingInviteValidation = null, error = null) }
        val invite = ContactInviteCodec.decodeFromQrString(qrString)
        if (invite == null) {
            _uiState.update { it.copy(error = "Invalid QR code format") }
            return
        }

        scanJob = viewModelScope.launch {
            try {
            val localIdentity = identityRepository.loadIdentity()
            val consumedInviteIds = database.consumedInviteDao().getAllConsumedInviteIds().toSet()
            val result = ContactInviteCodec.validate(invite, localIdentity, consumedInviteIds)

            when (result) {
                is InviteValidationResult.Valid -> {
                    val candidates = database.contactDao().getAll()
                        .filter { it.signingPublicKey.contentEquals(invite.identitySigningPublicKey) }
                        .map { contact ->
                            val relationship = database.pairRelationshipDao().getById(contact.relationshipId)
                            val connection = database.connectionDao().getByRelationshipId(contact.relationshipId)
                            ExistingPeerCandidate(contact, relationship != null, connection != null,
                                sessionCrypto.hasSession(contact.relationshipId),
                                relationship?.state == "ACTIVE" && connection?.state == "ACTIVE",
                                database.conversationDao().getById(contact.conversationId) != null,
                                connection?.generation ?: 0)
                        }
                    val existing = selectExistingPeer(candidates)
                    if (existing != null) {
                        val conversation = database.conversationDao().getById(existing.conversationId)
                        val relationship = database.pairRelationshipDao().getById(existing.relationshipId)
                        val connection = database.connectionDao().getByRelationshipId(existing.relationshipId)
                        val hasSession = sessionCrypto.hasSession(existing.relationshipId)
                        val action = existingContactScanAction(conversation != null, relationship != null, connection != null, hasSession)
                        if (action != ExistingContactScanAction.REPAIR_REQUIRED && invite.torOnionAddress != null) {
                            val repository = requireNotNull(peerTorEndpoints) { "Peer endpoint repository unavailable" }
                            repository.refresh(existing.relationshipId)
                            val expected = repository.resolve(existing.relationshipId)
                            val candidate = TorRoute(invite.torOnionAddress)
                            if (requiresPeerEndpointConfirmation(expected, candidate)) {
                                _uiState.update { it.copy(pendingEndpointUpdate = PeerEndpointUpdate(existing, result,
                                    expected, candidate, action == ExistingContactScanAction.REOPEN), error = null) }
                                return@launch
                            }
                            if (!repository.bindVerifiedIfUnchanged(existing.relationshipId, candidate, expected)) {
                                _uiState.update { it.copy(error = "This contact's address changed while scanning. Scan their latest QR again.") }
                                return@launch
                            }
                        }
                        when (action) {
                            ExistingContactScanAction.ALREADY_OPEN -> {
                            agent?.triggerImmediateRetry(relationshipId = existing.relationshipId)
                            _uiState.update { it.copy(error = "Contact already exists with this peer") }
                            return@launch
                            }
                            ExistingContactScanAction.REPAIR_REQUIRED -> {
                            _uiState.update { it.copy(error = "The saved secure pairing is incomplete. Keep this contact's encrypted data; session recovery requires an authenticated pairing repair.") }
                            return@launch
                            }
                            ExistingContactScanAction.REOPEN -> Unit
                        }
                        requireNotNull(connection)
                        database.conversationDao().upsert(
                            ConversationEntity(
                                conversationId = existing.conversationId,
                                type = ConversationType.DIRECT,
                                title = existing.displayName,
                                avatarHash = existing.avatarHash
                            )
                        )
                        _uiState.update { it.copy(reopenedContact = existing, error = null) }
                        agent?.triggerImmediateRetry(relationshipId = connection.relationshipId)
                        return@launch
                    }
                    invite.torOnionAddress?.let {
                        torRouteManager?.bind("invite-${invite.inviteId}", com.torxone.app.transport.tor.TorRoute(it))
                    }
                    nearbyTransport?.registerScannedInvite(invite.inviteId)
                    _uiState.update { it.copy(pendingInviteValidation = result, error = null) }
                }
                is InviteValidationResult.Invalid -> {
                    _uiState.update { it.copy(error = "Invalid invite: ${result.error}") }
                }
            }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(error = "The contact could not be checked. Try scanning again.") }
            }
        }
    }

    fun confirmEndpointUpdate() {
        if (endpointJob?.isActive == true) return
        val pending = _uiState.value.pendingEndpointUpdate ?: return
        endpointJob = viewModelScope.launch {
            _uiState.update { it.copy(updatingEndpoint = true, error = null) }
            try {
                val localIdentity = identityRepository.loadIdentity()
                val consumed = database.consumedInviteDao().getAllConsumedInviteIds().toSet()
                val valid = ContactInviteCodec.validate(pending.valid.invite, localIdentity, consumed)
                val contact = database.contactDao().getByRelationshipId(pending.contact.relationshipId)
                val relationship = database.pairRelationshipDao().getById(pending.contact.relationshipId)
                val connection = database.connectionDao().getByRelationshipId(pending.contact.relationshipId)
                require(valid is InviteValidationResult.Valid && contact != null && relationship != null && connection != null &&
                    contact.contactId == pending.contact.contactId && contact.conversationId == pending.contact.conversationId &&
                    contact.signingPublicKey.contentEquals(pending.valid.invite.identitySigningPublicKey) &&
                    sessionCrypto.hasSession(contact.relationshipId))
                if (peerTorEndpoints?.bindVerifiedIfUnchanged(contact.relationshipId, pending.candidate, pending.expected) != true) {
                    _uiState.update { it.copy(pendingEndpointUpdate = null,
                        error = "This contact's address changed while you were reviewing it. Scan their latest QR again.") }
                    return@launch
                }
                if (pending.reopen) database.conversationDao().upsert(ConversationEntity(
                    conversationId = contact.conversationId, type = ConversationType.DIRECT,
                    title = contact.displayName, avatarHash = contact.avatarHash))
                agent?.triggerImmediateRetry(relationshipId = contact.relationshipId)
                _uiState.update { it.copy(pendingEndpointUpdate = null, reopenedContact = contact, error = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(pendingEndpointUpdate = null, error = "The address could not be updated. Scan a fresh QR from this contact.") }
            } finally { _uiState.update { it.copy(updatingEndpoint = false) } }
        }
    }

    fun dismissEndpointUpdate() {
        if (endpointJob?.isActive != true) _uiState.update { it.copy(pendingEndpointUpdate = null) }
    }

    /**
     * Confirm adding contact after user inspects safety fingerprint.
     */
    fun confirmAddContact(onComplete: (conversationId: String) -> Unit) {
        if (pairingJob?.isActive == true) return
        val valid = _uiState.value.pendingInviteValidation ?: return

        pairingJob = viewModelScope.launch {
            _uiState.update { it.copy(addingContact = true, error = null) }
            try {
                val localIdentity = identityRepository.loadIdentity()
                    ?: throw IllegalStateException("Local identity not initialized")

                val contactId = UUID.randomUUID().toString()

                // 1. Establish pairwise relationship (3DH)
                val bootstrap = RelationshipService.establishFromInvite(
                    localIdentity = localIdentity,
                    invite = valid.invite,
                    contactId = contactId
                )
                val conversationId = bootstrap.relationship.relationshipId

                // 2. Persist Relationship, Contact, Connection, Conversation, ConsumedInvite, and BootstrapState transactionally
                val relationshipEntity = PairRelationshipEntity(
                    relationshipId = bootstrap.relationship.relationshipId,
                    localIdentityId = localIdentity.identityId,
                    contactId = contactId,
                    rootSecret = keyProtector.wrap(bootstrap.relationship.pairRootSecret),
                    state = "LOCAL_ESTABLISHED",
                    generation = 1,
                    verifiedAt = System.currentTimeMillis(),
                    cryptoFormatVersion = 3
                )

                val contactEntity = ContactEntity(
                    contactId = contactId,
                    relationshipId = bootstrap.relationship.relationshipId,
                    displayName = valid.invite.displayName,
                    signingPublicKey = valid.invite.identitySigningPublicKey,
                    verificationState = "VERIFIED",
                    conversationId = conversationId,
                    remoteIdentityId = valid.invite.identityId
                )

                val connection = Connection(
                    relationshipId = bootstrap.relationship.relationshipId,
                    generation = 1,
                    sendQueueId = bootstrap.aliceToBobQueueId,
                    recvQueueId = bootstrap.bobToAliceQueueId,
                    sendAuth = bootstrap.aliceSendAuth,
                    recvAuth = bootstrap.bobSendAuth
                )

                val connectionDbEntity = ConnectionDbEntity(
                    connectionId = connection.connectionId,
                    relationshipId = connection.relationshipId,
                    generation = connection.generation,
                    sendQueueId = connection.sendQueueId,
                    recvQueueId = connection.recvQueueId,
                    sendAuth = keyProtector.wrap(connection.sendAuth),
                    recvAuth = keyProtector.wrap(connection.recvAuth),
                    state = "LOCAL_ESTABLISHED",
                    cryptoFormatVersion = 3
                )

                val conversationEntity = ConversationEntity(
                    conversationId = conversationId,
                    type = ConversationType.DIRECT,
                    title = valid.invite.displayName,
                    unreadCount = 0
                )

                val consumedInviteEntity = ConsumedInviteEntity(
                    inviteId = valid.invite.inviteId,
                    consumedAt = System.currentTimeMillis()
                )

                // Validate/sign the local return route before committing any pairing rows.
                val initiatorOnion = localOnionAddress()
                    ?: throw IllegalStateException("Tor address became unavailable. Recreate the contact invite.")
                val bootstrapWire = signedBootstrapPayload(localIdentity, valid.invite.inviteId,
                    bootstrap.aliceEphemeralPublicKey, initiatorOnion)
                val outboxEntity = bootstrapOutboxItem(valid.invite.inviteId, connection,
                    conversationId, bootstrapWire.toByteArray())

                // The actor owns initialization; the callback owns one Room transaction.
                // A crash exposes either no pairing or its complete session/bootstrap.
                sessionCrypto.initializeAndCommit(
                    relationshipId = bootstrap.relationship.relationshipId,
                    sessionInitializationSecret = bootstrap.secrets.sessionInitializationSecret,
                    isInitiator = true,
                    remoteRatchetPublicKey = valid.invite.bootstrapEphemeralPublicKey,
                    localRatchetPrivateKey = bootstrap.aliceEphemeralPrivateKey!!,
                    localRatchetPublicKey = bootstrap.aliceEphemeralPublicKey
                ) { initialSession -> database.withTransaction {
                    database.bootstrapStateDao().upsert(
                        BootstrapStateEntity(
                            relationshipId = bootstrap.relationship.relationshipId,
                            inviteId = valid.invite.inviteId,
                            status = BootstrapStatus.BOOTSTRAP_QUEUED,
                            isInitiator = true
                        )
                    )
                    database.pairRelationshipDao().upsert(relationshipEntity)
                    valid.invite.torOnionAddress?.let { onion ->
                        database.peerTorEndpointDao().upsert(com.torxone.app.transport.tor.PeerTorEndpointRepository.entity(
                            connection.relationshipId, com.torxone.app.transport.tor.TorRoute(onion)))
                    }
                    database.contactDao().upsert(contactEntity)
                    database.connectionDao().upsert(connectionDbEntity)
                    database.conversationDao().upsert(conversationEntity)
                    database.consumedInviteDao().insert(consumedInviteEntity)
                    sessionStore.saveSession(initialSession)
                    database.outboxDao().insert(outboxEntity)
                    consolidateDirectConversations(database)
                } }

                // 2. Register Connection in-memory and initialize Double Ratchet session
                connectionManager.registerConnection(connection)

                // The invite address is only a bootstrap capability. Normal traffic and
                // the authenticated bootstrap ACK use the derived permanent send queue.
                // Bind that queue to the already verified invite onion before bootstrap
                // is sent, then persist it through TorRouteManager for process restarts.
                peerTorEndpoints?.refresh(connection.relationshipId)

                nearbyTransport?.registerScannedInvite(valid.invite.inviteId)

                agent?.wake()

                _uiState.update { it.copy(pendingInviteValidation = null) }
                onComplete(database.contactDao().getByRelationshipId(connection.relationshipId)?.conversationId ?: conversationId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update { it.copy(error = e.message ?: "Failed to add contact") }
            } finally {
                _uiState.update { it.copy(addingContact = false) }
            }
        }
    }

    fun dismissValidation() {
        if (pairingJob?.isActive == true) return
        _uiState.update { it.copy(pendingInviteValidation = null) }
    }

    fun consumeReopenedContact() {
        _uiState.update { it.copy(reopenedContact = null) }
    }
}

internal fun requiresPeerEndpointConfirmation(current: TorRoute?, candidate: TorRoute): Boolean =
    current != null && current != candidate

internal enum class ExistingContactScanAction { ALREADY_OPEN, REOPEN, REPAIR_REQUIRED }

internal fun existingContactScanAction(
    conversationExists: Boolean,
    relationshipExists: Boolean,
    connectionExists: Boolean,
    sessionExists: Boolean
): ExistingContactScanAction = when {
    !relationshipExists || !connectionExists || !sessionExists -> ExistingContactScanAction.REPAIR_REQUIRED
    conversationExists -> ExistingContactScanAction.ALREADY_OPEN
    else -> ExistingContactScanAction.REOPEN
}

internal fun bindPermanentTorRoute(
    routeManager: com.torxone.app.transport.tor.TorRouteManager?,
    connection: Connection,
    peerOnionAddress: String?
) {
    if (routeManager == null || peerOnionAddress == null) return
    routeManager.bind(connection.sendQueueId, com.torxone.app.transport.tor.TorRoute(peerOnionAddress))
}

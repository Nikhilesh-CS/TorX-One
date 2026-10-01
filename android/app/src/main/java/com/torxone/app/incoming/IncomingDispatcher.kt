package com.torxone.app.incoming

import android.util.Log
import kotlinx.coroutines.sync.withLock
import com.torxone.app.agent.DeliveryItem
import com.torxone.app.agent.DeliveryPriority
import com.torxone.app.agent.DeliveryStatus
import com.torxone.app.agent.TorXAgent
import com.torxone.app.connection.Connection
import com.torxone.app.connection.ConnectionManager
import com.torxone.app.crypto.EncryptedSessionMessage
import com.torxone.app.crypto.SessionCrypto
import com.torxone.app.data.dao.ProcessedEnvelopeDao
import com.torxone.app.data.entity.ProcessedEnvelopeEntity
import com.torxone.app.identity.IdentityCrypto
import com.torxone.app.protocol.*
import com.torxone.app.transport.TransportType
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 12-Stage Incoming Dispatcher pipeline.
 *
 * Each stage is independent and strictly verified:
 * 1. Validate transport frame length
 * 2. Parse opaque transport envelope
 * 3. Resolve queue and connection
 * 4. Authenticate outer capability via constant-time HMAC-SHA256
 * 5. Dedupe transport envelope (re-ACKs duplicates)
 * 6. Crypto decrypt via Double Ratchet
 * 7. Parse SecureEnvelope
 * 8. Validate secure envelope bindings and timestamp skew
 * 9. Dispatch to feature handler
 * 10. Persist message state
 * 11. Commit receive state to deduplication table
 * 12. Send secure authenticated ACK back to sender
 */
class IncomingDispatcher(
    private val connectionManager: ConnectionManager,
    private val sessionCrypto: SessionCrypto,
    private val processedEnvelopeDao: ProcessedEnvelopeDao,
    private val chatReceiver: ChatReceiver,
    private val deliveryReceiptHandler: DeliveryReceiptHandler,
    private val agent: TorXAgent,
    private val localIdentityIdProvider: () -> String?,
    private val presenceHandler: PresenceHandler? = null,
    private val typingHandler: TypingHandler? = null,
    private val reactionHandler: ReactionHandler? = null,
    private val editHandler: EditHandler? = null,
    private val deleteHandler: DeleteHandler? = null,
    private val mediaHandler: MediaHandler? = null,
    private val groupHandler: GroupHandler? = null,
    private val callHandler: com.torxone.app.calls.CallHandler? = null,
    private val groupDao: com.torxone.app.data.dao.GroupDao? = null,
    private val groupMemberDao: com.torxone.app.data.dao.GroupMemberDao? = null,
    private val transactionRunner: suspend (suspend () -> Unit) -> Unit = { it() },
    private val sessionStore: com.torxone.app.crypto.SessionStore? = null,
    private val pendingInviteDao: com.torxone.app.data.dao.PendingInviteDao? = null,
    private val identityRepository: com.torxone.app.identity.IdentityRepository? = null,
    private val connectionDao: com.torxone.app.data.dao.ConnectionDao? = null,
    private val contactDao: com.torxone.app.data.dao.ContactDao? = null,
    private val conversationDao: com.torxone.app.data.dao.ConversationDao? = null,
    private val consumedInviteDao: com.torxone.app.data.dao.ConsumedInviteDao? = null,
    private val bootstrapStateDao: com.torxone.app.data.dao.BootstrapStateDao? = null,
    private val pairRelationshipDao: com.torxone.app.data.dao.PairRelationshipDao? = null,
    private val keyProtector: com.torxone.app.crypto.KeyProtector,
    private val authenticatedRemoteIdentityProvider: suspend (String) -> String? = { null },
    private val torRouteManager: com.torxone.app.transport.tor.TorRouteManager? = null,
    private val consolidateDirectChats: suspend () -> Unit = {},
    private val peerTorEndpointDao: com.torxone.app.data.dao.PeerTorEndpointDao? = null,
    private val peerTorEndpoints: com.torxone.app.transport.tor.PeerTorEndpointRepository? = null,
    private val profileAvatarContext: android.content.Context? = null,
    private val featureDao: com.torxone.app.data.dao.FeatureDao? = null,
    private val securityPolicyService: com.torxone.app.privacy.SecurityPolicyService? = null
) {
    companion object {
        private const val TAG = "IncomingDispatcher"
    }

    private val processingEnvelopeIds = ConcurrentHashMap.newKeySet<String>()
    private val ephemeralReplay = object : LinkedHashMap<String, Long>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 4096
    }

    private val bootstrapLocks = Array(32) { kotlinx.coroutines.sync.Mutex() }

    suspend fun dispatch(rawBytes: ByteArray, transportType: TransportType): Boolean {
        if (rawBytes.size > ProtocolLimits.MAX_TRANSPORT_ENVELOPE_BYTES) return false
        val queue = runCatching { ProtocolCodec.decodeTransportEnvelope(rawBytes).queueAddress }.getOrNull()
        if (queue?.startsWith("invite-") == true) {
            return bootstrapLocks[(queue.hashCode() and Int.MAX_VALUE) % bootstrapLocks.size].withLock {
                dispatchInternal(rawBytes, transportType)
            }
        }
        return dispatchInternal(rawBytes, transportType)
    }

    private suspend fun dispatchInternal(rawBytes: ByteArray, transportType: TransportType): Boolean {
        // Stage 1: Validate transport frame length
        if (rawBytes.size > ProtocolLimits.MAX_TRANSPORT_ENVELOPE_BYTES) {
            Log.e(TAG, "[STAGE 1 FAIL] Frame size ${rawBytes.size} exceeds maximum ${ProtocolLimits.MAX_TRANSPORT_ENVELOPE_BYTES}")
            return false
        }

        // Stage 2: Parse opaque transport envelope
        val opaqueEnvelope = try {
            ProtocolCodec.decodeTransportEnvelope(rawBytes)
        } catch (e: Exception) {
            Log.e(TAG, "[STAGE 2 FAIL] Malformed transport envelope: ${e.message}")
            return false
        }

        val envShort = opaqueEnvelope.envelopeId.take(8)
        Log.d(TAG, "[RX] env=$envShort arrived via $transportType on queue ${opaqueEnvelope.queueAddress.take(8)}")

        // Stage 3: Resolve queue / connection (or bilateral bootstrap handshake)
        var connection = connectionManager.getConnectionByRecvQueue(opaqueEnvelope.queueAddress)
        if (connection == null) {
            if (opaqueEnvelope.queueAddress.startsWith("invite-") && pendingInviteDao != null && identityRepository != null) {
                val inviteId = opaqueEnvelope.queueAddress.removePrefix("invite-")
                if (consumedInviteDao?.getById(inviteId) != null) {
                    return confirmBootstrapRetry(inviteId, opaqueEnvelope)
                }
                val pendingInvite = pendingInviteDao.getById(inviteId)
                if (pendingInvite == null) {
                    Log.e(TAG, "[BOOTSTRAP REJECT] No matching pending invite found for $inviteId")
                    return false
                }
                if (System.currentTimeMillis() > pendingInvite.expiresAt) {
                    Log.e(TAG, "[BOOTSTRAP REJECT] Pending invite $inviteId has expired")
                    pendingInviteDao.delete(pendingInvite.inviteId)
                    return false
                }
                val localIdentity = identityRepository.loadIdentity()
                if (localIdentity != null) {
                    val bootstrapPayload = try {
                        com.torxone.app.relationship.ContactBootstrapPayload.fromByteArray(opaqueEnvelope.opaqueCiphertext)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse bootstrap payload: ${e.message}")
                        return false
                    }
                    if (bootstrapPayload.inviteId != inviteId) {
                        Log.e(TAG, "[BOOTSTRAP REJECT] Invite ID mismatch: queue=$inviteId, payload=${bootstrapPayload.inviteId}")
                        return false
                    }
                    val signedData = com.torxone.app.relationship.ContactBootstrapPayload.serializeForSigning(
                        inviteId = bootstrapPayload.inviteId,
                        initiatorIdentityId = bootstrapPayload.initiatorIdentityId,
                        displayName = bootstrapPayload.initiatorDisplayName,
                        signingPub = bootstrapPayload.initiatorSigningPublicKey,
                        encryptionPub = bootstrapPayload.initiatorEncryptionPublicKey,
                        ephemeralPub = bootstrapPayload.initiatorEphemeralPublicKey,
                        initiatorTorOnionAddress = bootstrapPayload.initiatorTorOnionAddress
                    )
                    if (!IdentityCrypto.verifyEd25519(bootstrapPayload.initiatorSigningPublicKey, signedData, bootstrapPayload.signature)) {
                        Log.e(TAG, "Bootstrap payload signature verification failed!")
                        return false
                    }

                    val verifiedTorRoute = bootstrapPayload.initiatorTorOnionAddress?.let {
                        com.torxone.app.transport.tor.TorRoute(it)
                    }
                    val invitePrivateKey = identityRepository.getPendingInviteEphemeralPrivateKey(inviteId)
                        ?: return false
                    // Establish responder 3DH
                    val responderResult = com.torxone.app.relationship.RelationshipService.establishResponder(
                        localIdentity = localIdentity,
                        ephemeralBootstrapPrivateKey = invitePrivateKey,
                        remoteEphemeralPublicKey = bootstrapPayload.initiatorEphemeralPublicKey,
                        remoteSigningPublicKey = bootstrapPayload.initiatorSigningPublicKey,
                        remoteEncryptionPublicKey = bootstrapPayload.initiatorEncryptionPublicKey,
                        remoteDisplayName = bootstrapPayload.initiatorDisplayName
                    )

                    val conn = Connection(
                        connectionId = UUID.randomUUID().toString(),
                        relationshipId = responderResult.relationship.relationshipId,
                        generation = 1,
                        sendQueueId = responderResult.bobToAliceQueueId,
                        recvQueueId = responderResult.aliceToBobQueueId,
                        sendAuth = responderResult.bobSendAuth,
                        recvAuth = responderResult.aliceSendAuth
                    )

                    val contactId = responderResult.relationship.contactId

                    transactionRunner {
                        bootstrapStateDao?.upsert(
                            com.torxone.app.data.entity.BootstrapStateEntity(
                                relationshipId = responderResult.relationship.relationshipId,
                                inviteId = inviteId,
                                status = com.torxone.app.data.entity.BootstrapStatus.LOCAL_ESTABLISHED,
                                isInitiator = false
                            )
                        )

                        pairRelationshipDao?.upsert(
                            com.torxone.app.data.entity.PairRelationshipEntity(
                                relationshipId = responderResult.relationship.relationshipId,
                                localIdentityId = localIdentity.identityId,
                                contactId = contactId,
                                rootSecret = keyProtector.wrap(responderResult.relationship.pairRootSecret),
                                state = "LOCAL_ESTABLISHED",
                                generation = 1,
                                cryptoFormatVersion = 3
                            )
                        )

                        verifiedTorRoute?.let { route ->
                            peerTorEndpointDao?.upsert(com.torxone.app.transport.tor.PeerTorEndpointRepository.entity(conn.relationshipId, route))
                        }

                        connectionDao?.upsert(
                            com.torxone.app.data.entity.ConnectionDbEntity(
                                connectionId = conn.connectionId,
                                relationshipId = conn.relationshipId,
                                generation = conn.generation,
                                sendQueueId = conn.sendQueueId,
                                recvQueueId = conn.recvQueueId,
                                sendAuth = keyProtector.wrap(conn.sendAuth),
                                recvAuth = keyProtector.wrap(conn.recvAuth),
                                state = "LOCAL_ESTABLISHED",
                                cryptoFormatVersion = 3
                            )
                        )

                        contactDao?.upsert(
                            com.torxone.app.data.entity.ContactEntity(
                                contactId = contactId,
                                relationshipId = responderResult.relationship.relationshipId,
                                displayName = bootstrapPayload.initiatorDisplayName,
                                signingPublicKey = bootstrapPayload.initiatorSigningPublicKey,
                                verificationState = "VERIFIED",
                                conversationId = responderResult.relationship.relationshipId,
                                remoteIdentityId = bootstrapPayload.initiatorIdentityId
                            )
                        )

                        conversationDao?.upsert(
                            com.torxone.app.data.entity.ConversationEntity(
                                conversationId = responderResult.relationship.relationshipId,
                                type = com.torxone.app.data.entity.ConversationType.DIRECT,
                                title = bootstrapPayload.initiatorDisplayName,
                                unreadCount = 0
                            )
                        )
                    }

                    consolidateDirectChats()
                    // Durably initialize Double Ratchet session before invite deletion and activation
                    sessionCrypto.initializeSession(
                        relationshipId = responderResult.relationship.relationshipId,
                        sessionInitializationSecret = responderResult.secrets.sessionInitializationSecret,
                        isInitiator = false,
                        remoteRatchetPublicKey = bootstrapPayload.initiatorEphemeralPublicKey,
                        localRatchetPrivateKey = invitePrivateKey,
                        localRatchetPublicKey = pendingInvite.ephemeralPublicKey
                    )

                    // Atomically consume invite and transition state to ACTIVE
                    transactionRunner {
                        consumedInviteDao?.insert(
                            com.torxone.app.data.entity.ConsumedInviteEntity(
                                inviteId = inviteId,
                                consumedAt = System.currentTimeMillis()
                            )
                        )

                        pendingInviteDao.delete(pendingInvite.inviteId)

                        connectionDao?.updateState(conn.connectionId, "ACTIVE")
                        pairRelationshipDao?.updateState(responderResult.relationship.relationshipId, "ACTIVE")

                        bootstrapStateDao?.updateStatus(
                            responderResult.relationship.relationshipId,
                            com.torxone.app.data.entity.BootstrapStatus.ACTIVE
                        )
                    }

                    connectionManager.registerConnection(conn)

                    peerTorEndpoints?.refresh(conn.relationshipId)
                    // Legacy test wiring may omit the Room repository; production never does.
                    if (peerTorEndpoints == null && verifiedTorRoute != null)
                        torRouteManager?.bind(conn.sendQueueId, verifiedTorRoute)

                    sendAck(conn, bootstrapPayload.inviteId, opaqueEnvelope.envelopeId, bootstrapPayload.initiatorIdentityId)

                    Log.i(TAG, "[BOOTSTRAP SUCCESS] Established bilateral relationship with ${bootstrapPayload.initiatorDisplayName}")
                    return true
                }
            }

            Log.w(TAG, "[STAGE 3 FAIL] No connection found for queue ${opaqueEnvelope.queueAddress}")
            return false
        }

        // Stage 4: Authenticate outer capability via constant-time HMAC-SHA256
        if (connection.recvAuth.size != 32 || opaqueEnvelope.queueAuthenticator.size != 32) {
            Log.e(TAG, "[STAGE 4 FAIL CLOSED] Established connection or envelope has invalid queue-authenticator length")
            return false
        }
        val expectedAuth = IdentityCrypto.computeQueueAuthenticator(
            queueAuthSecret = connection.recvAuth,
            envelopeId = opaqueEnvelope.envelopeId,
            queueAddress = opaqueEnvelope.queueAddress,
            ciphertext = opaqueEnvelope.opaqueCiphertext
        )
        if (!MessageDigest.isEqual(expectedAuth, opaqueEnvelope.queueAuthenticator)) {
            Log.e(TAG, "[STAGE 4 FAIL] Outer queue HMAC authenticator verification failed")
            return false
        }

        if (com.torxone.app.crypto.EphemeralCipher.isFrame(opaqueEnvelope.opaqueCiphertext)) {
            return try {
                val aad = "torx-aad-v1:${connection.generation}:${opaqueEnvelope.queueAddress}".toByteArray(Charsets.UTF_8)
                val env = ProtocolCodec.decodeSecureEnvelope(com.torxone.app.crypto.EphemeralCipher.decrypt(
                    connection.recvAuth, opaqueEnvelope.opaqueCiphertext, aad
                ))
                val expectedRemoteIdentity = contactDao?.getByRelationshipId(connection.relationshipId)
                    ?.takeIf { it.isRemoteIdentityKnown }?.remoteIdentityId
                    ?: authenticatedRemoteIdentityProvider(connection.relationshipId)
                    ?: return false
                require(env.senderIdentity == expectedRemoteIdentity)
                require(env.recipientBinding == localIdentityIdProvider() && env.recipientBinding.isNotBlank())
                require(env.directionSequence == 0L && env.groupMetadata == null)
                require(env.messageType in setOf(MessageType.PRESENCE_UPDATE, MessageType.TYPING_START, MessageType.TYPING_STOP))
                val now = System.currentTimeMillis()
                require(env.timestamp in (now - 45_000L)..(now + 5_000L))
                val key = "${connection.relationshipId}:${env.logicalMessageId}"
                synchronized(ephemeralReplay) {
                    if (ephemeralReplay.containsKey(key)) return true
                    ephemeralReplay[key] = now
                }
                when (env.messageType) {
                    MessageType.PRESENCE_UPDATE -> presenceHandler?.handlePresenceUpdate(connection, env)
                    else -> typingHandler?.handleTypingEvent(connection, env)
                }
                true
            } catch (e: Exception) {
                Log.w(TAG, "Invalid ephemeral frame", e)
                false
            }
        }

        // Stage 5: Dedupe transport envelope
        val existingProcessed = processedEnvelopeDao.getByEnvelopeId(opaqueEnvelope.envelopeId)
        if (existingProcessed != null) {
            Log.w(TAG, "[STAGE 5] Duplicate envelope $envShort already processed — re-sending ACK")
            // Re-send ACK with original logical message ID
            sendAck(connection, existingProcessed.logicalMessageId, opaqueEnvelope.envelopeId)
            return true
        }
        // Stage 6: Deserialize ciphertext
        val localId = localIdentityIdProvider()
        if (localId.isNullOrBlank()) {
            Log.e(TAG, "[STAGE 6 FAIL CLOSED] Established relationship envelope cannot be processed: local identity is unavailable")
            return false
        }

        val encryptedMsg = try {
            EncryptedSessionMessage.deserialize(opaqueEnvelope.opaqueCiphertext)
        } catch (e: Exception) {
            Log.e(TAG, "[STAGE 6 FAIL] Deserializing session message failed: ${e.message}")
            return false
        }
        val aad = "torx-aad-v1:${connection.generation}:${opaqueEnvelope.queueAddress}".toByteArray(Charsets.UTF_8)

        val processingKey = "${connection.relationshipId}:${opaqueEnvelope.envelopeId}"
        if (!processingEnvelopeIds.add(processingKey)) return false

        var decryptedEnvelope: SecureEnvelope? = null
        var requiresSequence = false
        var reservedSequence: Long? = null

        // Stage 6 to 11: Decrypt & Atomic Commit Boundary
        try {
            sessionCrypto.decryptAndCommit(
                relationshipId = connection.relationshipId,
                message = encryptedMsg,
                associatedData = aad
            ) { decryptedBytes, updatedState ->
                // Stage 7: Parse SecureEnvelope
                val secureEnvelope = ProtocolCodec.decodeSecureEnvelope(decryptedBytes)

                // Stage 8: Validate secure envelope
                val now = System.currentTimeMillis()
                val timestamp = secureEnvelope.timestamp
                val expiredContent = com.torxone.app.privacy.DisappearingPolicy.expired(secureEnvelope.expiresAt, now)
                val expiredMediaControl = knownExpiredMediaControl(connection, secureEnvelope)
                if (timestamp <= 0L || (!expiredContent && !expiredMediaControl && timestamp < now && now - timestamp > ProtocolLimits.MAX_TIMESTAMP_SKEW_MS) ||
                    (timestamp > now && timestamp - now > ProtocolLimits.MAX_TIMESTAMP_SKEW_MS)
                ) {
                    val skew = if (timestamp <= 0L) Long.MAX_VALUE else if (timestamp < now) now - timestamp else timestamp - now
                    throw IllegalStateException("Timestamp skew $skew exceeds allowed ${ProtocolLimits.MAX_TIMESTAMP_SKEW_MS}")
                }
                require(secureEnvelope.expiresAt == null || secureEnvelope.messageType in setOf(
                    MessageType.TEXT, MessageType.IMAGE, MessageType.VIDEO, MessageType.AUDIO, MessageType.FILE, MessageType.VOICE_NOTE,
                    MessageType.UNKNOWN, MessageType.LOCATION, MessageType.CONTACT, MessageType.STICKER
                )) { "Expiry is only valid on message content" }

                if (secureEnvelope.recipientBinding.isBlank() || secureEnvelope.recipientBinding != localId) {
                    throw IllegalStateException("Recipient binding mismatch: expected $localId, got ${secureEnvelope.recipientBinding}")
                }

                // Stage 8b: Sender-Authentication Invariant (Phase 2)
                // Bind remote identity to authenticated relationship
                val expectedRemoteIdentity = contactDao?.getByRelationshipId(connection.relationshipId)
                    ?.takeIf { it.isRemoteIdentityKnown }?.remoteIdentityId
                    ?: authenticatedRemoteIdentityProvider(connection.relationshipId)
                    ?: throw IllegalStateException("Sender-Authentication failure: relationship identity binding is unavailable")
                if (secureEnvelope.senderIdentity.isBlank() || secureEnvelope.senderIdentity != expectedRemoteIdentity) {
                    throw IllegalStateException("Sender-Authentication failure: envelope sender does not match authenticated relationship")
                }

                // Stage 8c: Validate directional sequence requirements (read current sequence, validate incoming > current, DO NOT mutate memory or DB yet)
                requiresSequence = secureEnvelope.messageType.requiresApplicationSequence() ||
                    (secureEnvelope.messageType == MessageType.UNKNOWN && secureEnvelope.directionSequence > 0)
                if (requiresSequence) {
                    if (secureEnvelope.directionSequence <= 0) {
                        throw IllegalStateException("Message type ${secureEnvelope.messageType} requires positive directional sequence, got ${secureEnvelope.directionSequence}")
                    }
                    val accepted = connectionManager.reserveRecvSequence(connection.relationshipId, secureEnvelope.directionSequence)
                    if (!accepted) {
                        throw IllegalStateException("Non-monotonic directional sequence: received ${secureEnvelope.directionSequence}, current ${connection.recvSequence}")
                    }
                    reservedSequence = secureEnvelope.directionSequence
                }

                // Stage 8c: Validate group authorization and epoch if group envelope
                if (secureEnvelope.groupMetadata != null) {
                    val gMeta = secureEnvelope.groupMetadata
                    if (groupDao != null && groupMemberDao != null) {
                        if (gMeta.groupId.isBlank() || secureEnvelope.conversationId != gMeta.groupId) {
                            throw IllegalStateException("Group envelope metadata does not match its conversation binding")
                        }
                        // Only validate membership for non-invite messages
                        if (secureEnvelope.messageType != MessageType.GROUP_CREATE &&
                            secureEnvelope.messageType != MessageType.GROUP_MEMBER_INVITE
                        ) {
                            val group = groupDao.getById(gMeta.groupId)
                            if (group == null) {
                                throw IllegalStateException("Received group message for unknown group ${gMeta.groupId}")
                            }
                            val member = groupMemberDao.getMember(gMeta.groupId, secureEnvelope.senderIdentity)
                            if (member == null || member.state != com.torxone.app.data.entity.GroupMemberState.ACTIVE.name) {
                                throw IllegalStateException("Sender ${secureEnvelope.senderIdentity} is not an active member of group ${gMeta.groupId}")
                            }
                            if (gMeta.groupEpoch < member.joinedEpoch) {
                                throw IllegalStateException("Group message epoch ${gMeta.groupEpoch} precedes member joined epoch ${member.joinedEpoch}")
                            }
                        }
                    }
                }

                // Stage 9, 10, 11: Atomic Database Transaction
                // Ratchet receive state + Persisted receive sequence + Message persistence + Dedup record committed together!
                transactionRunner {
                    sessionStore?.saveSession(updatedState)
                    if (requiresSequence) {
                        connectionDao?.updateRecvSequence(connection.relationshipId, secureEnvelope.directionSequence)
                    }
                    // Dispatch to feature handler
                    when (secureEnvelope.messageType) {
                        MessageType.TEXT -> {
                            val ok = chatReceiver.receiveTextMessage(connection, secureEnvelope)
                            if (!ok) throw IllegalStateException("Text message receiver returned failure")
                        }
                        MessageType.DELIVERY_ACK -> {
                            deliveryReceiptHandler.handleDeliveryAck(secureEnvelope, connection)
                        }
                        MessageType.READ_RECEIPT -> {
                            deliveryReceiptHandler.handleReadReceipt(secureEnvelope, connection)
                        }
                        MessageType.PRESENCE_UPDATE -> {
                            presenceHandler?.handlePresenceUpdate(connection, secureEnvelope)
                        }
                        MessageType.TYPING_START, MessageType.TYPING_STOP -> {
                            typingHandler?.handleTypingEvent(connection, secureEnvelope)
                        }
                        MessageType.REACTION -> {
                            requireHandlerSuccess(reactionHandler?.handleReaction(secureEnvelope), "REACTION")
                        }
                        MessageType.EDIT -> {
                            requireHandlerSuccess(editHandler?.handleEdit(secureEnvelope), "EDIT")
                        }
                        MessageType.DELETE -> {
                            requireHandlerSuccess(deleteHandler?.handleDelete(secureEnvelope), "DELETE")
                        }
                        MessageType.IMAGE,
                        MessageType.VIDEO,
                        MessageType.AUDIO,
                        MessageType.FILE,
                        MessageType.VOICE_NOTE -> {
                            requireHandlerSuccess(mediaHandler?.handleMediaDescriptor(connection, secureEnvelope), "MEDIA_DESCRIPTOR")
                        }
                        MessageType.FILE_PROGRESS -> {
                            requireHandlerSuccess(mediaHandler?.handleMediaChunk(connection, secureEnvelope), "MEDIA_CHUNK")
                        }
                        MessageType.FILE_ACCEPT -> {
                            requireHandlerSuccess(mediaHandler?.handleMediaAccept(connection, secureEnvelope), "MEDIA_ACCEPT")
                        }
                        MessageType.FILE_COMPLETE -> {
                            requireHandlerSuccess(mediaHandler?.handleMediaComplete(connection, secureEnvelope), "MEDIA_COMPLETE")
                        }
                        MessageType.FILE_RESUME -> {
                            requireHandlerSuccess(mediaHandler?.handleMediaResume(connection, secureEnvelope), "MEDIA_RESUME")
                        }
                        MessageType.FILE_CANCEL -> {
                            requireHandlerSuccess(mediaHandler?.handleMediaCancel(connection, secureEnvelope), "MEDIA_CANCEL")
                        }
                        MessageType.GROUP_CREATE,
                        MessageType.GROUP_MEMBER_INVITE -> {
                            requireHandlerSuccess(groupHandler?.handleGroupCreateOrInvite(connection, secureEnvelope), "GROUP_INVITE")
                        }
                        MessageType.GROUP_MEMBER_ACCEPT -> {
                            requireHandlerSuccess(groupHandler?.handleMemberJoined(connection, secureEnvelope), "GROUP_MEMBER_ACCEPT")
                        }
                        MessageType.GROUP_MEMBER_REMOVE -> {
                            requireHandlerSuccess(groupHandler?.handleMemberRemove(connection, secureEnvelope), "GROUP_MEMBER_REMOVE")
                        }
                        MessageType.GROUP_ROLE_CHANGE -> {
                            requireHandlerSuccess(groupHandler?.handleRoleChange(connection, secureEnvelope), "GROUP_ROLE_CHANGE")
                        }
                        MessageType.GROUP_NAME_CHANGE -> {
                            requireHandlerSuccess(groupHandler?.handleNameChange(connection, secureEnvelope), "GROUP_NAME_CHANGE")
                        }
                        MessageType.GROUP_AVATAR_CHANGE -> {
                            requireHandlerSuccess(groupHandler?.handleAvatarChange(connection, secureEnvelope), "GROUP_AVATAR_CHANGE")
                        }
                        MessageType.CALL_OFFER,
                        MessageType.CALL_RINGING,
                        MessageType.CALL_ANSWER,
                        MessageType.CALL_ICE_CANDIDATE,
                        MessageType.CALL_CONNECTED,
                        MessageType.CALL_END,
                        MessageType.CALL_DECLINE,
                        MessageType.CALL_BUSY -> {
                            if (callHandler == null) throw IllegalStateException("Call handler unavailable")
                            // Defer responses until this actor has completed its crypto commit.
                            // RINGING/BUSY/ICE can encrypt through this same relationship.
                        }
                        MessageType.PROFILE_UPDATE -> {
                            if (contactDao == null || conversationDao == null) throw IllegalStateException("Profile update dependencies unavailable")
                            handleProfileUpdate(connection, secureEnvelope)
                        }
                        else -> {
                            // Commit the authenticated ratchet/deduplication state even when a
                            // future feature is unavailable. Unknown data never becomes an action.
                            if (secureEnvelope.conversationId.isNotBlank()) {
                                requireHandlerSuccess(chatReceiver.receiveTextMessage(connection, secureEnvelope.copy(
                                    messageType = MessageType.UNKNOWN,
                                    payload = "This message requires a newer TorX version".toByteArray(),
                                    replyToMessageId = null
                                )), "Unsupported message")
                            }
                        }
                    }

                    // 3. Commit deduplication record
                    processedEnvelopeDao.insert(
                        ProcessedEnvelopeEntity(
                            envelopeId = opaqueEnvelope.envelopeId,
                            logicalMessageId = secureEnvelope.logicalMessageId,
                            processedAt = now
                        )
                    )
                }

                decryptedEnvelope = secureEnvelope
                if (requiresSequence) {
                    connectionManager.commitRecvSequence(connection.relationshipId, secureEnvelope.directionSequence)
                }
            }
        } catch (e: Exception) {
            reservedSequence?.let { connectionManager.releaseRecvSequenceReservation(connection.relationshipId, it) }
            Log.e(TAG, "[STAGE 6-11 FAIL] Decryption or atomic commit failed: ${e.message}")
            return false
        } finally {
            processingEnvelopeIds.remove(processingKey)
        }

        // Stage 12: acknowledge every durable frame after its transaction commits.
        // ACK itself is excluded to prevent acknowledgement loops; best-effort ephemeral
        // frames are handled before this durable path.
        val env = decryptedEnvelope
        if (env != null && env.messageType.name.startsWith("CALL_")) {
            callHandler?.handleCallSignal(connection, env)
        }
        if (env != null && env.messageType != MessageType.DELIVERY_ACK) {
            sendAck(connection, env.logicalMessageId, opaqueEnvelope.envelopeId, env.senderIdentity)
        }

        return true
    }

    /** Retransmission after a lost ACK must confirm, never initialize or reset the ratchet. */
    private suspend fun knownExpiredMediaControl(connection: Connection, envelope: SecureEnvelope): Boolean {
        val security = securityPolicyService ?: return false
        val codec = com.torxone.app.media.MediaProtocolCodec
        val mediaId = try { when (envelope.messageType) {
            MessageType.FILE_PROGRESS -> codec.decodeChunk(envelope.payload).mediaId
            MessageType.FILE_ACCEPT -> codec.decodeAccept(envelope.payload).mediaId
            MessageType.FILE_COMPLETE -> codec.decodeComplete(envelope.payload).mediaId
            MessageType.FILE_RESUME -> codec.decodeResumeRequest(envelope.payload).mediaId
            MessageType.FILE_CANCEL -> codec.decodeCancel(envelope.payload).mediaId
            else -> return false
        } } catch (_: Exception) { return false }
        val tombstone = security.dao.expiredMedia(mediaId, connection.relationshipId) ?: return false
        val localConversation = envelope.groupMetadata?.groupId
            ?: envelope.conversationId.takeIf { it == tombstone.conversationId }
            ?: contactDao?.getByRelationshipId(connection.relationshipId)?.conversationId
        return localConversation == tombstone.conversationId
    }

    private suspend fun confirmBootstrapRetry(inviteId: String, opaque: OpaqueTransportEnvelope): Boolean {
        val state = bootstrapStateDao?.getByInviteId(inviteId) ?: return false
        if (state.isInitiator || state.status != com.torxone.app.data.entity.BootstrapStatus.ACTIVE) return false
        val contact = contactDao?.getByRelationshipId(state.relationshipId) ?: return false
        val conn = connectionManager.getConnectionByRelationship(state.relationshipId) ?: return false
        val payload = runCatching {
            com.torxone.app.relationship.ContactBootstrapPayload.fromByteArray(opaque.opaqueCiphertext)
        }.getOrNull() ?: return false
        if (payload.inviteId != inviteId || payload.initiatorIdentityId != contact.remoteIdentityId ||
            !MessageDigest.isEqual(payload.initiatorSigningPublicKey, contact.signingPublicKey)) return false
        val signed = com.torxone.app.relationship.ContactBootstrapPayload.serializeForSigning(
            payload.inviteId, payload.initiatorIdentityId, payload.initiatorDisplayName,
            payload.initiatorSigningPublicKey, payload.initiatorEncryptionPublicKey,
            payload.initiatorEphemeralPublicKey, payload.initiatorTorOnionAddress)
        if (!IdentityCrypto.verifyEd25519(contact.signingPublicKey, signed, payload.signature)) return false
        payload.initiatorTorOnionAddress?.let { onion ->
            val route = com.torxone.app.transport.tor.TorRoute(onion)
            peerTorEndpoints?.bindVerified(conn.relationshipId, route)
            if (peerTorEndpoints == null) torRouteManager?.bind(conn.sendQueueId, route)
        }
        sendAck(conn, inviteId, opaque.envelopeId, contact.remoteIdentityId)
        return true
    }

    private suspend fun sendAck(
        connection: Connection,
        originalMessageId: String,
        originalEnvelopeId: String,
        recipientBinding: String? = null
    ) {
        try {
            val boundRecipient = recipientBinding
                ?: contactDao?.getByRelationshipId(connection.relationshipId)?.remoteIdentityId
                ?: throw IllegalStateException("Cannot send an established ACK without a verified remote identity binding")
            require(boundRecipient.isNotBlank() && boundRecipient != com.torxone.app.data.entity.ContactEntity.REMOTE_IDENTITY_UNKNOWN)
            val ack = DeliveryAck(
                originalMessageId = originalMessageId,
                originalEnvelopeId = originalEnvelopeId,
                receivedAt = System.currentTimeMillis()
            )
            val ackEnvelope = SecureEnvelope(
                protocolVersion = 1,
                logicalMessageId = UUID.randomUUID().toString(),
                conversationId = "",
                senderIdentity = localIdentityIdProvider() ?: "",
                recipientBinding = boundRecipient,
                messageType = MessageType.DELIVERY_ACK,
                payload = ack.toByteArray()
            )
            val ackBytes = ProtocolCodec.encodeSecureEnvelope(ackEnvelope)
            val aad = "torx-aad-v1:${connection.generation}:${connection.sendQueueId}".toByteArray(Charsets.UTF_8)

            val encryptedAck = sessionCrypto.encrypt(connection.relationshipId, ackBytes, aad)
            val opaqueAck = encryptedAck.serialize()

            val deliveryItem = DeliveryItem(
                deliveryId = UUID.randomUUID().toString(),
                logicalMessageId = ackEnvelope.logicalMessageId,
                conversationId = "",
                connectionId = connection.connectionId,
                queueAddress = connection.sendQueueId,
                ciphertext = opaqueAck,
                queueAuthenticator = connection.sendAuth,
                status = DeliveryStatus.QUEUED,
                priority = DeliveryPriority.HIGH,
                expectsAck = false
            )

            Log.d(TAG, "[ACK] Enqueueing ACK for msg=${originalMessageId.take(8)}")
            agent.enqueue(deliveryItem)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send ACK: ${e.message}")
        }
    }

    private suspend fun handleProfileUpdate(connection: Connection, envelope: SecureEnvelope) {
        val capabilities = PeerCapabilitiesCodec.decode(envelope.payload)
        if (capabilities != null) {
            val previous = featureDao?.capabilities(connection.relationshipId)
            if (previous == null || previous.updatedAt <= envelope.timestamp) {
                featureDao?.saveCapabilities(com.torxone.app.data.entity.PeerCapabilitiesEntity(
                    connection.relationshipId, capabilities.sorted().joinToString(","), envelope.timestamp))
            }
            return
        }
        val update = com.torxone.app.profile.ProfileUpdateCodec.decode(envelope.payload)
        val contact = contactDao?.getByRelationshipId(connection.relationshipId) ?: return
        if (update.version > 0 && update.version <= contact.profileUpdatedAt) return
        if (update.version == 0L && contact.profileUpdatedAt > 0) return
        val avatarHash = if (!update.hasAvatarUpdate) contact.avatarHash else update.avatar?.let {
            com.torxone.app.profile.ProfileAvatarStorage.save(requireNotNull(profileAvatarContext), it)
        }
        val newDisplayName = update.name
        // Separate secure lanes belonging to the same verified identity share profile metadata.
        val aliases = contactDao.getAll().filter {
            it.remoteIdentityId == contact.remoteIdentityId && it.signingPublicKey.contentEquals(contact.signingPublicKey)
        }
        for (alias in aliases) {
            if (update.version > 0 && alias.profileUpdatedAt > update.version) continue
            contactDao.upsert(alias.copy(displayName = newDisplayName, about = update.about,
                avatarHash = if (update.hasAvatarUpdate) avatarHash else alias.avatarHash, profileUpdatedAt = update.version))
            val conv = conversationDao?.getById(alias.conversationId)
            if (conv != null && conv.type == com.torxone.app.data.entity.ConversationType.DIRECT) {
                conversationDao?.upsert(conv.copy(title = newDisplayName,
                    avatarHash = if (update.hasAvatarUpdate) avatarHash else alias.avatarHash))
            }
        }
        Log.i(TAG, "[PROFILE_UPDATE] Updated contact ${contact.contactId.take(8)} displayName to '$newDisplayName'")
    }

    private fun requireHandlerSuccess(result: Boolean?, handler: String) {
        if (result != true) {
            throw IllegalStateException("$handler handler did not apply the envelope; receive transaction must be retried")
        }
    }
}

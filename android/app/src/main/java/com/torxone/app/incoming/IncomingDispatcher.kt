package com.torxone.app.incoming

import android.util.Log
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
    private val bootstrapStateDao: com.torxone.app.data.dao.BootstrapStateDao? = null
) {
    companion object {
        private const val TAG = "IncomingDispatcher"
    }

    suspend fun dispatch(rawBytes: ByteArray, transportType: TransportType): Boolean {
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
                    Log.e(TAG, "[BOOTSTRAP REJECT] Invite $inviteId has already been consumed")
                    return false
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
                        ephemeralPub = bootstrapPayload.initiatorEphemeralPublicKey
                    )
                    if (!IdentityCrypto.verifyEd25519(bootstrapPayload.initiatorSigningPublicKey, signedData, bootstrapPayload.signature)) {
                        Log.e(TAG, "Bootstrap payload signature verification failed!")
                        return false
                    }

                    // Establish responder 3DH
                    val responderResult = com.torxone.app.relationship.RelationshipService.establishResponder(
                        localIdentity = localIdentity,
                        ephemeralBootstrapPrivateKey = pendingInvite.ephemeralPrivateKey,
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

                        connectionDao?.upsert(
                            com.torxone.app.data.entity.ConnectionDbEntity(
                                connectionId = conn.connectionId,
                                relationshipId = conn.relationshipId,
                                generation = conn.generation,
                                sendQueueId = conn.sendQueueId,
                                recvQueueId = conn.recvQueueId,
                                sendAuth = conn.sendAuth,
                                recvAuth = conn.recvAuth,
                                state = "ACTIVE"
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

                        consumedInviteDao?.insert(
                            com.torxone.app.data.entity.ConsumedInviteEntity(
                                inviteId = inviteId,
                                consumedAt = System.currentTimeMillis()
                            )
                        )

                        pendingInviteDao.delete(pendingInvite.inviteId)

                        bootstrapStateDao?.updateStatus(
                            responderResult.relationship.relationshipId,
                            com.torxone.app.data.entity.BootstrapStatus.ACTIVE
                        )
                    }

                    connectionManager.registerConnection(conn)

                    sessionCrypto.initializeSession(
                        relationshipId = responderResult.relationship.relationshipId,
                        sessionInitializationSecret = responderResult.secrets.sessionInitializationSecret,
                        isInitiator = false,
                        remoteRatchetPublicKey = bootstrapPayload.initiatorEphemeralPublicKey,
                        localRatchetPrivateKey = pendingInvite.ephemeralPrivateKey,
                        localRatchetPublicKey = pendingInvite.ephemeralPublicKey
                    )

                    sendAck(conn, bootstrapPayload.inviteId, opaqueEnvelope.envelopeId, bootstrapPayload.initiatorDisplayName)

                    Log.i(TAG, "[BOOTSTRAP SUCCESS] Established bilateral relationship with ${bootstrapPayload.initiatorDisplayName}")
                    return true
                }
            }

            Log.w(TAG, "[STAGE 3 FAIL] No connection found for queue ${opaqueEnvelope.queueAddress}")
            return false
        }

        // Stage 4: Authenticate outer capability via constant-time HMAC-SHA256
        if (connection.recvAuth.isNotEmpty()) {
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
        val encryptedMsg = try {
            EncryptedSessionMessage.deserialize(opaqueEnvelope.opaqueCiphertext)
        } catch (e: Exception) {
            Log.e(TAG, "[STAGE 6 FAIL] Deserializing session message failed: ${e.message}")
            return false
        }
        val aad = "torx-aad-v1:${connection.generation}:${opaqueEnvelope.queueAddress}".toByteArray(Charsets.UTF_8)

        var decryptedEnvelope: SecureEnvelope? = null

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
                val skew = Math.abs(now - secureEnvelope.timestamp)
                if (skew > ProtocolLimits.MAX_TIMESTAMP_SKEW_MS) {
                    throw IllegalStateException("Timestamp skew $skew exceeds allowed ${ProtocolLimits.MAX_TIMESTAMP_SKEW_MS}")
                }

                val localId = localIdentityIdProvider()
                if (localId != null && secureEnvelope.recipientBinding.isNotEmpty() && secureEnvelope.recipientBinding != localId) {
                    throw IllegalStateException("Recipient binding mismatch: expected $localId, got ${secureEnvelope.recipientBinding}")
                }

                // Stage 8b: Validate directional sequence requirements (read current sequence, validate incoming > current, DO NOT mutate memory or DB yet)
                val requiresSequence = secureEnvelope.messageType.requiresApplicationSequence()
                if (requiresSequence) {
                    if (secureEnvelope.directionSequence <= 0) {
                        throw IllegalStateException("Message type ${secureEnvelope.messageType} requires positive directional sequence, got ${secureEnvelope.directionSequence}")
                    }
                    val accepted = connectionManager.validateRecvSequence(connection.relationshipId, secureEnvelope.directionSequence)
                    if (!accepted) {
                        throw IllegalStateException("Non-monotonic directional sequence: received ${secureEnvelope.directionSequence}, current ${connection.recvSequence}")
                    }
                }

                // Stage 8c: Validate group authorization and epoch if group envelope
                if (secureEnvelope.groupMetadata != null) {
                    val gMeta = secureEnvelope.groupMetadata
                    if (groupDao != null && groupMemberDao != null) {
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
                            deliveryReceiptHandler.handleDeliveryAck(secureEnvelope)
                        }
                        MessageType.READ_RECEIPT -> {
                            deliveryReceiptHandler.handleReadReceipt(secureEnvelope)
                        }
                        MessageType.PRESENCE_UPDATE -> {
                            presenceHandler?.handlePresenceUpdate(connection, secureEnvelope)
                        }
                        MessageType.TYPING_START, MessageType.TYPING_STOP -> {
                            typingHandler?.handleTypingEvent(connection, secureEnvelope)
                        }
                        MessageType.REACTION -> {
                            reactionHandler?.handleReaction(secureEnvelope)
                        }
                        MessageType.EDIT -> {
                            editHandler?.handleEdit(secureEnvelope)
                        }
                        MessageType.DELETE -> {
                            deleteHandler?.handleDelete(secureEnvelope)
                        }
                        MessageType.IMAGE,
                        MessageType.VIDEO,
                        MessageType.AUDIO,
                        MessageType.FILE,
                        MessageType.VOICE_NOTE -> {
                            mediaHandler?.handleMediaDescriptor(connection, secureEnvelope)
                        }
                        MessageType.FILE_PROGRESS -> {
                            mediaHandler?.handleMediaChunk(connection, secureEnvelope)
                        }
                        MessageType.FILE_COMPLETE -> {
                            mediaHandler?.handleMediaComplete(connection, secureEnvelope)
                        }
                        MessageType.FILE_RESUME -> {
                            mediaHandler?.handleMediaResume(connection, secureEnvelope)
                        }
                        MessageType.FILE_CANCEL -> {
                            mediaHandler?.handleMediaCancel(secureEnvelope)
                        }
                        MessageType.GROUP_CREATE,
                        MessageType.GROUP_MEMBER_INVITE -> {
                            groupHandler?.handleGroupCreateOrInvite(connection, secureEnvelope)
                        }
                        MessageType.GROUP_MEMBER_ACCEPT -> {
                            groupHandler?.handleMemberJoined(connection, secureEnvelope)
                        }
                        MessageType.GROUP_MEMBER_REMOVE -> {
                            groupHandler?.handleMemberRemove(connection, secureEnvelope)
                        }
                        MessageType.GROUP_ROLE_CHANGE -> {
                            groupHandler?.handleRoleChange(connection, secureEnvelope)
                        }
                        MessageType.GROUP_NAME_CHANGE -> {
                            groupHandler?.handleNameChange(connection, secureEnvelope)
                        }
                        MessageType.GROUP_AVATAR_CHANGE -> {
                            groupHandler?.handleAvatarChange(connection, secureEnvelope)
                        }
                        MessageType.CALL_OFFER,
                        MessageType.CALL_RINGING,
                        MessageType.CALL_ANSWER,
                        MessageType.CALL_ICE_CANDIDATE,
                        MessageType.CALL_CONNECTED,
                        MessageType.CALL_END,
                        MessageType.CALL_DECLINE,
                        MessageType.CALL_BUSY -> {
                            callHandler?.handleCallSignal(connection, secureEnvelope)
                        }
                        MessageType.PROFILE_UPDATE -> {
                            handleProfileUpdate(connection, secureEnvelope)
                        }
                        else -> {
                            Log.w(TAG, "Unhandled message type ${secureEnvelope.messageType}")
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

                // Stage 11b: Only after transaction succeeds, update in-memory Connection state
                if (requiresSequence) {
                    connectionManager.commitRecvSequence(connection.relationshipId, secureEnvelope.directionSequence)
                }

                decryptedEnvelope = secureEnvelope
            }
        } catch (e: Exception) {
            Log.e(TAG, "[STAGE 6-11 FAIL] Decryption or atomic commit failed: ${e.message}")
            return false
        }

        // Stage 12: If TEXT or Media descriptor, send secure authenticated ACK
        val env = decryptedEnvelope
        if (env != null && (env.messageType == MessageType.TEXT ||
                    env.messageType == MessageType.IMAGE ||
                    env.messageType == MessageType.VIDEO ||
                    env.messageType == MessageType.AUDIO ||
                    env.messageType == MessageType.FILE ||
                    env.messageType == MessageType.VOICE_NOTE)
        ) {
            sendAck(connection, env.logicalMessageId, opaqueEnvelope.envelopeId, env.senderIdentity)
        }

        return true
    }

    private suspend fun sendAck(
        connection: Connection,
        originalMessageId: String,
        originalEnvelopeId: String,
        recipientBinding: String = ""
    ) {
        try {
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
                recipientBinding = recipientBinding,
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
        val payloadStr = String(envelope.payload, Charsets.UTF_8)
        val parts = payloadStr.split("\n", limit = 2)
        val newDisplayName = parts.getOrNull(0)?.trim() ?: return
        if (newDisplayName.isBlank()) return

        val contact = contactDao?.getByRelationshipId(connection.relationshipId) ?: return
        val updatedContact = contact.copy(displayName = newDisplayName)
        contactDao.upsert(updatedContact)

        val conv = conversationDao?.getById(contact.conversationId)
        if (conv != null && conv.type == com.torxone.app.data.entity.ConversationType.DIRECT) {
            conversationDao?.upsert(conv.copy(title = newDisplayName))
        }
        Log.i(TAG, "[PROFILE_UPDATE] Updated contact ${contact.contactId.take(8)} displayName to '$newDisplayName'")
    }
}

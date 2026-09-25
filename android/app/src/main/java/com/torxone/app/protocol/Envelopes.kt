package com.torxone.app.protocol

import java.util.UUID

/**
 * Authenticated inner envelope — exists INSIDE encryption.
 * The transport cannot inspect it.
 *
 * This is the canonical structure for ALL TorX messages:
 * chat, receipts, reactions, edits, group events, call signals, etc.
 */
data class SecureEnvelope(
    val protocolVersion: Int = 1,

    /** Unique logical message ID for deduplication */
    val logicalMessageId: String = UUID.randomUUID().toString(),

    /** Conversation this belongs to */
    val conversationId: String,

    /** Sender's identity (signing public key fingerprint) */
    val senderIdentity: String,

    /** Binding to intended recipient — prevents relay misdirection */
    val recipientBinding: String,

    /** What kind of message this is */
    val messageType: MessageType,

    /** Sender's wall clock timestamp */
    val timestamp: Long = System.currentTimeMillis(),

    /** The actual message payload */
    val payload: ByteArray,

    /** Optional: what message this replies to */
    val replyToMessageId: String? = null,

    /** Optional: group metadata for group messages */
    val groupMetadata: GroupEnvelopeMetadata? = null,

    /** Optional: message expiry */
    val expiresAt: Long? = null,

    /** Directional sequence number (Phase 7 & 8) — independent for each peer direction */
    val directionSequence: Long = 0L
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SecureEnvelope) return false
        return logicalMessageId == other.logicalMessageId
    }

    override fun hashCode(): Int = logicalMessageId.hashCode()
}

/**
 * Group metadata included in group message envelopes.
 */
data class GroupEnvelopeMetadata(
    val groupId: String,
    val groupEpoch: Int,
    val keyVersion: Int
)

/**
 * The outer transport envelope — all the network ever sees.
 *
 * NOT included: senderPublicKey, recipientPublicKey, contactName,
 * groupId, messageType, profile. That's the biggest metadata privacy rule.
 */
data class OpaqueTransportEnvelope(
    val version: Int = 1,

    /** Queue address this envelope is destined for */
    val queueAddress: String,

    /** Unique envelope ID (for deduplication at transport level) */
    val envelopeId: String = UUID.randomUUID().toString(),

    /** Encrypted ciphertext — opaque to all intermediaries */
    val opaqueCiphertext: ByteArray,

    /** Authenticator proving sender has queue send authority */
    val queueAuthenticator: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OpaqueTransportEnvelope) return false
        return envelopeId == other.envelopeId
    }

    override fun hashCode(): Int = envelopeId.hashCode()
}

/**
 * All message types in TorX.
 */
enum class MessageType {
    // Chat
    TEXT,
    IMAGE,
    VIDEO,
    AUDIO,
    FILE,
    VOICE_NOTE,
    LOCATION,
    CONTACT,
    STICKER,

    // Meta
    DELIVERY_ACK,
    READ_RECEIPT,
    TYPING_START,
    TYPING_STOP,
    CONTACT_BOOTSTRAP,
    CONTACT_BOOTSTRAP_ACK,

    // Reactions & edits
    REACTION,
    EDIT,
    DELETE,

    // Group
    GROUP_CREATE,
    GROUP_MEMBER_INVITE,
    GROUP_MEMBER_ACCEPT,
    GROUP_MEMBER_REMOVE,
    GROUP_ROLE_CHANGE,
    GROUP_NAME_CHANGE,
    GROUP_AVATAR_CHANGE,
    GROUP_KEY_ROTATE,

    // Calls
    CALL_OFFER,
    CALL_RINGING,
    CALL_ANSWER,
    CALL_ICE_CANDIDATE,
    CALL_CONNECTED,
    CALL_END,
    CALL_DECLINE,
    CALL_BUSY,

    // Media transfer
    FILE_OFFER,
    FILE_ACCEPT,
    FILE_PROGRESS,
    FILE_COMPLETE,
    FILE_RESUME,
    FILE_CANCEL,

    // Profile
    PROFILE_UPDATE,

    // Connection management
    CONNECTION_ROTATE,
    QUEUE_ROTATE,

    // Presence
    PRESENCE_UPDATE
}

/**
 * Policy defining whether a message type requires an application-level directional sequence.
 * User-visible durable messages require a strictly positive sequence.
 * Internal transfer/control frames are sequence-exempt.
 */
fun MessageType.requiresApplicationSequence(): Boolean = when (this) {
    MessageType.TEXT,
    MessageType.IMAGE,
    MessageType.VIDEO,
    MessageType.AUDIO,
    MessageType.FILE,
    MessageType.VOICE_NOTE,
    MessageType.LOCATION,
    MessageType.CONTACT,
    MessageType.STICKER,
    MessageType.REACTION,
    MessageType.EDIT,
    MessageType.DELETE,
    MessageType.GROUP_CREATE,
    MessageType.GROUP_MEMBER_INVITE,
    MessageType.GROUP_MEMBER_ACCEPT,
    MessageType.GROUP_MEMBER_REMOVE,
    MessageType.GROUP_ROLE_CHANGE,
    MessageType.GROUP_NAME_CHANGE,
    MessageType.GROUP_AVATAR_CHANGE,
    MessageType.GROUP_KEY_ROTATE -> true

    MessageType.DELIVERY_ACK,
    MessageType.READ_RECEIPT,
    MessageType.TYPING_START,
    MessageType.TYPING_STOP,
    MessageType.CONTACT_BOOTSTRAP,
    MessageType.CONTACT_BOOTSTRAP_ACK,
    MessageType.CALL_OFFER,
    MessageType.CALL_RINGING,
    MessageType.CALL_ANSWER,
    MessageType.CALL_ICE_CANDIDATE,
    MessageType.CALL_CONNECTED,
    MessageType.CALL_END,
    MessageType.CALL_DECLINE,
    MessageType.CALL_BUSY,
    MessageType.FILE_OFFER,
    MessageType.FILE_ACCEPT,
    MessageType.FILE_PROGRESS,
    MessageType.FILE_COMPLETE,
    MessageType.FILE_RESUME,
    MessageType.FILE_CANCEL,
    MessageType.PROFILE_UPDATE,
    MessageType.CONNECTION_ROTATE,
    MessageType.QUEUE_ROTATE,
    MessageType.PRESENCE_UPDATE -> false
}


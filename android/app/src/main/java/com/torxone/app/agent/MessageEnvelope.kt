package com.torxone.app.agent

/**
 * TorX One 2.0 — Message Envelope
 *
 * The fundamental unit of communication in the TorX Agent protocol.
 * Every piece of data — text messages, call signals, group events,
 * profile updates, presence — is wrapped in this envelope before
 * entering the delivery queue.
 *
 * Key properties:
 * 1. Transport-agnostic: The envelope is encrypted once, delivered by any transport.
 * 2. Sequence-tracked: Every envelope has a monotonic sequence number within its queue.
 * 3. Hash-chained: previousMessageHash creates a tamper-evident ordering chain.
 * 4. Protocol-backed states: CREATED → ENCRYPTED → QUEUED → TRANSMITTING → ACCEPTED → DELIVERED → READ
 */
data class MessageEnvelope(
    /** Globally unique envelope identifier. */
    val envelopeId: String,

    /** Pairwise connection this envelope belongs to. */
    val connectionId: String,

    /** Application-level message identifier (may group multiple envelopes). */
    val messageId: String,

    /** Queue this envelope is in (send queue or receive queue). */
    val queueId: String,

    /** Monotonic sequence number within the queue. */
    val sequenceNumber: Long,

    /** SHA-256 hash of the previous envelope for ordering verification. */
    val previousMessageHash: String?,

    /** When this envelope was created. */
    val timestamp: Long,

    /** Application-level message type. Determines how the payload is interpreted after decryption. */
    val messageType: EnvelopeType,

    /** Crypto header for the ratchet session. */
    val cryptoHeader: CryptoHeader?,

    /** Encrypted payload. Opaque to transport layer. */
    val ciphertext: String,

    /** Current state in the delivery state machine. */
    val state: EnvelopeState = EnvelopeState.CREATED,

    /** How many times delivery has been attempted. */
    val retryCount: Int = 0,

    /** When to next attempt delivery (null = immediate). */
    val nextRetryAt: Long? = null,

    /** When this envelope expires and should be dropped. */
    val expiresAt: Long = timestamp + DEFAULT_TTL_MS,

    /** Which transport successfully delivered this envelope (filled after success). */
    val transportUsed: String? = null
) {
    companion object {
        /** Default time-to-live: 7 days */
        const val DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000
        /** Maximum retry count before giving up. */
        const val MAX_RETRIES = 100
    }
}

/**
 * All application-level message types that flow through the delivery queue.
 * This replaces the scattered MeshProtocol.TYPE_* constants for the agent layer.
 *
 * Every type gets the same delivery guarantees: sequence tracking,
 * ACK/retry, deduplication, transport failover.
 */
enum class EnvelopeType {
    // Chat messages
    MSG,
    REACTION,
    POLL_VOTE,

    // Delivery receipts
    ACK,
    READ,

    // Call signaling (now goes through the same queue!)
    CALL_OFFER,
    CALL_ANSWER,
    ICE_CANDIDATE,
    CALL_ACK,
    CALL_END,

    // Group protocol
    GROUP_MESSAGE,
    GROUP_INVITE,
    GROUP_JOIN,
    GROUP_UPDATE,
    GROUP_LEAVE,
    GROUP_KEY,
    GROUP_SYNC_REQUEST,
    GROUP_SYNC_RESPONSE,
    GROUP_KEY_REQUEST,
    GROUP_JOIN_REQUEST,
    GROUP_INVITE_LINK,

    // Media transfer
    MEDIA_OFFER,
    MEDIA_CHUNK,
    MEDIA_ACK,
    MEDIA_COMPLETE,

    // Presence & profile
    PRESENCE,
    PROFILE_UPDATE,
    REQUEST_PROFILE_PHOTO,
    PROFILE_PHOTO_CHUNK,

    // Music sync
    MUSIC_NOTE,
    MUSIC_SYNC,

    // Session protocol
    SESSION_MSG,

    // System
    PING,
    PONG,
    HELLO,
    RELAY;

    companion object {
        /** Map legacy MeshProtocol type strings to EnvelopeType. */
        fun fromWireType(wireType: String): EnvelopeType = when (wireType) {
            "msg" -> MSG
            "reaction" -> REACTION
            "poll_vote" -> POLL_VOTE
            "ack" -> ACK
            "read" -> READ
            "call_offer" -> CALL_OFFER
            "call_answer" -> CALL_ANSWER
            "ice_candidate" -> ICE_CANDIDATE
            "call_ack" -> CALL_ACK
            "call_end" -> CALL_END
            "group_msg" -> GROUP_MESSAGE
            "group_invite" -> GROUP_INVITE
            "group_join" -> GROUP_JOIN
            "group_update" -> GROUP_UPDATE
            "group_leave" -> GROUP_LEAVE
            "group_key" -> GROUP_KEY
            "group_sync_request" -> GROUP_SYNC_REQUEST
            "group_sync_response" -> GROUP_SYNC_RESPONSE
            "group_key_request" -> GROUP_KEY_REQUEST
            "group_join_request" -> GROUP_JOIN_REQUEST
            "group_invite_link" -> GROUP_INVITE_LINK
            "media_offer" -> MEDIA_OFFER
            "media_chunk" -> MEDIA_CHUNK
            "media_ack" -> MEDIA_ACK
            "media_complete" -> MEDIA_COMPLETE
            "presence" -> PRESENCE
            "profile_update" -> PROFILE_UPDATE
            "req_profile_photo" -> REQUEST_PROFILE_PHOTO
            "profile_photo_chunk" -> PROFILE_PHOTO_CHUNK
            "music_note" -> MUSIC_NOTE
            "music_sync" -> MUSIC_SYNC
            "session_msg" -> SESSION_MSG
            "ping" -> PING
            "pong" -> PONG
            "hello" -> HELLO
            "relay" -> RELAY
            else -> MSG // Fallback
        }

        /** Convert back to legacy wire type string for compatibility. */
        fun toWireType(type: EnvelopeType): String = when (type) {
            MSG -> "msg"
            REACTION -> "reaction"
            POLL_VOTE -> "poll_vote"
            ACK -> "ack"
            READ -> "read"
            CALL_OFFER -> "call_offer"
            CALL_ANSWER -> "call_answer"
            ICE_CANDIDATE -> "ice_candidate"
            CALL_ACK -> "call_ack"
            CALL_END -> "call_end"
            GROUP_MESSAGE -> "group_msg"
            GROUP_INVITE -> "group_invite"
            GROUP_JOIN -> "group_join"
            GROUP_UPDATE -> "group_update"
            GROUP_LEAVE -> "group_leave"
            GROUP_KEY -> "group_key"
            GROUP_SYNC_REQUEST -> "group_sync_request"
            GROUP_SYNC_RESPONSE -> "group_sync_response"
            GROUP_KEY_REQUEST -> "group_key_request"
            GROUP_JOIN_REQUEST -> "group_join_request"
            GROUP_INVITE_LINK -> "group_invite_link"
            MEDIA_OFFER -> "media_offer"
            MEDIA_CHUNK -> "media_chunk"
            MEDIA_ACK -> "media_ack"
            MEDIA_COMPLETE -> "media_complete"
            PRESENCE -> "presence"
            PROFILE_UPDATE -> "profile_update"
            REQUEST_PROFILE_PHOTO -> "req_profile_photo"
            PROFILE_PHOTO_CHUNK -> "profile_photo_chunk"
            MUSIC_NOTE -> "music_note"
            MUSIC_SYNC -> "music_sync"
            SESSION_MSG -> "session_msg"
            PING -> "ping"
            PONG -> "pong"
            HELLO -> "hello"
            RELAY -> "relay"
        }
    }
}

/**
 * Protocol-backed delivery states.
 *
 * These are NOT just UI labels — each transition is backed by
 * a protocol event (ACK, cryptographic confirmation, etc.)
 *
 * UI mapping:
 *   🚀 TRANSMITTING
 *   ✓  ACCEPTED (queued at relay / confirmed written to network)
 *   ✓✓ DELIVERED (recipient actually processed it)
 *   👁  READ (recipient viewed it)
 */
enum class EnvelopeState {
    /** Envelope created but not yet encrypted. */
    CREATED,
    /** Encrypted and ready for delivery. */
    ENCRYPTED,
    /** In the delivery queue, awaiting transport. */
    QUEUED,
    /** Currently being transmitted over a transport. */
    TRANSMITTING,
    /** Transport confirmed the bytes were sent. */
    ACCEPTED,
    /** Recipient's agent confirmed receipt (ACK received). */
    DELIVERED,
    /** Recipient has read the message (READ receipt received). */
    READ,
    /** Delivery failed after all retries exhausted. */
    FAILED,
    /** Envelope expired (TTL exceeded). */
    EXPIRED
}

/**
 * Crypto header attached to envelopes for the ratchet session.
 * This is what SessionManager currently bakes into the wire JSON.
 */
data class CryptoHeader(
    val sessionId: String,
    val msgNum: Int,
    val ratchetPubHex: String,
    val schemaVersion: Int = 1
)

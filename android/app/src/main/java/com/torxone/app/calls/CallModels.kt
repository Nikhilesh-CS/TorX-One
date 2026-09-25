package com.torxone.app.calls

import androidx.room.*

/**
 * Deterministic call state machine.
 * Every transition is owned exclusively by CallManager.
 *
 * State graph:
 *   IDLE → OUTGOING_PREPARING → OUTGOING_RINGING → CONNECTING → CONNECTED → ENDING → ENDED
 *   IDLE → INCOMING_RINGING → CONNECTING → CONNECTED → ENDING → ENDED
 *   CONNECTED → RECONNECTING → CONNECTED (or FAILED)
 *   INCOMING_RINGING → DECLINED
 *   OUTGOING_RINGING → MISSED (timeout)
 *   Any → FAILED (ICE/network)
 *   INCOMING_RINGING → BUSY (already in call)
 */
enum class CallState {
    IDLE,

    /** Local user initiated, preparing SDP offer */
    OUTGOING_PREPARING,

    /** Offer sent, waiting for remote answer */
    OUTGOING_RINGING,

    /** Remote offer received, ringing locally */
    INCOMING_RINGING,

    /** SDP exchange done, ICE connecting */
    CONNECTING,

    /** Media flowing */
    CONNECTED,

    /** ICE temporarily broken, attempting recovery */
    RECONNECTING,

    /** Hangup/end initiated, tearing down */
    ENDING,

    /** Call completed normally */
    ENDED,

    /** Remote declined */
    DECLINED,

    /** Remote was in another call */
    BUSY,

    /** No answer within timeout */
    MISSED,

    /** ICE/network/permission failure */
    FAILED
}

enum class CallType {
    VOICE,
    VIDEO
}

enum class CallDirection {
    OUTGOING,
    INCOMING
}

/**
 * Reason a call ended — determines user-facing copy and call history outcome.
 */
enum class CallEndReason {
    LOCAL_HANGUP,
    REMOTE_HANGUP,
    DECLINED,
    BUSY,
    NO_ANSWER,
    CONNECTION_FAILED,
    NETWORK_LOST,
    PERMISSION_DENIED,
    COLLISION_SUPERSEDED
}

/**
 * Call outcome for call history display.
 */
enum class CallOutcome {
    COMPLETED,
    MISSED,
    DECLINED,
    BUSY,
    FAILED,
    NO_ANSWER
}

/**
 * Live call session — the in-memory representation of an active call.
 * Owned entirely by CallManager. Never persisted mid-call.
 */
data class CallSession(
    val callId: String,
    val conversationId: String,
    val relationshipId: String,
    val peerIdentityId: String,
    val direction: CallDirection,
    val type: CallType,
    val state: CallState,
    val startedAt: Long,
    val connectedAt: Long? = null,
    val endedAt: Long? = null,
    val endReason: CallEndReason? = null,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val isCameraOn: Boolean = false,
    val isRemoteCameraOn: Boolean = true
)

/**
 * Persisted call history record — written once after call ends.
 * Completely separate from MessageEntity to avoid polluting chat protocol log.
 */
@Entity(
    tableName = "call_history",
    indices = [
        Index(value = ["conversationId"]),
        Index(value = ["peerIdentityId"])
    ]
)
data class CallHistoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "callId")
    val callId: String,

    @ColumnInfo(name = "conversationId")
    val conversationId: String,

    @ColumnInfo(name = "peerIdentityId")
    val peerIdentityId: String,

    @ColumnInfo(name = "direction")
    val direction: String,

    @ColumnInfo(name = "type")
    val type: String,

    @ColumnInfo(name = "outcome")
    val outcome: String,

    @ColumnInfo(name = "startedAt")
    val startedAt: Long,

    @ColumnInfo(name = "connectedAt")
    val connectedAt: Long? = null,

    @ColumnInfo(name = "endedAt")
    val endedAt: Long? = null,

    @ColumnInfo(name = "durationMs")
    val durationMs: Long? = null
)

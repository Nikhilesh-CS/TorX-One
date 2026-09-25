package com.torxone.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.torxone.app.MainActivity
import com.torxone.app.data.dao.ConversationDao
import com.torxone.app.data.dao.LocalMessageStateDao
import com.torxone.app.data.dao.MessageDao
import com.torxone.app.data.entity.MessageDirection
import com.torxone.app.incoming.ActiveConversationTracker
import com.torxone.app.profile.AppSettingsRepository
import com.torxone.app.service.NotificationActionReceiver
import kotlinx.coroutines.flow.first

/**
 * TorXNotificationManager — Complete notification authority for TorX One.
 *
 * Architecture & Invariants:
 * - Sits above incoming message persistence (never inside transport).
 * - Only notifies for user-visible TEXT messages (never control packets).
 * - Suppresses notifications if conversation is active in foreground.
 * - Grouped by conversation using NotificationCompat.MessagingStyle.
 * - Supports FULL, SENDER_ONLY, and HIDDEN privacy modes.
 * - Supports Inline Quick Reply and Mark as Read actions.
 * - Updates active notifications on remote edits, tombstones, and local deletes.
 * - Reconstructs state from Room on restart (no fragile memory state).
 */
class TorXNotificationManager(
    private val context: Context,
    private val activeConversationTracker: ActiveConversationTracker,
    private val appVisibilityTracker: AppVisibilityTracker,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val localMessageStateDao: LocalMessageStateDao,
    private val privacyModeProvider: () -> NotificationPrivacyMode = { NotificationPrivacyMode.FULL },
    private val contactDao: com.torxone.app.data.dao.ContactDao? = null,
    private val appSettingsRepository: AppSettingsRepository? = null
) {
    companion object {
        private const val TAG = "TorXNotificationManager"
        const val KEY_TEXT_REPLY = "key_text_reply"
        const val ACTION_MARK_AS_READ = "com.torxone.app.ACTION_MARK_AS_READ"
        const val ACTION_REPLY = "com.torxone.app.ACTION_REPLY"
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        const val EXTRA_RELATIONSHIP_ID = "extra_relationship_id"
    }

    private val notificationManager = NotificationManagerCompat.from(context)

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            // 1. Messages Channel (High importance, sound, vibration)
            val msgChannel = NotificationChannel(
                NotificationPolicy.CHANNEL_MESSAGES,
                "TorX Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming end-to-end encrypted chat messages"
                enableVibration(true)
            }

            // 2. Silent Messages Channel (Low importance, no sound/vibration for muted chats)
            val silentChannel = NotificationChannel(
                NotificationPolicy.CHANNEL_SILENT,
                "Silent Messages",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Messages from muted conversations"
                enableVibration(false)
                setSound(null, null)
            }

            nm.createNotificationChannel(msgChannel)
            nm.createNotificationChannel(silentChannel)
        }
    }

    /**
     * Handle incoming text message that was just committed to Room.
     */
    suspend fun handleIncomingTextMessage(
        conversationId: String,
        messageId: String,
        senderId: String,
        text: String,
        timestamp: Long
    ) {
        val isForeground = appVisibilityTracker.isForeground()
        val activeChat = activeConversationTracker.getActiveConversationId()

        val notifsEnabled = appSettingsRepository?.notificationsEnabled?.first() ?: true
        if (!notifsEnabled) {
            Log.d(TAG, "[NOTIFY SUPPRESSED] Notifications disabled in App Settings")
            return
        }

        if (!NotificationPolicy.shouldNotify(conversationId, isForeground, activeChat)) {
            Log.d(TAG, "[NOTIFY SUPPRESSED] Conversation $conversationId is currently active in foreground")
            return
        }

        refreshConversationNotification(conversationId)
    }

    /**
     * Refresh or create conversation notification using Room database as source of truth.
     */
    suspend fun refreshConversationNotification(conversationId: String) {
        try {
            val notifsEnabled = appSettingsRepository?.notificationsEnabled?.first() ?: true
            if (!notifsEnabled) {
                cancelForConversation(conversationId)
                return
            }

            val conv = conversationDao.getById(conversationId)
            val contactTitle = conv?.title ?: "Contact"

            // 1. Query unread incoming messages for this conversation
            val allMessages = messageDao.getMessagesForConversationDesc(conversationId)
            val hiddenIds = localMessageStateDao.getHiddenMessageIds(conversationId).toSet()

            // Filter: INCOMING, status != READ, and NOT hidden locally
            val unreadIncoming = allMessages
                .filter { it.direction == MessageDirection.INCOMING && it.status != "READ" && !hiddenIds.contains(it.logicalMessageId) }
                .reversed() // Chronological order for MessagingStyle

            if (unreadIncoming.isEmpty()) {
                cancelForConversation(conversationId)
                return
            }

            val isMuted = NotificationPolicy.isConversationMuted(conv?.mutedUntil)
            val channelId = NotificationPolicy.getChannelId(isMuted)
            val privacyMode = if (appSettingsRepository != null) {
                val modeStr = appSettingsRepository.notificationPreviewMode.first()
                try {
                    NotificationPrivacyMode.valueOf(modeStr)
                } catch (_: Exception) {
                    NotificationPrivacyMode.FULL
                }
            } else {
                privacyModeProvider()
            }

            // 2. Build MessagingStyle
            val isGroup = conv?.type == com.torxone.app.data.entity.ConversationType.GROUP
            val userPerson = Person.Builder().setName("You").build()
            val messagingStyle = NotificationCompat.MessagingStyle(userPerson)
                .setConversationTitle(contactTitle)
                .setGroupConversation(isGroup)

            val contactsMap = if (isGroup && contactDao != null) {
                try {
                    contactDao.getAll().associateBy { it.contactId }
                } catch (_: Exception) {
                    null
                }
            } else null

            for (msg in unreadIncoming) {
                val rawText = if (msg.deletedAt != null) {
                    "This message was deleted"
                } else {
                    when (msg.type) {
                        "IMAGE" -> "📷 Photo"
                        "VIDEO" -> "🎥 Video"
                        "VOICE_NOTE" -> "🎤 Voice message"
                        "AUDIO" -> "🎵 Audio"
                        "FILE" -> "📄 ${msg.body ?: "Document"}"
                        else -> msg.body ?: ""
                    }
                }

                val senderName = if (isGroup) {
                    contactsMap?.get(msg.senderId)?.displayName ?: msg.senderId.take(8)
                } else {
                    contactTitle
                }

                val formatted = NotificationPolicy.formatContent(privacyMode, senderName, rawText)
                val senderPerson = Person.Builder().setName(formatted.title).build()

                messagingStyle.addMessage(
                    formatted.text,
                    msg.createdAt,
                    senderPerson
                )
            }

            // 3. Notification Tap Intent (opens MainActivity into conversation)
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("conversationId", conversationId)
            }
            val openPendingIntent = PendingIntent.getActivity(
                context,
                conversationId.hashCode(),
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // 4. Quick Reply Action (RemoteInput)
            val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
                .setLabel("Reply")
                .build()

            val replyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = ACTION_REPLY
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
                putExtra(EXTRA_RELATIONSHIP_ID, conversationId)
            }
            val replyPendingIntent = PendingIntent.getBroadcast(
                context,
                conversationId.hashCode() + 1,
                replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            val replyAction = NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_send,
                "Reply",
                replyPendingIntent
            ).addRemoteInput(remoteInput).build()

            // 5. Mark as Read Action
            val markReadIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = ACTION_MARK_AS_READ
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
                putExtra(EXTRA_RELATIONSHIP_ID, conversationId)
            }
            val markReadPendingIntent = PendingIntent.getBroadcast(
                context,
                conversationId.hashCode() + 2,
                markReadIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val markReadAction = NotificationCompat.Action.Builder(
                android.R.drawable.checkbox_on_background,
                "Mark as read",
                markReadPendingIntent
            ).build()

            val soundEnabled = appSettingsRepository?.soundEnabled?.first() ?: true
            val vibrationEnabled = appSettingsRepository?.vibrationEnabled?.first() ?: true

            // 6. Build Notification
            val notificationId = NotificationPolicy.getNotificationId(conversationId)
            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setStyle(messagingStyle)
                .setContentIntent(openPendingIntent)
                .setAutoCancel(true)
                .setPriority(if (isMuted) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
                .apply {
                    if (isMuted || (!soundEnabled && !vibrationEnabled)) {
                        setSilent(true)
                    } else {
                        if (!soundEnabled) {
                            setSound(null)
                        }
                        if (!vibrationEnabled) {
                            setVibrate(longArrayOf(0))
                        }
                    }
                }
                .addAction(replyAction)
                .addAction(markReadAction)
                .build()

            notificationManager.notify(notificationId, notification)
            Log.d(TAG, "[NOTIFIED] Updated notification for conv=$conversationId on $channelId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh conversation notification: ${e.message}")
        }
    }

    /**
     * Update notification when a message is tombstoned (Delete for everyone).
     */
    suspend fun onMessageTombstoned(conversationId: String, messageId: String) {
        refreshConversationNotification(conversationId)
    }

    /**
     * Update notification when a message is edited.
     */
    suspend fun onMessageEdited(conversationId: String, messageId: String, newText: String) {
        refreshConversationNotification(conversationId)
    }

    /**
     * Update notification when a message is hidden locally (Delete for me).
     */
    suspend fun onMessageDeletedLocally(conversationId: String, messageId: String) {
        refreshConversationNotification(conversationId)
    }

    /**
     * Dismiss notification for a conversation (e.g. user opened chat or marked read).
     */
    fun cancelForConversation(conversationId: String) {
        val notificationId = NotificationPolicy.getNotificationId(conversationId)
        notificationManager.cancel(notificationId)
        Log.d(TAG, "[NOTIFICATION CANCELLED] Cancelled notification for conv=$conversationId")
    }
}

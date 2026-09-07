package com.torxone.app.data

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class UnifiedConversation(
    val id: String,
    val type: String, // "direct" or "group"
    val name: String,
    val avatarUri: String?,
    val endpointId: String,
    val onionAddress: String,
    val lastMsgTime: Long
)

@Dao
interface ConversationDao {
    @Query("""
        SELECT 
            c.signingPublicKey AS id,
            'direct' AS type,
            c.name AS name,
            null AS avatarUri,
            c.endpointId AS endpointId,
            c.onionAddress AS onionAddress,
            COALESCE(m.last_msg_time, 0) AS lastMsgTime
        FROM contacts c
        LEFT JOIN (
            SELECT contactKey, MAX(timestamp) AS last_msg_time
            FROM messages WHERE conversationType = 'direct'
            GROUP BY contactKey
        ) m ON c.signingPublicKey = m.contactKey
        
        UNION ALL
        
        SELECT 
            g.groupId AS id,
            'group' AS type,
            g.name AS name,
            g.avatarUri AS avatarUri,
            '' AS endpointId,
            '' AS onionAddress,
            COALESCE(m.last_msg_time, g.createdAt) AS lastMsgTime
        FROM groups g
        LEFT JOIN (
            SELECT contactKey, MAX(timestamp) AS last_msg_time
            FROM messages WHERE conversationType = 'group'
            GROUP BY contactKey
        ) m ON g.groupId = m.contactKey
        
        ORDER BY lastMsgTime DESC, name ASC
    """)
    fun getUnifiedConversations(): Flow<List<UnifiedConversation>>
}

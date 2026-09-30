package com.torxone.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/** Stored in encrypted Room alongside the relationship that authenticated this endpoint. */
@Entity(tableName = "peer_tor_endpoints", foreignKeys = [ForeignKey(
    entity = PairRelationshipEntity::class,
    parentColumns = ["relationship_id"], childColumns = ["relationship_id"],
    onDelete = ForeignKey.CASCADE
)])
data class PeerTorEndpointEntity(
    @PrimaryKey @ColumnInfo(name = "relationship_id") val relationshipId: String,
    @ColumnInfo(name = "onion_address") val onionAddress: String,
    val port: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    val source: String = "SIGNED_PAIRING"
)

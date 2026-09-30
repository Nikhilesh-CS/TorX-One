package com.torxone.app.data.dao

import androidx.room.*
import com.torxone.app.data.entity.PeerTorEndpointEntity

@Dao
interface PeerTorEndpointDao {
    @Query("SELECT * FROM peer_tor_endpoints")
    suspend fun getAll(): List<PeerTorEndpointEntity>
    @Query("SELECT * FROM peer_tor_endpoints WHERE relationship_id = :relationshipId")
    suspend fun getByRelationshipId(relationshipId: String): PeerTorEndpointEntity?
    @Upsert
    suspend fun upsert(endpoint: PeerTorEndpointEntity)
    @Query("DELETE FROM peer_tor_endpoints WHERE relationship_id = :relationshipId")
    suspend fun delete(relationshipId: String)
}

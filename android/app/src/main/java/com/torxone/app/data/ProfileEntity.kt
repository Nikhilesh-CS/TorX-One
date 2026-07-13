package com.torxone.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val ownerKey: String, // signingPublicKey of contact, or "LOCAL_USER"
    val name: String,
    val bio: String = "",
    val statusMessage: String = "",
    val avatarHash: String? = null,
    val profileHash: String = "",
    val profileVersion: Int = 1,
    val lastUpdatedAt: Long = 0L,
    val avatarLocalPath: String? = null,
    
    // Future-proofing fields
    val nickname: String? = null,
    val pronouns: String? = null,
    val verifiedBadge: Boolean = false,
    val avatarTheme: String? = null,
    val customStatusEmoji: String? = null,
    val presenceCapabilities: String? = null
)

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles WHERE ownerKey = :ownerKey LIMIT 1")
    fun getProfile(ownerKey: String): Flow<ProfileEntity?>

    @Query("SELECT * FROM profiles WHERE ownerKey = :ownerKey LIMIT 1")
    fun getProfileSync(ownerKey: String): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertProfile(profile: ProfileEntity)

    @Query("DELETE FROM profiles WHERE ownerKey = :ownerKey")
    fun deleteProfile(ownerKey: String)
}

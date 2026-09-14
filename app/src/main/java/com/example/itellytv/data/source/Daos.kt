package com.example.itellytv.data.source

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.itellytv.data.model.ChannelEntity
import com.example.itellytv.data.model.PlaylistEntity
import com.example.itellytv.data.model.RecentPlayEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY loaded_at DESC")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun byId(id: Long): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(playlist: PlaylistEntity): Long

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface ChannelDao {

    @Query("SELECT * FROM channels WHERE playlist_id = :playlistId")
    fun observeByPlaylist(playlistId: Long): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE playlist_id = :playlistId")
    suspend fun listByPlaylist(playlistId: Long): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun byId(id: Long): ChannelEntity?

    @Query("SELECT * FROM channels WHERE name LIKE :pattern COLLATE NOCASE OR tvg_name LIKE :pattern COLLATE NOCASE LIMIT 200")
    suspend fun search(pattern: String): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE is_favorite = 1")
    fun observeFavorites(): Flow<List<ChannelEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(channels: List<ChannelEntity>): List<Long>

    @Query("UPDATE channels SET is_favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("UPDATE channels SET last_played_at = :timestamp WHERE id = :id")
    suspend fun markLastPlayed(id: Long, timestamp: Long)

    /**
     * Atomic "replace this playlist's channels" — deletes existing
     * channels for the playlist and inserts the new ones inside a
     * transaction. Mirrors the iTelly-macOS "refresh subscription"
     * behavior, where we re-read the whole playlist and replace.
     */
    @Transaction
    suspend fun replaceForPlaylist(playlistId: Long, newChannels: List<ChannelEntity>) {
        deleteByPlaylist(playlistId)
        if (newChannels.isNotEmpty()) {
            insertAll(newChannels)
        }
    }

    @Query("DELETE FROM channels WHERE playlist_id = :playlistId")
    suspend fun deleteByPlaylist(playlistId: Long)
}

@Dao
interface RecentPlayDao {

    @Query("SELECT * FROM recent_plays ORDER BY played_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 40): Flow<List<RecentPlayEntity>>

    @Insert
    suspend fun insert(recent: RecentPlayEntity): Long

    /**
     * Cap the recent-play log at [keep] rows. Called after every
     * insert. iTelly-macOS caps at 40 — we expose the limit as a
     * parameter so the user setting can change it later.
     */
    @Query("""
        DELETE FROM recent_plays
        WHERE id NOT IN (
            SELECT id FROM recent_plays ORDER BY played_at DESC LIMIT :keep
        )
    """)
    suspend fun trim(keep: Int)

    @Transaction
    suspend fun recordAndTrim(channelId: Long, keep: Int = 40) {
        insert(RecentPlayEntity(channelId = channelId))
        trim(keep)
    }
}

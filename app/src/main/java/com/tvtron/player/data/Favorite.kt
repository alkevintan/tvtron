package com.tvtron.player.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "favorites",
    foreignKeys = [
        ForeignKey(
            entity = Channel::class,
            parentColumns = ["id"],
            childColumns = ["channelId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Playlist::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("playlistId"), Index(value = ["channelId"], unique = true)]
)
data class Favorite(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelId: Long,
    val playlistId: Long,
    val addedAt: Long = System.currentTimeMillis()
)

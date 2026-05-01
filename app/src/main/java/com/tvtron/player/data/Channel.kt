package com.tvtron.player.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "channels",
    foreignKeys = [
        ForeignKey(
            entity = Playlist::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("playlistId"), Index(value = ["playlistId", "tvgId"])]
)
data class Channel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val tvgId: String = "",
    val name: String,
    val logo: String = "",
    val groupTitle: String = "",
    val streamUrl: String,
    val userAgent: String = "",
    val referer: String = "",
    val sortIndex: Int = 0
)

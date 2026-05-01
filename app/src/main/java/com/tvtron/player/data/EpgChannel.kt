package com.tvtron.player.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "epg_channels",
    primaryKeys = ["playlistId", "xmltvId"],
    foreignKeys = [
        ForeignKey(
            entity = Playlist::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("playlistId")]
)
data class EpgChannel(
    val playlistId: Long,
    val xmltvId: String,
    val displayName: String = "",
    val icon: String = ""
)

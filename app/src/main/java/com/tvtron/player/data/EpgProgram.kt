package com.tvtron.player.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "epg_programs",
    foreignKeys = [
        ForeignKey(
            entity = Playlist::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("playlistId"),
        Index(value = ["playlistId", "xmltvId", "start"]),
        Index(value = ["playlistId", "start"])
    ]
)
data class EpgProgram(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val xmltvId: String,
    /** Epoch millis */
    val start: Long,
    /** Epoch millis */
    val stop: Long,
    val title: String,
    val description: String = "",
    val category: String = ""
)

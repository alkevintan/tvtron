package com.tvtron.player.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tvtron.player.R
import com.tvtron.player.data.Playlist
import java.text.DateFormat
import java.util.Date

class PlaylistAdapter(
    private val onClick: (Playlist) -> Unit,
    private val onDelete: (Playlist) -> Unit
) : ListAdapter<Playlist, PlaylistAdapter.VH>(DIFF) {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.playlist_name)
        val source: TextView = v.findViewById(R.id.playlist_source)
        val meta: TextView = v.findViewById(R.id.playlist_meta)
        val delete: ImageButton = v.findViewById(R.id.playlist_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_playlist, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val p = getItem(pos)
        h.name.text = p.name
        h.source.text = p.source
        val refreshTxt = if (p.lastRefresh > 0)
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(p.lastRefresh))
        else "never"
        h.meta.text = "${p.autoRefresh.name.lowercase()} · last refresh: $refreshTxt"
        h.itemView.setOnClickListener { onClick(p) }
        h.delete.setOnClickListener { onDelete(p) }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Playlist>() {
            override fun areItemsTheSame(a: Playlist, b: Playlist) = a.id == b.id
            override fun areContentsTheSame(a: Playlist, b: Playlist) = a == b
        }
    }
}

package com.tvtron.player.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso
import com.tvtron.player.R
import com.tvtron.player.data.Channel

class ChannelAdapter(
    private val onClick: (Channel) -> Unit,
    private val onLongClick: (Channel) -> Unit
) : ListAdapter<ChannelAdapter.Item, ChannelAdapter.VH>(DIFF) {

    data class Item(
        val channel: Channel,
        val isFavorite: Boolean,
        val nowTitle: String?,
        val nextTitle: String?
    )

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val no: TextView = v.findViewById(R.id.channel_no)
        val logo: ImageView = v.findViewById(R.id.channel_logo)
        val name: TextView = v.findViewById(R.id.channel_name)
        val group: TextView = v.findViewById(R.id.channel_group)
        val now: TextView = v.findViewById(R.id.channel_now)
        val next: TextView = v.findViewById(R.id.channel_next)
        val fav: ImageView = v.findViewById(R.id.channel_fav)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = getItem(pos)
        h.no.text = "%03d".format(pos + 1)
        h.name.text = item.channel.name
        h.group.text = item.channel.groupTitle
        h.group.visibility = if (item.channel.groupTitle.isBlank()) View.GONE else View.VISIBLE
        h.now.text = item.nowTitle?.let { t -> "Now: $t" } ?: ""
        h.now.visibility = if (item.nowTitle.isNullOrBlank()) View.GONE else View.VISIBLE
        h.next.text = item.nextTitle?.let { t -> "Next: $t" } ?: ""
        h.next.visibility = if (item.nextTitle.isNullOrBlank()) View.GONE else View.VISIBLE
        h.fav.setImageResource(if (item.isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_off)

        if (item.channel.logo.isNotBlank()) {
            Picasso.get().load(item.channel.logo).placeholder(R.drawable.ic_tv).into(h.logo)
        } else {
            h.logo.setImageResource(R.drawable.ic_tv)
        }
        h.itemView.setOnClickListener { onClick(item.channel) }
        h.itemView.setOnLongClickListener { onLongClick(item.channel); true }
        h.fav.setOnClickListener { onLongClick(item.channel) }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Item>() {
            override fun areItemsTheSame(a: Item, b: Item) = a.channel.id == b.channel.id
            override fun areContentsTheSame(a: Item, b: Item) = a == b
        }
    }
}

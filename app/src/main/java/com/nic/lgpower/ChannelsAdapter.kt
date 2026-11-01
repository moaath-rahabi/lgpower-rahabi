package com.nic.lgpower

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChannelsAdapter(
    private val onChannelClick: (TvChannel) -> Unit,
    private val onFavoriteToggle: (TvChannel) -> Unit,
    private val onMenuClick: (TvChannel) -> Unit
) : RecyclerView.Adapter<ChannelsAdapter.ChannelViewHolder>() {

    private var channels: List<TvChannel> = emptyList()
    private var customizations: Map<String, ChannelCustomization> = emptyMap()
    private var currentChannelId: String? = null

    fun updateData(
        newChannels: List<TvChannel>,
        newCustomizations: Map<String, ChannelCustomization>,
        newCurrentChannelId: String? = currentChannelId
    ) {
        channels = newChannels
        customizations = newCustomizations
        currentChannelId = newCurrentChannelId
        notifyDataSetChanged()
    }

    fun setCurrentChannel(channelId: String?) {
        currentChannelId = channelId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        return ChannelViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        val channel = channels[position]
        val key = "${(channel.source ?: "LIVE_TV").trim().uppercase()}\u0000${channel.id ?: ""}"
        val custom = customizations[key] ?: customizations["*\u0000${channel.id ?: ""}"]

        holder.bind(channel, custom, currentChannelId, onChannelClick, onFavoriteToggle, onMenuClick)
    }

    override fun getItemCount(): Int = channels.size

    class ChannelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val txtNumber: TextView = itemView.findViewById(R.id.txt_channel_number)
        private val txtName: TextView = itemView.findViewById(R.id.txt_channel_name)
        private val txtShortcut: TextView = itemView.findViewById(R.id.txt_shortcut_badge)
        private val txtSource: TextView = itemView.findViewById(R.id.txt_channel_source)
        private val txtLive: TextView = itemView.findViewById(R.id.txt_live_badge)
        private val btnFav: ImageButton = itemView.findViewById(R.id.btn_channel_fav)
        private val btnMenu: ImageButton = itemView.findViewById(R.id.btn_channel_menu)

        fun bind(
            channel: TvChannel,
            custom: ChannelCustomization?,
            currentChannelId: String?,
            onClick: (TvChannel) -> Unit,
            onFav: (TvChannel) -> Unit,
            onMenu: (TvChannel) -> Unit
        ) {
            val displayName = custom?.displayName?.takeIf { it.isNotBlank() } ?: channel.name
            val hasShortcut = !custom?.shortcutNumber.isNullOrBlank()
            val isFav = custom?.favorite == true
            val isCurrent = (channel.id != null && channel.id == currentChannelId) || channel.reportedCurrent

            txtNumber.text = channel.number
            txtName.text = displayName

            if (hasShortcut) {
                txtShortcut.text = "[#${custom?.shortcutNumber}]"
                txtShortcut.visibility = View.VISIBLE
            } else {
                txtShortcut.visibility = View.GONE
            }

            val sourceText = buildString {
                if (displayName != channel.name) {
                    append(channel.name)
                    append(" • ")
                }
                append(channel.source?.takeIf { it.isNotBlank() } ?: "TV")
            }
            txtSource.text = sourceText
            txtLive.visibility = if (isCurrent) View.VISIBLE else View.GONE

            btnFav.setImageResource(if (isFav) R.drawable.ic_star_filled else R.drawable.ic_star_outline)

            itemView.setOnClickListener { onClick(channel) }
            itemView.setOnLongClickListener {
                onMenu(channel)
                true
            }
            btnFav.setOnClickListener { onFav(channel) }
            btnMenu.setOnClickListener { onMenu(channel) }
        }
    }
}

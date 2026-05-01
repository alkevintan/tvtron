package com.tvtron.player.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.tvtron.player.R
import com.tvtron.player.data.Channel
import com.tvtron.player.util.QrCodeGenerator
import com.tvtron.player.util.TvtronUri

/** Bottom-sheet QR for a single Channel. Encodes a tvtron://channel?... deep-link URI. */
class ShareChannelQrDialog : BottomSheetDialogFragment() {

    companion object {
        fun newInstance(channel: Channel): ShareChannelQrDialog = ShareChannelQrDialog().apply {
            arguments = Bundle().apply {
                putString("name", channel.name)
                putString("stream", channel.streamUrl)
                putString("logo", channel.logo)
                putString("group", channel.groupTitle)
                putString("tvg", channel.tvgId)
                putString("ua", channel.userAgent)
                putString("ref", channel.referer)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.dialog_share_qr, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val a = requireArguments()
        val name = a.getString("name").orEmpty()
        val stream = a.getString("stream").orEmpty()
        view.findViewById<TextView>(R.id.qrPlaylistName).text = name
        view.findViewById<TextView>(R.id.qrSourceUrl).text = stream

        val payload = TvtronUri.encodeChannel(
            name = name,
            streamUrl = stream,
            logo = a.getString("logo").orEmpty(),
            groupTitle = a.getString("group").orEmpty(),
            tvgId = a.getString("tvg").orEmpty(),
            userAgent = a.getString("ua").orEmpty(),
            referer = a.getString("ref").orEmpty()
        )
        try {
            view.findViewById<ImageView>(R.id.qrImageView).setImageBitmap(QrCodeGenerator.generateBitmap(payload, 640))
        } catch (_: Exception) {
            view.findViewById<ImageView>(R.id.qrImageView).setImageDrawable(null)
        }

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.qrCopyButton).setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("TVTron channel URL", stream))
            Toast.makeText(requireContext(), "URL copied", Toast.LENGTH_SHORT).show()
        }
    }
}

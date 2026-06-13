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
                putString("drmKeyId", channel.drmKeyId)
                putString("drmKey", channel.drmKey)
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
        val drmKeyId = a.getString("drmKeyId").orEmpty()
        val drmKey = a.getString("drmKey").orEmpty()
        val hasDrm = drmKeyId.isNotBlank() && drmKey.isNotBlank()

        view.findViewById<TextView>(R.id.qrHeader).text = "Share Channel"
        view.findViewById<TextView>(R.id.qrPlaylistName).text = name
        view.findViewById<TextView>(R.id.qrSourceUrl).text = stream

        // Show a DRM badge next to the name if this is an encrypted channel.
        if (hasDrm) {
            view.findViewById<TextView>(R.id.qrDrmBadge)?.visibility = View.VISIBLE
        }

        val payload = TvtronUri.encodeChannel(
            name = name,
            streamUrl = stream,
            logo = a.getString("logo").orEmpty(),
            groupTitle = a.getString("group").orEmpty(),
            tvgId = a.getString("tvg").orEmpty(),
            userAgent = a.getString("ua").orEmpty(),
            referer = a.getString("ref").orEmpty(),
            drmKeyId = drmKeyId,
            drmKey = drmKey
        )
        try {
            view.findViewById<ImageView>(R.id.qrImageView).setImageBitmap(QrCodeGenerator.generateBitmap(payload, 640))
        } catch (_: Exception) {
            view.findViewById<ImageView>(R.id.qrImageView).setImageDrawable(null)
        }

        // Copy the full tvtron:// deep link so DRM keys are preserved on the clipboard.
        val copyText = if (hasDrm) payload else stream
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.qrCopyButton).setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("TVTron channel URL", copyText))
            val label = if (hasDrm) "Deep link with DRM keys copied" else "URL copied"
            Toast.makeText(requireContext(), label, Toast.LENGTH_SHORT).show()
        }
    }
}

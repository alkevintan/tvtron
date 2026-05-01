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
import com.tvtron.player.data.Playlist
import com.tvtron.player.util.QrCodeGenerator

/**
 * Bottom-sheet QR code for a Playlist.
 *
 * Encoded payload (text), pipe-delimited:
 *   TVTRON|<name>|<m3u-url>|<epg-url>
 *
 * Local file playlists (content://) are not shareable; the dialog shows a hint instead.
 */
class ShareQrDialog : BottomSheetDialogFragment() {

    companion object {
        private const val SCHEME = "TVTRON"
        fun newInstance(playlist: Playlist): ShareQrDialog = ShareQrDialog().apply {
            arguments = Bundle().apply {
                putString("name", playlist.name)
                putString("source", playlist.source)
                putString("epg", playlist.epgUrl)
                putBoolean("remote", playlist.isRemote)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.dialog_share_qr, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        val name = args.getString("name").orEmpty()
        val source = args.getString("source").orEmpty()
        val epg = args.getString("epg").orEmpty()
        val remote = args.getBoolean("remote", false)

        view.findViewById<TextView>(R.id.qrPlaylistName).text = name

        if (!remote) {
            view.findViewById<TextView>(R.id.qrSourceUrl).text = "Local playlists can't be shared via QR"
            view.findViewById<ImageView>(R.id.qrImageView).setImageDrawable(null)
            view.findViewById<View>(R.id.qrCopyButton).visibility = View.GONE
            return
        }

        view.findViewById<TextView>(R.id.qrSourceUrl).text = source

        val payload = buildString {
            append(SCHEME).append('|').append(name).append('|').append(source)
            if (epg.isNotBlank()) append('|').append(epg)
        }
        try {
            view.findViewById<ImageView>(R.id.qrImageView).setImageBitmap(QrCodeGenerator.generateBitmap(payload, 640))
        } catch (_: Exception) {
            view.findViewById<ImageView>(R.id.qrImageView).setImageDrawable(null)
        }

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.qrCopyButton).setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("TVTron playlist URL", source))
            Toast.makeText(requireContext(), "URL copied", Toast.LENGTH_SHORT).show()
        }
    }
}

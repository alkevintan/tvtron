package com.tvtron.player.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Decode a QR code from a user-picked image. Handles arbitrary image sizes by
 * down-sampling on load and falls back to inverted-luminance + multi-format
 * reading if the strict QR pass misses.
 */
object QrImageDecoder {

    private const val TAG = "QrImageDecoder"
    private const val MAX_DIM = 2048

    fun decode(context: Context, uri: Uri): String? {
        val bitmap = loadBitmap(context, uri) ?: return null
        return try {
            decodeBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun loadBitmap(context: Context, uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        while (bounds.outWidth / sample > MAX_DIM || bounds.outHeight / sample > MAX_DIM) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "loadBitmap failed", t); null
    }

    private fun decodeBitmap(bitmap: Bitmap): String? {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val source = RGBLuminanceSource(w, h, pixels)
        val binary = BinaryBitmap(HybridBinarizer(source))

        val hints = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE)
        )

        // First pass: strict QR reader.
        try {
            return QRCodeReader().decode(binary, hints).text
        } catch (_: Throwable) { }

        // Second pass: inverted (some QRs print on dark backgrounds).
        try {
            return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source.invert())), hints).text
        } catch (_: Throwable) { }

        // Last resort: MultiFormatReader (slower, more permissive).
        return try {
            MultiFormatReader().apply { setHints(hints) }.decode(binary).text
        } catch (t: Throwable) {
            Log.i(TAG, "no QR found in image: ${t.message}")
            null
        }
    }
}

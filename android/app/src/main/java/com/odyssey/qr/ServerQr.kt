package com.odyssey.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Render a QR string as a black-on-white [Bitmap] of the requested
 * pixel size. Uses ZXing's QRCodeWriter directly so the bitmap is
 * pure software — no Compose / View dependency, callable from any
 * thread.
 *
 * sizePx is both width and height; QR codes are square. ECC level
 * is M (~15% damage tolerance) — high enough that the printed-and-
 * photographed handoff still scans, low enough that a 7-character
 * URL + 64-character token still fits in a small module count.
 */
fun renderServerQrBitmap(payload: String, sizePx: Int): Bitmap {
    val hints = mapOf(
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1,
    )
    val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h)
    for (y in 0 until h) {
        val rowOffset = y * w
        for (x in 0 until w) {
            pixels[rowOffset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
    }
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, w, 0, 0, w, h)
    }
}

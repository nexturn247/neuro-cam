package com.neuroflow.cam

import android.graphics.Bitmap
import android.media.Image

/** Convierte una imagen YUV_420_888 del decodificador a Bitmap para la IA. */
object Yuv {
    fun toBitmap(img: Image): Bitmap {
        val w = img.width; val h = img.height
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yB = yP.buffer; val uB = uP.buffer; val vB = vP.buffer
        val yRow = yP.rowStride; val yPix = yP.pixelStride
        val uvRow = uP.rowStride; val uvPix = uP.pixelStride
        val out = IntArray(w * h)
        var i = 0
        for (row in 0 until h) {
            val yOff = row * yRow
            val uvOff = (row shr 1) * uvRow
            for (col in 0 until w) {
                val y = (yB.get(yOff + col * yPix).toInt() and 0xFF) - 16
                val uvi = uvOff + (col shr 1) * uvPix
                val u = (uB.get(uvi).toInt() and 0xFF) - 128
                val v = (vB.get(uvi).toInt() and 0xFF) - 128
                val yy = 1192 * (if (y < 0) 0 else y)
                var r = (yy + 1634 * v) shr 10
                var g = (yy - 833 * v - 400 * u) shr 10
                var b = (yy + 2066 * u) shr 10
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255
                out[i++] = -0x1000000 or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }
}

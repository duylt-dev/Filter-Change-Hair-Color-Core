package com.piontech.changehaircolor.core

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.myapp.haircolor.DevHairSegmentation
import java.io.FileOutputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.PBEParameterSpec
import kotlin.math.min
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale

/**
 * Hair segmentation using the ORIGINAL app's native engine (TNN, libdev_hair.so).
 *
 * Faithful re-implementation of the decompiled `com.myapp.haircolor` pipeline:
 *  1. Copy `segmentation.devmodel` + `segmentation.devproto` from assets to filesDir.
 *  2. `init(filesDir, decryptedKey, mode)` — mode 1 = photo, 0 = camera.
 *  3. Per frame: `setHairColor(mColor)` → `getNV21(w,h)` → `predictFromStream(nv21,"bitmap",h,w,1)`.
 *  4. The result `ImageInfo[1]` is an ARGB_8888 image whose ALPHA channel is the hair
 *     mask; we return it as the mask bitmap (RGB is irrelevant — recolour uses SRC_IN).
 */
internal class NativeHairSegmenter private constructor(private val engine: DevHairSegmentation) {

    /** Default target colour {R,G,B,A}; the colour is re-applied in Java afterwards. */
    private val mColor = byteArrayOf(62, 1, 15, 100)

    /** Returns the hair mask (ARGB_8888, alpha = hair coverage) or null. */
    fun segment(bitmap: Bitmap): Bitmap? {
        val even = ensureEven(bitmap)
        val w = even.width
        val h = even.height
        engine.setHairColor(mColor)
        val nv21 = getNV21(w, h, even)
        // NOTE original arg order: (data, tag, HEIGHT, WIDTH, rotation=1)
        val result = engine.predictFromStream(nv21, "bitmap", h, w, 1) ?: return null
        if (result.size < 2) return null
        val info = result[1] ?: return null
        val data = info.data
        if (info.image_channel != 4 || data == null) return null
        val mask = createBitmap(info.image_width, info.image_height)
        mask.copyPixelsFromBuffer(ByteBuffer.wrap(data))
        return mask
    }

    fun close() {
        try { engine.deinit() } catch (t: Throwable) { t.printStackTrace() }
    }

    companion object {
        private const val ENCRYPTED_KEY = "2wJobuNcBKOiZ5tNbG3fcw=="

        fun createForImage(context: Context): NativeHairSegmenter? = create(context, mode = 1)
        fun createForCamera(context: Context): NativeHairSegmenter? = create(context, mode = 0)

        private fun create(context: Context, mode: Int): NativeHairSegmenter? {
            return try {
                val dir = context.filesDir.absolutePath
                copyAsset(context, "hair_segmentation/segmentation.devmodel", "$dir/segmentation.devmodel")
                copyAsset(context, "hair_segmentation/segmentation.devproto", "$dir/segmentation.devproto")
                val engine = DevHairSegmentation()
                val ret = engine.init(dir, decrypt(ENCRYPTED_KEY), mode)
                if (ret != 0) {
                    engine.deinit()
                    null
                } else {
                    NativeHairSegmenter(engine)
                }
            } catch (t: Throwable) {
                t.printStackTrace()
                null
            }
        }

        // ---- ported from the original com.myapp.haircolor utils ----

        private fun ensureEven(src: Bitmap): Bitmap {
            var b = src
            if (b.width % 2 != 0) b = b.scale(b.width + 1, b.height, false)
            if (b.height % 2 != 0) b = b.scale(b.width, b.height + 1, false)
            return b
        }

        /** Port of BitmapUtil.getNV21 — note the buffer is sized w*h*3 (as in the original). */
        private fun getNV21(width: Int, height: Int, bitmap: Bitmap): ByteArray {
            val size = width * height
            val argb = IntArray(size)
            bitmap.getPixels(argb, 0, width, 0, 0, width, height)
            val yuv = ByteArray(size * 3)
            encodeYUV420SP(yuv, argb, width, height)
            return yuv
        }

        /** Port of BitmapUtil.encodeYUV420SP (NV21, V before U). */
        private fun encodeYUV420SP(yuv420sp: ByteArray, argb: IntArray, width: Int, height: Int) {
            val frameSize = width * height
            var yIndex = 0
            var uvIndex = frameSize
            var index = 0
            for (j in 0 until height) {
                for (i in 0 until width) {
                    val rgb = argb[index]
                    val r = (rgb and 0xff0000) shr 16
                    val g = (rgb and 0xff00) shr 8
                    val b = rgb and 0xff
                    val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    yuv420sp[yIndex++] = (if (y < 0) 0 else min(y, 255)).toByte()
                    if (j % 2 == 0 && index % 2 == 0) {
                        yuv420sp[uvIndex++] = (min(v, 255)).toByte()
                        yuv420sp[uvIndex++] = (if (u < 0) 0 else min(u, 255)).toByte()
                    }
                    index++
                }
            }
        }

        /** Port of Const.decrypt — PBEWithMD5AndDES, password/salt/iterations from the original. */
        private fun decrypt(encrypted: String): String {
            val password = "enfldsgbnlsngdlksdsgm".toCharArray()
            val salt = byteArrayOf(-34, 51, 16, 18, -34, 51, 16, 18)
            val key = SecretKeyFactory.getInstance("PBEWithMD5AndDES")
                .generateSecret(PBEKeySpec(password))
            val cipher = Cipher.getInstance("PBEWithMD5AndDES")
            cipher.init(Cipher.DECRYPT_MODE, key, PBEParameterSpec(salt, 20))
            val decoded = Base64.decode(encrypted, Base64.DEFAULT)
            return String(cipher.doFinal(decoded), Charsets.UTF_8)
        }

        private fun copyAsset(context: Context, src: String, destPath: String) {
            context.assets.open(src).use { input ->
                FileOutputStream(destPath).use { output -> input.copyTo(output) }
            }
        }
    }
}

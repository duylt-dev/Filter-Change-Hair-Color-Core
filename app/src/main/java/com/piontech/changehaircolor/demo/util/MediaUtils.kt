package com.piontech.changehaircolor.demo.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/** Image loading (with EXIF + downscale) and saving/sharing helpers. */
object MediaUtils {

    private const val MAX_DIM = 1600

    /** Loads a content [uri] into an ARGB_8888 bitmap, EXIF-rotated and downscaled. */
    fun loadBitmap(context: Context, uri: Uri, maxDim: Int = MAX_DIM): Bitmap? {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds, maxDim) }
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val orientation = cr.openInputStream(uri)?.use { readOrientation(it) } ?: ExifInterface.ORIENTATION_NORMAL
        bmp = applyOrientation(bmp, orientation)
        return finalize(bmp, maxDim)
    }

    /** Loads a bundled asset image (e.g. "samples/1.jpg") into an ARGB_8888 bitmap. */
    fun loadAsset(context: Context, assetPath: String, maxDim: Int = MAX_DIM): Bitmap? {
        val am = context.assets
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        am.open(assetPath).use { BitmapFactory.decodeStream(it, null, bounds) }
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds, maxDim) }
        val bmp = am.open(assetPath).use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        return finalize(bmp, maxDim)
    }

    private fun finalize(src: Bitmap, maxDim: Int): Bitmap {
        val scaled = scaleToMax(src, maxDim)
        return if (scaled.config == Bitmap.Config.ARGB_8888) {
            scaled
        } else {
            scaled.copy(Bitmap.Config.ARGB_8888, true)
        }
    }

    private fun readOrientation(input: java.io.InputStream): Int =
        ExifInterface(input).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )

    private fun applyOrientation(bmp: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private fun scaleToMax(bmp: Bitmap, maxDim: Int): Bitmap {
        val largest = maxOf(bmp.width, bmp.height)
        if (largest <= maxDim) return bmp
        val ratio = maxDim.toFloat() / largest
        val w = (bmp.width * ratio).toInt().coerceAtLeast(1)
        val h = (bmp.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, w, h, true)
    }

    private fun sampleSize(opts: BitmapFactory.Options, maxDim: Int): Int {
        var sample = 1
        val largest = maxOf(opts.outWidth, opts.outHeight)
        while (largest / sample > maxDim * 2) sample *= 2
        return sample
    }

    /** Saves [bitmap] as PNG into the public Pictures/ChangeHairColor album. */
    fun saveToGallery(context: Context, bitmap: Bitmap, displayName: String): Uri? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ChangeHairColor")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val cr = context.contentResolver
            val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            cr.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            cr.update(uri, values, null, null)
            return uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ChangeHairColor")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, displayName)
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png"), null)
            return Uri.fromFile(file)
        }
    }

    /** Writes [bitmap] to the cache and returns a shareable FileProvider uri. */
    fun cacheForShare(context: Context, bitmap: Bitmap): Uri {
        val dir = File(context.cacheDir, "shared")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "hair_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }
}

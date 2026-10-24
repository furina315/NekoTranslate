package com.nekohome.translate.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.nekohome.translate.llama.RgbImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

/** A picked image, ready for the vision encoder, plus a small thumbnail for the UI. */
class PreparedImage(
    val image: RgbImage,
    val thumbnail: Bitmap,
)

/**
 * Decodes a picked / captured image straight to the size the vision encoder needs
 * ([ImagePrep.targetSize]), honouring EXIF orientation, and converts it to packed RGB888.
 * The full-resolution bitmap is never materialised: ImageDecoder decodes at the target size, and
 * the BitmapFactory fallback subsamples while decoding.
 */
object ImageLoader {

    suspend fun load(context: Context, uri: Uri, maxPixels: Long): PreparedImage = withContext(Dispatchers.Default) {
        val bitmap = decode(context.applicationContext, uri, maxPixels)
        try {
            ensureActive()
            val rgb = toRgb(bitmap)
            val t = ImagePrep.thumbnailSize(ImageSize(bitmap.width, bitmap.height))
            val thumb = if (t.width == bitmap.width && t.height == bitmap.height) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                Bitmap.createScaledBitmap(bitmap, t.width, t.height, true)
            }
            PreparedImage(rgb, thumb)
        } finally {
            bitmap.recycle()
        }
    }

    private fun decode(context: Context, uri: Uri, maxPixels: Long): Bitmap =
        try {
            decodeWithImageDecoder(context, uri, maxPixels)
        } catch (e: IOException) {
            decodeWithBitmapFactory(context, uri, maxPixels) ?: throw e
        } catch (e: RuntimeException) {
            decodeWithBitmapFactory(context, uri, maxPixels) ?: throw e
        }

    /** ImageDecoder (API 28+) applies EXIF orientation itself and decodes the first GIF/WebP frame. */
    private fun decodeWithImageDecoder(context: Context, uri: Uri, maxPixels: Long): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // Uniform scale, so it does not matter whether info.size is before or after rotation.
            val t = ImagePrep.targetSize(ImageSize(info.size.width, info.size.height), maxPixels = maxPixels)
            decoder.setTargetSize(t.width, t.height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }
    }

    /** Fallback: subsampled BitmapFactory decode + manual EXIF rotation and exact resize. */
    private fun decodeWithBitmapFactory(context: Context, uri: Uri, maxPixels: Long): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val degrees = runCatching {
            resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees() } ?: 0
        }.getOrDefault(0)

        val raw = ImageSize(bounds.outWidth, bounds.outHeight)
        val target = ImagePrep.targetSize(ImagePrep.rotated(raw, degrees), maxPixels = maxPixels)
        val targetRaw = ImagePrep.rotated(target, degrees) // back to the stored orientation
        val opts = BitmapFactory.Options().apply {
            inSampleSize = ImagePrep.sampleSize(raw, targetRaw)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        val m = Matrix()
        m.postScale(targetRaw.width.toFloat() / decoded.width, targetRaw.height.toFloat() / decoded.height)
        if (degrees != 0) m.postRotate(degrees.toFloat())
        if (m.isIdentity) return decoded
        val out = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        if (out !== decoded) decoded.recycle()
        return out
    }

    private fun ExifInterface.rotationDegrees(): Int =
        when (getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
            else -> 0
        }

    /** Row-by-row conversion, so only one row of ARGB ints is alive at a time. */
    private fun toRgb(bitmap: Bitmap): RgbImage {
        val w = bitmap.width
        val h = bitmap.height
        val out = ByteArray(w * h * 3)
        val row = IntArray(w)
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            ImagePrep.argbToRgb(row, w, out, y * w * 3)
        }
        return RgbImage(w, h, out)
    }
}

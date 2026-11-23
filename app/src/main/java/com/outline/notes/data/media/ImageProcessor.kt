package com.outline.notes.data.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

data class ProcessedImage(
    val file: File,
    val contentType: String,
    val fileName: String,
)

object ImageProcessor {
    private const val MAX_DIMENSION = 2048
    private const val JPEG_QUALITY = 85

    /**
     * Compresses large JPEG/PNG/WebP photos (with proper EXIF orientation correction)
     * before uploading to MinIO. Animated GIFs and small files are left untouched.
     */
    fun processForUpload(
        sourceFile: File,
        originalName: String,
        originalMime: String,
        enableCompression: Boolean,
    ): ProcessedImage {
        if (!enableCompression) {
            return ProcessedImage(sourceFile, originalMime, originalName)
        }
        val lowerMime = originalMime.lowercase()
        if (lowerMime.contains("gif") || lowerMime.contains("svg")) {
            return ProcessedImage(sourceFile, originalMime, originalName)
        }
        // Skip re-encoding already small images (< 350 KB)
        if (sourceFile.length() < 350 * 1024L) {
            return ProcessedImage(sourceFile, originalMime, originalName)
        }

        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(sourceFile.absolutePath, bounds)
            val rawW = bounds.outWidth
            val rawH = bounds.outHeight
            if (rawW <= 0 || rawH <= 0) {
                return@runCatching ProcessedImage(sourceFile, originalMime, originalName)
            }

            var inSample = 1
            while ((rawW / inSample) > MAX_DIMENSION * 2 || (rawH / inSample) > MAX_DIMENSION * 2) {
                inSample *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = inSample }
            var bitmap = BitmapFactory.decodeFile(sourceFile.absolutePath, decodeOpts)
                ?: return@runCatching ProcessedImage(sourceFile, originalMime, originalName)

            // Apply EXIF orientation if present
            val rotationDegrees = readExifRotation(sourceFile)
            if (rotationDegrees != 0f) {
                val matrix = Matrix().apply { postRotate(rotationDegrees) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated !== bitmap) bitmap.recycle()
                bitmap = rotated
            }

            // Scale down smoothly if still exceeding MAX_DIMENSION
            val maxSide = max(bitmap.width, bitmap.height)
            if (maxSide > MAX_DIMENSION) {
                val scale = MAX_DIMENSION.toFloat() / maxSide.toFloat()
                val targetW = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
                val targetH = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
                if (scaled !== bitmap) bitmap.recycle()
                bitmap = scaled
            }

            val outFile = File(sourceFile.parentFile, "${sourceFile.nameWithoutExtension}_opt.jpg")
            FileOutputStream(outFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)
            }
            bitmap.recycle()

            if (outFile.exists() && outFile.length() > 0L && outFile.length() < sourceFile.length()) {
                sourceFile.delete()
                val baseName = originalName.substringBeforeLast('.')
                ProcessedImage(outFile, "image/jpeg", "$baseName.jpg")
            } else {
                outFile.delete()
                ProcessedImage(sourceFile, originalMime, originalName)
            }
        }.getOrElse {
            ProcessedImage(sourceFile, originalMime, originalName)
        }
    }

    private fun readExifRotation(file: File): Float {
        return runCatching {
            val exif = ExifInterface(file.absolutePath)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
    }

    /**
     * Saves an image file into the Android system Gallery (Pictures/OutlineNotes).
     */
    fun saveImageToGallery(context: Context, imageFile: File, mimeType: String): Result<String> {
        return runCatching {
            val ext = when {
                mimeType.contains("png") -> "png"
                mimeType.contains("webp") -> "webp"
                mimeType.contains("gif") -> "gif"
                else -> "jpg"
            }
            val displayName = "Outline_${System.currentTimeMillis()}.$ext"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/OutlineNotes")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法创建相册文件")

            resolver.openOutputStream(uri)?.use { out ->
                imageFile.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IllegalStateException("无法写入相册文件")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            displayName
        }
    }
}

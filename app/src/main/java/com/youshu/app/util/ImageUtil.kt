package com.youshu.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.net.toUri
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import kotlin.math.roundToInt

data class CropRectFraction(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun normalized(): CropRectFraction {
        val normalizedLeft = minOf(left, right).coerceIn(0f, 1f)
        val normalizedRight = maxOf(left, right).coerceIn(0f, 1f)
        val normalizedTop = minOf(top, bottom).coerceIn(0f, 1f)
        val normalizedBottom = maxOf(top, bottom).coerceIn(0f, 1f)
        return CropRectFraction(
            left = normalizedLeft,
            top = normalizedTop,
            right = normalizedRight,
            bottom = normalizedBottom
        )
    }
}

object ImageUtil {

    fun saveImageToInternal(context: Context, uri: Uri): String? {
        return saveImageToInternal(
            context = context,
            inputStreamProvider = { context.contentResolver.openInputStream(uri) },
            rotationDegrees = 0,
            cropToSquare = false,
            cropRect = null
        )
    }

    fun saveImageToInternal(context: Context, file: File): String? {
        return saveImageToInternal(
            context = context,
            inputStreamProvider = { file.inputStream() },
            rotationDegrees = 0,
            cropToSquare = false,
            cropRect = null
        )
    }

    fun createEditedImage(
        context: Context,
        uri: Uri,
        rotationDegrees: Int = 0,
        cropToSquare: Boolean = false,
        cropRect: CropRectFraction? = null
    ): String? {
        return saveImageToInternal(
            context = context,
            inputStreamProvider = { context.contentResolver.openInputStream(uri) },
            rotationDegrees = rotationDegrees,
            cropToSquare = cropToSquare,
            cropRect = cropRect
        )
    }

    fun getDisplayAspectRatio(
        context: Context,
        uri: Uri,
        rotationDegrees: Int = 0
    ): Float? {
        return try {
            val imageBytes = context.contentResolver.openInputStream(uri)
                ?.use(InputStream::readBytes)
                ?: return null
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
            var width = options.outWidth
            var height = options.outHeight
            if (width <= 0 || height <= 0) return null

            val totalRotation = normalizeDegrees(readExifRotationDegrees(imageBytes) + rotationDegrees)
            if (totalRotation == 90 || totalRotation == 270) {
                val oldWidth = width
                width = height
                height = oldWidth
            }
            width.toFloat() / height.toFloat()
        } catch (_: Exception) {
            null
        }
    }

    fun createPreviewBitmap(
        context: Context,
        uri: Uri,
        rotationDegrees: Int = 0,
        maxSide: Int = 1400
    ): Bitmap? {
        return try {
            val imageBytes = context.contentResolver.openInputStream(uri)
                ?.use(InputStream::readBytes)
                ?: return null
            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
            val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxSide)
            val bitmap = BitmapFactory.decodeByteArray(
                imageBytes,
                0,
                imageBytes.size,
                BitmapFactory.Options().apply { inSampleSize = sampleSize }
            ) ?: return null
            bitmap
                .applyExifOrientation(imageBytes)
                .rotate(rotationDegrees)
        } catch (_: Exception) {
            null
        }
    }

    private fun saveImageToInternal(
        context: Context,
        inputStreamProvider: () -> InputStream?,
        rotationDegrees: Int,
        cropToSquare: Boolean,
        cropRect: CropRectFraction?
    ): String? {
        return try {
            val imageBytes = inputStreamProvider()?.use(InputStream::readBytes) ?: return null
            val imagesDir = File(context.filesDir, "images")
            if (!imagesDir.exists()) imagesDir.mkdirs()

            val fileName = "item_${UUID.randomUUID()}.jpg"
            val file = File(imagesDir, fileName)

            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)?.let { bitmap ->
                val normalized = bitmap
                    .applyExifOrientation(imageBytes)
                    .rotate(rotationDegrees)
                    .let { current ->
                        when {
                            cropRect != null -> current.crop(cropRect)
                            cropToSquare -> current.centerCropSquare()
                            else -> current
                        }
                    }
                FileOutputStream(file).use { out ->
                    normalized.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                if (normalized != bitmap && !bitmap.isRecycled) {
                    bitmap.recycle()
                }
                file.absolutePath
            } ?: null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun ensureImagePaths(context: Context, paths: List<String>): List<String> {
        return paths.mapNotNull { path ->
            when {
                path.startsWith("content://") -> saveImageToInternal(context, path.toUri())
                path.startsWith("file://") -> saveImageToInternal(context, path.toUri())
                File(path).exists() -> path
                else -> null
            }
        }.distinct()
    }

    fun copyImageInto(context: Context, sourcePath: String, targetName: String): String? {
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists()) return null
        return try {
            val imagesDir = File(context.filesDir, "images")
            if (!imagesDir.exists()) imagesDir.mkdirs()
            val targetFile = File(imagesDir, targetName)
            sourceFile.copyTo(targetFile, overwrite = true)
            targetFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun deleteImage(path: String) {
        try {
            val file = File(path)
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun Bitmap.applyExifOrientation(imageBytes: ByteArray): Bitmap {
        return rotate(readExifRotationDegrees(imageBytes))
    }

    private fun readExifRotationDegrees(imageBytes: ByteArray): Int {
        val orientation = try {
            ExifInterface(ByteArrayInputStream(imageBytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }

    private fun Bitmap.rotate(degrees: Int): Bitmap {
        val normalizedDegrees = normalizeDegrees(degrees)
        if (normalizedDegrees == 0) return this
        val matrix = Matrix().apply { postRotate(normalizedDegrees.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true).also {
            if (it != this && !isRecycled) recycle()
        }
    }

    private fun normalizeDegrees(degrees: Int): Int {
        return ((degrees % 360) + 360) % 360
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxSide: Int): Int {
        if (width <= 0 || height <= 0 || maxSide <= 0) return 1
        var sampleSize = 1
        var sampledWidth = width
        var sampledHeight = height
        while (sampledWidth / 2 >= maxSide || sampledHeight / 2 >= maxSide) {
            sampleSize *= 2
            sampledWidth /= 2
            sampledHeight /= 2
        }
        return sampleSize.coerceAtLeast(1)
    }

    private fun Bitmap.centerCropSquare(): Bitmap {
        val side = minOf(width, height)
        if (side <= 0 || (width == side && height == side)) return this
        val left = (width - side) / 2
        val top = (height - side) / 2
        return Bitmap.createBitmap(this, left, top, side, side).also {
            if (it != this && !isRecycled) recycle()
        }
    }

    private fun Bitmap.crop(cropRect: CropRectFraction): Bitmap {
        val normalized = cropRect.normalized()
        val cropLeft = (normalized.left * width).roundToInt().coerceIn(0, width - 1)
        val cropTop = (normalized.top * height).roundToInt().coerceIn(0, height - 1)
        val cropRight = (normalized.right * width).roundToInt().coerceIn(cropLeft + 1, width)
        val cropBottom = (normalized.bottom * height).roundToInt().coerceIn(cropTop + 1, height)
        if (cropLeft == 0 && cropTop == 0 && cropRight == width && cropBottom == height) return this
        return Bitmap.createBitmap(
            this,
            cropLeft,
            cropTop,
            cropRight - cropLeft,
            cropBottom - cropTop
        ).also {
            if (it != this && !isRecycled) recycle()
        }
    }
}

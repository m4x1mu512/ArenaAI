package com.example.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.domain.model.ChatAttachment
import com.example.domain.model.ChatAttachmentKind
import com.example.domain.model.GeneratedImage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Copies picker images into app-private storage and normalizes them to bounded JPEG files. */
@Singleton
class ImageAttachmentStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun importImage(uri: Uri): ChatAttachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val sourceMime = resolver.getType(uri)?.lowercase()
            ?: throw IllegalArgumentException("The selected file is not a supported image")
        require(sourceMime.startsWith("image/")) { "Only image files can be attached" }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = resolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Unable to open the selected image")
        boundsStream.use { stream ->
            // decodeStream intentionally returns null when inJustDecodeBounds is true.
            // The dimensions are written to Options, so do not treat that null as a read failure.
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The selected image is invalid" }

        var sample = 1
        while (bounds.outWidth / sample > MAX_DIMENSION || bounds.outHeight / sample > MAX_DIMENSION) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalArgumentException("Unable to decode the selected image")

        val directory = File(context.filesDir, "chat-images").apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val destination = File(directory, "$id.jpg")
        try {
            destination.outputStream().buffered().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    throw IllegalArgumentException("Unable to process the selected image")
                }
            }
        } finally {
            bitmap.recycle()
        }
        if (destination.length() > MAX_FILE_BYTES) {
            destination.delete()
            throw IllegalArgumentException("Image is too large after processing")
        }

        ChatAttachment(
            id = id,
            localPath = destination.absolutePath,
            mimeType = "image/jpeg",
            fileName = "image.jpg",
            sizeBytes = destination.length(),
            width = bounds.outWidth.coerceAtMost(MAX_DIMENSION),
            height = bounds.outHeight.coerceAtMost(MAX_DIMENSION),
            kind = ChatAttachmentKind.USER_IMAGE,
        )
    }

    /** Saves a generated base64 image in the same private store used by chat attachments. */
    suspend fun importGeneratedImage(image: GeneratedImage): ChatAttachment = withContext(Dispatchers.IO) {
        val mimeType = image.mimeType.lowercase().substringBefore(';')
        require(mimeType in setOf("image/jpeg", "image/png", "image/webp")) {
            "The image provider returned an unsupported image format"
        }
        val encoded = image.base64Data?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("The image provider returned no image data")
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("The image provider returned invalid image data")
        }
        require(bytes.isNotEmpty() && bytes.size <= MAX_GENERATED_FILE_BYTES) {
            "Generated image is empty or too large"
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "The image provider returned an invalid image"
        }

        val id = UUID.randomUUID().toString()
        val extension = when (mimeType) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val directory = File(context.filesDir, "chat-images").apply { mkdirs() }
        val destination = File(directory, "$id.$extension")
        destination.writeBytes(bytes)

        ChatAttachment(
            id = id,
            localPath = destination.absolutePath,
            mimeType = mimeType,
            fileName = "generated-image.$extension",
            sizeBytes = destination.length(),
            width = bounds.outWidth,
            height = bounds.outHeight,
            kind = ChatAttachmentKind.ASSISTANT_IMAGE,
        )
    }

    fun delete(attachment: ChatAttachment) {
        val file = File(attachment.localPath).canonicalFile
        val root = File(context.filesDir, "chat-images").canonicalFile
        if (file.parentFile == root) file.delete()
    }

    companion object {
        const val MAX_IMAGES = 8
        private const val MAX_DIMENSION = 2048
        private const val MAX_FILE_BYTES = 5L * 1024L * 1024L
        private const val MAX_GENERATED_FILE_BYTES = 20 * 1024 * 1024
        private const val JPEG_QUALITY = 82
    }
}

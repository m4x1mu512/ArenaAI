package com.example.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.domain.model.ChatAttachment
import com.example.domain.model.ChatAttachmentKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Copies picker images into app-private storage and normalizes them to bounded JPEG files. */
@Singleton
class ImageAttachmentStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun import(uri: Uri): ChatAttachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val sourceMime = resolver.getType(uri)?.lowercase()
            ?: throw IllegalArgumentException("The selected file is not a supported image")
        require(sourceMime.startsWith("image/")) { "Only image files can be attached" }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw IllegalArgumentException("Unable to read the selected image")
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

    fun delete(attachment: ChatAttachment) {
        val file = File(attachment.localPath).canonicalFile
        val root = File(context.filesDir, "chat-images").canonicalFile
        if (file.parentFile == root) file.delete()
    }

    companion object {
        const val MAX_IMAGES = 8
        private const val MAX_DIMENSION = 2048
        private const val MAX_FILE_BYTES = 6L * 1024L * 1024L
        private const val JPEG_QUALITY = 82
    }
}

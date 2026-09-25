package com.torxone.app.media

import android.content.Context
import java.io.File
import java.io.RandomAccessFile

/**
 * App-private storage authority for encrypted and decrypted media files.
 *
 * Invariant: Never stores raw media in public Android shared storage (e.g. Pictures/Downloads)
 * unless explicitly requested by the user via an export action.
 */
class MediaStorage(
    private val context: Context? = null,
    private val customBaseDir: File? = null
) {

    val mediaBaseDir: File
        get() = customBaseDir ?: File(context?.filesDir ?: File(System.getProperty("java.io.tmpdir"), "torx_media"), "media").apply { if (!exists()) mkdirs() }

    val incomingDir: File
        get() = File(mediaBaseDir, "incoming").apply { if (!exists()) mkdirs() }

    val outgoingDir: File
        get() = File(mediaBaseDir, "outgoing").apply { if (!exists()) mkdirs() }

    val tempTransfersDir: File
        get() = File(mediaBaseDir, "temp_transfers").apply { if (!exists()) mkdirs() }

    val thumbnailsDir: File
        get() = File(mediaBaseDir, "thumbnails").apply { if (!exists()) mkdirs() }

    /**
     * Allocates or gets the temporary encrypted file for assembling chunks of an incoming transfer.
     */
    fun getTempEncryptedFile(mediaId: String): File {
        return File(tempTransfersDir, "$mediaId.enc")
    }

    /**
     * Writes a chunk into the temporary encrypted file at the specified chunk offset.
     */
    fun writeChunk(
        mediaId: String,
        chunkIndex: Int,
        chunkSize: Int,
        chunkData: ByteArray
    ) {
        val file = getTempEncryptedFile(mediaId)
        RandomAccessFile(file, "rw").use { raf ->
            val offset = chunkIndex.toLong() * chunkSize.toLong()
            raf.seek(offset)
            raf.write(chunkData)
        }
    }

    /**
     * Reads a specific chunk from an encrypted file.
     */
    fun readChunk(
        encryptedFile: File,
        chunkIndex: Int,
        chunkSize: Int,
        totalBytes: Long
    ): ByteArray {
        val offset = chunkIndex.toLong() * chunkSize.toLong()
        val remaining = totalBytes - offset
        val actualChunkSize = Math.min(chunkSize.toLong(), remaining).toInt()
        val buffer = ByteArray(actualChunkSize)

        RandomAccessFile(encryptedFile, "r").use { raf ->
            raf.seek(offset)
            raf.readFully(buffer)
        }
        return buffer
    }

    /**
     * Saves decrypted media bytes into app-private incoming directory.
     */
    fun saveIncomingFile(mediaId: String, fileName: String, data: ByteArray): File {
        val safeName = fileName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        val destination = File(incomingDir, "${mediaId}_$safeName")
        destination.writeBytes(data)
        return destination
    }

    /**
     * Saves outgoing encrypted file in temp or outgoing directory.
     */
    fun saveOutgoingEncryptedFile(mediaId: String, data: ByteArray): File {
        val file = File(tempTransfersDir, "$mediaId.enc")
        file.writeBytes(data)
        return file
    }

    /**
     * Deletes temporary transfer file if it exists.
     */
    fun cleanupTempTransfer(mediaId: String) {
        try {
            getTempEncryptedFile(mediaId).delete()
        } catch (_: Exception) {}
    }

    /**
     * Deletes a local media file.
     */
    fun deleteLocalFile(path: String?) {
        if (path.isNullOrEmpty()) return
        try {
            val file = File(path)
            if (file.exists() && file.canonicalPath.startsWith(mediaBaseDir.canonicalPath)) {
                file.delete()
            }
        } catch (_: Exception) {}
    }
}

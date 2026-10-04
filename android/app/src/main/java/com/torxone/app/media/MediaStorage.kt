package com.torxone.app.media

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

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
    companion object {
        private const val TAG = "MediaStorage"
    }

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

    /** Only acknowledge deletion after the owned private file is actually absent. */
    fun ownedFile(path: String): File {
        val file = File(path).canonicalFile
        val base = mediaBaseDir.canonicalFile
        require(file.path.startsWith(base.path + File.separator)) { "File is outside private media storage" }
        return file
    }

    fun deleteOwnedFileConfirmed(path: String): Boolean = try {
        val file = ownedFile(path)
        !file.exists() || (file.isFile && file.delete() && !file.exists())
    } catch (_: Exception) { false }

    /**
     * Allocates or gets the temporary encrypted file for assembling chunks of an incoming transfer.
     */
    fun getTempEncryptedFile(mediaId: String): File {
        return File(tempTransfersDir, "${canonicalMediaId(mediaId)}.enc")
    }

    private fun canonicalMediaId(mediaId: String): String {
        require(mediaId.length in 32..36 && mediaId.matches(Regex("[A-Fa-f0-9-]{32,36}"))) { "Invalid media ID" }
        return UUID.fromString(mediaId).toString()
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
        require(chunkSize in 1..MediaProtocolCodec.MAX_CHUNK_BYTES)
        require(chunkIndex in 0 until MediaProtocolCodec.MAX_CHUNK_COUNT)
        require(chunkData.size in 1..chunkSize)
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
        require(chunkSize in 1..MediaProtocolCodec.MAX_CHUNK_BYTES)
        require(chunkIndex in 0 until MediaProtocolCodec.MAX_CHUNK_COUNT)
        require(totalBytes in 1..MediaProtocolCodec.MAX_MEDIA_BYTES)
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
        val destination = incomingFile(mediaId, fileName)
        destination.writeBytes(data)
        return destination
    }

    fun incomingFile(mediaId: String, fileName: String): File {
        val canonicalId = canonicalMediaId(mediaId)
        val safeName = fileName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        return File(incomingDir, "${canonicalId}_$safeName")
    }

    /**
     * Saves outgoing encrypted file in temp or outgoing directory.
     */
    fun saveOutgoingEncryptedFile(mediaId: String, data: ByteArray): File {
        val file = getTempEncryptedFile(mediaId)
        file.writeBytes(data)
        return file
    }

    /**
     * Deletes temporary transfer file if it exists.
     */
    fun cleanupTempTransfer(mediaId: String) {
        try {
            val file = getTempEncryptedFile(mediaId)
            if (!file.exists()) return
            var deleted = file.delete()
            var retries = 0
            while (!deleted && retries < 10) {
                // On Windows, file handles or antivirus scanners may linger briefly.
                // Retry with System.gc() hints to clear any lingering handles.
                System.gc()
                Thread.sleep(50)
                deleted = file.delete()
                retries++
            }
            if (!deleted) {
                file.deleteOnExit()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean up temp transfer")
        }
    }

    /**
     * Deletes a local media file.
     */
    fun deleteLocalFile(path: String?) {
        if (path.isNullOrEmpty()) return
        deleteOwnedFileConfirmed(path)
    }

    /**
     * Purges temporary transfer files that do not correspond to any active transfer.
     */
    fun cleanupOrphanTempTransfers(activeMediaIds: Set<String>) {
        try {
            val files = tempTransfersDir.listFiles() ?: return
            for (file in files) {
                val mediaId = file.name.removeSuffix(".enc")
                if (activeMediaIds.none { active ->
                        runCatching { canonicalMediaId(active) }.getOrNull() == mediaId
                    }) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean up orphan temp transfers")
        }
    }
}

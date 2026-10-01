package com.torxone.app.media

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class MediaFileOwnershipTest {
    @Test fun incomingPathIsPredictableBeforeFileCreationAndNameIsSanitized() {
        val directory = Files.createTempDirectory("media-ownership").toFile()
        try {
            val storage = MediaStorage(customBaseDir = directory)
            val id = UUID.randomUUID().toString()
            val destination = storage.incomingFile(id, "../../private.txt")
            assertFalse(destination.exists())
            assertEquals(storage.incomingDir.canonicalFile, requireNotNull(destination.parentFile).canonicalFile)
            assertEquals(destination, storage.saveIncomingFile(id, "../../private.txt", byteArrayOf(1)))
            assertTrue(storage.deleteOwnedFileConfirmed(destination.path))
        } finally { directory.deleteRecursively() }
    }

    @Test fun siblingDirectoryWithSamePrefixCannotBeDeletedOrTracked() {
        val parent = Files.createTempDirectory("media-boundary").toFile()
        try {
            val storage = MediaStorage(customBaseDir = File(parent, "owned"))
            val outside = File(parent, "owned-other/keep.txt").apply { requireNotNull(parentFile).mkdirs(); writeText("keep") }
            assertTrue(runCatching { storage.ownedFile(outside.path) }.isFailure)
            assertFalse(storage.deleteOwnedFileConfirmed(outside.path))
            assertTrue(outside.exists())
        } finally { parent.deleteRecursively() }
    }

    @Test fun absentOwnedFileIsAnIdempotentSuccessfulCleanup() {
        val directory = Files.createTempDirectory("media-absent").toFile()
        try {
            val storage = MediaStorage(customBaseDir = directory)
            val file = storage.getTempEncryptedFile(UUID.randomUUID().toString())
            assertTrue(storage.deleteOwnedFileConfirmed(file.path))
            assertTrue(storage.deleteOwnedFileConfirmed(file.path))
        } finally { directory.deleteRecursively() }
    }
}

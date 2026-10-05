package com.torxone.app.chat

import com.torxone.app.media.MediaType
import org.junit.Assert.*
import org.junit.Test

class MediaUiModelTest {
    private val model = MediaUiModel("media", MediaType.IMAGE, "photo.jpg", "image/jpeg", 100,
        thumbnailData = byteArrayOf(1, 2), waveformData = byteArrayOf(3, 4))
    @Test fun equivalentByteArraysDoNotCauseSpuriousUpdates() {
        val same = model.copy(thumbnailData = byteArrayOf(1, 2), waveformData = byteArrayOf(3, 4))
        assertEquals(model, same)
        assertEquals(model.hashCode(), same.hashCode())
    }
    @Test fun lateThumbnailAndWaveformAreVisibleUpdates() {
        assertNotEquals(model, model.copy(thumbnailData = byteArrayOf(2, 3)))
        assertNotEquals(model, model.copy(waveformData = byteArrayOf(4, 5)))
        assertNotEquals(model, model.copy(thumbnailData = null))
    }
    @Test fun correctedMetadataIsNotSuppressedByStateFlowEquality() {
        assertNotEquals(model, model.copy(fileName = "new.jpg"))
        assertNotEquals(model, model.copy(mimeType = "image/png"))
        assertNotEquals(model, model.copy(fileSize = 200))
        assertNotEquals(model, model.copy(type = MediaType.VIDEO))
        assertNotEquals(model, model.copy(durationMs = 1000))
    }
}

package com.torxone.app.media

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for VoiceNoteHelper.
 */
class VoiceNoteTest {

    @Test
    fun `formatDuration formats timestamps accurately`() {
        assertEquals("00:00", VoiceNoteHelper.formatDuration(0L))
        assertEquals("00:01", VoiceNoteHelper.formatDuration(1000L))
        assertEquals("00:15", VoiceNoteHelper.formatDuration(15000L))
        assertEquals("01:05", VoiceNoteHelper.formatDuration(65000L))
        assertEquals("10:00", VoiceNoteHelper.formatDuration(600000L))
        assertEquals("00:00", VoiceNoteHelper.formatDuration(-500L))
    }

    @Test
    fun `generateWaveform produces correct number of bounded amplitudes`() {
        val count = 32
        val waveform = VoiceNoteHelper.generateWaveform(count, seed = 42L)
        assertEquals(count, waveform.size)

        for (byteVal in waveform) {
            val amp = byteVal.toInt() and 0xFF
            assertTrue("Amplitude $amp must be between 5 and 100", amp in 5..100)
        }
    }

    @Test
    fun `normalizeWaveform converts to valid float list`() {
        val raw = byteArrayOf(10, 50, 80, 100)
        val normalized = VoiceNoteHelper.normalizeWaveform(raw)

        assertEquals(4, normalized.size)
        assertEquals(0.10f, normalized[0], 0.01f)
        assertEquals(0.50f, normalized[1], 0.01f)
        assertEquals(0.80f, normalized[2], 0.01f)
        assertEquals(1.00f, normalized[3], 0.01f)

        val emptyNorm = VoiceNoteHelper.normalizeWaveform(null)
        assertEquals(VoiceNoteHelper.DEFAULT_WAVEFORM_BAR_COUNT, emptyNorm.size)
    }

    @Test
    fun `generateSyntheticAudio produces valid WAV header`() {
        val wav = VoiceNoteHelper.generateSyntheticAudio(durationSeconds = 1, sampleRate = 8000)
        assertTrue(wav.size > 44)

        // Verify RIFF header
        assertEquals('R'.code.toByte(), wav[0])
        assertEquals('I'.code.toByte(), wav[1])
        assertEquals('F'.code.toByte(), wav[2])
        assertEquals('F'.code.toByte(), wav[3])

        // Verify WAVE
        assertEquals('W'.code.toByte(), wav[8])
        assertEquals('A'.code.toByte(), wav[9])
        assertEquals('V'.code.toByte(), wav[10])
        assertEquals('E'.code.toByte(), wav[11])
    }
}

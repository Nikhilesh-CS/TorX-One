package com.torxone.app.media

import java.util.Locale
import kotlin.random.Random

/**
 * Utility helper for voice notes: duration formatting, waveform generation,
 * amplitude normalization, and synthetic audio for tests.
 */
object VoiceNoteHelper {

    const val DEFAULT_WAVEFORM_BAR_COUNT = 32

    /**
     * Formats duration in milliseconds to mm:ss (e.g. 00:05, 01:42).
     */
    fun formatDuration(durationMs: Long): String {
        val totalSeconds = Math.max(0L, durationMs / 1000L)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    /**
     * Generates a realistic normalized waveform byte array (values between 5 and 100).
     */
    fun generateWaveform(sampleCount: Int = DEFAULT_WAVEFORM_BAR_COUNT, seed: Long? = null): ByteArray {
        val rng = if (seed != null) Random(seed) else Random.Default
        val waveform = ByteArray(sampleCount)
        var lastAmp = 30
        for (i in 0 until sampleCount) {
            val delta = rng.nextInt(-15, 16)
            lastAmp = (lastAmp + delta).coerceIn(10, 95)
            waveform[i] = lastAmp.toByte()
        }
        return waveform
    }

    /**
     * Converts a raw amplitude ByteArray into a List<Float> (0.0f..1.0f) for Compose UI rendering.
     */
    fun normalizeWaveform(data: ByteArray?): List<Float> {
        if (data == null || data.isEmpty()) {
            return List(DEFAULT_WAVEFORM_BAR_COUNT) { 0.2f }
        }
        return data.map { b -> (b.toInt() and 0xFF).toFloat() / 100f }
    }

    /**
     * Generates a synthetic minimal WAV audio header + audio bytes strictly for unit tests.
     * Production audio capture must use VoiceNoteRecorder / AudioRecord.
     */
    @androidx.annotation.VisibleForTesting
    fun generateSyntheticAudio(durationSeconds: Int = 2, sampleRate: Int = 8000): ByteArray {
        val totalAudioLen = sampleRate * durationSeconds
        val totalDataLen = totalAudioLen + 36
        val channels = 1
        val byteRate = sampleRate * channels * 1

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte() // RIFF/WAVE header
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte() // 'fmt ' chunk
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // 4 bytes: size of 'fmt ' chunk
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // format = 1 (PCM)
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = 1 // block align
        header[33] = 0
        header[34] = 8 // bits per sample
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        val audioData = ByteArray(totalAudioLen) { (it % 128).toByte() }
        return header + audioData
    }
}

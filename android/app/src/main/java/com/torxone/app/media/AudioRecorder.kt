package com.torxone.app.media

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * Result of a completed voice note recording.
 */
data class VoiceRecordingResult(
    val audioData: ByteArray,
    val durationMs: Long,
    val waveform: ByteArray
)

/**
 * Interface for capturing voice note audio.
 */
interface VoiceNoteRecorder {
    val isRecording: Boolean
    val amplitudeFlow: StateFlow<Float>
    fun startRecording(): Boolean
    fun stopRecording(): VoiceRecordingResult?
    fun cancelRecording()
}

/**
 * Production-ready Android AudioRecord implementation.
 *
 * Records 16 kHz 16-bit mono PCM microphone audio, computes real RMS amplitude
 * in real-time for live waveform visualization, and formats output as standard WAV.
 */
class RealVoiceNoteRecorder(
    private val context: Context,
    private val sampleRate: Int = 16000
) : VoiceNoteRecorder {

    companion object {
        private const val TAG = "RealVoiceNoteRecorder"
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val _amplitudeFlow = MutableStateFlow(0f)
    override val amplitudeFlow: StateFlow<Float> = _amplitudeFlow.asStateFlow()

    @Volatile
    override var isRecording: Boolean = false
        private set

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val pcmOutputStream = ByteArrayOutputStream()
    private val waveformAmplitudes = mutableListOf<Byte>()
    private var startTimeMs: Long = 0L

    @SuppressLint("MissingPermission")
    override fun startRecording(): Boolean {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Cannot start recording: RECORD_AUDIO permission not granted")
            return false
        }

        if (isRecording) {
            Log.w(TAG, "Recording already in progress")
            return false
        }

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid buffer size for AudioRecord: $minBufferSize")
            return false
        }

        val bufferSize = minBufferSize * 2

        try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                record.release()
                return false
            }

            audioRecord = record
            pcmOutputStream.reset()
            waveformAmplitudes.clear()
            startTimeMs = System.currentTimeMillis()
            isRecording = true

            record.startRecording()

            recordingJob = scope.launch {
                val buffer = ShortArray(bufferSize / 2)
                val byteBuffer = ByteArray(bufferSize)

                while (isActive && isRecording) {
                    val readShorts = record.read(buffer, 0, buffer.size)
                    if (readShorts > 0) {
                        // 1. Convert shorts to 16-bit little-endian bytes and append to output stream
                        var sumSquares = 0.0
                        for (i in 0 until readShorts) {
                            val sample = buffer[i]
                            val b1 = (sample.toInt() and 0xFF).toByte()
                            val b2 = ((sample.toInt() shr 8) and 0xFF).toByte()
                            byteBuffer[i * 2] = b1
                            byteBuffer[i * 2 + 1] = b2
                            sumSquares += sample.toDouble() * sample.toDouble()
                        }
                        synchronized(pcmOutputStream) {
                            pcmOutputStream.write(byteBuffer, 0, readShorts * 2)
                        }

                        // 2. Compute RMS amplitude
                        val rms = sqrt(sumSquares / readShorts)
                        // Normalize 0..32767 to 0.0f..1.0f (with sensible speech floor)
                        val normalized = (rms / 32767.0).toFloat().coerceIn(0f, 1f)
                        _amplitudeFlow.value = normalized

                        // Map to 5..100 byte range for stored waveform
                        val byteAmp = (normalized * 95f + 5f).toInt().coerceIn(5, 100).toByte()
                        synchronized(waveformAmplitudes) {
                            waveformAmplitudes.add(byteAmp)
                        }
                    } else if (readShorts < 0) {
                        Log.e(TAG, "Error reading from AudioRecord: $readShorts")
                        break
                    }
                }
            }

            Log.i(TAG, "Voice note recording started successfully at ${sampleRate}Hz")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord: ${e.message}", e)
            cleanup()
            return false
        }
    }

    override fun stopRecording(): VoiceRecordingResult? {
        if (!isRecording) return null
        val duration = System.currentTimeMillis() - startTimeMs
        isRecording = false

        recordingJob?.cancel()
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord: ${e.message}")
        }

        val rawPcm = synchronized(pcmOutputStream) {
            pcmOutputStream.toByteArray()
        }

        cleanup()

        if (rawPcm.isEmpty() || duration < 500) {
            Log.w(TAG, "Recording too short or empty ($duration ms, ${rawPcm.size} bytes)")
            return null
        }

        // Build standard 44-byte WAV header + PCM audio
        val wavData = wrapPcmInWav(rawPcm, sampleRate)

        // Resample waveform amplitudes to standard 32 bars
        val waveform = resampleWaveform(waveformAmplitudes, VoiceNoteHelper.DEFAULT_WAVEFORM_BAR_COUNT)

        Log.i(TAG, "Voice note recording complete: ${wavData.size} bytes, ${duration}ms")
        return VoiceRecordingResult(
            audioData = wavData,
            durationMs = duration,
            waveform = waveform
        )
    }

    override fun cancelRecording() {
        if (!isRecording) return
        isRecording = false
        recordingJob?.cancel()
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord on cancel: ${e.message}")
        }
        cleanup()
        Log.i(TAG, "Voice note recording cancelled")
    }

    private fun cleanup() {
        isRecording = false
        _amplitudeFlow.value = 0f
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord: ${e.message}")
        }
        audioRecord = null
    }

    private fun wrapPcmInWav(pcmData: ByteArray, sampleRate: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = channels * (bitsPerSample / 8)
        val totalAudioLen = pcmData.size
        val totalDataLen = totalAudioLen + 36

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
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
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // PCM format
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
        header[32] = blockAlign.toByte()
        header[33] = 0
        header[34] = bitsPerSample.toByte()
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        return header + pcmData
    }

    private fun resampleWaveform(samples: List<Byte>, targetCount: Int): ByteArray {
        if (samples.isEmpty()) {
            return ByteArray(targetCount) { 30.toByte() }
        }
        if (samples.size == targetCount) {
            return samples.toByteArray()
        }

        val result = ByteArray(targetCount)
        val step = samples.size.toFloat() / targetCount.toFloat()
        for (i in 0 until targetCount) {
            val idx = (i * step).toInt().coerceIn(0, samples.size - 1)
            result[i] = samples[idx]
        }
        return result
    }
}

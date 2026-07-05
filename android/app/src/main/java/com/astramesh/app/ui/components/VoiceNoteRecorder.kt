package com.astramesh.app.ui.components

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException

class VoiceNoteRecorder(private val context: Context) {

    companion object {
        private const val TAG = "VoiceNoteRecorder"
    }

    private var recorder: MediaRecorder? = null
    var outputFile: File? = null
        private set

    fun startRecording(): Boolean {
        try {
            val dir = File(context.cacheDir, "voice_notes")
            if (!dir.exists()) dir.mkdirs()

            outputFile = File(dir, "voice_note_${System.currentTimeMillis()}.ogg")

            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                
                // Use Opus / OGG if possible (API 29+), else fallback to AAC / M4A
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setOutputFormat(MediaRecorder.OutputFormat.OGG)
                    setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                    setAudioEncodingBitRate(32000) // 32 kbps
                    setAudioSamplingRate(16000) // 16 kHz
                } else {
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(32000)
                    setAudioSamplingRate(16000)
                }
                
                setOutputFile(outputFile?.absolutePath)
                prepare()
                start()
            }
            return true
        } catch (e: IOException) {
            Log.e(TAG, "prepare() failed", e)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "startRecording() failed", e)
            return false
        }
    }

    fun stopRecording(): File? {
        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopRecording() failed", e)
        } finally {
            recorder = null
        }
        return outputFile
    }
}

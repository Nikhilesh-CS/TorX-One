package com.astramesh.app.music

import android.content.Context
import android.content.Intent
import android.net.Uri

class MusicProviderResolver(private val context: Context) {
    fun openTrack(track: DetectedMusicTrack): Boolean {
        val providerIntent = Intent(Intent.ACTION_VIEW).apply {
            data = providerUri(track)
            setPackage(track.provider.takeIf { it.contains(".") })
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val fallbackIntent = Intent(Intent.ACTION_VIEW, searchUri(track)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            if (providerIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(providerIntent)
            } else {
                context.startActivity(fallbackIntent)
            }
            true
        }.getOrDefault(false)
    }

    private fun providerUri(track: DetectedMusicTrack): Uri {
        return when {
            track.provider.contains("spotify", ignoreCase = true) -> {
                Uri.parse("spotify:search:${Uri.encode("${track.trackName} ${track.artist}")}")
            }
            else -> searchUri(track)
        }
    }

    private fun searchUri(track: DetectedMusicTrack): Uri {
        return Uri.parse("https://www.google.com/search?q=${Uri.encode("${track.trackName} ${track.artist}")}")
    }
}

package com.torxone.app.music

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.torxone.app.data.MusicNoteEntity

class MusicNotificationManager(private val context: Context) {
    fun showMusicNoteNotification(note: MusicNoteEntity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notification = NotificationCompat.Builder(context, "astra_messages")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("${note.authorName} shared a new Music Note")
            .setContentText("${note.trackName} • ${note.artist}")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(note.noteId.hashCode(), notification)
        }
    }
}

package com.ingoviq.jarvis

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Bundle
import android.provider.MediaStore
import android.view.KeyEvent

/** YouTube Music control: gaana lav, pause, next, volume. */
class MusicController(private val ctx: Context) {

    private val am = ctx.getSystemService(AudioManager::class.java)

    fun perform(action: String, query: String) {
        when (action) {
            "play" -> play(query)
            "pause" -> key(KeyEvent.KEYCODE_MEDIA_PAUSE)
            "resume" -> key(KeyEvent.KEYCODE_MEDIA_PLAY)
            "next" -> key(KeyEvent.KEYCODE_MEDIA_NEXT)
            "previous" -> key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "volume_up" -> repeat(3) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0) }
            "volume_down" -> repeat(3) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0) }
        }
    }

    private fun key(code: Int) {
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun play(query: String) {
        if (query.isBlank()) {
            key(KeyEvent.KEYCODE_MEDIA_PLAY)
            return
        }
        JarvisLog.add("🎵 YouTube Music: $query")

        // 1) YouTube Music aadhich chalu asel tar background madhech gaana lavto
        val controller = ytController()
        if (controller != null) {
            controller.transportControls.playFromSearch(query, Bundle())
            return
        }

        // 2) Nahitar YouTube Music app ughdun gaana lavto
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage(YT_MUSIC)
            putExtra(SearchManager.QUERY, query)
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            JarvisLog.add("❌ YouTube Music ughdla nahi: ${e.message}")
        }
    }

    private fun ytController(): MediaController? = try {
        val msm = ctx.getSystemService(MediaSessionManager::class.java)
        msm.getActiveSessions(ComponentName(ctx, MediaListener::class.java))
            .firstOrNull { it.packageName == YT_MUSIC }
    } catch (e: SecurityException) {
        null // Notification access dila nahi
    }

    companion object {
        const val YT_MUSIC = "com.google.android.apps.youtube.music"
    }
}

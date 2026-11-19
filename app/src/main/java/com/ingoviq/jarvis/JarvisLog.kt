package com.ingoviq.jarvis

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** App screen var disnara live log. */
object JarvisLog {
    private val lines = ArrayDeque<String>()
    private val main = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    var listener: ((String) -> Unit)? = null

    fun add(msg: String) {
        Log.d("JARVIS", msg)
        val text: String
        synchronized(this) {
            lines.addLast("${fmt.format(Date())}  $msg")
            while (lines.size > 80) lines.removeFirst()
            text = lines.reversed().joinToString("\n")
        }
        main.post { listener?.invoke(text) }
    }

    fun all(): String = synchronized(this) { lines.reversed().joinToString("\n") }
}

package com.ingoviq.jarvis

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Jarvis cha awaaz — Android Text-to-Speech (Marathi / Hindi / English). */
class Speaker(ctx: Context) {
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private val tts: TextToSpeech = TextToSpeech(
        ctx.applicationContext,
        { status -> ready.complete(status == TextToSpeech.SUCCESS) },
        "com.google.android.tts",
    )

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                utteranceId?.let { pending.remove(it)?.complete(Unit) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId?.let { pending.remove(it)?.complete(Unit) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let { pending.remove(it)?.complete(Unit) }
            }
        })
    }

    /** Bolto ani bolna sampepryant thambto. */
    suspend fun say(text: String, lang: String) {
        if (text.isBlank()) return
        val ok = withTimeoutOrNull(10_000) { ready.await() } ?: false
        if (!ok) {
            JarvisLog.add("⚠️ Text-to-Speech ready nahi")
            return
        }
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts.setLanguage(pickLocale(lang))
        tts.setPitch(0.95f)
        tts.setSpeechRate(1.05f)

        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
        withTimeoutOrNull(60_000) { done.await() }
    }

    private fun pickLocale(lang: String): Locale {
        val candidates = listOf(lang, "hi-IN", "en-IN", "en-US")
        for (tag in candidates) {
            val loc = Locale.forLanguageTag(tag)
            if (tts.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) {
                if (tag != lang) JarvisLog.add("⚠️ $lang voice nahi, $tag vaparto")
                return loc
            }
        }
        return Locale.getDefault()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}

package com.ingoviq.jarvis

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Jarvis cha brain: tuzha awaaz (audio) direct Gemini la pathavto.
 * Gemini bhasha olakhto, uttar deto ani gaana command asel tar action sangto.
 */
class GeminiBrain(private val prefs: Prefs) {

    data class Reply(
        val heard: String,
        val language: String,
        val reply: String,
        val action: String,
        val query: String,
        val continueConversation: Boolean,
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    /** Magchya gappa lakshat thevnyasathi (shevatche kahi turns). */
    private val history = ArrayDeque<JSONObject>()

    suspend fun ask(wav: ByteArray): Result<Reply> = withContext(Dispatchers.IO) {
        runCatching {
            val audioPart = JSONObject().put(
                "inlineData", JSONObject()
                    .put("mimeType", "audio/wav")
                    .put("data", Base64.encodeToString(wav, Base64.NO_WRAP))
            )
            val userTurn = JSONObject().put("role", "user").put("parts", JSONArray().put(audioPart))

            val contents = JSONArray()
            synchronized(history) { history.forEach { contents.put(it) } }
            contents.put(userTurn)

            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt()))))
                .put("contents", contents)
                .put(
                    "generationConfig", JSONObject()
                        .put("temperature", 0.7)
                        .put("responseMimeType", "application/json")
                        .put("responseSchema", JSONObject(SCHEMA))
                )

            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/${prefs.model}:generateContent")
                .header("x-goog-api-key", prefs.geminiKey)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val raw = http.newCall(req).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${txt.take(300)}")
                txt
            }

            val parts = JSONObject(raw).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (!p.optBoolean("thought", false)) sb.append(p.optString("text", ""))
            }
            val text = sb.toString().trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val o = JSONObject(text)

            val reply = Reply(
                heard = o.optString("heard", ""),
                language = o.optString("language", "mr-IN").ifBlank { "mr-IN" },
                reply = o.optString("reply", ""),
                action = o.optString("action", "none").ifBlank { "none" },
                query = o.optString("query", ""),
                continueConversation = o.optBoolean("continue_conversation", false),
            )

            // History madhe audio nahi, fakt text thevto (request lahan rahte)
            synchronized(history) {
                history.addLast(
                    JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", "[voice] ${reply.heard}")))
                )
                history.addLast(
                    JSONObject().put("role", "model")
                        .put("parts", JSONArray().put(JSONObject().put("text", text)))
                )
                while (history.size > 12) history.removeFirst()
            }
            reply
        }
    }

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale.ENGLISH).format(Date())
        val name = prefs.userName
        return """
You are JARVIS, the personal voice assistant of $name. Address him as "$name sir" or "sir", like Tony Stark's JARVIS: smart, warm, a little witty and loyal.
You hear him through a Bluetooth speaker. The audio may contain background music or noise.
Current date and time (India): $now.

LANGUAGE
- Detect the language he spoke (Marathi, Hindi, English, or a mix like Hinglish) and reply in that SAME language and style.
- Write Marathi and Hindi replies in Devanagari script, because the reply is read aloud by a text-to-speech engine. English replies in Latin script.
- Put the BCP-47 code of your reply language in "language": mr-IN, hi-IN or en-IN (or the right code if he used another language).

STYLE
- The reply is SPOKEN, so keep it short: usually 1-2 sentences, at most 4 when explaining something. No emojis, markdown, lists or URLs.

MUSIC (the music app is YouTube Music)
- Play a song, artist, film's songs, mood or playlist: action "play", and put a good YouTube Music search query in "query" (for example "Zingaat Sairat", "Arijit Singh romantic songs", "Marathi lofi"). Confirm in one short line.
- Pause or stop the song: "pause". Resume: "resume". Next or change song: "next". Previous song: "previous". Louder: "volume_up". Softer: "volume_down".
- Anything else: action "none" and query "".

OTHER
- "heard": what he said, in its original language.
- If the audio is empty, only noise or unclear: action "none", politely ask him to repeat, continue_conversation true.
- continue_conversation: true if you asked him something or it is a back-and-forth chat; false after music commands, goodbyes ("bas", "thank you", "bye") or when the chat is clearly finished.
""".trimIndent()
    }

    companion object {
        private const val SCHEMA = """
{
  "type": "OBJECT",
  "properties": {
    "heard": {"type": "STRING"},
    "language": {"type": "STRING"},
    "reply": {"type": "STRING"},
    "action": {"type": "STRING", "enum": ["none", "play", "pause", "resume", "next", "previous", "volume_up", "volume_down"]},
    "query": {"type": "STRING"},
    "continue_conversation": {"type": "BOOLEAN"}
  },
  "required": ["heard", "language", "reply", "action", "query", "continue_conversation"]
}
"""

        /** 16-bit mono PCM la WAV file madhe convert karto. */
        fun pcmToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(36 + pcm.size)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1)            // PCM
            header.putShort(1)            // mono
            header.putInt(sampleRate)
            header.putInt(sampleRate * 2) // byte rate
            header.putShort(2)            // block align
            header.putShort(16)           // bits per sample
            header.put("data".toByteArray())
            header.putInt(pcm.size)
            return header.array() + pcm
        }
    }
}

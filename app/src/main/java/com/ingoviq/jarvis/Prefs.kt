package com.ingoviq.jarvis

import android.content.Context

/** App settings (keys phone madhech save hotat, GitHub var nahi). */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    private fun str(key: String, def: String) = sp.getString(key, def) ?: def
    private fun put(key: String, v: String) = sp.edit().putString(key, v).apply()

    var userName: String
        get() = str("name", "Abhishek")
        set(v) = put("name", v)

    var geminiKey: String
        get() = str("gemini_key", "")
        set(v) = put("gemini_key", v.trim())

    var picoKey: String
        get() = str("pico_key", "")
        set(v) = put("pico_key", v.trim())

    var model: String
        get() = str("model", "gemini-flash-latest")
        set(v) = put("model", v.trim().ifEmpty { "gemini-flash-latest" })

    /** Rikama = koni pan Bluetooth audio device */
    var deviceAddress: String
        get() = str("dev_addr", "")
        set(v) = put("dev_addr", v)

    var deviceName: String
        get() = str("dev_name", "")
        set(v) = put("dev_name", v)

    var useSpeakerMic: Boolean
        get() = sp.getBoolean("speaker_mic", false)
        set(v) = sp.edit().putBoolean("speaker_mic", v).apply()

    var autoStart: Boolean
        get() = sp.getBoolean("auto_start", true)
        set(v) = sp.edit().putBoolean("auto_start", v).apply()
}

package com.ingoviq.jarvis

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import ai.picovoice.porcupine.Porcupine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * JARVIS cha main engine.
 * Speaker connect -> "Jarvis" wake word aikto -> tuzi command record -> Gemini -> bolto / gaana lavto.
 */
class JarvisService : Service() {

    companion object {
        @Volatile
        var running = false
        private const val ACTION_STOP = "com.ingoviq.jarvis.STOP"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, JarvisService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, JarvisService::class.java))
        }
    }

    private enum class State { IDLE, RECORDING, BUSY }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var prefs: Prefs
    private lateinit var speaker: Speaker
    private lateinit var brain: GeminiBrain
    private lateinit var music: MusicController
    private lateinit var audioManager: AudioManager

    private var listenJob: Job? = null
    private var a2dpProxy: BluetoothProfile? = null
    private var focusRequest: AudioFocusRequest? = null

    @Volatile private var state = State.IDLE
    @Volatile private var vad: Vad? = null
    @Volatile private var followUp = false
    @Volatile private var prompted = false
    private val cmdBuffer = ByteArrayOutputStream()
    private var sampleRate = 16000
    private var frameLength = 512

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val device = deviceFrom(intent) ?: return
            if (!isMySpeaker(device)) return
            when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                BluetoothProfile.STATE_CONNECTED -> onSpeakerConnected()
                BluetoothProfile.STATE_DISCONNECTED -> onSpeakerDisconnected()
            }
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        Notifs.createChannels(this)
        try {
            val n = buildNotification("Speaker chi vaat baghtoy…")
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(Notifs.ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(Notifs.ID_SERVICE, n)
            }
        } catch (e: Exception) {
            // Android 14+: background madhun mic service suru karu det nahi
            JarvisLog.add("⚠️ Background madhun start nahi zala, notification var tap kara")
            Notifs.showTapToStart(this)
            stopSelf()
            return
        }
        running = true
        speaker = Speaker(this)
        brain = GeminiBrain(prefs)
        music = MusicController(this)
        audioManager = getSystemService(AudioManager::class.java)

        val filter = IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(btReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(btReceiver, filter)
        }
        JarvisLog.add("✅ Jarvis service suru")
        checkAlreadyConnected()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        if (running) {
            running = false
            try { unregisterReceiver(btReceiver) } catch (ignored: Exception) {}
            listenJob?.cancel()
            abandonFocus()
            speaker.shutdown()
            a2dpProxy?.let {
                getSystemService(BluetoothManager::class.java)?.adapter
                    ?.closeProfileProxy(BluetoothProfile.A2DP, it)
            }
            JarvisLog.add("⏹ Jarvis band")
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- bluetooth

    private fun deviceFrom(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun isMySpeaker(device: BluetoothDevice): Boolean =
        prefs.deviceAddress.isEmpty() || device.address == prefs.deviceAddress

    private fun checkAlreadyConnected() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        try {
            adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    a2dpProxy = proxy
                    val connected = try {
                        proxy.connectedDevices.any { isMySpeaker(it) }
                    } catch (e: SecurityException) {
                        false
                    }
                    if (connected) onSpeakerConnected()
                    else JarvisLog.add("🔌 Speaker connect kara, Jarvis vaat baghtoy")
                }

                override fun onServiceDisconnected(profile: Int) {
                    a2dpProxy = null
                }
            }, BluetoothProfile.A2DP)
        } catch (e: SecurityException) {
            JarvisLog.add("❌ Bluetooth permission nahi")
        }
    }

    private fun onSpeakerConnected() {
        if (!running || listenJob?.isActive == true) return
        JarvisLog.add("🔊 Speaker connect zala")
        updateNotification("Jarvis tayar aahe — \"Jarvis\" mhana")
        listenJob = scope.launch {
            delay(2000) // Bluetooth audio settle hou de
            speaker.say("नमस्कार ${prefs.userName} सर! जार्विस तयार आहे.", "mr-IN")
            listenLoop()
        }
    }

    private fun onSpeakerDisconnected() {
        if (listenJob == null) return
        JarvisLog.add("🔌 Speaker disconnect zala, Jarvis jhoplay")
        listenJob?.cancel()
        listenJob = null
        state = State.IDLE
        abandonFocus()
        updateNotification("Speaker chi vaat baghtoy…")
    }

    // ---------------------------------------------------------------- audio loop

    private suspend fun listenLoop() = withContext(Dispatchers.IO) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            JarvisLog.add("❌ Mic permission nahi")
            return@withContext
        }
        val porcupine = try {
            Porcupine.Builder()
                .setAccessKey(prefs.picoKey)
                .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                .setSensitivity(0.7f)
                .build(applicationContext)
        } catch (e: Exception) {
            JarvisLog.add("❌ Wake word suru nahi zala: ${e.message}")
            speaker.say("माफ करा सर, Picovoice key मध्ये प्रॉब्लेम आहे. ॲपमध्ये तपासा.", "mr-IN")
            return@withContext
        }
        sampleRate = porcupine.sampleRate
        frameLength = porcupine.frameLength

        val useBt = prefs.useSpeakerMic
        if (useBt) {
            routeToSpeakerMic(true)
            delay(1500) // Bluetooth mic (SCO) connect hou de
        }
        val source = if (useBt) MediaRecorder.AudioSource.VOICE_COMMUNICATION
        else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

        val rec = try {
            AudioRecord(source, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, frameLength * 2 * 10))
        } catch (e: SecurityException) {
            null
        }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            JarvisLog.add("❌ Mic suru nahi zala")
            porcupine.delete()
            if (useBt) routeToSpeakerMic(false)
            return@withContext
        }

        val frame = ShortArray(frameLength)
        val bytes = ByteArray(frameLength * 2)
        rec.startRecording()
        state = State.IDLE
        JarvisLog.add("👂 \"Jarvis\" sathi aikat aahe" + if (useBt) " (speaker mic)" else " (phone mic)")

        try {
            while (isActive) {
                var read = 0
                while (read < frameLength) {
                    val n = rec.read(frame, read, frameLength - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < frameLength) {
                    delay(20)
                    continue
                }
                when (state) {
                    State.IDLE -> if (porcupine.process(frame) >= 0) onWake()
                    State.RECORDING -> {
                        for (i in 0 until frameLength) {
                            val s = frame[i].toInt()
                            bytes[i * 2] = (s and 0xFF).toByte()
                            bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                        }
                        cmdBuffer.write(bytes)
                        when (vad?.feed(frame)) {
                            Vad.Result.DONE -> {
                                state = State.BUSY
                                val pcm = cmdBuffer.toByteArray()
                                scope.launch { handleCommand(pcm) }
                            }
                            Vad.Result.NO_SPEECH -> {
                                state = State.BUSY
                                scope.launch { handleSilence() }
                            }
                            null -> Unit
                        }
                    }
                    State.BUSY -> Unit // Jarvis bolat ki vichar karat astana aikat nahi
                }
            }
        } finally {
            try { rec.stop() } catch (ignored: Exception) {}
            rec.release()
            porcupine.delete()
            if (useBt) routeToSpeakerMic(false)
        }
    }

    private fun onWake() {
        JarvisLog.add("✨ Jarvis jaga zala!")
        requestFocus() // chalu gaana thodya veli thambavto
        followUp = false
        prompted = false
        startRecording(startTimeoutMs = 1500)
    }

    private fun startRecording(startTimeoutMs: Int) {
        cmdBuffer.reset()
        vad = Vad(sampleRate, frameLength, startTimeoutMs)
        state = State.RECORDING
    }

    /** Fakt "Jarvis" mhatla ani thambla tar: "बोला सर?" */
    private suspend fun handleSilence() {
        when {
            followUp -> finishInteraction(null) // gappa samplya
            !prompted -> {
                prompted = true
                speaker.say("बोला सर?", "mr-IN")
                startRecording(startTimeoutMs = 5000)
            }
            else -> finishInteraction(null)
        }
    }

    private suspend fun handleCommand(pcm: ByteArray) {
        JarvisLog.add("🤔 Vichar kartoy…")
        val result = brain.ask(GeminiBrain.pcmToWav(pcm, sampleRate))
        val r = result.getOrElse {
            JarvisLog.add("❌ Gemini error: ${it.message}")
            speaker.say("माफ करा सर, आत्ता उत्तर मिळालं नाही. इंटरनेट किंवा API key तपासा.", "mr-IN")
            finishInteraction(null)
            return
        }
        JarvisLog.add("🗣️ Tumhi: ${r.heard}")
        JarvisLog.add("🤖 Jarvis [${r.language}]: ${r.reply}" + if (r.action != "none") "  → ${r.action} ${r.query}" else "")

        speaker.say(r.reply, r.language)

        if (r.action == "none" && r.continueConversation) {
            // Parat "Jarvis" na mhanta pudhe bolta yeta
            followUp = true
            prompted = true
            startRecording(startTimeoutMs = 4000)
        } else {
            finishInteraction(r)
        }
    }

    private suspend fun finishInteraction(r: GeminiBrain.Reply?) {
        abandonFocus()
        if (r != null && r.action != "none") {
            delay(500) // gaana resume hou de, mag command
            withContext(Dispatchers.Main) { music.perform(r.action, r.query) }
        }
        followUp = false
        prompted = false
        state = State.IDLE
    }

    // ---------------------------------------------------------------- audio helpers

    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        focusRequest = req
        audioManager.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    /** Speaker cha mic (Bluetooth SCO). Lakshat theva: chalu astana gaana call-quality madhe vajta. */
    @Suppress("DEPRECATION")
    private fun routeToSpeakerMic(on: Boolean) {
        try {
            if (on) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= 31) {
                    val dev = audioManager.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                    if (dev != null) audioManager.setCommunicationDevice(dev)
                    else JarvisLog.add("⚠️ Speaker cha mic sapadla nahi, phone mic vaparto")
                } else {
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                }
            } else {
                if (Build.VERSION.SDK_INT >= 31) {
                    audioManager.clearCommunicationDevice()
                } else {
                    audioManager.stopBluetoothSco()
                    audioManager.isBluetoothScoOn = false
                }
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        } catch (e: Exception) {
            JarvisLog.add("⚠️ Mic routing: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- notification

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, JarvisService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, Notifs.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("JARVIS")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_jarvis), "Band kara", stop).build()
            )
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(Notifs.ID_SERVICE, buildNotification(text))
        } catch (ignored: SecurityException) {
        }
    }
}

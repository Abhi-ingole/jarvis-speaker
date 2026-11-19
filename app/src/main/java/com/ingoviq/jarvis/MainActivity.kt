package com.ingoviq.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        const val EXTRA_START = "start_jarvis"
    }

    private lateinit var prefs: Prefs
    private lateinit var etName: EditText
    private lateinit var etGemini: EditText
    private lateinit var etPico: EditText
    private lateinit var etModel: EditText
    private lateinit var tvSpeaker: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var swMic: Switch
    private lateinit var swAuto: Switch
    private lateinit var btnStart: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        etName = findViewById(R.id.etName)
        etGemini = findViewById(R.id.etGemini)
        etPico = findViewById(R.id.etPico)
        etModel = findViewById(R.id.etModel)
        tvSpeaker = findViewById(R.id.tvSpeaker)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        swMic = findViewById(R.id.swMic)
        swAuto = findViewById(R.id.swAuto)
        btnStart = findViewById(R.id.btnStart)

        etName.setText(prefs.userName)
        etGemini.setText(prefs.geminiKey)
        etPico.setText(prefs.picoKey)
        etModel.setText(prefs.model)
        swMic.isChecked = prefs.useSpeakerMic
        swAuto.isChecked = prefs.autoStart

        findViewById<Button>(R.id.btnPick).setOnClickListener { pickSpeaker() }
        findViewById<Button>(R.id.btnPerms).setOnClickListener { askPermissions() }
        findViewById<Button>(R.id.btnNotif).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { askBattery() }
        btnStart.setOnClickListener { toggleJarvis() }

        if (!hasCorePermissions()) askPermissions()
        handleStartExtra(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleStartExtra(intent)
    }

    override fun onResume() {
        super.onResume()
        tvLog.text = JarvisLog.all()
        JarvisLog.listener = { tvLog.text = it }
        refreshUi()
    }

    override fun onPause() {
        super.onPause()
        save()
        JarvisLog.listener = null
    }

    private fun handleStartExtra(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_START, false) == true && !JarvisService.running && hasCorePermissions()) {
            JarvisService.start(this)
            btnStart.postDelayed({ refreshUi() }, 600)
        }
    }

    private fun save() {
        prefs.userName = etName.text.toString().trim().ifEmpty { "Abhishek" }
        prefs.geminiKey = etGemini.text.toString()
        prefs.picoKey = etPico.text.toString()
        prefs.model = etModel.text.toString()
        prefs.useSpeakerMic = swMic.isChecked
        prefs.autoStart = swAuto.isChecked
    }

    private fun toggleJarvis() {
        save()
        if (JarvisService.running) {
            JarvisService.stop(this)
        } else {
            when {
                prefs.geminiKey.isBlank() -> toast("Gemini API key taka")
                prefs.picoKey.isBlank() -> toast("Picovoice AccessKey taka")
                !hasCorePermissions() -> {
                    toast("Aadhi permission dya")
                    askPermissions()
                }
                else -> JarvisService.start(this)
            }
        }
        btnStart.postDelayed({ refreshUi() }, 600)
    }

    private fun refreshUi() {
        btnStart.text = if (JarvisService.running) "⏹  Jarvis band kara" else "▶  Jarvis suru kara"
        tvSpeaker.text = if (prefs.deviceAddress.isEmpty()) "Koni pan Bluetooth speaker"
        else "Speaker: ${prefs.deviceName}"

        val notifOk = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        val overlayOk = Settings.canDrawOverlays(this)
        val batteryOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        fun mark(ok: Boolean) = if (ok) "✅" else "❌"
        tvStatus.text = "${mark(hasCorePermissions())} Permissions   ${mark(notifOk)} Notification access\n" +
            "${mark(overlayOk)} Display over apps   ${mark(batteryOk)} Battery"
    }

    private fun corePermissions(): Array<String> {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) list += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        return list.toTypedArray()
    }

    private fun hasCorePermissions() = corePermissions().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun askPermissions() = requestPermissions(corePermissions(), 1)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshUi()
    }

    @SuppressLint("BatteryLife")
    private fun askBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    @SuppressLint("MissingPermission")
    private fun pickSpeaker() {
        if (!hasCorePermissions()) {
            askPermissions()
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            toast("Aadhi Bluetooth on kara")
            return
        }
        val devices = adapter.bondedDevices.toList()
        if (devices.isEmpty()) {
            toast("Aadhi speaker phone shi pair kara")
            return
        }
        val names = devices.map { it.name ?: it.address }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Tuza speaker nivad")
            .setItems(names) { _, i ->
                prefs.deviceAddress = devices[i].address
                prefs.deviceName = names[i]
                refreshUi()
                toast("Restart kara mhanje navin speaker lagu hoil")
            }
            .setNeutralButton("Koni pan speaker") { _, _ ->
                prefs.deviceAddress = ""
                prefs.deviceName = ""
                refreshUi()
            }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

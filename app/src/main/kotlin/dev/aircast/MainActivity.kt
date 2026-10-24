package dev.aircast

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.android.material.button.MaterialButton
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private data class Device(val name: String, val address: String, val identifier: String, val model: String) {
        override fun toString(): String =
            if (model.isNotBlank() && model != "Unknown") "$name  ($model)\n$address" else "$name\n$address"
    }

    private val devices = mutableListOf<Device>()
    private lateinit var adapter: ArrayAdapter<Device>

    private lateinit var etIp: EditText
    private lateinit var lvDevices: ListView
    private lateinit var tvFile: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnScan: MaterialButton
    private lateinit var btnPlay: MaterialButton
    private lateinit var btnStop: MaterialButton

    private var pickedUri: Uri? = null
    @Volatile private var pythonReady = false
    private var multicastLock: WifiManager.MulticastLock? = null

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pickedUri = uri
            tvFile.text = displayName(uri)
        }
    }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etIp = findViewById(R.id.etIp)
        lvDevices = findViewById(R.id.lvDevices)
        tvFile = findViewById(R.id.tvFile)
        tvStatus = findViewById(R.id.tvStatus)
        btnScan = findViewById(R.id.btnScan)
        btnPlay = findViewById(R.id.btnPlay)
        btnStop = findViewById(R.id.btnStop)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, devices)
        lvDevices.adapter = adapter

        btnScan.setOnClickListener { scan() }
        findViewById<MaterialButton>(R.id.btnPick).setOnClickListener { pickAudio.launch(arrayOf("audio/*")) }
        btnPlay.setOnClickListener { play() }
        btnStop.setOnClickListener { stop() }
        findViewById<MaterialButton>(R.id.btnLog).setOnClickListener { showLog() }

        setUiEnabled(false)
        tvStatus.text = "Starting Python engine…"

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Starting Python and importing pyatv takes a few seconds: do it off the UI thread.
        Thread {
            try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
                Python.getInstance().getModule("bridge")
                pythonReady = true
                runOnUiThread {
                    setUiEnabled(true)
                    tvStatus.text = if (PlaybackService.status.isNotEmpty()) PlaybackService.status else "Ready. Tap “Scan for speakers”."
                }
            } catch (e: Throwable) {
                runOnUiThread { tvStatus.text = "Python failed to start: ${e.message}" }
            }
        }.start()
    }

    override fun onStart() {
        super.onStart()
        PlaybackService.listener = { text, _ -> tvStatus.text = text }
        if (pythonReady && PlaybackService.status.isNotEmpty()) tvStatus.text = PlaybackService.status
    }

    override fun onStop() {
        PlaybackService.listener = null
        super.onStop()
    }

    private fun setUiEnabled(enabled: Boolean) {
        btnScan.isEnabled = enabled
        btnPlay.isEnabled = enabled
        btnStop.isEnabled = enabled
    }

    private fun scan() {
        val host = etIp.text.toString().trim()
        btnScan.isEnabled = false
        tvStatus.text = "Scanning…"

        Thread {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("aircast:scan").apply {
                setReferenceCounted(false)
                acquire()
            }
            try {
                NetUtil.bindToWifi(applicationContext)
                val bridge = Python.getInstance().getModule("bridge")
                val result = JSONObject(bridge.callAttr("scan", 5, host).toString())

                val found = mutableListOf<Device>()
                if (result.optBoolean("ok")) {
                    val arr = result.getJSONArray("devices")
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        found.add(Device(o.getString("name"), o.getString("address"), o.getString("identifier"), o.optString("model")))
                    }
                }
                runOnUiThread {
                    devices.clear()
                    devices.addAll(found)
                    adapter.notifyDataSetChanged()
                    if (found.isNotEmpty()) lvDevices.setItemChecked(0, true)
                    tvStatus.text = when {
                        !result.optBoolean("ok") -> "Scan failed: " + result.optString("error")
                        found.isEmpty() -> "No AirPlay speakers found. Make sure the phone is on the same Wi-Fi, or type the speaker's IP address above and scan again."
                        else -> "Found ${found.size} speaker(s)."
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread { tvStatus.text = "Scan failed: ${e.message}" }
            } finally {
                try { multicastLock?.release() } catch (_: Exception) {}
                runOnUiThread { btnScan.isEnabled = true }
            }
        }.start()
    }

    private fun play() {
        val pos = lvDevices.checkedItemPosition
        val uri = pickedUri
        if (pos < 0 || pos >= devices.size) {
            Toast.makeText(this, "Scan and select a speaker first", Toast.LENGTH_SHORT).show()
            return
        }
        if (uri == null) {
            Toast.makeText(this, "Choose an audio file first", Toast.LENGTH_SHORT).show()
            return
        }
        val d = devices[pos]
        val intent = Intent(this, PlaybackService::class.java)
            .setAction(PlaybackService.ACTION_PLAY)
            .putExtra(PlaybackService.EXTRA_URI, uri.toString())
            .putExtra(PlaybackService.EXTRA_IDENTIFIER, d.identifier)
            .putExtra(PlaybackService.EXTRA_ADDRESS, d.address)
            .putExtra(PlaybackService.EXTRA_NAME, d.name)
        ContextCompat.startForegroundService(this, intent)
        tvStatus.text = "Starting…"
    }

    private fun stop() {
        startService(Intent(this, PlaybackService::class.java).setAction(PlaybackService.ACTION_STOP))
    }

    private fun showLog() {
        val text = if (pythonReady) {
            try {
                Python.getInstance().getModule("bridge").callAttr("get_log").toString()
            } catch (e: Throwable) {
                "Could not read log: ${e.message}"
            }
        } else {
            "Python engine is not running yet."
        }
        val tv = TextView(this).apply {
            this.text = text.ifBlank { "(empty)" }
            setTextIsSelectable(true)
            textSize = 11f
            setPadding(32, 24, 32, 24)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle("Debug log")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0)
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: uri.toString()
    }
}

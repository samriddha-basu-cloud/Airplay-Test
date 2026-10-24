package dev.aircast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.chaquo.python.Python
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CancellationException

/**
 * Foreground service so playback keeps going when the screen is off or the app is in the
 * background: decodes the chosen file to WAV, then streams it to the speaker through pyatv.
 */
class PlaybackService : Service() {

    companion object {
        const val ACTION_PLAY = "dev.aircast.PLAY"
        const val ACTION_STOP = "dev.aircast.STOP"
        const val EXTRA_URI = "uri"
        const val EXTRA_IDENTIFIER = "identifier"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_NAME = "name"

        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1

        @Volatile var status: String = ""
        @Volatile var running: Boolean = false
        @Volatile var listener: ((String, Boolean) -> Unit)? = null

        private val main = Handler(Looper.getMainLooper())

        fun publish(text: String, isRunning: Boolean) {
            status = text
            running = isRunning
            main.post { listener?.invoke(text, isRunning) }
        }
    }

    @Volatile private var cancelled = false
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> startPlayback(intent)
            ACTION_STOP -> stopPlayback()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startPlayback(intent: Intent) {
        if (worker?.isAlive == true) {
            publish("Already playing – press Stop first", true)
            return
        }

        val uri = Uri.parse(intent.getStringExtra(EXTRA_URI))
        val identifier = intent.getStringExtra(EXTRA_IDENTIFIER) ?: ""
        val address = intent.getStringExtra(EXTRA_ADDRESS) ?: ""
        val name = intent.getStringExtra(EXTRA_NAME) ?: "speaker"

        goForeground("Preparing audio for $name")
        acquireLocks()
        cancelled = false

        worker = Thread({
            val wav = File(cacheDir, "aircast_current.wav")
            try {
                NetUtil.bindToWifi(applicationContext)

                publish("Decoding audio…", true)
                AudioDecoder.decodeToWav(
                    applicationContext, uri, wav,
                    isCancelled = { cancelled },
                    onProgress = { pct -> publish("Decoding audio… $pct%", true) },
                )
                if (cancelled) throw CancellationException("cancelled")

                updateNotification("Playing on $name")
                publish("Connecting to $name…", true)

                val bridge = Python.getInstance().getModule("bridge")
                val result = JSONObject(bridge.callAttr("play", identifier, address, wav.absolutePath).toString())

                when {
                    result.optBoolean("stopped") -> publish("Stopped", false)
                    result.optBoolean("ok") -> publish("Finished", false)
                    else -> publish("Error: " + result.optString("error"), false)
                }
            } catch (_: CancellationException) {
                publish("Stopped", false)
            } catch (e: Throwable) {
                publish("Error: ${e.javaClass.simpleName}: ${e.message}", false)
            } finally {
                wav.delete()
                releaseLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, "aircast-playback").also { it.start() }
    }

    private fun stopPlayback() {
        cancelled = true
        publish("Stopping…", true)
        Thread {
            try {
                Python.getInstance().getModule("bridge").callAttr("stop")
            } catch (_: Throwable) {
            }
        }.start()
        if (worker?.isAlive != true) {
            publish("Stopped", false)
            stopSelf()
        }
    }

    private fun goForeground(text: String) {
        createChannel()
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("AirCast")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aircast:playback").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wm.createWifiLock(mode, "aircast:wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }
}

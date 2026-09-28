package es.iesvirgendelacaridad.etcp.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import es.iesvirgendelacaridad.etcp.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MeetingRecordingService : Service() {

    companion object {
        const val ACTION_START = "es.iesvirgendelacaridad.etcp.action.START_RECORDING"
        const val ACTION_STOP = "es.iesvirgendelacaridad.etcp.action.STOP_RECORDING"
        const val ACTION_RECORDING_SAVED = "es.iesvirgendelacaridad.etcp.action.RECORDING_SAVED"
        const val EXTRA_URI = "recording_uri"
        const val EXTRA_ERROR = "recording_error"
        private const val CHANNEL_ID = "meeting_recording"
        private const val NOTIFICATION_ID = 1201

        @Volatile
        var isRecording: Boolean = false
            private set
    }

    private var recorder: MediaRecorder? = null
    private var outputUri: Uri? = null
    private var outputPfd: android.os.ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!isRecording) startRecording()
            ACTION_STOP -> stopRecordingAndSelf()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        try {
            val uri = createOutputUri()
            val pfd = requireNotNull(contentResolver.openFileDescriptor(uri, "rw")) {
                "No se pudo abrir el archivo de salida"
            }

            val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(44_100)
                setAudioEncodingBitRate(96_000)
                setOutputFile(pfd.fileDescriptor)
                prepare()
                start()
            }

            recorder = mediaRecorder
            outputUri = uri
            outputPfd = pfd
            isRecording = true

            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (t: Throwable) {
            cleanupFailedRecording()
            broadcastResult(null, t.message ?: "No se pudo iniciar la grabación")
            stopSelf()
        }
    }

    private fun stopRecordingAndSelf() {
        if (!isRecording) {
            stopSelf()
            return
        }

        var error: String? = null
        try {
            recorder?.stop()
        } catch (t: Throwable) {
            error = t.message ?: "La grabación no pudo cerrarse correctamente"
        } finally {
            runCatching { recorder?.reset() }
            runCatching { recorder?.release() }
            recorder = null
            runCatching { outputPfd?.close() }
            outputPfd = null
            isRecording = false
        }

        val uri = outputUri
        if (uri != null && error == null) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }
            contentResolver.update(uri, values, null, null)
            broadcastResult(uri, null)
        } else {
            uri?.let { runCatching { contentResolver.delete(it, null, null) } }
            broadcastResult(null, error ?: "No se pudo guardar la grabación")
        }

        outputUri = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanupFailedRecording() {
        runCatching { recorder?.release() }
        recorder = null
        runCatching { outputPfd?.close() }
        outputPfd = null
        outputUri?.let { runCatching { contentResolver.delete(it, null, null) } }
        outputUri = null
        isRecording = false
    }

    private fun createOutputUri(): Uri {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "Reunion_$stamp.m4a")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/TanscriptorRVA")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        return requireNotNull(
            contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        ) { "No se pudo crear el archivo de grabación" }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Grabación de reuniones",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Mantiene activa la grabación de una reunión"
                }
            )
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = Intent(this, MeetingRecordingService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("TanscriptorRVA")
            .setContentText("Grabando reunión…")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(pendingOpen)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_pause,
                    "Detener",
                    pendingStop
                ).build()
            )
            .build()
    }

    private fun broadcastResult(uri: Uri?, error: String?) {
        sendBroadcast(
            Intent(ACTION_RECORDING_SAVED).apply {
                setPackage(packageName)
                uri?.let { putExtra(EXTRA_URI, it.toString()) }
                error?.let { putExtra(EXTRA_ERROR, it) }
            }
        )
    }

    override fun onDestroy() {
        if (isRecording) {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            runCatching { outputPfd?.close() }
            outputUri?.let { uri ->
                runCatching {
                    val values = ContentValues().apply {
                        put(MediaStore.Audio.Media.IS_PENDING, 0)
                    }
                    contentResolver.update(uri, values, null, null)
                }
            }
            isRecording = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

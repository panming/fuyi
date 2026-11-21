package com.fanyi.overlay.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fanyi.overlay.FanyiApp
import com.fanyi.overlay.MainActivity
import com.fanyi.overlay.R
import com.fanyi.overlay.audio.PlaybackCapturer
import com.fanyi.overlay.data.SessionBus
import com.fanyi.overlay.model.RunPhase
import com.fanyi.overlay.model.recognitionFingerprint
import com.fanyi.overlay.overlay.OverlayController
import com.fanyi.overlay.pipeline.CaptionPipeline
import com.fanyi.overlay.translate.friendlyFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val alive = AtomicBoolean(false)
    private val tearingDown = AtomicBoolean(false)
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var surface: Surface? = null
    private var surfaceTexture: android.graphics.SurfaceTexture? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var capturer: PlaybackCapturer? = null
    private var pipeline: CaptionPipeline? = null
    private var pump: Job? = null
    private var audioChannel = Channel<ByteArray>(8, BufferOverflow.DROP_OLDEST)
    private var userStop = false
    private var paused = false
    private var swapping = false
    private lateinit var overlay: OverlayController

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (SessionBus.phaseValue != RunPhase.ERROR) {
                SessionBus.detailValue = "系统结束了本次捕获"
            }
            requestStop()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        overlay = OverlayController(this)
        val app = application as FanyiApp
        serviceScope.launch {
            var lastKey = recognitionFingerprint(app.settings.settings.value)
            app.settings.settings.collect { settings ->
                val key = recognitionFingerprint(settings)
                if (key == lastKey) return@collect
                lastKey = key
                if (swapping || paused || !alive.get() || projection == null) return@collect
                restartRecognition()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> requestStop()
            ACTION_START -> begin(intent)
            ACTION_TOGGLE -> toggleFromShade()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun begin(intent: Intent) {
        if (alive.get() || tearingDown.get()) return
        userStop = false
        tearingDown.set(false)
        val code = pendingCode
        val data = pendingData
        pendingData = null
        if (data == null || code != Activity.RESULT_OK) {
            stopSelf()
            SessionBus.phaseValue = RunPhase.ERROR
            SessionBus.detailValue = "没有拿到屏幕捕获授权。"
            return
        }
        SessionBus.resetForStart()
        try {
            startForegroundNow()
        } catch (error: Exception) {
            failStart(error)
            return
        }
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val created = manager.getMediaProjection(code, data)
                ?: error("系统没有返回内录授权。")
            projection = created
            created.registerCallback(projectionCallback, mainHandler)
            virtualDisplay = createKeepalive(created)
            overlay.show()
            alive.set(true)
            paused = false
            acquireWakeLock()
            startRecognition()
            refreshChrome()
        } catch (error: Throwable) {
            failStart(error)
        }
    }

    private fun startForegroundNow() {
        val channelId = "capture"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channelId, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(channelId),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
    }

    private fun buildNotification(channelId: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val toggle = PendingIntent.getService(
            this,
            1,
            Intent(this, CaptureService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val listening = alive.get() && !paused
        val title = if (listening) getString(R.string.notification_title) else getString(R.string.notification_paused_title)
        val text = if (listening) getString(R.string.notification_text) else getString(R.string.notification_paused_text)
        val action = if (listening) getString(R.string.notification_pause) else getString(R.string.notification_resume)
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, action, toggle)
            .build()
    }

    private fun refreshChrome() {
        val manager = getSystemService(NotificationManager::class.java)
        if (alive.get()) {
            manager.notify(NOTIFICATION_ID, buildNotification("capture"))
        }
        FanyiTileService.poke(this)
    }

    private fun toggleFromShade() {
        when {
            projection != null && alive.get() && paused -> resumeFromShade()
            projection != null && alive.get() -> pauseFromShade()
            else -> openConsent()
        }
    }

    fun pauseFromShade() {
        if (!alive.get() || paused || tearingDown.get()) return
        serviceScope.launch {
            haltRecognition()
            if (!alive.get()) return@launch
            paused = true
            SessionBus.phaseValue = RunPhase.PAUSED
            SessionBus.detailValue = "已暂停。内录授权还在，可以马上继续。"
            SessionBus.levelValue = 0f
            refreshChrome()
        }
    }

    fun resumeFromShade() {
        if (projection == null || !alive.get()) {
            openConsent()
            return
        }
        serviceScope.launch {
            paused = false
            SessionBus.phaseValue = RunPhase.STARTING
            SessionBus.detailValue = "正在继续"
            startRecognition()
            refreshChrome()
        }
    }

    private fun openConsent() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_AUTO_START, true)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            SessionBus.detailValue = "请点通知回到浮译，允许屏幕捕获后再开始。"
        }
    }

    private fun restartRecognition() {
        serviceScope.launch {
            if (swapping || projection == null || !alive.get() || paused) return@launch
            haltRecognition()
            if (!alive.get() || projection == null) return@launch
            SessionBus.detailValue = "正在按新的来源语言重新连接"
            startRecognition()
            refreshChrome()
        }
    }

    private suspend fun haltRecognition() {
        swapping = true
        capturer?.stop()
        capturer = null
        audioChannel.close()
        val job = pump
        val asr = pipeline?.asr
        val caption = pipeline
        pump = null
        pipeline = null
        withTimeoutOrNull(1_500) { job?.join() }
        job?.cancel()
        withTimeoutOrNull(1_500) {
            try {
                asr?.finish()
            } catch (_: Throwable) {
            }
        }
        caption?.close()
        swapping = false
    }

    private fun startRecognition() {
        val created = projection ?: return
        val app = application as FanyiApp
        val settings = app.settings.settings.value
        audioChannel = Channel(8, BufferOverflow.DROP_OLDEST)
        val caption = CaptionPipeline(settings, serviceScope)
        pipeline = caption
        capturer = PlaybackCapturer(
            projection = created,
            onPcm = { frame -> audioChannel.trySend(frame) },
            onLevel = { level -> SessionBus.levelValue = level },
            onError = { message ->
                SessionBus.errorValue = message
                SessionBus.phaseValue = RunPhase.ERROR
                SessionBus.detailValue = message
                requestStop()
            },
        ).also { it.start() }
        pump = serviceScope.launch(Dispatchers.IO) {
            try {
                caption.asr.start()
                withContext(Dispatchers.Main) {
                    if (SessionBus.phaseValue != RunPhase.ERROR && alive.get()) {
                        paused = false
                        SessionBus.phaseValue = RunPhase.LISTENING
                        refreshChrome()
                    }
                }
                for (frame in audioChannel) {
                    caption.asr.sendPcm(frame)
                }
            } catch (error: Throwable) {
                if (!userStop && !swapping && error !is kotlinx.coroutines.CancellationException) {
                    SessionBus.errorValue = friendlyFailure(error)
                    SessionBus.phaseValue = RunPhase.ERROR
                    SessionBus.detailValue = friendlyFailure(error)
                    requestStop()
                }
            }
        }
    }

    private fun createKeepalive(created: MediaProjection): VirtualDisplay? {
        return try {
            val texture = android.graphics.SurfaceTexture(false)
            texture.setDefaultBufferSize(64, 64)
            surfaceTexture = texture
            val createdSurface = Surface(texture)
            surface = createdSurface
            created.createVirtualDisplay(
                "fuyi-keepalive",
                64,
                64,
                160,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                createdSurface,
                null,
                mainHandler,
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun acquireWakeLock() {
        val lock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fanyi:capture")
        lock.setReferenceCounted(false)
        lock.acquire(4 * 60 * 60 * 1000L)
        wakeLock = lock
    }

    private fun failStart(error: Throwable) {
        SessionBus.errorValue = friendlyFailure(error)
        SessionBus.phaseValue = RunPhase.ERROR
        SessionBus.detailValue = friendlyFailure(error)
        requestStop()
    }

    fun requestStop() {
        mainHandler.post { teardown() }
    }

    private fun teardown() {
        if (!tearingDown.compareAndSet(false, true)) return
        alive.set(false)
        userStop = true
        capturer?.stop()
        capturer = null
        audioChannel.close()
        val job = pump
        val asr = pipeline?.asr
        pump = null
        serviceScope.launch {
            withTimeoutOrNull(2_000) { job?.join() }
            job?.cancel()
            withTimeoutOrNull(2_000) {
                try {
                    asr?.finish()
                } catch (_: Throwable) {
                }
            }
            withContext(Dispatchers.Main) { releaseQuietly() }
        }
    }

    private fun releaseQuietly() {
        overlay.hide()
        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {
        }
        virtualDisplay = null
        try {
            surface?.release()
        } catch (_: Throwable) {
        }
        surface = null
        try {
            surfaceTexture?.release()
        } catch (_: Throwable) {
        }
        surfaceTexture = null
        try {
            projection?.unregisterCallback(projectionCallback)
        } catch (_: Throwable) {
        }
        try {
            projection?.stop()
        } catch (_: Throwable) {
        }
        projection = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        if (SessionBus.phaseValue != RunPhase.ERROR) {
            SessionBus.phaseValue = RunPhase.IDLE
            SessionBus.detailValue = "已停止"
        }
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
        tearingDown.set(false)
        paused = false
        FanyiTileService.poke(this)
        stopSelf()
    }

    companion object {
        const val ACTION_START = "com.fanyi.overlay.START"
        const val ACTION_STOP = "com.fanyi.overlay.STOP"
        const val ACTION_TOGGLE = "com.fanyi.overlay.TOGGLE"
        const val EXTRA_AUTO_START = "auto_start"

        fun hasLiveSession(): Boolean = instance?.projection != null && instance?.alive?.get() == true

        fun isPausedNow(): Boolean = instance?.paused == true

        fun instanceAction(block: (CaptureService) -> Unit) {
            instance?.let(block)
        }
        private const val NOTIFICATION_ID = 42

        @Volatile private var instance: CaptureService? = null
        @Volatile private var pendingCode: Int = Activity.RESULT_CANCELED
        @Volatile private var pendingData: Intent? = null

        fun start(context: Context, resultCode: Int, data: Intent) {
            pendingCode = resultCode
            pendingData = data
            val intent = Intent(context, CaptureService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun requestStop() {
            val running = instance
            if (running != null) {
                running.requestStop()
                return
            }
        }

        fun stop(context: Context) {
            requestStop()
            try {
                context.startService(Intent(context, CaptureService::class.java).setAction(ACTION_STOP))
            } catch (_: Exception) {
            }
        }
    }
}

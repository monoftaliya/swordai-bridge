package com.swordai.bridge.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.swordai.bridge.AppGraph
import com.swordai.bridge.accessibility.AgentAccessibilityService
import com.swordai.bridge.accessibility.ScreenMetrics
import com.swordai.bridge.agent.StatusReporter
import com.swordai.bridge.agent.ToolOutcome
import com.swordai.bridge.core.commands.CaptureArgs
import com.swordai.bridge.core.jsonOf
import com.swordai.bridge.core.log.LogEntry
import com.swordai.bridge.service.BridgeService
import com.swordai.bridge.service.Notifications
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Захват экрана через MediaProjection — только с согласия пользователя в системном диалоге.
 * Сессия держится, пока пользователь её не остановит, телефон не заблокируется (Android 15+)
 * или не сработает аварийная остановка. Окна с FLAG_SECURE система отдаёт чёрными — это не обходится.
 */
object ScreenCapture {
    private const val TAG = "ScreenCapture"
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val lock = Any()
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var latest: Image? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var consent: CompletableDeferred<Boolean>? = null

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped")
            cleanup("Захват экрана остановлен системой или пользователем")
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            resize(width, height)
        }
    }

    suspend fun capture(context: Context, args: CaptureArgs): ToolOutcome {
        if (!_active.value) {
            if (BridgeService.instance == null) {
                return ToolOutcome.Failure("service_not_running", "Сервис SwordAI Bridge не запущен")
            }
            val granted = requestConsent(context)
            if (!granted) {
                return ToolOutcome.Failure(
                    "capture_not_permitted",
                    "Пользователь не разрешил захват экрана (или не ответил за 60 с). Разрешение запрашивается на телефоне.",
                )
            }
        }
        val bitmap = withContext(Dispatchers.Default) { waitForFrame() }
            ?: return ToolOutcome.Failure("capture_failed", "Не удалось получить кадр экрана")
        return withContext(Dispatchers.Default) { encode(bitmap, args) }
    }

    /** Ручной запуск сессии из приложения (кнопка на экране «Разрешения»). */
    suspend fun startManually(context: Context): Boolean = _active.value || requestConsent(context)

    private suspend fun requestConsent(context: Context): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        consent = deferred
        val intent = Intent(context, CaptureConsentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        // Служба специальных возможностей может открывать окна из фона; иначе — уведомление.
        val launcher: Context = AgentAccessibilityService.instance ?: context
        try {
            launcher.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot start consent activity directly: $e")
            Notifications.showCaptureRequest(context)
        }
        val result = withTimeoutOrNull(60_000) { deferred.await() } ?: false
        Notifications.cancelCaptureRequest(context)
        return result
    }

    /** Вызывается из CaptureConsentActivity, пока она на экране. */
    fun onConsentResult(context: Context, resultCode: Int, data: Intent?) {
        val waiter = consent
        consent = null
        if (resultCode != Activity.RESULT_OK || data == null) {
            AppGraph.log.add(LogEntry.CAT_SECURITY, "Захват экрана не разрешён", null, LogEntry.ST_WARN)
            waiter?.complete(false)
            return
        }
        try {
            start(context.applicationContext, resultCode, data)
            waiter?.complete(true)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot start projection", e)
            AppGraph.log.add(LogEntry.CAT_SYSTEM, "Ошибка запуска захвата экрана", e.message, LogEntry.ST_ERROR)
            cleanup(null)
            waiter?.complete(false)
        }
    }

    private fun start(context: Context, resultCode: Int, data: Intent) {
        val service = BridgeService.instance ?: throw IllegalStateException("Сервис не запущен")
        // До getMediaProjection сервис должен работать с типом mediaProjection.
        service.setProjectionForeground(true)
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        val mp = manager.getMediaProjection(resultCode, data) ?: throw IllegalStateException("MediaProjection недоступен")
        val captureThread = HandlerThread("swordai-capture").apply { start() }
        val captureHandler = Handler(captureThread.looper)
        mp.registerCallback(callback, captureHandler) // обязательно до createVirtualDisplay (Android 14+)
        val metrics = ScreenMetrics.get(context)
        val imageReader = ImageReader.newInstance(metrics.width, metrics.height, PixelFormat.RGBA_8888, 3)
        imageReader.setOnImageAvailableListener({ onImage(it) }, captureHandler)
        val display = mp.createVirtualDisplay(
            "SwordAI-capture",
            metrics.width,
            metrics.height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            captureHandler,
        )
        synchronized(lock) {
            projection = mp
            reader = imageReader
            virtualDisplay = display
            thread = captureThread
            handler = captureHandler
        }
        _active.value = true
        AppGraph.log.add(LogEntry.CAT_SECURITY, "Захват экрана разрешён пользователем", null, LogEntry.ST_OK)
        StatusReporter.event("projection_started")
    }

    private fun onImage(imageReader: ImageReader) {
        val image = try {
            imageReader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return
        synchronized(lock) {
            latest?.close()
            latest = image
        }
    }

    private fun resize(width: Int, height: Int) {
        synchronized(lock) {
            val display = virtualDisplay ?: return
            val oldReader = reader ?: return
            val h = handler ?: return
            if (width <= 0 || height <= 0 || (oldReader.width == width && oldReader.height == height)) return
            val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            newReader.setOnImageAvailableListener({ onImage(it) }, h)
            display.resize(width, height, ScreenMetrics.get(AppGraph.context).densityDpi)
            display.surface = newReader.surface
            latest?.close()
            latest = null
            oldReader.close()
            reader = newReader
        }
    }

    private suspend fun waitForFrame(): Bitmap? {
        repeat(40) {
            synchronized(lock) {
                val image = latest
                if (image != null) {
                    return try {
                        toBitmap(image)
                    } catch (e: IllegalStateException) {
                        latest = null
                        null
                    }
                }
            }
            delay(50)
        }
        return null
    }

    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        buffer.rewind()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val padded = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        return if (rowPadding == 0) padded else Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
    }

    private fun encode(source: Bitmap, args: CaptureArgs): ToolOutcome {
        val longest = max(source.width, source.height)
        val scaled = if (longest > args.maxDimension) {
            val scale = args.maxDimension.toFloat() / longest
            Bitmap.createScaledBitmap(source, (source.width * scale).roundToInt(), (source.height * scale).roundToInt(), true)
        } else {
            source
        }
        val png = args.format == "png"
        val output = ByteArrayOutputStream()
        scaled.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, args.quality, output)
        val data = Base64.getEncoder().encodeToString(output.toByteArray())
        val dark = isMostlyBlack(scaled)
        val foreground = AgentAccessibilityService.instance?.foregroundPackage()
        return ToolOutcome.Success(
            data = jsonOf(
                "width" to scaled.width,
                "height" to scaled.height,
                "format" to args.format,
                "package" to foreground,
                "app_label" to foreground?.let { AppGraph.apps.label(it) },
                "note" to if (dark) "Изображение почти чёрное: окно защищено от записи (FLAG_SECURE) или экран выключен." else null,
            ),
            image = jsonOf("mime" to if (png) "image/png" else "image/jpeg", "data" to data),
        )
    }

    private fun isMostlyBlack(bitmap: Bitmap): Boolean {
        var bright = 0
        val steps = 16
        for (i in 0 until steps) {
            for (j in 0 until steps) {
                val x = (bitmap.width - 1) * i / (steps - 1)
                val y = (bitmap.height - 1) * j / (steps - 1)
                val c = bitmap.getPixel(x, y)
                if (Color.red(c) + Color.green(c) + Color.blue(c) > 30) bright++
            }
        }
        return bright < steps * steps / 50
    }

    /** Остановить сессию (аварийная остановка или кнопка в приложении). */
    fun stop() {
        val mp = synchronized(lock) { projection }
        try {
            mp?.stop()
        } catch (e: Exception) {
            // уже остановлен
        }
        cleanup("Захват экрана остановлен")
    }

    private fun cleanup(message: String?) {
        val wasActive: Boolean
        synchronized(lock) {
            wasActive = projection != null
            latest?.close()
            latest = null
            try {
                virtualDisplay?.release()
            } catch (e: Exception) {
                // игнорируем
            }
            virtualDisplay = null
            reader?.close()
            reader = null
            projection?.unregisterCallback(callback)
            projection = null
            thread?.quitSafely()
            thread = null
            handler = null
        }
        _active.value = false
        BridgeService.instance?.setProjectionForeground(false)
        if (wasActive) {
            if (message != null) AppGraph.log.add(LogEntry.CAT_SYSTEM, message, null, LogEntry.ST_INFO)
            StatusReporter.event("projection_stopped")
        }
    }
}

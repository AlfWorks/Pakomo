package com.pakomo.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pakomo.MainActivity
import com.pakomo.R
import com.pakomo.core.model.AppLanguage
import com.pakomo.core.model.EngineStage
import com.pakomo.core.model.defaultRules
import com.pakomo.data.PakomoPreferences
import com.pakomo.vpn.VpnServiceController
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class QuickControlService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private lateinit var preferences: PakomoPreferences
    private val appLanguage: AppLanguage
        get() = AppLanguage.fromName(preferences.readLanguage())
    private var controlView: QuickControlView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var lastToggleAtMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        preferences = PakomoPreferences(this)
        windowManager = getSystemService(WindowManager::class.java)
        createNotificationChannel()
        serviceScope.launch {
            VpnServiceController.runtime.collectLatest { runtime ->
                controlView?.stage = runtime.stage
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISABLE) {
            preferences.writeQuickControlEnabled(false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!preferences.readQuickControlEnabled() || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        updateVisibility()
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controlView?.post { snapToNearestEdge(save = false) }
    }

    override fun onDestroy() {
        removeControl()
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateVisibility() {
        if (hostVisible) {
            removeControl()
        } else {
            showControl()
        }
    }

    private fun showControl() {
        if (controlView != null || !Settings.canDrawOverlays(this)) return
        val view = QuickControlView(this).apply {
            stage = VpnServiceController.runtime.value.stage
            setOnClickListener {
                val wasActive = VpnServiceController.runtime.value.stage.isActive
                if (toggleVpn()) {
                    stage = if (wasActive) EngineStage.STOPPED else EngineStage.STARTING
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
            }
        }
        val width = dp(48)
        val height = dp(68)
        val screen = screenBounds()
        val storedY = overlayState.getInt(KEY_Y, screen.height() / 3)
        val onRight = overlayState.getBoolean(KEY_RIGHT, true)
        view.attachedRight = onRight
        val params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (onRight) screen.width() - width else 0
            y = storedY.coerceIn(0, (screen.height() - height).coerceAtLeast(0))
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        installDragHandling(view, params)
        runCatching { windowManager.addView(view, params) }
            .onSuccess {
                controlView = view
                layoutParams = params
            }
            .onFailure { Log.e(TAG, "Unable to show quick control", it) }
    }

    private fun installDragHandling(
        view: QuickControlView,
        params: WindowManager.LayoutParams,
    ) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    target.isPressed = true
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        target.isPressed = false
                    }
                    if (dragging) {
                        val screen = screenBounds()
                        params.x = (startX + dx.toInt())
                            .coerceIn(0, (screen.width() - params.width).coerceAtLeast(0))
                        params.y = (startY + dy.toInt())
                            .coerceIn(0, (screen.height() - params.height).coerceAtLeast(0))
                        windowManager.updateViewLayout(target, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    target.isPressed = false
                    if (dragging) {
                        snapToNearestEdge(save = true)
                    } else {
                        target.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    target.isPressed = false
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToNearestEdge(save: Boolean) {
        val view = controlView ?: return
        val params = layoutParams ?: return
        val screen = screenBounds()
        val maxX = (screen.width() - params.width).coerceAtLeast(0)
        params.x = if (params.x + params.width / 2 >= screen.width() / 2) maxX else 0
        view.attachedRight = params.x == maxX
        params.y = params.y.coerceIn(0, (screen.height() - params.height).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(view, params) }
        if (save) {
            overlayState.edit()
                .putBoolean(KEY_RIGHT, params.x == maxX)
                .putInt(KEY_Y, params.y)
                .apply()
        }
    }

    private fun removeControl() {
        val view = controlView ?: return
        runCatching { windowManager.removeView(view) }
        controlView = null
        layoutParams = null
    }

    private fun toggleVpn(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastToggleAtMs < TOGGLE_DEBOUNCE_MS) return false
        lastToggleAtMs = now
        if (VpnServiceController.runtime.value.stage.isActive) {
            VpnServiceController.stop(this)
            return true
        }
        if (VpnService.prepare(this) != null) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_START_FROM_QUICK_CONTROL, true),
            )
            return true
        }
        val selectedPackages = preferences.readSelectedPackages()
        val domainsByPackage = preferences.readDomainsByPackage()
            .filterKeys(selectedPackages::contains)
        val rules = preferences.readRules()
        val activeRule = rules.firstOrNull { it.id == preferences.readActiveRuleId() }
            ?: rules.firstOrNull()
            ?: defaultRules[1]
        VpnServiceController.start(
            context = this,
            scope = preferences.readScope(),
            selectedPackages = selectedPackages.toList(),
            targetDomains = preferences.readAddressDomains(),
            domainsByPackage = domainsByPackage,
            rule = activeRule,
        )
        return true
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disableIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, QuickControlService::class.java).setAction(ACTION_DISABLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(appLanguage.tr("Pakomo 快捷控制", "Pakomo quick control"))
            .setContentText(appLanguage.tr("贴边快捷键已开启", "Edge shortcut is on"))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, appLanguage.tr("关闭", "Turn off"), disableIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            appLanguage.tr("快捷悬浮控制", "Floating quick control"),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = appLanguage.tr("保持贴边快捷键可用", "Keeps the edge shortcut available")
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private val overlayState
        get() = getSharedPreferences(OVERLAY_STATE_FILE, Context.MODE_PRIVATE)

    private fun screenBounds(): android.graphics.Rect =
        if (Build.VERSION.SDK_INT >= 30) {
            windowManager.currentWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            android.util.DisplayMetrics().also(windowManager.defaultDisplay::getRealMetrics)
                .let { android.graphics.Rect(0, 0, it.widthPixels, it.heightPixels) }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private class QuickControlView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val backgroundPath = Path()
        var attachedRight: Boolean = true
            set(value) {
                field = value
                invalidate()
            }
        var stage: EngineStage = EngineStage.STOPPED
            set(value) {
                field = value
                val language = AppLanguage.fromName(PakomoPreferences(context).readLanguage())
                contentDescription = when (value) {
                    EngineStage.STARTING -> language.tr("Pakomo 正在启动", "Pakomo starting")
                    EngineStage.FORWARDING -> language.tr("停止 Pakomo", "Stop Pakomo")
                    EngineStage.ERROR, EngineStage.STOPPED -> language.tr("启动 Pakomo", "Start Pakomo")
                }
                invalidate()
            }

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            isClickable = true
            isFocusable = true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val density = resources.displayMetrics.density
            // Room on the inward side / top / bottom for the soft shadow and active glow; the docked
            // edge stays flush to the screen.
            val pad = 7f * density
            val press = if (isPressed) 1f * density else 0f
            val left: Float
            val right: Float
            if (attachedRight) {
                left = pad + press
                right = width.toFloat() // flush to the right screen edge
            } else {
                left = 0f // flush to the left screen edge
                right = width - pad - press
            }
            val bounds = RectF(left, pad + press, right, height - pad - press)
            val innerRadius = 18f * density
            val edgeRadius = 5f * density
            val radii = if (attachedRight) {
                floatArrayOf(
                    innerRadius, innerRadius, edgeRadius, edgeRadius,
                    edgeRadius, edgeRadius, innerRadius, innerRadius,
                )
            } else {
                floatArrayOf(
                    edgeRadius, edgeRadius, innerRadius, innerRadius,
                    innerRadius, innerRadius, edgeRadius, edgeRadius,
                )
            }
            backgroundPath.reset()
            backgroundPath.addRoundRect(bounds, radii, Path.Direction.CW)

            val active = stage == EngineStage.FORWARDING
            val (topColor, bottomColor) = fillColors()

            // Soft drop shadow behind the tab; when active it becomes a slowly breathing brand glow
            // so "running" is unmistakable at a glance (the only state that pulses).
            paint.style = Paint.Style.FILL
            paint.shader = null
            paint.alpha = 255
            if (active) {
                val t = (android.os.SystemClock.uptimeMillis() % 2600L) / 2600f
                val breathe = 0.5f + 0.5f * kotlin.math.sin(t * 2f * Math.PI).toFloat()
                val glowAlpha = (0x3A + 0x50 * breathe).toInt() and 0xFF
                paint.color = 0x2A3B6FE0
                paint.setShadowLayer(
                    (7f + 5f * breathe) * density, 0f, 1.5f * density,
                    (glowAlpha shl 24) or 0x3B6FE0,
                )
                canvas.drawPath(backgroundPath, paint)
                paint.clearShadowLayer()
                postInvalidateOnAnimation()
            } else {
                paint.color = 0x33000000
                paint.setShadowLayer(6f * density, 0f, 1.5f * density, 0x38000000)
                canvas.drawPath(backgroundPath, paint)
                paint.clearShadowLayer()
            }

            // Glass fill: a gentle top-to-bottom sheen.
            paint.shader = LinearGradient(
                0f, bounds.top, 0f, bounds.bottom, topColor, bottomColor, Shader.TileMode.CLAMP,
            )
            paint.alpha = if (isPressed) 235 else 255
            canvas.drawPath(backgroundPath, paint)
            paint.shader = null
            paint.alpha = 255

            // Hairline body edge + a brighter top highlight rim for a lit-from-above glass feel.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f * density
            paint.color = 0x1FFFFFFF
            canvas.drawPath(backgroundPath, paint)
            paint.strokeWidth = 1.4f * density
            paint.shader = LinearGradient(
                0f, bounds.top, 0f, bounds.top + bounds.height() * 0.55f,
                0x73FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP,
            )
            canvas.drawPath(backgroundPath, paint)
            paint.shader = null

            drawGlyph(canvas, bounds.centerX(), bounds.centerY(), density)
        }

        private fun fillColors(): Pair<Int, Int> = when (stage) {
            // bright blue → accent, reads solid and "on"
            EngineStage.FORWARDING -> 0xFF5A86EE.toInt() to 0xFF3B6FE0.toInt()
            // frosted dark glass (the sweeping arc carries the "starting" meaning)
            EngineStage.STARTING -> 0xF23C434D.toInt() to 0xF6272C33.toInt()
            EngineStage.ERROR -> 0xFFCE5A50.toInt() to 0xFFB8463C.toInt()
            // translucent slate — unobtrusive when idle, works over light or dark apps
            EngineStage.STOPPED -> 0xE63C434D.toInt() to 0xF0272C33.toInt()
        }

        /**
         * Each state gets a distinct silhouette (not just a background colour), so it stays legible
         * over any app and regardless of colour perception:
         *  - stopped  → play triangle  (tap to start)
         *  - starting → sweeping arc   (spinner)
         *  - running  → stop square    (active · tap to stop) + the breathing glow
         *  - error    → exclamation
         */
        private fun drawGlyph(canvas: Canvas, cx: Float, cy: Float, density: Float) {
            when (stage) {
                EngineStage.STARTING -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeCap = Paint.Cap.ROUND
                    paint.color = Color.WHITE
                    paint.strokeWidth = 2.6f * density
                    val half = 8f * density
                    val angle = (android.os.SystemClock.uptimeMillis() / 4L % 360L).toFloat()
                    canvas.drawArc(
                        RectF(cx - half, cy - half, cx + half, cy + half),
                        angle, 250f, false, paint,
                    )
                    postInvalidateOnAnimation()
                }

                EngineStage.FORWARDING -> {
                    paint.style = Paint.Style.FILL
                    paint.color = Color.WHITE
                    val h = 6.5f * density
                    canvas.drawRoundRect(
                        RectF(cx - h, cy - h, cx + h, cy + h),
                        2.6f * density, 2.6f * density, paint,
                    )
                }

                EngineStage.STOPPED -> {
                    paint.style = Paint.Style.FILL
                    paint.color = 0xF2FFFFFF.toInt()
                    paint.strokeJoin = Paint.Join.ROUND
                    paint.strokeCap = Paint.Cap.ROUND
                    paint.strokeWidth = 3f * density
                    // play triangle, nudged right so it reads optically centred
                    val hw = 6f * density
                    val hh = 7.5f * density
                    backgroundPath.reset()
                    backgroundPath.moveTo(cx - hw + 1f * density, cy - hh)
                    backgroundPath.lineTo(cx + hw + 2.5f * density, cy)
                    backgroundPath.lineTo(cx - hw + 1f * density, cy + hh)
                    backgroundPath.close()
                    paint.style = Paint.Style.FILL_AND_STROKE // stroke rounds the corners
                    canvas.drawPath(backgroundPath, paint)
                    paint.style = Paint.Style.FILL
                }

                EngineStage.ERROR -> {
                    paint.style = Paint.Style.FILL
                    paint.color = Color.WHITE
                    val barW = 3f * density
                    canvas.drawRoundRect(
                        RectF(cx - barW / 2f, cy - 8.5f * density, cx + barW / 2f, cy + 2.5f * density),
                        barW / 2f, barW / 2f, paint,
                    )
                    canvas.drawCircle(cx, cy + 7f * density, 1.9f * density, paint)
                }
            }
        }
    }

    companion object {
        const val ACTION_DISABLE = "com.pakomo.action.DISABLE_QUICK_CONTROL"
        private const val TAG = "PakomoQuickControl"
        private const val CHANNEL_ID = "pakomo_quick_control"
        private const val NOTIFICATION_ID = 4102
        private const val OVERLAY_STATE_FILE = "pakomo_quick_control_position"
        private const val KEY_RIGHT = "right"
        private const val KEY_Y = "y"
        private const val TOGGLE_DEBOUNCE_MS = 300L

        @Volatile
        private var instance: QuickControlService? = null

        @Volatile
        private var hostVisible: Boolean = false

        fun start(context: Context) {
            val intent = Intent(context, QuickControlService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.e(TAG, "Unable to start quick control", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, QuickControlService::class.java))
        }

        fun setHostVisible(visible: Boolean) {
            hostVisible = visible
            instance?.updateVisibility()
        }
    }
}

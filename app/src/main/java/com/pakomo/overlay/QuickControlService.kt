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
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
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
        val width = dp(42)
        val height = dp(54)
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
            .setContentTitle("Pakomo 快捷控制")
            .setContentText("贴边快捷键已开启")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "关闭", disableIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "快捷悬浮控制",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "保持贴边快捷键可用"
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
        private val iconPath = Path()
        private val backgroundPath = Path()
        var attachedRight: Boolean = true
            set(value) {
                field = value
                invalidate()
            }
        var stage: EngineStage = EngineStage.STOPPED
            set(value) {
                field = value
                contentDescription = when (value) {
                    EngineStage.STARTING -> "Pakomo 正在启动"
                    EngineStage.FORWARDING -> "停止 Pakomo"
                    EngineStage.ERROR, EngineStage.STOPPED -> "启动 Pakomo"
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
            val shadowInset = 3f * density
            val bounds = RectF(
                if (attachedRight) shadowInset else 0f,
                shadowInset,
                if (attachedRight) width.toFloat() else width - shadowInset,
                height - shadowInset,
            )
            val innerRadius = 11f * density
            val edgeRadius = 3f * density
            val radii = if (attachedRight) {
                floatArrayOf(
                    innerRadius, innerRadius,
                    edgeRadius, edgeRadius,
                    edgeRadius, edgeRadius,
                    innerRadius, innerRadius,
                )
            } else {
                floatArrayOf(
                    edgeRadius, edgeRadius,
                    innerRadius, innerRadius,
                    innerRadius, innerRadius,
                    edgeRadius, edgeRadius,
                )
            }
            backgroundPath.reset()
            backgroundPath.addRoundRect(bounds, radii, Path.Direction.CW)
            paint.style = Paint.Style.FILL
            paint.color = when (stage) {
                EngineStage.FORWARDING -> Color.rgb(59, 111, 224)
                EngineStage.STARTING -> Color.rgb(229, 162, 59)
                EngineStage.ERROR -> Color.rgb(192, 57, 46)
                EngineStage.STOPPED -> Color.rgb(112, 116, 124)
            }
            paint.alpha = if (isPressed) 215 else 245
            paint.setShadowLayer(2.5f * density, 0f, density, 0x28000000)
            canvas.drawPath(backgroundPath, paint)
            paint.clearShadowLayer()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density
            paint.color = 0x24FFFFFF
            paint.alpha = 255
            canvas.drawPath(backgroundPath, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            val cx = bounds.centerX()
            val cy = bounds.centerY()
            when (stage) {
                EngineStage.FORWARDING -> {
                    val half = 6f * density
                    canvas.drawRoundRect(
                        RectF(cx - half, cy - half, cx + half, cy + half),
                        2.5f * density,
                        2.5f * density,
                        paint,
                    )
                }
                EngineStage.STARTING -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2.5f * density
                    paint.strokeCap = Paint.Cap.ROUND
                    val half = 8f * density
                    val angle = (android.os.SystemClock.uptimeMillis() / 4L % 360L).toFloat()
                    canvas.drawArc(
                        RectF(cx - half, cy - half, cx + half, cy + half),
                        angle,
                        250f,
                        false,
                        paint,
                    )
                    postInvalidateOnAnimation()
                }
                EngineStage.ERROR, EngineStage.STOPPED -> {
                    val halfHeight = 8f * density
                    val halfWidth = 6f * density
                    iconPath.reset()
                    iconPath.moveTo(cx - halfWidth, cy - halfHeight)
                    iconPath.lineTo(cx + halfWidth + 2f * density, cy)
                    iconPath.lineTo(cx - halfWidth, cy + halfHeight)
                    iconPath.close()
                    canvas.drawPath(iconPath, paint)
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

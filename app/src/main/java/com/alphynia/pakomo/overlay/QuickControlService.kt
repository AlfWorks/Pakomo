package com.alphynia.pakomo.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.alphynia.pakomo.MainActivity
import com.alphynia.pakomo.R
import com.alphynia.pakomo.core.model.AppLanguage
import com.alphynia.pakomo.core.model.EngineStage
import com.alphynia.pakomo.core.model.FlowLatencySeries
import com.alphynia.pakomo.core.model.NetworkRule
import com.alphynia.pakomo.core.model.defaultRules
import com.alphynia.pakomo.data.PakomoPreferences
import com.alphynia.pakomo.ui.theme.PakomoTheme
import com.alphynia.pakomo.ui.theme.ThemeMode
import com.alphynia.pakomo.vpn.VpnServiceController
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class QuickControlService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private lateinit var preferences: PakomoPreferences
    private val appLanguage: AppLanguage
        get() = AppLanguage.fromName(preferences.readLanguage())

    // Window A: the always-present pill (fixed, draggable, never moves on expand).
    private var pill: OverlayContainer? = null
    private var pillParams: WindowManager.LayoutParams? = null
    // Window B: the rule panel; created on expand, positioned above/below the pill, removed on collapse.
    private var panel: OverlayContainer? = null
    private var panelParams: WindowManager.LayoutParams? = null
    // Window C: the latency chart; created on chart-toggle, slides out sideways (inward) from the pill.
    private var chart: OverlayContainer? = null
    private var chartParams: WindowManager.LayoutParams? = null

    private val state = MutableStateFlow(PillState())
    private var expanded = false
    private var openUp = false
    private var listMaxDp = 168
    private var switching = false
    private var switchMessage: String? = null
    private var lastToggleAtMs = 0L
    private var onRight = true
    // Chart window state. Its per-flow series update live (from RuntimeStats) only while it is open, on
    // a dedicated flow so the pill/panel state stays churn-free.
    private var chartOpen = false
    private var openLeft = false
    private var chartListMaxDp = 220
    private val chartData = MutableStateFlow<List<FlowLatencySeries>>(emptyList())
    private var chartJob: Job? = null

    data class PillState(
        val theme: ThemeMode = ThemeMode.Standard,
        val language: AppLanguage = AppLanguage.DEFAULT,
        val stage: EngineStage = EngineStage.STOPPED,
        val rule: NetworkRule? = null,
        val rules: List<NetworkRule> = emptyList(),
        val expanded: Boolean = false,
        val openUp: Boolean = false,
        val listMaxDp: Int = 168,
        val busy: Boolean = false,
        val message: String? = null,
        val chartOpen: Boolean = false,
    )

    override fun onCreate() {
        super.onCreate()
        instance = this
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        preferences = PakomoPreferences(this)
        windowManager = getSystemService(WindowManager::class.java)
        createNotificationChannel()
        serviceScope.launch {
            combine(VpnServiceController.runtime, VpnServiceController.activeRule) { r, rule -> r.stage to rule }
                .distinctUntilChanged()
                .collect { pushState() }
        }
        serviceScope.launch { preferences.observeActiveRuleId().collect { pushState() } }
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
        pill?.post { snapToNearestEdge(save = false) }
    }

    override fun onDestroy() {
        removeControl()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateVisibility() {
        if (hostVisible) removeControl() else showControl()
    }

    private fun composeHost(content: @androidx.compose.runtime.Composable () -> Unit): ComposeView =
        ComposeView(this).apply {
            setContent {
                val s by state.collectAsState()
                PakomoTheme(themeMode = s.theme, language = s.language) { content() }
            }
        }

    private fun wrap(compose: ComposeView, draggable: Boolean, onOutside: (Float, Float) -> Unit): OverlayContainer =
        OverlayContainer(
            this,
            draggable = draggable,
            onOutside = onOutside,
            onMove = ::onMove,
            onMoveEnd = { snapToNearestEdge(save = true) },
        ).apply {
            // The ComposeView resolves its recomposer from the window's ROOT view (this wrapper).
            setViewTreeLifecycleOwner(this@QuickControlService)
            setViewTreeViewModelStoreOwner(this@QuickControlService)
            setViewTreeSavedStateRegistryOwner(this@QuickControlService)
            addView(compose)
        }

    private fun baseParams(width: Int, watchOutside: Boolean) = WindowManager.LayoutParams(
        width,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (watchOutside) WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH else 0),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun showControl() {
        if (pill != null || !Settings.canDrawOverlays(this)) return
        val compose = composeHost {
            val s by state.collectAsState()
            QuickPill(
                stage = s.stage,
                expanded = s.expanded,
                chartOpen = s.chartOpen,
                busy = s.busy,
                onToggle = { if (toggleVpn()) { setExpanded(false); setChartOpen(false) } },
                onExpandToggle = { setExpanded(!expanded) },
                onChartToggle = { setChartOpen(!chartOpen) },
            )
        }
        val wrapper = wrap(compose, draggable = true, onOutside = { _, _ -> })
        val screen = screenBounds()
        val width = dp(PILL_W).coerceAtMost(screen.width())
        onRight = overlayState.getBoolean(KEY_RIGHT, true)
        val params = baseParams(width, watchOutside = false).apply {
            x = if (onRight) screen.width() - width else 0
            y = overlayState.getInt(KEY_Y, screen.height() / 3)
                .coerceIn(0, (screen.height() - dp(56)).coerceAtLeast(0))
        }
        runCatching { windowManager.addView(wrapper, params) }
            .onSuccess { pill = wrapper; pillParams = params; pushState() }
            .onFailure { Log.e(TAG, "Unable to show quick control", it) }
    }

    private fun addPanel() {
        if (panel != null) return
        val pillP = pillParams ?: return
        val pv = pill ?: return
        val screen = screenBounds()
        val gap = dp(6)
        val pillTop = pillP.y
        val pillH = pv.height.takeIf { it > 0 } ?: dp(50)
        val pillBottom = pillTop + pillH
        val roomBelow = screen.height() - pillBottom - dp(8)
        val roomAbove = pillTop - dp(8)
        openUp = roomAbove > roomBelow
        val avail = maxOf(roomBelow, roomAbove).coerceAtLeast(dp(80))
        listMaxDp = ((avail - dp(PANEL_CHROME_DP)).coerceAtLeast(dp(46)) / resources.displayMetrics.density).toInt()
        pushState()

        val compose = composeHost {
            val s by state.collectAsState()
            QuickPanel(
                stage = s.stage,
                rule = s.rule,
                rules = s.rules,
                expanded = s.expanded,
                openUp = s.openUp,
                listMaxDp = s.listMaxDp,
                busy = s.busy,
                message = s.message,
                onRule = { selectRule(it) },
            )
        }
        // Tap-outside-to-close, but ignore taps that land on the pill window: a tap on the pill's own
        // buttons already fires the pill's toggle, so closing here too would race on `expanded` and make
        // it impossible to collapse. ACTION_OUTSIDE carries the screen coords, so filter by the pill's
        // bounds — taps on the pill go to the pill; taps anywhere else close the panel.
        val wrapper = wrap(compose, draggable = false, onOutside = { x, y -> if (!pillContains(x, y)) setExpanded(false) })
        // The panel is wider than the compact pill so the rule names stay readable; align it to the
        // same docked edge.
        val panelWidth = dp(PANEL_W).coerceAtMost(screen.width())
        val params = baseParams(panelWidth, watchOutside = true).apply {
            x = if (onRight) (screen.width() - panelWidth).coerceAtLeast(0) else 0
            // Provisional; for an upward panel the exact top is set once its height is measured (below).
            y = if (openUp) pillTop else pillBottom + gap
        }
        runCatching { windowManager.addView(wrapper, params) }
            .onSuccess {
                panel = wrapper
                panelParams = params
                if (openUp) {
                    // Anchor the panel's bottom just above the pill once its measured height is known.
                    // Only the panel repositions here — the pill window never moves — so there is no jump.
                    wrapper.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                        override fun onLayoutChange(
                            v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or2: Int, ob: Int,
                        ) {
                            val h = v.height
                            if (h <= 0) return
                            val ny = (pillTop - gap - h).coerceAtLeast(0)
                            if (ny != params.y) {
                                params.y = ny
                                runCatching { windowManager.updateViewLayout(wrapper, params) }
                            }
                        }
                    })
                }
            }
            .onFailure { Log.e(TAG, "Unable to show rule panel", it) }
    }

    private fun removePanel() {
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
        panelParams = null
    }

    private fun setChartOpen(value: Boolean) {
        if (value == chartOpen || pill == null) return
        chartOpen = value
        // Mutually exclusive with the rule panel — only one side window at a time.
        if (value && expanded) setExpanded(false)
        when {
            // Re-open while the previous chart is still animating out: just push state so it slides
            // back in (addChart's early-return would otherwise skip pushState).
            value && chart != null -> pushState()
            value -> addChart()
            else -> {
                pushState() // the chart slides out via its transition, then the window is removed
                pill?.postDelayed({ if (!chartOpen) removeChart() }, PANEL_EXIT_MS)
            }
        }
    }

    private fun addChart() {
        if (chart != null) return
        val pillP = pillParams ?: return
        val screen = screenBounds()
        val gap = dp(6)
        val chartWidth = dp(CHART_W).coerceAtMost(screen.width())
        // Slide out toward the screen interior: pill docked right → chart on its left, and vice versa.
        openLeft = onRight
        val provisionalX = if (openLeft) {
            (pillP.x - gap - chartWidth).coerceAtLeast(0)
        } else {
            (pillP.x + pillP.width + gap).coerceAtMost((screen.width() - chartWidth).coerceAtLeast(0))
        }
        // Cap the scrollable list to what fits vertically on screen (the chart isn't tied to the pill's
        // Y the way the panel is, but it must still fit the display).
        val avail = (screen.height() - dp(24)).coerceAtLeast(dp(120))
        chartListMaxDp = ((avail - dp(CHART_CHROME_DP)).coerceAtLeast(dp(80)) / resources.displayMetrics.density).toInt()
        pushState()

        // Update the live per-flow series only while the chart is open.
        chartJob?.cancel()
        chartJob = serviceScope.launch {
            VpnServiceController.runtime
                .map { it.stats.flowLatencies }
                .distinctUntilChanged()
                .collect { chartData.value = it }
        }

        val compose = composeHost {
            val s by state.collectAsState()
            val data by chartData.collectAsState()
            QuickLatencyChart(
                series = data,
                expanded = s.chartOpen,
                openLeft = openLeft,
                listMaxDp = chartListMaxDp,
            )
        }
        val wrapper = wrap(compose, draggable = false, onOutside = { x, y -> if (!pillContains(x, y)) setChartOpen(false) })
        val pillTop = pillP.y
        val params = baseParams(chartWidth, watchOutside = true).apply {
            x = provisionalX
            y = pillTop // refined once measured so the chart stays on-screen
        }
        runCatching { windowManager.addView(wrapper, params) }
            .onSuccess {
                chart = wrapper
                chartParams = params
                // Keep the chart fully on-screen: top-align with the pill, but nudge up if its measured
                // height would overflow the bottom. Only the chart window moves — the pill never does.
                wrapper.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(
                        v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or2: Int, ob: Int,
                    ) {
                        val h = v.height
                        if (h <= 0) return
                        val ny = pillTop.coerceIn(dp(8), (screen.height() - h - dp(8)).coerceAtLeast(dp(8)))
                        if (ny != params.y) {
                            params.y = ny
                            runCatching { windowManager.updateViewLayout(wrapper, params) }
                        }
                    }
                })
            }
            .onFailure { Log.e(TAG, "Unable to show latency chart", it) }
    }

    private fun removeChart() {
        chartJob?.cancel()
        chartJob = null
        chart?.let { runCatching { windowManager.removeView(it) } }
        chart = null
        chartParams = null
    }

    // Dragging only touches the pill window (window A); the panel is its own window and never drags.
    private fun onMove(dx: Float, dy: Float) {
        val view = pill ?: return
        val params = pillParams ?: return
        if (expanded) { // collapse immediately so the panel doesn't linger at the old spot mid-drag
            expanded = false
            removePanel()
            pushState()
        }
        if (chartOpen) { // same for the chart — a side window shouldn't trail the pill mid-drag
            chartOpen = false
            removeChart()
            pushState()
        }
        val screen = screenBounds()
        params.x = (params.x + dx.toInt()).coerceIn(0, (screen.width() - params.width).coerceAtLeast(0))
        params.y = (params.y + dy.toInt()).coerceIn(0, (screen.height() - view.height).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun snapToNearestEdge(save: Boolean) {
        val view = pill ?: return
        val params = pillParams ?: return
        val screen = screenBounds()
        val maxX = (screen.width() - params.width).coerceAtLeast(0)
        onRight = params.x + params.width / 2 >= screen.width() / 2
        params.x = if (onRight) maxX else 0
        params.y = params.y.coerceIn(0, (screen.height() - view.height).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(view, params) }
        if (save) {
            overlayState.edit().putBoolean(KEY_RIGHT, onRight).putInt(KEY_Y, params.y).apply()
        }
    }

    private fun removeControl() {
        removePanel()
        removeChart()
        pill?.let { runCatching { windowManager.removeView(it) } }
        pill = null
        pillParams = null
        expanded = false
        chartOpen = false
    }

    /** Whether a screen-space point falls inside the pill window (so a panel ACTION_OUTSIDE on it is a
     *  pill-button tap the pill already handles, not an outside dismiss). */
    private fun pillContains(x: Float, y: Float): Boolean {
        val params = pillParams ?: return false
        val view = pill ?: return false
        return x >= params.x && x < params.x + params.width && y >= params.y && y < params.y + view.height
    }

    private fun setExpanded(value: Boolean) {
        if (value == expanded || pill == null) return
        expanded = value
        // The rule panel and the chart are mutually exclusive — only one side window at a time.
        if (value && chartOpen) setChartOpen(false)
        when {
            // Re-expanding while the previous panel is still animating out (a pending removePanel hasn't
            // fired): don't recreate — just push the new state so it animates back in. addPanel()'s
            // early-return would otherwise skip pushState and leave expanded=true with a hidden panel.
            value && panel != null -> pushState()
            value -> addPanel()
            else -> {
                pushState() // the panel animates out via its transition, then the window is removed
                pill?.postDelayed({ if (!expanded) removePanel() }, PANEL_EXIT_MS)
            }
        }
    }

    private fun selectedRule(): NetworkRule {
        val rules = preferences.readRules()
        return rules.firstOrNull { it.id == preferences.readActiveRuleId() }
            ?: rules.firstOrNull() ?: defaultRules[1]
    }

    private fun pushState() {
        val stage = VpnServiceController.runtime.value.stage
        val rule = (if (stage == EngineStage.FORWARDING) VpnServiceController.activeRule.value else null)
            ?: selectedRule()
        val theme = runCatching { ThemeMode.valueOf(preferences.readThemeMode() ?: ThemeMode.Standard.name) }
            .getOrDefault(ThemeMode.Standard)
        state.value = PillState(
            theme = theme,
            language = appLanguage,
            stage = stage,
            rule = rule,
            rules = preferences.readRules(),
            expanded = expanded,
            openUp = openUp,
            listMaxDp = listMaxDp,
            busy = switching,
            message = switchMessage,
            chartOpen = chartOpen,
        )
    }

    private fun selectRule(rule: NetworkRule) {
        if (switching || VpnServiceController.runtime.value.stage == EngineStage.STARTING) return
        switchMessage = null
        if (VpnServiceController.runtime.value.stage != EngineStage.FORWARDING) {
            preferences.writeActiveRuleId(rule.id)
            setExpanded(false)
            return
        }
        if (VpnServiceController.activeRule.value == rule) { setExpanded(false); return }
        switching = true
        pushState()
        serviceScope.launch {
            try {
                val packages = preferences.readSelectedPackages()
                val id = VpnServiceController.update(
                    this@QuickControlService, preferences.readScope(), packages.toList(),
                    preferences.readAddressDomains(),
                    preferences.readDomainsByPackage().filterKeys(packages::contains), rule,
                )
                val applied = withTimeoutOrNull(15_000L) {
                    combine(
                        VpnServiceController.appliedConfigId, VpnServiceController.failedConfigId,
                        VpnServiceController.runtime,
                    ) { success, failed, runtime ->
                        when {
                            failed >= id || runtime.stage != EngineStage.FORWARDING -> false
                            success >= id -> VpnServiceController.activeRule.value == rule
                            else -> null
                        }
                    }.first { it != null }
                } == true
                if (applied) {
                    preferences.writeActiveRuleId(rule.id)
                    setExpanded(false)
                } else {
                    switchMessage = appLanguage.tr("切换未确认，请重试", "Switch not confirmed. Try again.")
                }
            } finally {
                switching = false
                pushState()
            }
        }
    }

    private fun toggleVpn(): Boolean {
        if (switching || VpnServiceController.runtime.value.stage == EngineStage.STARTING) return false
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
        VpnServiceController.start(
            context = this,
            scope = preferences.readScope(),
            selectedPackages = selectedPackages.toList(),
            targetDomains = preferences.readAddressDomains(),
            domainsByPackage = preferences.readDomainsByPackage().filterKeys(selectedPackages::contains),
            rule = selectedRule(),
        )
        return true
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disableIntent = PendingIntent.getService(
            this, 1, Intent(this, QuickControlService::class.java).setAction(ACTION_DISABLE),
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

    /**
     * Hosts a ComposeView. When [draggable] it detects drags in screen-space rawX/rawY (immune to the
     * window moving under the finger) and intercepts only past touch-slop, so taps still reach Compose;
     * the panel window sets draggable=false so its list can scroll. ACTION_OUTSIDE fires [onOutside].
     */
    private class OverlayContainer(
        context: Context,
        private val draggable: Boolean,
        private val onOutside: (Float, Float) -> Unit,
        private val onMove: (Float, Float) -> Unit,
        private val onMoveEnd: () -> Unit,
    ) : FrameLayout(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var dragging = false

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (!draggable) return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; lastX = ev.rawX; lastY = ev.rawY; dragging = false
                }
                MotionEvent.ACTION_MOVE ->
                    if (!dragging && (abs(ev.rawX - downX) > slop || abs(ev.rawY - downY) > slop)) {
                        dragging = true
                        return true
                    }
            }
            return false
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (dragging) {
                    onMove(ev.rawX - lastX, ev.rawY - lastY); lastX = ev.rawX; lastY = ev.rawY
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                    dragging = false; onMoveEnd()
                }
            }
            return true
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) { onOutside(ev.rawX, ev.rawY); return true }
            return super.dispatchTouchEvent(ev)
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
        private const val PILL_W = 50
        private const val PANEL_W = 200
        private const val CHART_W = 232
        private const val PANEL_EXIT_MS = 230L

        // Panel chrome above the scrollable list (top margin + padding + "SWITCH RULE" title + summary),
        // subtracted from the available space to cap the list height so the panel fits its window.
        private const val PANEL_CHROME_DP = 78

        // Chart chrome around the scrollable flow list (padding + "LATENCY" title + legend row),
        // subtracted from the available vertical space to cap the list height.
        private const val CHART_CHROME_DP = 64

        @Volatile private var instance: QuickControlService? = null
        @Volatile private var hostVisible: Boolean = false

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

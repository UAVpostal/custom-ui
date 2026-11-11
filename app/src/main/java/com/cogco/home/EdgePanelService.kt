package com.cogco.home

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min

/**
 * Keeps the edge handle on screen over every app (like Samsung's Edge panels) and opens the panel window.
 */
class EdgePanelService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private var handle: HandleView? = null
    private var handleLp: WindowManager.LayoutParams? = null
    private var panel: PanelWindow? = null
    private var moveStartY = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = app.prefs
        wm = getSystemService(WindowManager::class.java)
        startInForeground()
        prefs.sp.registerOnSharedPreferenceChangeListener(this)
        addHandle()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (!Settings.canDrawOverlays(this) || !prefs.edgeEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (handle == null) addHandle()
        if (intent?.action == ACTION_OPEN) openPanel()
        return START_STICKY
    }

    override fun onDestroy() {
        prefs.sp.unregisterOnSharedPreferenceChangeListener(this)
        panel?.dismissNow()
        panel = null
        removeHandle()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        panel?.close()
        updateHandle()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == null || !key.startsWith("edge_")) return
        if (key == "edge_enabled" && !prefs.edgeEnabled) {
            stopSelf()
            return
        }
        if (key.startsWith("edge_handle") || key == "edge_side") updateHandle()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL, "Edge panel", NotificationManager.IMPORTANCE_MIN)
        ch.setShowBadge(false)
        ch.description = "Keeps the edge panel handle available over other apps"
        nm.createNotificationChannel(ch)
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, EdgeSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_edge)
            .setContentTitle("Edge panels are on")
            .setContentText("Tap to customise panels, the handle and colours")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ---------------------------------------------------------------- handle

    private fun handleParams(): WindowManager.LayoutParams {
        val screen = screenSize()
        val h = (screen.y * prefs.handleHeight.coerceIn(8, 50) / 100f).toInt()
        val touchW = dp(prefs.handleWidth.coerceIn(2, 24)) + dp(14)
        val lp = WindowManager.LayoutParams(
            touchW, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or (if (prefs.edgeSide == "left") Gravity.LEFT else Gravity.RIGHT)
        lp.x = 0
        lp.y = ((screen.y - h) * prefs.handlePos.coerceIn(0, 100) / 100f).toInt()
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return lp
    }

    private fun addHandle() {
        removeHandle()
        if (!Settings.canDrawOverlays(this)) return
        val v = HandleView(this)
        v.configure(prefs)
        val lp = handleParams()
        try {
            wm.addView(v, lp)
            handle = v
            handleLp = lp
        } catch (e: Exception) {
            handle = null
        }
    }

    private fun removeHandle() {
        val v = handle ?: return
        try {
            wm.removeView(v)
        } catch (e: Exception) {
            // already gone
        }
        handle = null
    }

    private fun updateHandle() {
        val v = handle
        if (v == null) {
            addHandle()
            return
        }
        v.configure(prefs)
        val lp = handleParams()
        handleLp = lp
        try {
            wm.updateViewLayout(v, lp)
        } catch (e: Exception) {
            addHandle()
        }
    }

    fun beginHandleMove() {
        moveStartY = handleLp?.y ?: 0
    }

    fun moveHandle(dy: Int) {
        val lp = handleLp ?: return
        val v = handle ?: return
        val screen = screenSize()
        lp.y = (moveStartY + dy).coerceIn(0, maxOf(0, screen.y - lp.height))
        try {
            wm.updateViewLayout(v, lp)
        } catch (e: Exception) {
            // ignore
        }
    }

    fun endHandleMove() {
        val lp = handleLp ?: return
        val range = screenSize().y - lp.height
        if (range > 0) prefs.handlePos = (lp.y * 100f / range).toInt().coerceIn(0, 100)
    }

    fun openPanel() {
        if (panel != null) return
        if (prefs.edgeHaptic) vibrateTick(12)
        handle?.visibility = View.INVISIBLE
        val p = PanelWindow(this, prefs) {
            panel = null
            handle?.visibility = View.VISIBLE
        }
        panel = p
        p.show()
    }

    companion object {
        const val ACTION_OPEN = "com.cogco.home.action.OPEN_EDGE_PANEL"
        private const val CHANNEL = "edge_panel"
        private const val NOTIF_ID = 42

        /** Start or stop the service so it matches the user's setting. */
        fun sync(ctx: Context) {
            val p = ctx.app.prefs
            if (p.edgeEnabled && Settings.canDrawOverlays(ctx)) start(ctx) else stop(ctx)
        }

        fun start(ctx: Context, open: Boolean = false) {
            val i = Intent(ctx, EdgePanelService::class.java)
            if (open) i.action = ACTION_OPEN
            try {
                ctx.startForegroundService(i)
            } catch (e: Exception) {
                // background start not allowed right now; HomeActivity retries on resume
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, EdgePanelService::class.java))
        }
    }
}

/** The thin bar on the screen edge. Swipe in (or tap) to open; long-press and drag to move it. */
@SuppressLint("ViewConstructor")
class HandleView(private val service: EdgePanelService) : View(service) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var right = true
    private var barW = 0f
    private var locked = false
    private val slop = ViewConfiguration.get(service).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var opened = false
    private var moving = false
    private val longPress = Runnable {
        if (!locked) {
            moving = true
            service.beginHandleMove()
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            alpha = 0.6f
        }
    }

    fun configure(p: Prefs) {
        right = p.edgeSide != "left"
        barW = dpf(p.handleWidth.coerceIn(2, 24).toFloat())
        paint.color = ColorUtil.withAlpha(p.handleColor, p.handleAlpha.coerceIn(5, 100) * 255 / 100)
        locked = p.handleLocked
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (Build.VERSION.SDK_INT >= 29) systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
    }

    override fun onDraw(canvas: Canvas) {
        val r = if (right) RectF(width - barW, 0f, width + barW, height.toFloat())
        else RectF(-barW, 0f, barW, height.toFloat())
        canvas.drawRoundRect(r, barW, barW, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                opened = false
                moving = false
                removeCallbacks(longPress)
                postDelayed(longPress, 450)
            }
            MotionEvent.ACTION_MOVE -> {
                if (moving) {
                    service.moveHandle((ev.rawY - downY).toInt())
                    return true
                }
                val inward = if (right) downX - ev.rawX else ev.rawX - downX
                if (!opened && inward > dpf(14f)) {
                    opened = true
                    removeCallbacks(longPress)
                    service.openPanel()
                } else if (abs(ev.rawY - downY) > slop || abs(ev.rawX - downX) > slop) {
                    removeCallbacks(longPress)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (moving) {
                    moving = false
                    alpha = 1f
                    service.endHandleMove()
                } else if (!opened && hypot(ev.rawX - downX, ev.rawY - downY) < slop) {
                    service.openPanel()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (moving) {
                    moving = false
                    alpha = 1f
                    service.endHandleMove()
                }
            }
        }
        return true
    }
}

/** What a panel can ask of the window that shows it. */
interface PanelHost {
    val context: Context
    fun close()
    fun launch(intent: Intent)
    fun launchApp(c: ComponentName)
}

/** The full-screen overlay with the panel card, page dots and settings button. */
class PanelWindow(
    private val service: EdgePanelService,
    private val prefs: Prefs,
    private val onClosed: () -> Unit
) : PanelHost {
    override val context: Context = ContextThemeWrapper(service, android.R.style.Theme_Material_Light_NoActionBar)
    private val wm = service.getSystemService(WindowManager::class.java)
    private lateinit var root: FrameLayout
    private lateinit var dim: View
    private lateinit var column: LinearLayout
    private lateinit var card: PanelCard
    private lateinit var pager: PagedView
    private lateinit var dots: PageIndicator
    private val panels = ArrayList<EdgePanel>()
    private val schemes = ArrayList<PanelScheme>()
    private var closing = false
    private var removed = false
    private var right = true
    private var cardW = 0

    fun show() {
        val ctx = context
        right = prefs.edgeSide != "left"
        val enabled = prefs.panelsEnabled
        val ids = prefs.panelOrder.filter { it in enabled }.ifEmpty { listOf("apps") }
        for (id in ids) {
            val p = EdgePanels.create(id) ?: continue
            panels += p
            schemes += Schemes.forPanel(ctx, prefs, id)
        }

        root = object : FrameLayout(ctx) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) close()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        root.isFocusableInTouchMode = true

        dim = View(ctx)
        dim.setBackgroundColor(Color.BLACK)
        dim.alpha = 0f
        dim.setOnClickListener { close() }
        root.addView(dim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val screen = ctx.screenSize()
        cardW = ctx.dp(
            when (prefs.panelSize) {
                "compact" -> 150
                "wide" -> 236
                else -> 184
            }
        )
        val cardH = min((screen.y * 0.64f).toInt(), ctx.dp(580))

        column = LinearLayout(ctx)
        column.orientation = LinearLayout.VERTICAL
        column.gravity = Gravity.CENTER_HORIZONTAL
        column.isClickable = true // taps between card and footer shouldn't close

        card = PanelCard(ctx)
        pager = PagedView(ctx)
        card.addView(pager, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        panels.forEachIndexed { i, p -> pager.addView(p.createView(ctx, schemes[i], this, cardW)) }
        column.addView(card, LinearLayout.LayoutParams(cardW, cardH))

        val footer = LinearLayout(ctx)
        footer.orientation = LinearLayout.HORIZONTAL
        footer.gravity = Gravity.CENTER_VERTICAL
        dots = PageIndicator(ctx)
        dots.count = panels.size
        footer.addView(View(ctx), LinearLayout.LayoutParams(ctx.dp(36), 1))
        footer.addView(dots, LinearLayout.LayoutParams(0, ctx.dp(20), 1f))
        val gear = ImageView(ctx)
        gear.setImageDrawable(GlyphDrawable(Glyph.SETTINGS, Color.WHITE))
        gear.background = oval(0x40000000)
        gear.setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
        gear.setOnClickListener { launch(Intent(ctx, EdgeSettingsActivity::class.java)) }
        footer.addView(gear, LinearLayout.LayoutParams(ctx.dp(36), ctx.dp(36)))
        column.addView(footer, LinearLayout.LayoutParams(cardW, ctx.dp(48)).apply { topMargin = ctx.dp(6) })

        root.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL or (if (right) Gravity.RIGHT else Gravity.LEFT)
        ).apply {
            leftMargin = ctx.dp(12)
            rightMargin = ctx.dp(12)
        })

        pager.onScroll = { pos ->
            applyBlend(pos)
            dots.position = pos
        }
        applyBlend(0f)

        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (prefs.edgeBlur && Build.VERSION.SDK_INT >= 31 && wm.isCrossWindowBlurEnabled) {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            lp.blurBehindRadius = ctx.dp(28)
        }
        try {
            wm.addView(root, lp)
        } catch (e: Exception) {
            removed = true
            onClosed()
            return
        }
        root.requestFocus()

        val off = (cardW + ctx.dp(24)).toFloat()
        column.translationX = if (right) off else -off
        column.alpha = 0f
        column.animate().translationX(0f).alpha(1f).setDuration(240).setInterpolator(DecelerateInterpolator(2f)).start()
        dim.animate().alpha(prefs.edgeDim.coerceIn(0, 90) / 100f).setDuration(240).start()
        panels.forEach {
            try {
                it.onShow()
            } catch (e: Exception) {
                // a tool's sensor/camera may be unavailable
            }
        }
    }

    private fun applyBlend(pos: Float) {
        if (schemes.isEmpty()) return
        val i = floor(pos).toInt().coerceIn(0, schemes.size - 1)
        val j = min(i + 1, schemes.size - 1)
        val t = (pos - i).coerceIn(0f, 1f)
        val a = schemes[i]
        val b = schemes[j]
        card.setColors(
            ColorUtil.blend(a.topArgb(), b.topArgb(), t),
            ColorUtil.blend(a.bottomArgb(), b.bottomArgb(), t),
            context.dpf(a.radius + (b.radius - a.radius) * t)
        )
    }

    override fun close() {
        if (closing || removed) return
        closing = true
        panels.forEach {
            try {
                it.onHide()
            } catch (e: Exception) {
                // ignore
            }
        }
        val off = (cardW + context.dp(24)).toFloat()
        column.animate().translationX(if (right) off else -off).alpha(0f).setDuration(170).start()
        dim.animate().alpha(0f).setDuration(170).withEndAction { removeNow() }.start()
    }

    fun dismissNow() {
        if (!closing) panels.forEach {
            try {
                it.onHide()
            } catch (e: Exception) {
                // ignore
            }
        }
        closing = true
        removeNow()
    }

    private fun removeNow() {
        if (removed) return
        removed = true
        try {
            wm.removeView(root)
        } catch (e: Exception) {
            // already gone
        }
        onClosed()
    }

    override fun launch(intent: Intent) {
        try {
            service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            service.toast("No app can do that")
        }
        close()
    }

    override fun launchApp(c: ComponentName) {
        launch(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(c)
                .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        )
    }
}

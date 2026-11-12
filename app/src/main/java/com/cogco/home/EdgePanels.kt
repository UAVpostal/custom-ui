package com.cogco.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** One page of the edge panel. A fresh instance is created every time the panel opens. */
abstract class EdgePanel(val id: String, val title: String) {
    abstract fun createView(ctx: Context, s: PanelScheme, host: PanelHost, widthPx: Int): View
    open fun onShow() {}
    open fun onHide() {}

    /** The panel's scroll area, so panels can jump to the end (e.g. the newest note). */
    protected var scroller: ScrollView? = null

    /** True when the user wants panel items at the bottom of the panel, within thumb reach. */
    protected fun itemsAtBottom(ctx: Context): Boolean = ctx.app.prefs.edgeItemsBottom

    protected fun scrollToEnd() {
        val sv = scroller ?: return
        sv.post { sv.scrollTo(0, sv.getChildAt(0)?.height ?: 0) }
    }

    /**
     * Title + content (scrollable), plus an optional [footer] pinned under the content.
     * Top layout: title at the top, items flow down from it.
     * Bottom layout: title and items sit together at the bottom of the panel; if there are
     * more items than fit, the list scrolls and the footer stays put at the very bottom.
     */
    protected fun frame(ctx: Context, s: PanelScheme, body: View, footer: View? = null): View {
        val bottom = itemsAtBottom(ctx)
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(ctx.dp(8), ctx.dp(if (bottom) 8 else 16), ctx.dp(8), ctx.dp(if (bottom) 14 else 8))
        val titleView = TextView(ctx).apply {
            text = title
            setTextColor(ColorUtil.withAlpha(s.text, 0xD0))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, ctx.dp(10))
        }
        val sv = ScrollView(ctx)
        sv.isVerticalScrollBarEnabled = false
        sv.overScrollMode = View.OVER_SCROLL_NEVER
        scroller = sv
        if (bottom) {
            // fillViewport stretches the wrapper to the panel height; gravity pushes everything down
            sv.isFillViewport = true
            val wrapper = LinearLayout(ctx)
            wrapper.orientation = LinearLayout.VERTICAL
            wrapper.gravity = Gravity.BOTTOM
            wrapper.addView(titleView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            wrapper.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            sv.addView(wrapper, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            col.addView(titleView)
            sv.addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        col.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (footer != null) {
            col.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ctx.dp(8)
            })
        }
        return col
    }

    protected fun circleButton(ctx: Context, glyph: Glyph, bg: Int, fg: Int, sizeDp: Int, onClick: () -> Unit): View {
        val f = FrameLayout(ctx)
        f.background = oval(bg)
        val iv = ImageView(ctx)
        iv.setImageDrawable(GlyphDrawable(glyph, fg))
        val inner = ctx.dp(sizeDp) / 2
        f.addView(iv, FrameLayout.LayoutParams(inner, inner, Gravity.CENTER))
        f.setOnClickListener { onClick() }
        f.layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
        return f
    }
}

object EdgePanels {
    val TITLES = linkedMapOf(
        "apps" to "Apps",
        "people" to "People",
        "tools" to "Tools",
        "notes" to "Quick notes"
    )

    fun title(id: String): String = TITLES[id] ?: id

    fun create(id: String): EdgePanel? = when (id) {
        "apps" -> AppsPanel()
        "people" -> PeoplePanel()
        "tools" -> ToolsPanel()
        "notes" -> NotesPanel()
        else -> null
    }

    val TOOL_NAMES = mapOf(
        "flashlight" to "Flashlight",
        "compass" to "Compass",
        "counter" to "Tally counter",
        "calculator" to "Calculator",
        "camera" to "Camera",
        "alarm" to "Alarms",
        "wifi" to "Wi-Fi",
        "volume" to "Volume",
        "bluetooth" to "Bluetooth",
        "settings" to "Settings"
    )
}

// ---------------------------------------------------------------- Apps edge

class AppsPanel : EdgePanel("apps", "Apps") {
    override fun createView(ctx: Context, s: PanelScheme, host: PanelHost, widthPx: Int): View {
        val a = ctx.app
        val cols = if (widthPx >= ctx.dp(220)) 3 else 2
        val apps = a.prefs.edgeApps
        val grid = CellLayout(ctx, cols, max(1, ceil((apps.size + 1) / cols.toFloat()).toInt()))
        grid.fixedCellHeight = ctx.dp(84)
        apps.forEachIndexed { i, c ->
            val iv = IconView(ctx)
            iv.iconSize = ctx.dp(46)
            iv.labelColor = s.text
            iv.labelShadow = false
            iv.labelSizeSp = 11f
            iv.label = a.repo.label(c)
            a.icons.load(c) { b -> iv.icon = b }
            iv.setOnClickListener { host.launchApp(c) }
            grid.addView(iv, CellLayout.LP(i % cols, i / cols))
        }
        val add = IconView(ctx)
        add.iconSize = ctx.dp(46)
        add.labelColor = s.text
        add.labelShadow = false
        add.labelSizeSp = 11f
        add.label = if (apps.isEmpty()) "Add apps" else "Edit"
        add.icon = Glyphs.bitmap(if (apps.isEmpty()) Glyph.PLUS else Glyph.EDIT, s.text, s.subtle(0x30), ctx.dp(46))
        add.setOnClickListener {
            host.launch(
                Intent(ctx, AppPickerActivity::class.java).putExtra(AppPickerActivity.EXTRA_MODE, AppPickerActivity.MODE_EDGE)
            )
        }
        grid.addView(add, CellLayout.LP(apps.size % cols, apps.size / cols))
        return frame(ctx, s, grid)
    }
}

// ---------------------------------------------------------------- People edge

class PeoplePanel : EdgePanel("people", "People") {
    override fun createView(ctx: Context, s: PanelScheme, host: PanelHost, widthPx: Int): View {
        val list = LinearLayout(ctx)
        list.orientation = LinearLayout.VERTICAL
        for (c in ctx.app.prefs.contacts) list.addView(contactRow(ctx, s, host, c))

        val add = LinearLayout(ctx)
        add.orientation = LinearLayout.HORIZONTAL
        add.gravity = Gravity.CENTER_VERTICAL
        add.setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
        add.addView(AvatarView(ctx, "", s.subtle(0x30), s.text, Glyph.PLUS), LinearLayout.LayoutParams(ctx.dp(38), ctx.dp(38)))
        add.addView(TextView(ctx).apply {
            text = "Add contact"
            setTextColor(s.text)
            textSize = 13f
            setPadding(ctx.dp(10), 0, 0, 0)
        })
        add.setOnClickListener { host.launch(Intent(ctx, ContactPickActivity::class.java)) }
        list.addView(add)
        return frame(ctx, s, list)
    }

    private fun initials(name: String): String =
        name.split(" ").filter { it.isNotBlank() }.take(2).map { it.first().uppercaseChar() }.joinToString("").ifEmpty { "?" }

    private fun contactRow(ctx: Context, s: PanelScheme, host: PanelHost, c: Contact): View {
        val wrap = LinearLayout(ctx)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.background = roundRect(s.subtle(0x16), ctx.dpf(18f))
        wrap.setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))

        val top = LinearLayout(ctx)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(AvatarView(ctx, initials(c.name), s.accent, ColorUtil.onColor(s.accent)), LinearLayout.LayoutParams(ctx.dp(38), ctx.dp(38)))
        top.addView(TextView(ctx).apply {
            text = c.name.ifEmpty { c.number }
            setTextColor(s.text)
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(ctx.dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        wrap.addView(top)

        val actions = LinearLayout(ctx)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.gravity = Gravity.CENTER
        actions.visibility = View.GONE
        actions.setPadding(0, ctx.dp(8), 0, 0)
        val fg = ColorUtil.onColor(s.accent)
        val call = circleButton(ctx, Glyph.CALL, s.accent, fg, 40) {
            host.launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(c.number))))
        }
        val text = circleButton(ctx, Glyph.MESSAGE, s.accent, fg, 40) {
            host.launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(c.number))))
        }
        (call.layoutParams as LinearLayout.LayoutParams).marginEnd = ctx.dp(12)
        actions.addView(call)
        actions.addView(text)
        wrap.addView(actions)
        wrap.setOnClickListener { actions.visibility = if (actions.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        wrap.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = ctx.dp(6)
        }
        return wrap
    }
}

// ---------------------------------------------------------------- Tools edge

class ToolsPanel : EdgePanel("tools", "Tools") {
    private var ctxRef: Context? = null
    private var torchTile: TileView? = null
    private var compassTile: TileView? = null
    private var camManager: CameraManager? = null
    private var torchCallback: CameraManager.TorchCallback? = null
    private var torchId: String? = null
    private var torchOn = false
    private var sensorManager: SensorManager? = null
    private var sensorListener: SensorEventListener? = null

    override fun createView(ctx: Context, s: PanelScheme, host: PanelHost, widthPx: Int): View {
        ctxRef = ctx
        val tools = ctx.app.prefs.tools.filter { it in Prefs.ALL_TOOLS }
        val cols = if (widthPx >= ctx.dp(220)) 3 else 2
        val grid = CellLayout(ctx, cols, max(1, ceil(tools.size / cols.toFloat()).toInt()))
        grid.fixedCellHeight = ctx.dp(88)
        tools.forEachIndexed { i, t -> grid.addView(tile(ctx, s, host, t), CellLayout.LP(i % cols, i / cols)) }
        return frame(ctx, s, grid)
    }

    private fun tile(ctx: Context, s: PanelScheme, host: PanelHost, id: String): TileView {
        val v = TileView(ctx)
        v.fg = s.text
        v.accent = s.accent
        v.idleBg = s.subtle(0x22)
        v.label = EdgePanels.TOOL_NAMES[id] ?: id
        when (id) {
            "flashlight" -> {
                v.glyph = Glyph.FLASHLIGHT
                torchTile = v
                v.setOnClickListener { toggleTorch(ctx) }
            }
            "compass" -> {
                v.glyph = Glyph.COMPASS
                compassTile = v
            }
            "counter" -> {
                val prefs = ctx.app.prefs
                v.label = "Counter"
                v.bigText = prefs.tally.toString()
                v.setOnClickListener {
                    prefs.tally = prefs.tally + 1
                    v.bigText = prefs.tally.toString()
                    ctx.vibrateTick(8)
                }
                v.setOnLongClickListener {
                    prefs.tally = 0
                    v.bigText = "0"
                    ctx.vibrateTick(30)
                    true
                }
            }
            "calculator" -> {
                v.glyph = Glyph.CALCULATOR
                v.setOnClickListener {
                    val i = calculatorIntent(ctx)
                    if (i != null) host.launch(i) else ctx.toast("No calculator app found")
                }
            }
            "camera" -> {
                v.glyph = Glyph.CAMERA
                v.setOnClickListener { host.launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) }
            }
            "alarm" -> {
                v.glyph = Glyph.ALARM
                v.setOnClickListener { host.launch(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
            }
            "wifi" -> {
                v.glyph = Glyph.WIFI
                v.setOnClickListener {
                    host.launch(Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS))
                }
            }
            "volume" -> {
                v.glyph = Glyph.VOLUME
                v.setOnClickListener {
                    host.close()
                    val am = ctx.getSystemService(AudioManager::class.java)
                    am?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
                }
            }
            "bluetooth" -> {
                v.glyph = Glyph.BLUETOOTH
                v.setOnClickListener { host.launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            }
            "settings" -> {
                v.glyph = Glyph.SETTINGS
                v.setOnClickListener { host.launch(Intent(Settings.ACTION_SETTINGS)) }
            }
        }
        return v
    }

    @Suppress("DEPRECATION")
    private fun calculatorIntent(ctx: Context): Intent? {
        val pm = ctx.packageManager
        val sel = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR)
        if (pm.resolveActivity(sel, 0) != null) return sel
        val known = listOf(
            "com.sec.android.app.popupcalculator",
            "com.google.android.calculator",
            "com.android.calculator2",
            "com.miui.calculator",
            "com.oneplus.calculator"
        )
        for (p in known) pm.getLaunchIntentForPackage(p)?.let { return it }
        return null
    }

    private fun toggleTorch(ctx: Context) {
        val cm = camManager ?: return
        val id = torchId
        if (id == null) {
            ctx.toast("No flashlight on this device")
            return
        }
        try {
            cm.setTorchMode(id, !torchOn)
        } catch (e: Exception) {
            ctx.toast("The flashlight is being used by another app")
        }
    }

    override fun onShow() {
        val ctx = ctxRef ?: return
        if (torchTile != null) setupTorch(ctx)
        if (compassTile != null) setupCompass(ctx)
    }

    private fun setupTorch(ctx: Context) {
        val cm = ctx.getSystemService(CameraManager::class.java) ?: return
        camManager = cm
        torchId = try {
            cm.cameraIdList.firstOrNull { cid ->
                cm.getCameraCharacteristics(cid).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (e: Exception) {
            null
        }
        if (torchId == null) {
            torchTile?.label = "No flash"
            return
        }
        val cb = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId == torchId) {
                    torchOn = enabled
                    torchTile?.active = enabled
                }
            }
        }
        torchCallback = cb
        try {
            cm.registerTorchCallback(cb, Async.main)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun setupCompass(ctx: Context) {
        val tile = compassTile ?: return
        val sm = ctx.getSystemService(SensorManager::class.java) ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor == null) {
            tile.label = "No sensor"
            return
        }
        sensorManager = sm
        val rot = FloatArray(9)
        val ori = FloatArray(3)
        var smooth = Float.NaN
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, event.values)
                SensorManager.getOrientation(rot, ori)
                var az = Math.toDegrees(ori[0].toDouble()).toFloat()
                if (az < 0) az += 360f
                smooth = if (smooth.isNaN()) az else smoothAngle(smooth, az)
                tile.glyphRotation = -smooth
                tile.label = "${smooth.roundToInt() % 360}° ${direction(smooth)}"
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorListener = l
        sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    private fun smoothAngle(prev: Float, next: Float): Float {
        val diff = ((next - prev + 540f) % 360f) - 180f
        return (prev + diff * 0.2f + 360f) % 360f
    }

    private fun direction(deg: Float): String {
        val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return names[(((deg + 22.5f) % 360f) / 45f).toInt().coerceIn(0, 7)]
    }

    override fun onHide() {
        torchCallback?.let {
            try {
                camManager?.unregisterTorchCallback(it)
            } catch (e: Exception) {
                // ignore
            }
        }
        torchCallback = null
        sensorListener?.let { sensorManager?.unregisterListener(it) }
        sensorListener = null
    }
}

// ---------------------------------------------------------------- Quick notes edge

class NotesPanel : EdgePanel("notes", "Quick notes") {
    private lateinit var ctx: Context
    private lateinit var scheme: PanelScheme
    private lateinit var list: LinearLayout
    private lateinit var input: EditText
    private var bottom = false

    override fun createView(ctx: Context, s: PanelScheme, host: PanelHost, widthPx: Int): View {
        this.ctx = ctx
        scheme = s
        bottom = itemsAtBottom(ctx)

        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        input = EditText(ctx)
        input.hint = "Write a note…"
        input.setTextColor(s.text)
        input.setHintTextColor(ColorUtil.withAlpha(s.text, 0x88))
        input.textSize = 13f
        input.background = roundRect(s.subtle(0x1C), ctx.dpf(14f))
        input.setPadding(ctx.dp(10), ctx.dp(8), ctx.dp(10), ctx.dp(8))
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.setHorizontallyScrolling(false)
        input.maxLines = 4
        input.imeOptions = EditorInfo.IME_ACTION_DONE
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addNote()
                true
            } else false
        }
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val add = circleButton(ctx, Glyph.PLUS, s.accent, ColorUtil.onColor(s.accent), 34) { addNote() }
        (add.layoutParams as LinearLayout.LayoutParams).marginStart = ctx.dp(6)
        row.addView(add)

        list = LinearLayout(ctx)
        list.orientation = LinearLayout.VERTICAL
        render()
        if (bottom) {
            // chat-style: writing box pinned at the bottom, newest note right above it
            val view = frame(ctx, s, list, footer = row)
            scrollToEnd()
            return view
        }
        list.setPadding(0, ctx.dp(10), 0, 0)
        val body = LinearLayout(ctx)
        body.orientation = LinearLayout.VERTICAL
        body.addView(row)
        body.addView(list)
        return frame(ctx, s, body)
    }

    private fun addNote() {
        val t = input.text?.toString()?.trim().orEmpty()
        if (t.isEmpty()) return
        val prefs = ctx.app.prefs
        prefs.notes = listOf(t) + prefs.notes
        input.setText("")
        render()
        if (bottom) scrollToEnd()
    }

    private fun render() {
        list.removeAllViews()
        val notes = ctx.app.prefs.notes
        if (notes.isEmpty()) {
            list.addView(TextView(ctx).apply {
                text = "Notes stay on your phone. Tap a note to copy it."
                setTextColor(ColorUtil.withAlpha(scheme.text, 0x99))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(ctx.dp(6), ctx.dp(16), ctx.dp(6), 0)
            })
            return
        }
        // notes are stored newest first; at the bottom, show newest last (closest to the thumb)
        val order = if (bottom) notes.indices.reversed() else notes.indices
        for (i in order) list.addView(noteCard(i, notes[i]))
    }

    private fun noteCard(index: Int, note: String): View {
        val card = FrameLayout(ctx)
        card.background = roundRect(scheme.subtle(0x18), ctx.dpf(14f))
        val tv = TextView(ctx)
        tv.text = note
        tv.setTextColor(scheme.text)
        tv.textSize = 13f
        tv.maxLines = 6
        tv.ellipsize = TextUtils.TruncateAt.END
        tv.setPadding(ctx.dp(10), ctx.dp(8), ctx.dp(28), ctx.dp(8))
        card.addView(tv)
        val del = ImageView(ctx)
        del.setImageDrawable(GlyphDrawable(Glyph.CLOSE, ColorUtil.withAlpha(scheme.text, 0xAA)))
        del.setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
        del.setOnClickListener {
            val prefs = ctx.app.prefs
            prefs.notes = prefs.notes.filterIndexed { j, _ -> j != index }
            render()
        }
        card.addView(del, FrameLayout.LayoutParams(ctx.dp(26), ctx.dp(26), Gravity.TOP or Gravity.END))
        card.setOnClickListener {
            val cm = ctx.getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("note", note))
            if (Build.VERSION.SDK_INT < 33) ctx.toast("Copied")
        }
        card.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = ctx.dp(6)
        }
        return card
    }
}

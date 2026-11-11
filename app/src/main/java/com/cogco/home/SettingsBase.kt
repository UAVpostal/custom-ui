package com.cogco.home

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** One UI-style settings screen built in code: big title, rounded cards, rows. */
abstract class SettingsBase : Activity() {
    protected lateinit var scroll: ScrollView
    protected lateinit var content: LinearLayout
    protected val prefs: Prefs get() = app.prefs

    protected var night = false
    protected var cBg = 0
    protected var cCard = 0
    protected var cText = 0
    protected var cSub = 0
    protected var cDivider = 0
    protected val cAccent = 0xFF3E91FF.toInt()

    abstract val screenTitle: String
    open val headerAction: Pair<String, () -> Unit>? get() = null
    abstract fun build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        night = isNight()
        cBg = if (night) Color.BLACK else 0xFFF4F4F6.toInt()
        cCard = if (night) 0xFF17171A.toInt() else Color.WHITE
        cText = if (night) 0xFFF4F4F6.toInt() else 0xFF111113.toInt()
        cSub = if (night) 0xFF9A9AA0.toInt() else 0xFF76767C.toInt()
        cDivider = if (night) 0xFF2A2A2E.toInt() else 0xFFEBEBEE.toInt()

        scroll = ScrollView(this)
        scroll.setBackgroundColor(cBg)
        scroll.isFillViewport = true
        scroll.fitsSystemWindows = true
        scroll.clipToPadding = false
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(14), 0, dp(14), dp(40))
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    fun refresh() {
        val y = scroll.scrollY
        content.removeAllViews()
        header()
        build()
        scroll.post { scroll.scrollTo(0, y) }
    }

    /** Rebuild after the current click finishes. */
    protected fun refreshSoon() {
        content.post { refresh() }
    }

    private fun header() {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        val back = ImageView(this)
        back.setImageDrawable(GlyphDrawable(Glyph.BACK, cText))
        back.setPadding(dp(10), dp(10), dp(10), dp(10))
        back.setOnClickListener { finish() }
        bar.addView(back, LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        headerAction?.let { (label, action) ->
            bar.addView(TextView(this).apply {
                text = label
                setTextColor(cAccent)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(14), dp(10), dp(10), dp(10))
                setOnClickListener { action() }
            })
        }
        content.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(6) })
        content.addView(TextView(this).apply {
            text = screenTitle
            setTextColor(cText)
            textSize = 30f
            setPadding(dp(10), dp(36), dp(10), dp(26))
        })
    }

    protected fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(cSub)
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(20), dp(4), dp(20), dp(8))
    }

    protected fun note(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(cSub)
        textSize = 13f
        setPadding(dp(20), 0, dp(20), dp(16))
    }

    protected fun section(title: String? = null, block: Card.() -> Unit) {
        if (title != null) content.addView(label(title))
        val c = Card()
        c.block()
        if (c.view.childCount > 0) {
            content.addView(c.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(18)
            })
        }
    }

    protected fun ripple(): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(ColorUtil.withAlpha(cText, 0x1F)), null, ColorDrawable(Color.WHITE))

    inner class Card {
        val view = LinearLayout(this@SettingsBase).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(cCard, dpf(26f))
            clipToOutline = true
            setPadding(0, dp(4), 0, dp(4))
        }

        fun add(row: View) {
            if (view.childCount > 0) {
                view.addView(View(this@SettingsBase).apply { setBackgroundColor(cDivider) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                        setMargins(dp(20), 0, dp(20), 0)
                    })
            }
            view.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        private fun row(title: String, summary: String?, summaryAccent: Boolean, glyph: Glyph?): Pair<LinearLayout, TextView?> {
            val r = LinearLayout(this@SettingsBase)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.minimumHeight = dp(60)
            r.setPadding(dp(20), dp(12), dp(18), dp(12))
            r.background = ripple()
            if (glyph != null) {
                val box = ImageView(this@SettingsBase)
                box.setImageDrawable(GlyphDrawable(glyph, Color.WHITE))
                box.background = roundRect(cAccent, dpf(12f))
                box.setPadding(dp(7), dp(7), dp(7), dp(7))
                r.addView(box, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(14) })
            }
            val texts = LinearLayout(this@SettingsBase)
            texts.orientation = LinearLayout.VERTICAL
            texts.addView(TextView(this@SettingsBase).apply {
                text = title
                setTextColor(cText)
                textSize = 17f
            })
            var sub: TextView? = null
            if (summary != null) {
                sub = TextView(this@SettingsBase).apply {
                    text = summary
                    setTextColor(if (summaryAccent) cAccent else cSub)
                    textSize = 13.5f
                    setPadding(0, dp(2), 0, 0)
                }
                texts.addView(sub)
            }
            r.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return Pair(r, sub)
        }

        fun action(title: String, summary: String? = null, summaryAccent: Boolean = false, glyph: Glyph? = null, onClick: () -> Unit) {
            val (r, _) = row(title, summary, summaryAccent, glyph)
            r.setOnClickListener { onClick() }
            add(r)
        }

        fun switch(title: String, summary: String?, checked: Boolean, glyph: Glyph? = null, onChange: (Boolean) -> Unit) {
            val (r, _) = row(title, summary, false, glyph)
            val sw = Switch(this@SettingsBase)
            sw.isChecked = checked
            sw.isClickable = false
            sw.isFocusable = false
            r.addView(sw)
            r.setOnClickListener {
                sw.isChecked = !sw.isChecked
                onChange(sw.isChecked)
                refreshSoon()
            }
            add(r)
        }

        fun choice(title: String, options: List<Pair<String, String>>, current: String, glyph: Glyph? = null, onPick: (String) -> Unit) {
            val currentLabel = options.firstOrNull { it.first == current }?.second ?: current
            val (r, _) = row(title, currentLabel, true, glyph)
            r.setOnClickListener {
                val labels = options.map { it.second }.toTypedArray()
                val idx = options.indexOfFirst { it.first == current }
                AlertDialog.Builder(this@SettingsBase)
                    .setTitle(title)
                    .setSingleChoiceItems(labels, idx) { d, which ->
                        onPick(options[which].first)
                        d.dismiss()
                        refreshSoon()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            add(r)
        }

        fun slider(
            title: String, min: Int, max: Int, value: Int, format: (Int) -> String,
            onLive: ((Int) -> Unit)? = null, onDone: (Int) -> Unit
        ) {
            val box = LinearLayout(this@SettingsBase)
            box.orientation = LinearLayout.VERTICAL
            box.setPadding(dp(20), dp(14), dp(20), dp(10))
            val top = LinearLayout(this@SettingsBase)
            top.orientation = LinearLayout.HORIZONTAL
            top.addView(TextView(this@SettingsBase).apply {
                text = title
                setTextColor(cText)
                textSize = 17f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val valueText = TextView(this@SettingsBase).apply {
                text = format(value)
                setTextColor(cAccent)
                textSize = 14f
            }
            top.addView(valueText)
            box.addView(top)
            val sb = SeekBar(this@SettingsBase)
            sb.min = min
            sb.max = max
            sb.progress = value.coerceIn(min, max)
            sb.setPadding(dp(4), dp(12), dp(4), dp(8))
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    valueText.text = format(progress)
                    if (fromUser) onLive?.invoke(progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    onDone(seekBar?.progress ?: value)
                }
            })
            box.addView(sb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            add(box)
        }

        fun color(title: String, color: Int, onPick: (Int) -> Unit) {
            val (r, _) = row(title, ColorUtil.toHex(color), false, null)
            val sw = View(this@SettingsBase)
            sw.background = oval(color).apply { setStroke(dp(1), ColorUtil.withAlpha(cText, 0x33)) }
            r.addView(sw, LinearLayout.LayoutParams(dp(30), dp(30)))
            r.setOnClickListener {
                ColorPicker.show(this@SettingsBase, color, title) {
                    onPick(it)
                    refreshSoon()
                }
            }
            add(r)
        }

        fun text(t: String) {
            add(TextView(this@SettingsBase).apply {
                text = t
                setTextColor(cSub)
                textSize = 14f
                setPadding(dp(20), dp(14), dp(20), dp(14))
            })
        }
    }
}

/** HSV colour picker with swatches and a hex field. */
object ColorPicker {
    private val SWATCHES = listOf(
        0xFFFFFFFF, 0xFFE0E0E0, 0xFF9E9E9E, 0xFF424242, 0xFF1B1B1D, 0xFF000000,
        0xFFF44336, 0xFFE91E63, 0xFFFFB3C6, 0xFF9C27B0, 0xFF673AB7, 0xFF3F51B5,
        0xFF3E91FF, 0xFF03A9F4, 0xFF00BCD4, 0xFF009688, 0xFF4CAF50, 0xFF8BC34A,
        0xFFCDDC39, 0xFFFFEB3B, 0xFFFFC107, 0xFFFF9800, 0xFFFF5722, 0xFF795548
    ).map { it.toInt() }

    fun show(ctx: Context, initial: Int, title: String, onPick: (Int) -> Unit) {
        val hsv = FloatArray(3)
        Color.colorToHSV(initial, hsv)
        var current = initial or 0xFF000000.toInt()
        var updating = false

        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(ctx.dp(22), ctx.dp(12), ctx.dp(22), ctx.dp(4))

        val top = LinearLayout(ctx)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val preview = View(ctx)
        preview.background = roundRect(current, ctx.dpf(14f))
        top.addView(preview, LinearLayout.LayoutParams(0, ctx.dp(52), 1f))
        val hex = EditText(ctx)
        hex.setText(ColorUtil.toHex(current))
        hex.isSingleLine = true
        hex.textSize = 15f
        hex.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        top.addView(hex, LinearLayout.LayoutParams(ctx.dp(118), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.dp(12) })
        box.addView(top)

        // swatches, 6 per row
        val swatchViews = ArrayList<Pair<View, Int>>()
        SWATCHES.chunked(6).forEach { rowColors ->
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(0, ctx.dp(10), 0, 0)
            for (c in rowColors) {
                val v = View(ctx)
                v.background = oval(c).apply { setStroke(ctx.dp(1), 0x33000000) }
                row.addView(v, LinearLayout.LayoutParams(0, ctx.dp(34), 1f).apply { setMargins(ctx.dp(5), 0, ctx.dp(5), 0) })
                swatchViews += Pair(v, c)
            }
            box.addView(row)
        }

        fun strip(): View = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(12)).apply {
                setMargins(ctx.dp(8), ctx.dp(18), ctx.dp(8), 0)
            }
        }

        fun bar(max: Int): SeekBar = SeekBar(ctx).apply {
            this.max = max
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val hueStrip = strip()
        hueStrip.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xFFFF0000.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FFFF.toInt(), 0xFF0000FF.toInt(), 0xFFFF00FF.toInt(), 0xFFFF0000.toInt())
        ).apply { cornerRadius = ctx.dpf(6f) }
        val hueBar = bar(360)
        val satStrip = strip()
        val satBar = bar(100)
        val valStrip = strip()
        val valBar = bar(100)
        box.addView(hueStrip)
        box.addView(hueBar)
        box.addView(satStrip)
        box.addView(satBar)
        box.addView(valStrip)
        box.addView(valBar)

        fun sync(fromHex: Boolean) {
            updating = true
            current = Color.HSVToColor(hsv)
            preview.background = roundRect(current, ctx.dpf(14f))
            if (!fromHex) hex.setText(ColorUtil.toHex(current))
            hueBar.progress = hsv[0].toInt()
            satBar.progress = (hsv[1] * 100).toInt()
            valBar.progress = (hsv[2] * 100).toInt()
            satStrip.background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.HSVToColor(floatArrayOf(hsv[0], 0f, hsv[2])), Color.HSVToColor(floatArrayOf(hsv[0], 1f, hsv[2])))
            ).apply { cornerRadius = ctx.dpf(6f) }
            valStrip.background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.BLACK, Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f)))
            ).apply { cornerRadius = ctx.dpf(6f) }
            updating = false
        }

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (updating || !fromUser) return
                when (seekBar) {
                    hueBar -> hsv[0] = progress.toFloat().coerceAtMost(359.9f)
                    satBar -> hsv[1] = progress / 100f
                    valBar -> hsv[2] = progress / 100f
                }
                sync(false)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        hueBar.setOnSeekBarChangeListener(listener)
        satBar.setOnSeekBarChangeListener(listener)
        valBar.setOnSeekBarChangeListener(listener)
        hex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updating) return
                val t = s?.toString() ?: return
                if (t.trim().removePrefix("#").length != 6) return
                val c = ColorUtil.parse(t) ?: return
                Color.colorToHSV(c, hsv)
                sync(true)
            }
        })
        for ((v, c) in swatchViews) {
            v.setOnClickListener {
                Color.colorToHSV(c, hsv)
                sync(false)
            }
        }
        sync(false)

        val sv = ScrollView(ctx)
        sv.addView(box)
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(sv)
            .setPositiveButton("Select") { _, _ -> onPick(current) }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

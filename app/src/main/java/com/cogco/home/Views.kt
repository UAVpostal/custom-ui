package com.cogco.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ---------------------------------------------------------------- drawing helpers

fun roundRect(color: Int, radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }

fun oval(color: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
}

fun Context.glyphView(glyph: Glyph, color: Int, sizeDp: Int = 24): ImageView = ImageView(this).apply {
    setImageDrawable(GlyphDrawable(glyph, color))
    layoutParams = ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp))
}

// ---------------------------------------------------------------- app icon

/** Icon + label, drawn by hand so hundreds of them stay cheap. */
class IconView(context: Context) : View(context) {
    var icon: Bitmap? = null
        set(v) {
            field = v
            invalidate()
        }
    var label: String = ""
        set(v) {
            field = v
            invalidate()
        }
    var showLabel = true
        set(v) {
            field = v
            invalidate()
        }
    var iconSize: Int = dp(56)
        set(v) {
            field = v
            requestLayout()
            invalidate()
        }
    var labelColor: Int = Color.WHITE
        set(v) {
            field = v
            invalidate()
        }
    var labelShadow = true
        set(v) {
            field = v
            invalidate()
        }
    var labelSizeSp = 12f
        set(v) {
            field = v
            invalidate()
        }
    var badgeCount = 0
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }
    var badgeNumbers = false
    var badgeColor = 0xFFFF6B3D.toInt()

    /** Payloads so click handlers know what this icon is. */
    var item: HomeItem? = null
    var app: AppInfo? = null

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
        isFakeBoldText = true
    }
    private val dst = RectF()

    /** Where the icon was last drawn, in view coordinates. */
    val iconRect: RectF get() = dst

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        textPaint.textSize = context.spf(labelSizeSp)
        val fm = textPaint.fontMetrics
        val textH = if (showLabel) (fm.descent - fm.ascent + dpf(6f)).toInt() else 0
        val w = iconSize + dp(12)
        val h = iconSize + textH + dp(12)
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        textPaint.textSize = context.spf(labelSizeSp)
        textPaint.color = labelColor
        if (labelShadow) textPaint.setShadowLayer(dpf(2.5f), 0f, dpf(1f), 0x99000000.toInt())
        else textPaint.clearShadowLayer()
        val fm = textPaint.fontMetrics
        val textH = fm.descent - fm.ascent
        val gap = dpf(6f)
        val hasLabel = showLabel && label.isNotEmpty()
        var size = iconSize.toFloat()
        val needed = size + if (hasLabel) gap + textH else 0f
        if (needed > height - dpf(4f)) size = max(dpf(20f), size - (needed - height + dpf(4f)))
        val content = size + if (hasLabel) gap + textH else 0f
        val top = max(0f, (height - content) / 2f)
        val left = (width - size) / 2f
        dst.set(left, top, left + size, top + size)
        icon?.let { canvas.drawBitmap(it, null, dst, iconPaint) }

        if (badgeCount > 0) {
            badgePaint.color = badgeColor
            if (badgeNumbers) {
                val txt = if (badgeCount > 99) "99+" else badgeCount.toString()
                badgeText.textSize = dpf(10f)
                val h = dpf(17f)
                val w = max(h, badgeText.measureText(txt) + dpf(9f))
                val cx = dst.right - dpf(3f)
                val r = RectF(cx - w / 2f, dst.top - dpf(3f), cx + w / 2f, dst.top - dpf(3f) + h)
                canvas.drawRoundRect(r, h / 2f, h / 2f, badgePaint)
                val bfm = badgeText.fontMetrics
                canvas.drawText(txt, r.centerX(), r.centerY() - (bfm.ascent + bfm.descent) / 2f, badgeText)
            } else {
                val r = dpf(5.5f)
                canvas.drawCircle(dst.right - r * 0.6f, dst.top + r * 0.6f, r, badgePaint)
            }
        }

        if (hasLabel) {
            val text = TextUtils.ellipsize(label, textPaint, width - dpf(4f), TextUtils.TruncateAt.END).toString()
            canvas.drawText(text, width / 2f, dst.bottom + gap - fm.ascent, textPaint)
        }
    }

    override fun setPressed(pressed: Boolean) {
        val changed = pressed != isPressed
        super.setPressed(pressed)
        if (changed) {
            animate().scaleX(if (pressed) 0.9f else 1f).scaleY(if (pressed) 0.9f else 1f).setDuration(110).start()
        }
    }
}

// ---------------------------------------------------------------- grid

/** Fixed grid of cells; children are positioned with [LP]. */
class CellLayout(context: Context, var cols: Int, var rows: Int) : ViewGroup(context) {
    class LP(var cellX: Int, var cellY: Int, var spanX: Int = 1, var spanY: Int = 1) :
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    /** When > 0 the layout is as tall as rows x this (for scrolling lists). */
    var fixedCellHeight = 0
    var cellW = 0
        private set
    var cellH = 0
        private set

    var onEmptyLongPress: (() -> Unit)? = null
    var onEmptyTap: (() -> Unit)? = null
    var outline = false
        set(v) {
            field = v
            invalidate()
        }

    private var highlight: RectF? = null
    private var highlightFolder = false
    private val hlStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(2f)
        color = 0xDDFFFFFF.toInt()
    }
    private val hlFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(1.5f)
        color = 0x99FFFFFF.toInt()
    }
    private val outlineFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1AFFFFFF }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private val longPress = Runnable {
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        moved = true
        onEmptyLongPress?.invoke()
    }

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        cellW = if (cols > 0) (w - paddingLeft - paddingRight) / cols else 0
        val h: Int
        if (fixedCellHeight > 0) {
            cellH = fixedCellHeight
            h = cellH * rows + paddingTop + paddingBottom
        } else {
            h = MeasureSpec.getSize(heightMeasureSpec)
            cellH = if (rows > 0) (h - paddingTop - paddingBottom) / rows else 0
        }
        for (i in 0 until childCount) {
            val ch = getChildAt(i)
            val lp = ch.layoutParams as LP
            ch.measure(
                MeasureSpec.makeMeasureSpec(max(0, cellW * lp.spanX), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(max(0, cellH * lp.spanY), MeasureSpec.EXACTLY)
            )
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val ch = getChildAt(i)
            val lp = ch.layoutParams as LP
            val x = paddingLeft + lp.cellX * cellW
            val y = paddingTop + lp.cellY * cellH
            ch.layout(x, y, x + cellW * lp.spanX, y + cellH * lp.spanY)
        }
    }

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?): Boolean = p is LP
    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams = LP(0, 0)
    override fun generateLayoutParams(p: ViewGroup.LayoutParams?): ViewGroup.LayoutParams = LP(0, 0)

    /** Cell (top-left of a span) under a point in this view's coordinates. */
    fun cellAt(localX: Float, localY: Float, spanX: Int, spanY: Int): Point {
        if (cellW <= 0 || cellH <= 0) return Point(0, 0)
        val cx = floor((localX - paddingLeft) / cellW - (spanX - 1) / 2f).toInt()
        val cy = floor((localY - paddingTop) / cellH - (spanY - 1) / 2f).toInt()
        return Point(cx.coerceIn(0, max(0, cols - spanX)), cy.coerceIn(0, max(0, rows - spanY)))
    }

    fun setHighlight(x: Int, y: Int, sx: Int, sy: Int, folder: Boolean) {
        val l = (paddingLeft + x * cellW).toFloat()
        val t = (paddingTop + y * cellH).toFloat()
        highlight = RectF(l, t, l + sx * cellW, t + sy * cellH)
        highlightFolder = folder
        invalidate()
    }

    fun clearHighlight() {
        if (highlight != null) {
            highlight = null
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (outline) {
            val r = RectF(dpf(2f), dpf(2f), width - dpf(2f), height - dpf(2f))
            canvas.drawRoundRect(r, dpf(24f), dpf(24f), outlineFill)
            canvas.drawRoundRect(r, dpf(24f), dpf(24f), outlinePaint)
        }
        val h = highlight ?: return
        val inset = dpf(6f)
        val r = RectF(h.left + inset, h.top + inset, h.right - inset, h.bottom - inset)
        if (highlightFolder) {
            val rad = min(r.width(), r.height()) / 2f
            canvas.drawCircle(r.centerX(), r.centerY() - dpf(6f), rad, hlFill)
            canvas.drawCircle(r.centerX(), r.centerY() - dpf(6f), rad, hlStroke)
        } else {
            canvas.drawRoundRect(r, dpf(18f), dpf(18f), hlFill)
            canvas.drawRoundRect(r, dpf(18f), dpf(18f), hlStroke)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onEmptyLongPress == null && onEmptyTap == null) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                moved = false
                removeCallbacks(longPress)
                if (onEmptyLongPress != null) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(event.x - downX, event.y - downY) > slop) {
                    moved = true
                    removeCallbacks(longPress)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (!moved) onEmptyTap?.invoke()
            }
            MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        return true
    }
}

// ---------------------------------------------------------------- horizontal pager

open class PagedView(context: Context) : ViewGroup(context) {
    var currentPage = 0
        private set
    var onScroll: ((Float) -> Unit)? = null
    var onPageChanged: ((Int) -> Unit)? = null
    var swipeEnabled = true
    val pageCount: Int get() = childCount

    private val scroller = OverScroller(context, DecelerateInterpolator(2f))
    private var vt: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var floatScroll = 0f
    private var dragging = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val cw = MeasureSpec.makeMeasureSpec(max(0, w - paddingLeft - paddingRight), MeasureSpec.EXACTLY)
        val ch = MeasureSpec.makeMeasureSpec(max(0, h - paddingTop - paddingBottom), MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(cw, ch)
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val left = i * w + paddingLeft
            c.layout(left, paddingTop, left + c.measuredWidth, paddingTop + c.measuredHeight)
        }
        if (currentPage >= childCount) currentPage = max(0, childCount - 1)
        if (scroller.isFinished && !dragging) scrollTo(currentPage * w, 0)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, 0)
            postInvalidateOnAnimation()
        }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (width > 0) onScroll?.invoke(l.toFloat() / width)
    }

    fun position(): Float = if (width > 0) scrollX.toFloat() / width else currentPage.toFloat()

    fun snapTo(page: Int, animate: Boolean = true) {
        val p = page.coerceIn(0, max(0, childCount - 1))
        val changed = p != currentPage
        currentPage = p
        if (width == 0) {
            if (changed) onPageChanged?.invoke(p)
            return
        }
        val target = p * width
        scroller.forceFinished(true)
        if (animate) {
            val dx = target - scrollX
            val dur = (220 + abs(dx) * 180 / max(1, width)).coerceAtMost(420)
            scroller.startScroll(scrollX, 0, dx, 0, dur)
            postInvalidateOnAnimation()
        } else {
            scrollTo(target, 0)
        }
        if (changed) onPageChanged?.invoke(p)
    }

    private fun startDrag(x: Float) {
        dragging = true
        lastX = x
        floatScroll = scrollX.toFloat()
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!swipeEnabled || childCount < 2) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastX = ev.x
                dragging = false
                if (!scroller.isFinished) {
                    scroller.forceFinished(true)
                    startDrag(ev.x)
                }
            }
            MotionEvent.ACTION_MOVE -> if (!dragging) {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dx) > slop && abs(dx) > abs(dy)) startDrag(ev.x)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!swipeEnabled || childCount < 2) return false
        val tracker = vt ?: VelocityTracker.obtain().also { vt = it }
        tracker.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastX = ev.x
                if (!scroller.isFinished) {
                    scroller.forceFinished(true)
                    startDrag(ev.x)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dx) > slop && abs(dx) > abs(dy)) startDrag(ev.x)
                }
                if (dragging) {
                    var delta = lastX - ev.x
                    lastX = ev.x
                    val maxScroll = (childCount - 1) * width
                    if ((floatScroll < 0 && delta < 0) || (floatScroll > maxScroll && delta > 0)) delta *= 0.35f
                    floatScroll += delta
                    scrollTo(floatScroll.roundToInt(), 0)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging && width > 0) {
                    tracker.computeCurrentVelocity(1000)
                    val v = tracker.xVelocity
                    val cur = scrollX.toFloat() / width
                    val target = when {
                        v < -minFling * 6 -> floor(cur).toInt() + 1
                        v > minFling * 6 -> ceil(cur).toInt() - 1
                        else -> cur.roundToInt()
                    }
                    snapTo(target)
                }
                dragging = false
                vt?.recycle()
                vt = null
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging && width > 0) snapTo((scrollX.toFloat() / width).roundToInt())
                dragging = false
                vt?.recycle()
                vt = null
            }
        }
        return true
    }
}

class PageIndicator(context: Context) : View(context) {
    var count = 1
        set(v) {
            field = v
            invalidate()
        }
    var position = 0f
        set(v) {
            field = v
            invalidate()
        }
    var color = Color.WHITE
        set(v) {
            field = v
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        if (count <= 1) return
        val r = dpf(3.2f)
        val gap = dpf(12f)
        val total = (count - 1) * gap
        val start = width / 2f - total / 2f
        for (i in 0 until count) {
            val d = abs(i - position).coerceAtMost(1f)
            paint.color = ColorUtil.withAlpha(color, (255 - 160 * d).toInt())
            canvas.drawCircle(start + i * gap, height / 2f, r * (1.25f - 0.25f * d), paint)
        }
    }
}

// ---------------------------------------------------------------- touch: click / long press / drag

/**
 * Click, long press (shows a popup) and "keep moving after long press" (starts a drag),
 * the same way One UI Home handles icons.
 */
class ItemGesture(
    private val view: View,
    private val pressFeedback: Boolean,
    private val onClick: () -> Unit,
    private val onLongPress: () -> Unit,
    private val onDragStart: (() -> Unit)?
) {
    var longPressed = false
        private set
    private var dragStarted = false
    private var tracking = false
    private var downX = 0f
    private var downY = 0f
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
    private val lp = Runnable {
        longPressed = true
        view.parent?.requestDisallowInterceptTouchEvent(true)
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (pressFeedback) view.isPressed = false
        onLongPress()
    }

    fun onTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.removeCallbacks(lp)
                downX = ev.rawX
                downY = ev.rawY
                longPressed = false
                dragStarted = false
                tracking = true
                if (pressFeedback) view.isPressed = true
                view.postDelayed(lp, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return true
                val d = hypot(ev.rawX - downX, ev.rawY - downY)
                if (!longPressed) {
                    if (d > slop) {
                        view.removeCallbacks(lp)
                        if (pressFeedback) view.isPressed = false
                        tracking = false
                    }
                } else if (!dragStarted && d > slop * 1.5f) {
                    val start = onDragStart
                    if (start != null) {
                        dragStarted = true
                        start()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                view.removeCallbacks(lp)
                val click = tracking && !longPressed
                if (pressFeedback) view.isPressed = false
                tracking = false
                if (click) onClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                view.removeCallbacks(lp)
                if (pressFeedback) view.isPressed = false
                tracking = false
            }
        }
        return true
    }
}

class IconShadow(private val bmp: Bitmap, private val sizePx: Int) : View.DragShadowBuilder() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
        outShadowSize.set(sizePx, sizePx)
        outShadowTouchPoint.set(sizePx / 2, sizePx / 2)
    }

    override fun onDrawShadow(canvas: Canvas) {
        canvas.drawBitmap(bmp, null, Rect(0, 0, sizePx, sizePx), paint)
    }
}

// ---------------------------------------------------------------- edge panel bits

/** Tool tile: glyph (or big number) + label on a rounded square. */
class TileView(context: Context) : View(context) {
    var glyph: Glyph? = null
        set(v) {
            field = v
            drawable = v?.let { GlyphDrawable(it, fg) }
            invalidate()
        }
    var bigText: String? = null
        set(v) {
            field = v
            invalidate()
        }
    var label = ""
        set(v) {
            field = v
            invalidate()
        }
    var active = false
        set(v) {
            field = v
            invalidate()
        }
    var fg = Color.WHITE
    var accent = 0xFF3E91FF.toInt()
    var idleBg = 0x22FFFFFF
    var glyphRotation = 0f
        set(v) {
            field = v
            drawable?.rotationDeg = v
            invalidate()
        }
    private var drawable: GlyphDrawable? = null
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val big = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    override fun onDraw(canvas: Canvas) {
        val inset = dpf(4f)
        val r = RectF(inset, inset, width - inset, height - inset)
        bg.color = if (active) accent else idleBg
        canvas.drawRoundRect(r, dpf(18f), dpf(18f), bg)
        val c = if (active) ColorUtil.onColor(accent) else fg
        val gs = dp(26)
        val gy = (r.top + r.height() * 0.38f).toInt()
        val bt = bigText
        if (bt != null) {
            big.color = c
            big.textSize = context.spf(20f)
            canvas.drawText(bt, r.centerX(), gy + big.textSize / 3f, big)
        } else {
            drawable?.let {
                it.setColor(c)
                it.setBounds((r.centerX() - gs / 2f).toInt(), gy - gs / 2, (r.centerX() + gs / 2f).toInt(), gy + gs / 2)
                it.draw(canvas)
            }
        }
        text.color = ColorUtil.withAlpha(c, 230)
        text.textSize = context.spf(11f)
        val t = TextUtils.ellipsize(label, text, r.width() - dpf(8f), TextUtils.TruncateAt.END).toString()
        canvas.drawText(t, r.centerX(), r.bottom - dpf(12f), text)
    }

    override fun setPressed(pressed: Boolean) {
        val changed = pressed != isPressed
        super.setPressed(pressed)
        if (changed) animate().scaleX(if (pressed) 0.93f else 1f).scaleY(if (pressed) 0.93f else 1f).setDuration(100).start()
    }
}

/** Circle with initials (or a glyph). */
class AvatarView(context: Context, private val initials: String, private val bgColor: Int, private val fgColor: Int, private val glyph: Glyph? = null) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val g = glyph?.let { GlyphDrawable(it, fgColor) }

    override fun onDraw(canvas: Canvas) {
        val r = min(width, height) / 2f
        paint.color = bgColor
        canvas.drawCircle(width / 2f, height / 2f, r, paint)
        val gd = g
        if (gd != null) {
            val s = (r * 1.1f).toInt()
            gd.setBounds(width / 2 - s / 2, height / 2 - s / 2, width / 2 + s / 2, height / 2 + s / 2)
            gd.draw(canvas)
        } else {
            text.color = fgColor
            text.textSize = r * 0.8f
            val fm = text.fontMetrics
            canvas.drawText(initials, width / 2f, height / 2f - (fm.ascent + fm.descent) / 2f, text)
        }
    }
}

/** Rounded, gradient-filled card whose colours can be blended live while paging. */
class PanelCard(context: Context) : FrameLayout(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var topColor = Color.WHITE
    private var bottomColor = Color.WHITE
    var radius = dpf(28f)
        private set

    init {
        setWillNotDraw(false)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        clipToOutline = true
    }

    fun setColors(top: Int, bottom: Int, radiusPx: Float) {
        topColor = top
        bottomColor = bottom
        if (radiusPx != radius) {
            radius = radiusPx
            invalidateOutline()
        }
        updateShader()
        invalidate()
    }

    private fun updateShader() {
        paint.shader = if (height > 0) LinearGradient(0f, 0f, 0f, height.toFloat(), topColor, bottomColor, Shader.TileMode.CLAMP) else null
        if (height == 0) paint.color = topColor
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateShader()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, paint)
    }
}

/** Small mock panel used in the theme gallery and editor. */
class SchemePreview(context: Context) : View(context) {
    var scheme: PanelScheme? = null
        set(v) {
            field = v
            invalidate()
        }
    var chosen = false
        set(v) {
            field = v
            invalidate()
        }
    var ringColor = 0xFF3E91FF.toInt()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    override fun onDraw(canvas: Canvas) {
        val s = scheme ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val outer = RectF(dpf(3f), dpf(3f), w - dpf(3f), h - dpf(3f))
        // pretend wallpaper behind the panel
        p.shader = LinearGradient(0f, 0f, w, h, 0xFF6A7FDB.toInt(), 0xFF2B2F45.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(outer, dpf(18f), dpf(18f), p)
        p.shader = null
        // the panel
        val pw = outer.width() * 0.46f
        val panel = RectF(outer.right - pw - dpf(10f), outer.top + dpf(12f), outer.right - dpf(10f), outer.bottom - dpf(12f))
        val rad = min(dpf(s.radius.toFloat()) * 0.55f, pw / 2f)
        p.shader = LinearGradient(0f, panel.top, 0f, panel.bottom, s.topArgb(), s.bottomArgb(), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(panel, rad, rad, p)
        p.shader = null
        // fake icons
        val cell = panel.width() / 2f
        val ir = cell * 0.26f
        for (row in 0..2) for (col in 0..1) {
            val cx = panel.left + cell * (col + 0.5f)
            val cy = panel.top + dpf(16f) + row * cell * 1.05f + ir
            if (cy + ir > panel.bottom - dpf(8f)) continue
            p.color = if ((row + col) % 2 == 0) s.accent else ColorUtil.withAlpha(s.text, 160)
            canvas.drawCircle(cx, cy, ir, p)
        }
        // handle
        p.color = ColorUtil.withAlpha(Color.WHITE, 150)
        canvas.drawRoundRect(RectF(outer.right - dpf(5f), outer.centerY() - dpf(16f), outer.right - dpf(2f), outer.centerY() + dpf(16f)), dpf(2f), dpf(2f), p)
        if (chosen) {
            ring.color = ringColor
            ring.strokeWidth = dpf(3f)
            canvas.drawRoundRect(RectF(dpf(1.5f), dpf(1.5f), w - dpf(1.5f), h - dpf(1.5f)), dpf(19f), dpf(19f), ring)
        }
    }
}

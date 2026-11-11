package com.cogco.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Line icons drawn in code (24x24 grid), so they can be tinted with any panel colour scheme. */
enum class Glyph {
    SEARCH, SETTINGS, WALLPAPER, WIDGETS, EDGE, APPS, CLOSE, TRASH, INFO, HIDE, EYE, EDIT, CHECK, PLUS,
    FLASHLIGHT, COMPASS, COUNTER, CALCULATOR, CAMERA, ALARM, WIFI, VOLUME, BLUETOOTH, PERSON, CALL,
    MESSAGE, NOTE, PALETTE, ARROW_UP, ARROW_DOWN, BACK, HOME, MORE, RESIZE, COPY, GEAR_SMALL
}

class GlyphDrawable(private val glyph: Glyph, color: Int) : Drawable() {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }

    /** Used by the compass tile. */
    var rotationDeg = 0f
        set(v) {
            field = v
            invalidateSelf()
        }

    fun setColor(c: Int) {
        stroke.color = c
        fill.color = c
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val s = min(b.width(), b.height()) / 24f
        canvas.save()
        canvas.translate(b.left + (b.width() - 24f * s) / 2f, b.top + (b.height() - 24f * s) / 2f)
        canvas.scale(s, s)
        if (rotationDeg != 0f) canvas.rotate(rotationDeg, 12f, 12f)
        Glyphs.draw(glyph, canvas, stroke, fill)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        stroke.alpha = alpha
        fill.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        stroke.colorFilter = colorFilter
        fill.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

object Glyphs {
    /** A glyph on a filled circle, as a bitmap (used for "+" tiles etc). */
    fun bitmap(glyph: Glyph, fg: Int, bg: Int, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (bg != 0) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = bg
            c.drawCircle(size / 2f, size / 2f, size / 2f, p)
        }
        val d = GlyphDrawable(glyph, fg)
        val inset = size / 4
        d.setBounds(inset, inset, size - inset, size - inset)
        d.draw(c)
        return bmp
    }

    private fun poly(c: Canvas, p: Paint, vararg pts: Float, close: Boolean = false) {
        val path = Path()
        path.moveTo(pts[0], pts[1])
        var i = 2
        while (i + 1 < pts.size) {
            path.lineTo(pts[i], pts[i + 1])
            i += 2
        }
        if (close) path.close()
        c.drawPath(path, p)
    }

    private fun rr(c: Canvas, p: Paint, l: Float, t: Float, r: Float, b: Float, rad: Float) {
        c.drawRoundRect(RectF(l, t, r, b), rad, rad, p)
    }

    fun draw(g: Glyph, c: Canvas, s: Paint, f: Paint) {
        when (g) {
            Glyph.SEARCH -> {
                c.drawCircle(10.5f, 10.5f, 6.2f, s)
                c.drawLine(15.2f, 15.2f, 20f, 20f, s)
            }
            Glyph.SETTINGS, Glyph.GEAR_SMALL -> {
                c.drawCircle(12f, 12f, 3f, s)
                c.drawCircle(12f, 12f, 6.6f, s)
                val old = s.strokeWidth
                s.strokeWidth = 2.6f
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    c.drawLine(
                        12f + 7.4f * cos(a).toFloat(), 12f + 7.4f * sin(a).toFloat(),
                        12f + 9.4f * cos(a).toFloat(), 12f + 9.4f * sin(a).toFloat(), s
                    )
                }
                s.strokeWidth = old
            }
            Glyph.WALLPAPER -> {
                rr(c, s, 3f, 4f, 21f, 20f, 3f)
                poly(c, s, 6f, 17f, 10f, 12.5f, 13f, 15.5f, 15f, 13.5f, 18.5f, 17f)
                c.drawCircle(15.5f, 8.5f, 1.4f, f)
            }
            Glyph.WIDGETS -> {
                rr(c, s, 4f, 4f, 10.5f, 10.5f, 2f)
                rr(c, s, 13.5f, 4f, 20f, 10.5f, 2f)
                rr(c, s, 4f, 13.5f, 10.5f, 20f, 2f)
                rr(c, s, 13.5f, 13.5f, 20f, 20f, 2f)
            }
            Glyph.EDGE -> {
                rr(c, s, 4f, 3f, 15f, 21f, 2.5f)
                val old = s.strokeWidth
                s.strokeWidth = 2.6f
                c.drawLine(19f, 7f, 19f, 17f, s)
                s.strokeWidth = old
            }
            Glyph.APPS -> {
                for (x in 0..2) for (y in 0..2) c.drawCircle(6f + x * 6f, 6f + y * 6f, 1.6f, f)
            }
            Glyph.CLOSE -> {
                c.drawLine(6.5f, 6.5f, 17.5f, 17.5f, s)
                c.drawLine(17.5f, 6.5f, 6.5f, 17.5f, s)
            }
            Glyph.TRASH -> {
                c.drawLine(4f, 6.5f, 20f, 6.5f, s)
                poly(c, s, 9f, 6.5f, 9f, 4f, 15f, 4f, 15f, 6.5f)
                poly(c, s, 6f, 6.5f, 7f, 20f, 17f, 20f, 18f, 6.5f)
                c.drawLine(10f, 10f, 10f, 16.5f, s)
                c.drawLine(14f, 10f, 14f, 16.5f, s)
            }
            Glyph.INFO -> {
                c.drawCircle(12f, 12f, 9f, s)
                c.drawLine(12f, 11f, 12f, 16.5f, s)
                c.drawCircle(12f, 7.8f, 1.2f, f)
            }
            Glyph.HIDE, Glyph.EYE -> {
                val p = Path()
                p.moveTo(2.5f, 12f)
                p.quadTo(12f, 3f, 21.5f, 12f)
                p.quadTo(12f, 21f, 2.5f, 12f)
                p.close()
                c.drawPath(p, s)
                c.drawCircle(12f, 12f, 3f, s)
                if (g == Glyph.HIDE) c.drawLine(4f, 4f, 20f, 20f, s)
            }
            Glyph.EDIT -> {
                poly(c, s, 4f, 20f, 4f, 16.5f, 15.5f, 5f, 19f, 8.5f, 7.5f, 20f, close = true)
                c.drawLine(13f, 7.5f, 16.5f, 11f, s)
            }
            Glyph.CHECK -> poly(c, s, 5f, 12.5f, 10f, 17.5f, 19f, 7f)
            Glyph.PLUS -> {
                c.drawLine(12f, 5f, 12f, 19f, s)
                c.drawLine(5f, 12f, 19f, 12f, s)
            }
            Glyph.FLASHLIGHT -> {
                poly(c, s, 7f, 3f, 17f, 3f, 17f, 7f, 14.5f, 11f, 14.5f, 21f, 9.5f, 21f, 9.5f, 11f, 7f, 7f, close = true)
                c.drawLine(7f, 7f, 17f, 7f, s)
                c.drawCircle(12f, 14.5f, 1f, f)
            }
            Glyph.COMPASS -> {
                c.drawCircle(12f, 12f, 9f, s)
                val up = Path()
                up.moveTo(12f, 5.5f)
                up.lineTo(14.2f, 12f)
                up.lineTo(9.8f, 12f)
                up.close()
                c.drawPath(up, f)
                poly(c, s, 9.8f, 12f, 12f, 18.5f, 14.2f, 12f)
            }
            Glyph.COUNTER -> {
                rr(c, s, 3f, 6f, 21f, 18f, 3f)
                c.drawLine(6.5f, 12f, 11f, 12f, s)
                c.drawLine(8.75f, 9.75f, 8.75f, 14.25f, s)
                c.drawLine(15.5f, 9f, 15.5f, 15f, s)
                c.drawLine(14f, 10.3f, 15.5f, 9f, s)
            }
            Glyph.CALCULATOR -> {
                rr(c, s, 5f, 3f, 19f, 21f, 2.5f)
                rr(c, s, 8f, 6f, 16f, 9.5f, 1f)
                for (x in 0..2) for (y in 0..1) c.drawCircle(9f + x * 3f, 13.5f + y * 3.8f, 1f, f)
            }
            Glyph.CAMERA -> {
                rr(c, s, 3f, 7f, 21f, 19.5f, 2.5f)
                poly(c, s, 8.5f, 7f, 10f, 4.5f, 14f, 4.5f, 15.5f, 7f)
                c.drawCircle(12f, 13f, 3.5f, s)
            }
            Glyph.ALARM -> {
                c.drawCircle(12f, 13f, 7.5f, s)
                poly(c, s, 12f, 9.5f, 12f, 13f, 14.5f, 15f)
                c.drawLine(4f, 6f, 7f, 3.5f, s)
                c.drawLine(20f, 6f, 17f, 3.5f, s)
            }
            Glyph.WIFI -> {
                for (r in listOf(5f, 10f, 15f)) {
                    c.drawArc(RectF(12f - r, 19.5f - r, 12f + r, 19.5f + r), 225f, 90f, false, s)
                }
                c.drawCircle(12f, 19.5f, 1.4f, f)
            }
            Glyph.VOLUME -> {
                poly(c, s, 4f, 9.5f, 8f, 9.5f, 12.5f, 5.5f, 12.5f, 18.5f, 8f, 14.5f, 4f, 14.5f, close = true)
                c.drawArc(RectF(10.5f, 8f, 18.5f, 16f), -50f, 100f, false, s)
                c.drawArc(RectF(8f, 4.5f, 23f, 19.5f), -50f, 100f, false, s)
            }
            Glyph.BLUETOOTH -> poly(c, s, 7f, 7.5f, 17f, 16.5f, 12f, 21f, 12f, 3f, 17f, 7.5f, 7f, 16.5f)
            Glyph.PERSON -> {
                c.drawCircle(12f, 8f, 3.8f, s)
                val p = Path()
                p.moveTo(4.5f, 20.5f)
                p.quadTo(12f, 10.5f, 19.5f, 20.5f)
                c.drawPath(p, s)
            }
            Glyph.CALL -> {
                val p = Path()
                p.moveTo(6.5f, 3.5f)
                p.lineTo(9.5f, 3.5f)
                p.lineTo(11f, 8f)
                p.lineTo(9f, 9.5f)
                p.cubicTo(10f, 12.5f, 11.5f, 14f, 14.5f, 15f)
                p.lineTo(16f, 13f)
                p.lineTo(20.5f, 14.5f)
                p.lineTo(20.5f, 17.5f)
                p.cubicTo(20.5f, 19f, 19.2f, 20.5f, 17.5f, 20.3f)
                p.cubicTo(10f, 19.5f, 4.5f, 14f, 3.7f, 6.5f)
                p.cubicTo(3.5f, 4.8f, 5f, 3.5f, 6.5f, 3.5f)
                p.close()
                c.drawPath(p, s)
            }
            Glyph.MESSAGE -> {
                rr(c, s, 3f, 4f, 21f, 17f, 4f)
                poly(c, s, 8f, 17f, 7f, 21f, 12.5f, 17f)
            }
            Glyph.NOTE -> {
                rr(c, s, 5f, 3f, 19f, 21f, 2.5f)
                c.drawLine(8.5f, 8f, 15.5f, 8f, s)
                c.drawLine(8.5f, 12f, 15.5f, 12f, s)
                c.drawLine(8.5f, 16f, 13f, 16f, s)
            }
            Glyph.PALETTE -> {
                c.drawCircle(12f, 12f, 9f, s)
                c.drawCircle(8f, 10.5f, 1.4f, f)
                c.drawCircle(11f, 7.2f, 1.4f, f)
                c.drawCircle(15.2f, 8.2f, 1.4f, f)
                c.drawCircle(16.5f, 12.5f, 1.4f, f)
            }
            Glyph.ARROW_UP -> {
                c.drawLine(12f, 19f, 12f, 5f, s)
                poly(c, s, 6.5f, 10.5f, 12f, 5f, 17.5f, 10.5f)
            }
            Glyph.ARROW_DOWN -> {
                c.drawLine(12f, 5f, 12f, 19f, s)
                poly(c, s, 6.5f, 13.5f, 12f, 19f, 17.5f, 13.5f)
            }
            Glyph.BACK -> poly(c, s, 15f, 5f, 8f, 12f, 15f, 19f)
            Glyph.HOME -> {
                poly(c, s, 3.5f, 11.5f, 12f, 4f, 20.5f, 11.5f)
                poly(c, s, 6.5f, 9.5f, 6.5f, 20f, 17.5f, 20f, 17.5f, 9.5f)
            }
            Glyph.MORE -> {
                c.drawCircle(12f, 5.5f, 1.6f, f)
                c.drawCircle(12f, 12f, 1.6f, f)
                c.drawCircle(12f, 18.5f, 1.6f, f)
            }
            Glyph.RESIZE -> {
                poly(c, s, 14f, 4f, 20f, 4f, 20f, 10f)
                poly(c, s, 10f, 20f, 4f, 20f, 4f, 14f)
                c.drawLine(20f, 4f, 13.5f, 10.5f, s)
                c.drawLine(4f, 20f, 10.5f, 13.5f, s)
            }
            Glyph.COPY -> {
                rr(c, s, 8f, 8f, 20f, 20f, 2.5f)
                poly(c, s, 16f, 5f, 16f, 4f, 4f, 4f, 4f, 16f, 5f, 16f)
            }
        }
    }
}

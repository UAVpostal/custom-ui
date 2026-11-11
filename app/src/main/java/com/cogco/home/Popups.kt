package com.cogco.home

import android.app.Activity
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import kotlin.math.max

class PopupAction(val glyph: Glyph, val label: String, val run: () -> Unit)

/** One UI-style long-press bubble: app shortcuts on top, a row of actions underneath. */
object ItemPopup {
    fun show(
        activity: Activity,
        anchor: View,
        shortcuts: List<ShortcutInfo>,
        actions: List<PopupAction>,
        onShortcut: (ShortcutInfo) -> Unit
    ): PopupWindow {
        val night = activity.isNight()
        val bg = if (night) 0xF5262628.toInt() else 0xFAFFFFFF.toInt()
        val fg = if (night) 0xFFF2F2F4.toInt() else 0xFF1B1B1D.toInt()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(bg, activity.dpf(22f))
            setPadding(activity.dp(6), activity.dp(6), activity.dp(6), activity.dp(4))
        }
        lateinit var popup: PopupWindow
        val la = activity.getSystemService(LauncherApps::class.java)
        val density = activity.resources.displayMetrics.densityDpi

        for (si in shortcuts) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(activity.dp(12), activity.dp(9), activity.dp(12), activity.dp(9))
            }
            val iv = ImageView(activity)
            row.addView(iv, LinearLayout.LayoutParams(activity.dp(28), activity.dp(28)))
            Async.io {
                val d: Drawable? = try {
                    la?.getShortcutIconDrawable(si, density)
                } catch (e: Exception) {
                    null
                }
                Async.ui { iv.setImageDrawable(d) }
            }
            row.addView(TextView(activity).apply {
                text = (si.shortLabel ?: si.longLabel ?: "").toString()
                setTextColor(fg)
                textSize = 14f
                maxLines = 1
                setPadding(activity.dp(12), 0, 0, 0)
            })
            row.setOnClickListener {
                popup.dismiss()
                onShortcut(si)
            }
            content.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (shortcuts.isNotEmpty() && actions.isNotEmpty()) {
            content.addView(View(activity).apply { setBackgroundColor(ColorUtil.withAlpha(fg, 0x22)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(1)).apply {
                    setMargins(activity.dp(10), activity.dp(4), activity.dp(10), activity.dp(4))
                })
        }
        if (actions.isNotEmpty()) {
            val bar = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            for (a in actions) {
                val b = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(activity.dp(4), activity.dp(8), activity.dp(4), activity.dp(6))
                    background = roundRect(Color.TRANSPARENT, activity.dpf(14f))
                }
                b.addView(activity.glyphView(a.glyph, fg, 22))
                b.addView(TextView(activity).apply {
                    text = a.label
                    setTextColor(fg)
                    textSize = 11f
                    gravity = Gravity.CENTER
                    maxLines = 2
                    setPadding(0, activity.dp(5), 0, 0)
                })
                b.setOnClickListener {
                    popup.dismiss()
                    a.run()
                }
                bar.addView(b, LinearLayout.LayoutParams(activity.dp(68), ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            content.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        content.measure(unspecified, unspecified)
        val screen = activity.screenSize()
        val w = max(content.measuredWidth, activity.dp(220)).coerceAtMost(screen.x - activity.dp(16))
        content.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), unspecified)
        val h = content.measuredHeight

        popup = PopupWindow(content, w, h, true)
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.isOutsideTouchable = true
        popup.elevation = activity.dpf(12f)

        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val iconTop = if (anchor is IconView) loc[1] + anchor.iconRect.top.toInt() else loc[1]
        var x = loc[0] + anchor.width / 2 - w / 2
        x = x.coerceIn(activity.dp(8), max(activity.dp(8), screen.x - w - activity.dp(8)))
        var y = iconTop - h - activity.dp(6)
        if (y < activity.dp(36)) y = loc[1] + anchor.height + activity.dp(4)
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        return popup
    }
}

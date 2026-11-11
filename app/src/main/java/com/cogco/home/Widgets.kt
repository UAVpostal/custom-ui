package com.cogco.home

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Bundle
import android.view.MotionEvent

class WidgetHost(context: Context) : AppWidgetHost(context, HOST_ID) {
    override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
        WidgetView(context)

    companion object {
        const val HOST_ID = 0x0C06C0
    }
}

/** Hosted widget that also supports the launcher's long-press → popup → drag gesture. */
class WidgetView(context: Context) : AppWidgetHostView(context) {
    var gesture: ItemGesture? = null
    private var lastW = 0
    private var lastH = 0

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        val g = gesture ?: return false
        g.onTouch(ev)
        return g.longPressed
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val g = gesture ?: return super.onTouchEvent(ev)
        return g.onTouch(ev)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == lastW && h == lastH || w == 0 || h == 0) return
        lastW = w
        lastH = h
        val d = resources.displayMetrics.density
        val wd = (w / d).toInt()
        val hd = (h / d).toInt()
        post {
            try {
                @Suppress("DEPRECATION")
                updateAppWidgetSize(Bundle(), wd, hd, wd, hd)
            } catch (e: Exception) {
                // provider may have gone away
            }
        }
    }
}

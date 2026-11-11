package com.cogco.home

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** The Apps screen: alphabetical grid (paged like One UI, or a vertical list) with a search bar. */
@SuppressLint("ViewConstructor")
class AppDrawerView(private val home: HomeActivity) : FrameLayout(home) {
    private val prefs = home.app.prefs
    private val icons = home.app.icons

    private val bg = View(home)
    private val content = LinearLayout(home)
    private val searchBar = LinearLayout(home)
    private val search = EditText(home)
    private val searchIcon = ImageView(home)
    private val moreButton = ImageView(home)
    private val body = FrameLayout(home)
    private val pager = PagedView(home)
    private val indicator = PageIndicator(home)
    private val vScroll = ScrollView(home)
    private val vGrid = CellLayout(home, 4, 1)
    private val rScroll = ScrollView(home)
    private val rGrid = CellLayout(home, 4, 1)

    private var apps: List<AppInfo> = emptyList()
    private var results: List<AppInfo> = emptyList()
    private val allIcons = ArrayList<IconView>()
    private var fg = Color.WHITE

    private val slop = ViewConfiguration.get(home).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var vt: VelocityTracker? = null

    init {
        addView(bg, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        content.orientation = LinearLayout.VERTICAL

        searchBar.orientation = LinearLayout.HORIZONTAL
        searchBar.gravity = Gravity.CENTER_VERTICAL
        searchBar.setPadding(dp(16), 0, dp(6), 0)
        searchBar.addView(searchIcon, LinearLayout.LayoutParams(dp(20), dp(20)))
        search.hint = "Search"
        search.isSingleLine = true
        search.background = null
        search.textSize = 16f
        search.imeOptions = EditorInfo.IME_ACTION_SEARCH
        search.setPadding(dp(10), 0, dp(10), 0)
        searchBar.addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        moreButton.setPadding(dp(10), dp(10), dp(10), dp(10))
        searchBar.addView(moreButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        content.addView(searchBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
            setMargins(dp(16), dp(6), dp(16), dp(12))
        })

        pager.onScroll = { indicator.position = it }
        body.addView(pager, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        vScroll.addView(vGrid, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        vScroll.isVerticalScrollBarEnabled = false
        body.addView(vScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        rScroll.addView(rGrid, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        rScroll.visibility = View.GONE
        body.addView(rScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        content.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        content.addView(indicator, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26)))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filter(s?.toString() ?: "")
            }
        })
        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_GO) {
                results.firstOrNull()?.let { home.launchApp(it.component, null) }
                true
            } else false
        }
        moreButton.setOnClickListener { showMenu() }
        applyColors()
    }

    fun applyInsets(top: Int, bottom: Int) {
        content.setPadding(0, top + dp(6), 0, bottom + dp(6))
    }

    fun setApps(list: List<AppInfo>) {
        apps = list
        rebuild()
    }

    private fun applyColors() {
        val light = prefs.drawerBg == "light"
        fg = if (light) 0xFF1B1B1D.toInt() else Color.WHITE
        bg.setBackgroundColor(if (light) 0xF2F4F4F6.toInt() else 0xE6101012.toInt())
        searchBar.background = roundRect(ColorUtil.withAlpha(fg, 0x1F), dpf(25f))
        search.setTextColor(fg)
        search.setHintTextColor(ColorUtil.withAlpha(fg, 0x99))
        searchIcon.setImageDrawable(GlyphDrawable(Glyph.SEARCH, ColorUtil.withAlpha(fg, 0xCC)))
        moreButton.setImageDrawable(GlyphDrawable(Glyph.MORE, fg))
        indicator.color = fg
    }

    fun rebuild() {
        applyColors()
        allIcons.clear()
        val cols = prefs.appsCols.coerceIn(3, 7)
        val rows = prefs.appsRows.coerceIn(3, 8)
        val cellH = home.iconSizePx() + dp(44)
        if (prefs.drawerLayout == "vertical") {
            pager.removeAllViews()
            vGrid.cols = cols
            vGrid.rows = max(1, ceil(apps.size / cols.toFloat()).toInt())
            vGrid.fixedCellHeight = cellH
            vGrid.removeAllViews()
            vGrid.setPadding(dp(8), 0, dp(8), dp(16))
            apps.forEachIndexed { i, a -> vGrid.addView(makeIcon(a), CellLayout.LP(i % cols, i / cols)) }
            vGrid.requestLayout()
            indicator.count = 1
        } else {
            vGrid.removeAllViews()
            pager.removeAllViews()
            val per = cols * rows
            val pages = max(1, ceil(apps.size / per.toFloat()).toInt())
            for (p in 0 until pages) {
                val cl = CellLayout(home, cols, rows)
                cl.setPadding(dp(8), 0, dp(8), 0)
                for (i in 0 until per) {
                    val idx = p * per + i
                    if (idx >= apps.size) break
                    cl.addView(makeIcon(apps[idx]), CellLayout.LP(i % cols, i / cols))
                }
                pager.addView(cl)
            }
            indicator.count = pages
            if (pager.currentPage >= pages) pager.snapTo(0, false)
        }
        rGrid.cols = cols
        rGrid.fixedCellHeight = cellH
        filter(search.text?.toString() ?: "")
    }

    private fun showMain() {
        rScroll.visibility = View.GONE
        val vertical = prefs.drawerLayout == "vertical"
        pager.visibility = if (vertical) View.GONE else View.VISIBLE
        vScroll.visibility = if (vertical) View.VISIBLE else View.GONE
        indicator.visibility = if (vertical) View.INVISIBLE else View.VISIBLE
    }

    private fun filter(q: String) {
        val t = q.trim().lowercase()
        if (t.isEmpty()) {
            results = emptyList()
            showMain()
            return
        }
        results = apps.filter { it.label.lowercase().contains(t) }
            .sortedBy { if (it.label.lowercase().startsWith(t)) 0 else 1 }
        val cols = rGrid.cols
        rGrid.rows = max(1, ceil(results.size / cols.toFloat()).toInt())
        rGrid.removeAllViews()
        rGrid.setPadding(dp(8), 0, dp(8), dp(16))
        results.forEachIndexed { i, a -> rGrid.addView(makeIcon(a, track = false), CellLayout.LP(i % cols, i / cols)) }
        rGrid.requestLayout()
        pager.visibility = View.GONE
        vScroll.visibility = View.GONE
        indicator.visibility = View.INVISIBLE
        rScroll.visibility = View.VISIBLE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeIcon(a: AppInfo, track: Boolean = true): IconView {
        val iv = IconView(home)
        iv.app = a
        iv.label = a.label
        iv.iconSize = home.iconSizePx()
        iv.showLabel = true
        iv.labelColor = fg
        iv.labelShadow = fg == Color.WHITE
        iv.badgeNumbers = prefs.badgeNumbers
        iv.badgeCount = if (prefs.badges) Badges.count(a.component.packageName) else 0
        icons.load(a.component) { b -> iv.icon = b }
        val g = ItemGesture(
            iv, true,
            onClick = { home.launchApp(a.component, iv) },
            onLongPress = { home.showAppPopup(iv, a) },
            onDragStart = { home.startDrawerDrag(iv, a) }
        )
        iv.setOnTouchListener { _, ev -> g.onTouch(ev) }
        if (track) allIcons += iv
        return iv
    }

    fun updateBadges() {
        for (iv in allIcons) {
            val pkg = iv.app?.component?.packageName
            iv.badgeNumbers = prefs.badgeNumbers
            iv.badgeCount = if (prefs.badges) Badges.count(pkg) else 0
        }
    }

    fun reset() {
        if ((search.text?.length ?: 0) > 0) search.setText("")
        search.clearFocus()
        if (pager.childCount > 0) pager.snapTo(0, false)
        vScroll.scrollTo(0, 0)
        rScroll.scrollTo(0, 0)
    }

    private fun showMenu() {
        val m = PopupMenu(home, moreButton)
        m.menu.add(0, 1, 0, "Home screen settings")
        m.menu.add(0, 2, 1, "Hide apps")
        m.menu.add(0, 3, 2, "Edge panels")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> home.openSettings()
                2 -> home.startActivity(Intent(home, AppPickerActivity::class.java).putExtra(AppPickerActivity.EXTRA_MODE, AppPickerActivity.MODE_HIDE))
                3 -> home.startActivity(Intent(home, EdgeSettingsActivity::class.java))
            }
            true
        }
        m.show()
    }

    // ---- swipe down to close

    private fun canClose(): Boolean = when {
        rScroll.visibility == View.VISIBLE -> rScroll.scrollY == 0
        prefs.drawerLayout == "vertical" -> vScroll.scrollY == 0
        else -> true
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
                vt?.recycle()
                vt = VelocityTracker.obtain()
                vt?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                val dy = ev.y - downY
                val dx = ev.x - downX
                if (!dragging && dy > slop * 1.5f && dy > abs(dx) * 1.3f && canClose()) {
                    dragging = true
                    downY = ev.y
                    home.hideKeyboard()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (vt == null) vt = VelocityTracker.obtain()
        vt?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    val dy = ev.y - downY
                    if (dy > slop * 1.5f && dy > abs(ev.x - downX) * 1.3f && canClose()) {
                        dragging = true
                        downY = ev.y
                    }
                }
                if (dragging) {
                    val h = if (height > 0) height else 1
                    home.setDrawerProgress(1f - (ev.y - downY) / (h * 0.5f))
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    vt?.computeCurrentVelocity(1000)
                    val vy = vt?.yVelocity ?: 0f
                    val close = vy > dpf(700f) || (vy > -dpf(700f) && home.drawerProgressValue < 0.72f)
                    home.animateDrawer(!close)
                }
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) home.animateDrawer(true)
                dragging = false
            }
        }
        return true
    }
}

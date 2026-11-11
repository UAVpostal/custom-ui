package com.cogco.home

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.PopupWindow
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.ceil

/** What is being dragged (passed as the framework drag-and-drop local state). */
class DragInfo(val source: Int, val item: HomeItem?, val app: AppInfo?, val view: View?) {
    val spanX: Int get() = item?.spanX ?: 1
    val spanY: Int get() = item?.spanY ?: 1
    val isApp: Boolean get() = item?.type == HomeItem.TYPE_APP || (item == null && app != null)
    val isWidget: Boolean get() = item?.type == HomeItem.TYPE_WIDGET
    val component: ComponentName? get() = item?.component ?: app?.component

    companion object {
        const val SRC_HOME = 0
        const val SRC_DRAWER = 1
        const val SRC_FOLDER = 2
    }
}

/** Root of the home screen: catches vertical swipes (up = Apps screen, down = notifications). */
class HomeRootView(context: Context) : FrameLayout(context) {
    interface Callback {
        fun canSwipeUp(): Boolean
        fun canSwipeDown(): Boolean
        fun onSwipeUpDrag(dy: Float)
        fun onSwipeUpEnd(dy: Float, vy: Float)
        fun onSwipeDown()
    }

    var callback: Callback? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var mode = 0 // 0 none, 1 swiping up, 2 swipe-down handled
    private var vt: VelocityTracker? = null

    private fun detect(ev: MotionEvent, cb: Callback): Boolean {
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (mode == 0 && abs(dy) > slop * 1.5f && abs(dy) > abs(dx) * 1.3f) {
            if (dy < 0 && cb.canSwipeUp()) {
                mode = 1
            } else if (dy > 0 && cb.canSwipeDown()) {
                mode = 2
                cb.onSwipeDown()
            }
        }
        return mode != 0
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        val cb = callback ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                mode = 0
                vt?.recycle()
                vt = VelocityTracker.obtain()
                vt?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                return detect(ev, cb)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = 0
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val cb = callback ?: return false
        if (vt == null) vt = VelocityTracker.obtain()
        vt?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                mode = 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == 0) detect(ev, cb)
                if (mode == 1) cb.onSwipeUpDrag(ev.y - downY)
            }
            MotionEvent.ACTION_UP -> {
                if (mode == 1) {
                    vt?.computeCurrentVelocity(1000)
                    cb.onSwipeUpEnd(ev.y - downY, vt?.yVelocity ?: 0f)
                }
                mode = 0
            }
            MotionEvent.ACTION_CANCEL -> {
                if (mode == 1) cb.onSwipeUpEnd(0f, 0f)
                mode = 0
            }
        }
        return true
    }
}

class HomeActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var store: LayoutStore
    private lateinit var repo: AppRepository
    private lateinit var icons: IconCache
    private lateinit var host: WidgetHost
    private lateinit var widgetManager: AppWidgetManager

    lateinit var root: HomeRootView
        private set
    private lateinit var homeContainer: LinearLayout
    private lateinit var pageTools: LinearLayout
    private lateinit var workspace: PagedView
    private lateinit var indicator: PageIndicator
    private lateinit var dock: CellLayout
    private lateinit var drawer: AppDrawerView
    private lateinit var dropBar: LinearLayout
    private lateinit var dropRemove: LinearLayout
    private lateinit var dropUninstall: LinearLayout
    private lateinit var editBar: LinearLayout

    private val views = HashMap<Long, View>()
    var folderView: FolderView? = null
    private var popup: PopupWindow? = null
    private var editMode = false
    private var drawerOpen = false
    private var drawerProgress = 0f
    private var drawerAnim: ValueAnimator? = null
    private var isResumedNow = false
    private var cols = 4
    private var rows = 5
    private var dockCount = 4

    private var currentDrag: DragInfo? = null
    private var pageAddedForDrag = -1
    private var edgeDir = 0

    val drawerProgressValue: Float get() = drawerProgress

    private val layoutListener: () -> Unit = {
        syncViews()
        folderView?.refresh()
    }
    private val badgeListener: () -> Unit = { updateBadges() }
    private val appsListener = object : AppRepository.Listener {
        override fun onAppsChanged() {
            this@HomeActivity.onAppsChanged()
        }
    }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> onPrefChanged(key) }

    // ------------------------------------------------------------ lifecycle

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val a = app
        prefs = a.prefs
        store = a.layout
        repo = a.repo
        icons = a.icons
        host = a.widgetHost
        widgetManager = AppWidgetManager.getInstance(this)
        readGrid()

        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        buildUi()
        setContentView(root)

        store.addListener(layoutListener)
        Badges.addListener(badgeListener)
        repo.addListener(appsListener)
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        root.setOnDragListener { _, e -> onDragEvent(e) }

        store.fitToGrid(cols, rows, dockCount) // grid may have changed while we weren't running
        if (repo.loaded) onAppsChanged() else syncViews()
    }

    override fun onStart() {
        super.onStart()
        try {
            host.startListening()
        } catch (e: Exception) {
            // widget service unavailable
        }
    }

    override fun onResume() {
        super.onResume()
        isResumedNow = true
        EdgePanelService.sync(this)
    }

    override fun onPause() {
        super.onPause()
        isResumedNow = false
        dismissPopup()
    }

    override fun onStop() {
        super.onStop()
        try {
            host.stopListening()
        } catch (e: Exception) {
            // ignore
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        store.removeListener(layoutListener)
        Badges.removeListener(badgeListener)
        repo.removeListener(appsListener)
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val alreadyHome = isResumedNow && hasWindowFocus()
        dismissPopup()
        hideKeyboard()
        val fv = folderView
        when {
            fv != null -> fv.close(alreadyHome)
            drawerOpen || drawerProgress > 0f -> if (alreadyHome) animateDrawer(false) else {
                drawerAnim?.cancel()
                drawerOpen = false
                setDrawerProgress(0f)
                drawer.reset()
            }
            editMode -> exitEditMode()
            alreadyHome -> workspace.snapTo(0)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // A launcher never finishes on back.
        when {
            popup != null -> dismissPopup()
            folderView != null -> folderView?.close(true)
            drawerOpen -> animateDrawer(false)
            editMode -> exitEditMode()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        dismissPopup()
        drawer.rebuild()
    }

    // ------------------------------------------------------------ UI construction

    fun iconSizePx(): Int = dp(54 * prefs.iconScale / 100)
    private fun dockHeight(): Int = iconSizePx() + dp(34)

    private fun readGrid() {
        cols = prefs.gridCols.coerceIn(3, 7)
        rows = prefs.gridRows.coerceIn(3, 8)
        dockCount = prefs.dockCount.coerceIn(3, 6)
    }

    private fun buildUi() {
        root = HomeRootView(this)

        homeContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pageTools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        pageTools.addView(pill(Glyph.PLUS, "Add page") { addPageFromEdit() })
        pageTools.addView(pill(Glyph.TRASH, "Delete page") { deleteCurrentPage() })
        homeContainer.addView(pageTools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        workspace = PagedView(this)
        workspace.setPadding(dp(6), dp(10), dp(6), 0)
        workspace.onScroll = { indicator.position = it }
        homeContainer.addView(workspace, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        indicator = PageIndicator(this)
        homeContainer.addView(indicator, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)))

        dock = CellLayout(this, dockCount, 1)
        dock.setPadding(dp(10), 0, dp(10), 0)
        dock.onEmptyLongPress = { enterEditMode() }
        dock.onEmptyTap = { if (editMode) exitEditMode() }
        homeContainer.addView(dock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dockHeight()))
        root.addView(homeContainer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Drop targets shown at the top while dragging
        dropBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        dropRemove = dropTarget(Glyph.CLOSE, "Remove")
        dropUninstall = dropTarget(Glyph.TRASH, "Uninstall")
        dropBar.addView(dropRemove, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = dp(6) })
        dropBar.addView(dropUninstall, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(6) })
        root.addView(dropBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72), Gravity.TOP))

        // Edit mode bar (long-press on empty space)
        editBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            background = roundRect(0x8C000000.toInt(), dpf(28f))
            setPadding(dp(6), dp(10), dp(6), dp(10))
        }
        editBar.addView(editButton(Glyph.WALLPAPER, "Wallpapers") { openWallpaperPicker() }, weightLp())
        editBar.addView(editButton(Glyph.WIDGETS, "Widgets") { openWidgetPicker() }, weightLp())
        editBar.addView(editButton(Glyph.EDGE, "Edge panels") { startActivity(Intent(this, EdgeSettingsActivity::class.java)) }, weightLp())
        editBar.addView(editButton(Glyph.SETTINGS, "Settings") { openSettings() }, weightLp())
        root.addView(editBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(dp(16), 0, dp(16), dp(24))
        })

        drawer = AppDrawerView(this)
        drawer.visibility = View.INVISIBLE
        root.addView(drawer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        root.callback = object : HomeRootView.Callback {
            override fun canSwipeUp() = !prefs.homeOnly && !drawerOpen && folderView == null && !editMode && currentDrag == null
            override fun canSwipeDown() = prefs.swipeDownNotif && !drawerOpen && folderView == null && !editMode && currentDrag == null
            override fun onSwipeUpDrag(dy: Float) {
                drawerAnim?.cancel()
                val h = if (root.height > 0) root.height else 1
                setDrawerProgress((-dy) / (h * 0.42f))
            }

            override fun onSwipeUpEnd(dy: Float, vy: Float) {
                val fling = dpf(700f)
                animateDrawer(vy < -fling || (vy < fling && drawerProgress > 0.3f))
            }

            override fun onSwipeDown() {
                expandNotifications()
            }
        }

        root.setOnApplyWindowInsetsListener { _, insets ->
            applyInsets(barInsets(insets))
            insets
        }
    }

    private fun weightLp() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    @Suppress("DEPRECATION")
    private fun barInsets(insets: WindowInsets): Rect = if (Build.VERSION.SDK_INT >= 30) {
        val i = insets.getInsets(WindowInsets.Type.systemBars())
        Rect(i.left, i.top, i.right, i.bottom)
    } else {
        Rect(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
    }

    private fun applyInsets(r: Rect) {
        homeContainer.setPadding(r.left, r.top, r.right, r.bottom + dp(6))
        (dropBar.layoutParams as FrameLayout.LayoutParams).topMargin = r.top
        (editBar.layoutParams as FrameLayout.LayoutParams).bottomMargin = r.bottom + dp(24)
        dropBar.requestLayout()
        editBar.requestLayout()
        drawer.applyInsets(r.top, r.bottom)
    }

    private fun pill(glyph: Glyph, text: String, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = roundRect(0x66000000, dpf(18f))
        setPadding(dp(14), dp(7), dp(16), dp(7))
        addView(glyphView(glyph, Color.WHITE, 18))
        addView(TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(6), 0, 0, 0)
        })
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(6), 0, dp(6), 0)
        }
    }

    private fun dropTarget(glyph: Glyph, text: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = roundRect(0x66000000, dpf(20f))
        addView(glyphView(glyph, Color.WHITE, 20))
        addView(TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(8), 0, 0, 0)
        })
    }

    private fun editButton(glyph: Glyph, text: String, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(4), 0, dp(4))
        addView(glyphView(glyph, Color.WHITE, 26))
        addView(TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        })
        setOnClickListener { onClick() }
    }

    private fun newPage(): CellLayout = CellLayout(this, cols, rows).apply {
        onEmptyLongPress = { enterEditMode() }
        onEmptyTap = { if (editMode) exitEditMode() }
        outline = editMode
    }

    private fun ensurePages() {
        while (workspace.childCount < store.pageCount) workspace.addView(newPage())
        while (workspace.childCount > store.pageCount && workspace.childCount > 1) workspace.removeViewAt(workspace.childCount - 1)
        indicator.count = store.pageCount
    }

    // ------------------------------------------------------------ binding

    private fun visibleApps(): List<AppInfo> {
        val hidden = prefs.hiddenApps
        return repo.apps.filter { it.key !in hidden }
    }

    private fun onAppsChanged() {
        if (!prefs.firstRunDone && repo.apps.isNotEmpty()) {
            prefs.firstRunDone = true
            if (store.items.isEmpty()) createDefaultLayout()
        }
        if (repo.apps.isNotEmpty()) {
            val installed = repo.apps.map { it.component }.toHashSet()
            val stale = store.items.filter {
                val c = it.component
                it.type == HomeItem.TYPE_APP && c != null && c !in installed && !isPackageInstalled(c.packageName)
            }
            if (stale.isNotEmpty()) {
                store.items.removeAll(stale.toSet())
                store.cleanupFolders()
                store.save()
            }
        }
        drawer.setApps(visibleApps())
        syncViews()
        folderView?.refresh()
    }

    @Suppress("DEPRECATION")
    private fun isPackageInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun onPrefChanged(key: String?) {
        when (key) {
            Prefs.K_GRID_COLS, Prefs.K_GRID_ROWS, Prefs.K_DOCK -> {
                readGrid()
                store.fitToGrid(cols, rows, dockCount)
                store.commit()
            }
            Prefs.K_SHAPE, Prefs.K_ICON_SCALE -> {
                if (key == Prefs.K_SHAPE) icons.clear()
                views.values.forEach { (it.parent as? ViewGroup)?.removeView(it) }
                views.clear()
                syncViews()
                drawer.rebuild()
            }
            Prefs.K_LABELS, Prefs.K_BADGES, Prefs.K_BADGE_NUM -> {
                syncViews()
                drawer.rebuild()
            }
            Prefs.K_APPS_COLS, Prefs.K_APPS_ROWS, Prefs.K_APPS_LAYOUT, Prefs.K_APPS_BG -> drawer.rebuild()
            Prefs.K_HIDDEN -> drawer.setApps(visibleApps())
            Prefs.K_HOME_ONLY -> {
                if (prefs.homeOnly && drawerOpen) animateDrawer(false)
                app.ensureHomeOnly()
            }
        }
    }

    fun syncViews() {
        readGrid()
        ensurePages()
        for (i in 0 until workspace.childCount) {
            val cl = workspace.getChildAt(i) as CellLayout
            cl.cols = cols
            cl.rows = rows
            cl.removeAllViews()
            cl.requestLayout()
        }
        dock.cols = dockCount
        dock.removeAllViews()
        val dlp = dock.layoutParams as LinearLayout.LayoutParams
        if (dlp.height != dockHeight()) {
            dlp.height = dockHeight()
            dock.layoutParams = dlp
        }

        val alive = HashSet<Long>()
        val broken = ArrayList<HomeItem>()
        for (item in store.topLevel()) {
            var v = views[item.id]
            if (v == null) {
                v = createItemView(item)
                if (v == null) {
                    broken += item
                    continue
                }
                views[item.id] = v
            }
            bindItemView(v, item)
            val parentCl: CellLayout = if (item.container == HomeItem.DOCK) dock
            else (workspace.getChildAt(item.page) as? CellLayout) ?: continue
            (v.parent as? ViewGroup)?.removeView(v)
            parentCl.addView(v, CellLayout.LP(item.x, item.y, item.spanX, item.spanY))
            alive += item.id
        }
        views.keys.retainAll(alive)
        if (broken.isNotEmpty()) {
            for (b in broken) {
                if (b.widgetId >= 0) host.deleteAppWidgetId(b.widgetId)
                store.items.remove(b)
            }
            store.save()
        }
        indicator.count = store.pageCount
        if (workspace.currentPage >= store.pageCount) workspace.snapTo(store.pageCount - 1, false)
        indicator.position = workspace.position()
    }

    private fun createItemView(item: HomeItem): View? {
        if (item.type == HomeItem.TYPE_WIDGET) return createWidgetView(item)
        val iv = IconView(this)
        iv.item = item
        val g = ItemGesture(
            iv, true,
            onClick = { onItemClick(iv) },
            onLongPress = { onItemLongPress(iv) },
            onDragStart = { if (!prefs.lockLayout) startItemDrag(iv) }
        )
        iv.setOnTouchListener { _, ev -> g.onTouch(ev) }
        return iv
    }

    private fun createWidgetView(item: HomeItem): View? {
        val info = widgetManager.getAppWidgetInfo(item.widgetId) ?: return null
        val v = try {
            host.createView(this, item.widgetId, info) as? WidgetView
        } catch (e: Exception) {
            null
        } ?: return null
        v.tag = item
        v.gesture = ItemGesture(
            v, false,
            onClick = {},
            onLongPress = { onItemLongPress(v) },
            onDragStart = { if (!prefs.lockLayout) startItemDrag(v) }
        )
        return v
    }

    private fun bindItemView(v: View, item: HomeItem) {
        if (v is WidgetView) {
            v.tag = item
            return
        }
        if (v !is IconView) return
        val inDock = item.container == HomeItem.DOCK
        v.item = item
        v.iconSize = iconSizePx()
        v.showLabel = prefs.showLabels && !inDock
        v.labelColor = Color.WHITE
        v.labelShadow = true
        v.badgeNumbers = prefs.badgeNumbers
        when (item.type) {
            HomeItem.TYPE_APP -> {
                val c = item.component
                v.label = c?.let { repo.find(it)?.label } ?: item.title
                if (c != null) icons.load(c) { b -> if (v.item === item) v.icon = b }
                v.badgeCount = if (prefs.badges) Badges.count(c?.packageName) else 0
            }
            HomeItem.TYPE_FOLDER -> {
                v.label = item.title
                renderFolderIcon(item) { b -> if (v.item === item) v.icon = b }
                v.badgeCount = folderBadge(item)
            }
        }
    }

    fun renderFolderIcon(folder: HomeItem, cb: (Bitmap) -> Unit) {
        val kids = store.folderChildren(folder.id).mapNotNull { it.component }
        val color = folder.color
        val size = icons.iconPx
        val shape = prefs.iconShape
        Async.io {
            val bmps = kids.take(4).map { icons.getSync(it) }
            val b = IconShapes.folder(bmps, color, size, shape)
            Async.ui { cb(b) }
        }
    }

    private fun folderBadge(folder: HomeItem): Int {
        if (!prefs.badges) return 0
        return store.folderChildren(folder.id).sumOf { Badges.count(it.component?.packageName) }
    }

    private fun updateBadges() {
        for ((_, v) in views) {
            val iv = v as? IconView ?: continue
            val item = iv.item ?: continue
            iv.badgeNumbers = prefs.badgeNumbers
            iv.badgeCount = when {
                !prefs.badges -> 0
                item.type == HomeItem.TYPE_FOLDER -> folderBadge(item)
                else -> Badges.count(item.component?.packageName)
            }
        }
        drawer.updateBadges()
        folderView?.updateBadges()
    }

    private fun createDefaultLayout() {
        fun appForPkg(pkg: String?): ComponentName? =
            if (pkg == null) null else repo.apps.firstOrNull { it.component.packageName == pkg }?.component

        @Suppress("DEPRECATION")
        fun appFor(intent: Intent): ComponentName? {
            val ri = try {
                packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            } catch (e: Exception) {
                null
            } ?: return null
            val pkg = ri.activityInfo?.packageName ?: return null
            if (pkg == "android") return null // chooser, no default
            return appForPkg(pkg)
        }

        val sms = try {
            Telephony.Sms.getDefaultSmsPackage(this)
        } catch (e: Exception) {
            null
        }
        val dockApps = listOfNotNull(
            appFor(Intent(Intent.ACTION_DIAL)),
            appForPkg(sms),
            appFor(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com")).addCategory(Intent.CATEGORY_BROWSABLE)),
            appFor(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
        ).distinct()
        dockApps.take(dockCount).forEachIndexed { i, c ->
            store.items += HomeItem(store.newId(), HomeItem.TYPE_APP, HomeItem.DOCK, 0, i, 0, component = c, title = repo.label(c))
        }
        val homeApps = listOfNotNull(
            appForPkg("com.android.vending"),
            appFor(Intent(Settings.ACTION_SETTINGS)),
            appFor(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY)),
            appFor(Intent(AlarmClock.ACTION_SHOW_ALARMS)),
            appFor(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)),
            appFor(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_EMAIL)),
            appFor(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MAPS)),
            appFor(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC))
        ).distinct().filter { it !in dockApps }
        homeApps.take(cols * 2).forEachIndexed { i, c ->
            store.items += HomeItem(
                store.newId(), HomeItem.TYPE_APP, HomeItem.DESKTOP, 0, i % cols, rows - 1 - i / cols,
                component = c, title = repo.label(c)
            )
        }
        store.commit()
        app.ensureHomeOnly()
    }

    // ------------------------------------------------------------ clicks & popups

    private fun onItemClick(iv: IconView) {
        if (editMode) {
            exitEditMode()
            return
        }
        val item = iv.item ?: return
        when (item.type) {
            HomeItem.TYPE_APP -> item.component?.let { launchApp(it, iv) }
            HomeItem.TYPE_FOLDER -> openFolder(item)
        }
    }

    private fun itemOf(v: View): HomeItem? = (v as? IconView)?.item ?: (v.tag as? HomeItem)

    private fun onItemLongPress(v: View) {
        if (editMode) exitEditMode()
        val item = itemOf(v) ?: return
        val locked = prefs.lockLayout
        val actions = ArrayList<PopupAction>()
        when (item.type) {
            HomeItem.TYPE_APP -> {
                val c = item.component ?: return
                actions += PopupAction(Glyph.INFO, "App info") { appInfo(c) }
                if (!locked) actions += PopupAction(Glyph.CLOSE, "Remove") { removeItem(item) }
                actions += PopupAction(Glyph.EDGE, "Add to Edge") { addToEdge(c) }
                if (!repo.isSystemApp(c)) actions += PopupAction(Glyph.TRASH, "Uninstall") { uninstall(c.packageName) }
                showPopup(v, shortcutsFor(c), actions)
            }
            HomeItem.TYPE_FOLDER -> {
                actions += PopupAction(Glyph.EDIT, "Open") { openFolder(item) }
                if (!locked) actions += PopupAction(Glyph.CLOSE, "Remove") { confirmRemoveFolder(item) }
                showPopup(v, emptyList(), actions)
            }
            HomeItem.TYPE_WIDGET -> {
                if (!locked) {
                    actions += PopupAction(Glyph.CLOSE, "Remove") { removeItem(item) }
                    actions += PopupAction(Glyph.RESIZE, "Resize") { resizeWidget(item) }
                }
                val info = widgetManager.getAppWidgetInfo(item.widgetId)
                if (info?.configure != null) {
                    actions += PopupAction(Glyph.SETTINGS, "Settings") {
                        try {
                            host.startAppWidgetConfigureActivityForResult(this, item.widgetId, 0, REQ_RECONFIGURE, null)
                        } catch (e: Exception) {
                            toast("This widget can't be reconfigured")
                        }
                    }
                }
                if (actions.isNotEmpty()) showPopup(v, emptyList(), actions)
            }
        }
    }

    /** Long-press menu for an app in the Apps screen. */
    fun showAppPopup(v: IconView, a: AppInfo) {
        val c = a.component
        val actions = ArrayList<PopupAction>()
        actions += PopupAction(Glyph.INFO, "App info") { appInfo(c) }
        if (!prefs.homeOnly) actions += PopupAction(Glyph.HOME, "Add to Home") { addToHome(a) }
        actions += PopupAction(Glyph.EDGE, "Add to Edge") { addToEdge(c) }
        actions += PopupAction(Glyph.HIDE, "Hide") { hideApp(a) }
        if (!repo.isSystemApp(c)) actions += PopupAction(Glyph.TRASH, "Uninstall") { uninstall(c.packageName) }
        showPopup(v, shortcutsFor(c), actions)
    }

    /** Long-press menu for an app inside an open folder. */
    fun showFolderAppPopup(v: IconView, item: HomeItem) {
        val c = item.component ?: return
        val actions = ArrayList<PopupAction>()
        actions += PopupAction(Glyph.INFO, "App info") { appInfo(c) }
        if (!prefs.lockLayout) actions += PopupAction(Glyph.CLOSE, "Remove") { removeItem(item) }
        actions += PopupAction(Glyph.EDGE, "Add to Edge") { addToEdge(c) }
        if (!repo.isSystemApp(c)) actions += PopupAction(Glyph.TRASH, "Uninstall") { uninstall(c.packageName) }
        showPopup(v, shortcutsFor(c), actions)
    }

    private fun showPopup(anchor: View, shortcuts: List<ShortcutInfo>, actions: List<PopupAction>) {
        dismissPopup()
        popup = ItemPopup.show(this, anchor, shortcuts, actions) { startShortcut(it) }
        popup?.setOnDismissListener { popup = null }
    }

    fun dismissPopup() {
        val p = popup
        popup = null
        try {
            p?.dismiss()
        } catch (e: Exception) {
            // window already gone
        }
    }

    private fun shortcutsFor(c: ComponentName): List<ShortcutInfo> {
        val la = getSystemService(LauncherApps::class.java) ?: return emptyList()
        return try {
            if (!la.hasShortcutHostPermission()) return emptyList()
            val q = LauncherApps.ShortcutQuery()
                .setPackage(c.packageName)
                .setActivity(c)
                .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
            (la.getShortcuts(q, Process.myUserHandle()) ?: emptyList<ShortcutInfo>())
                .sortedBy { it.rank }
                .take(4)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun startShortcut(si: ShortcutInfo) {
        try {
            getSystemService(LauncherApps::class.java)?.startShortcut(si, null, null)
        } catch (e: Exception) {
            toast("Shortcut isn't available")
        }
    }

    fun launchApp(c: ComponentName, v: View?) {
        val la = getSystemService(LauncherApps::class.java)
        try {
            var bounds: Rect? = null
            var opts: Bundle? = null
            if (v != null && v.width > 0) {
                val loc = IntArray(2)
                v.getLocationOnScreen(loc)
                bounds = Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
                opts = ActivityOptions.makeScaleUpAnimation(v, 0, 0, v.width, v.height).toBundle()
            }
            la.startMainActivity(c, Process.myUserHandle(), bounds, opts)
        } catch (e: Exception) {
            try {
                startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(c)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                )
            } catch (e2: Exception) {
                toast("App isn't available")
            }
        }
    }

    private fun appInfo(c: ComponentName) {
        try {
            getSystemService(LauncherApps::class.java).startAppDetailsActivity(c, Process.myUserHandle(), null, null)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", c.packageName, null)))
        }
    }

    private fun uninstall(pkg: String) {
        try {
            @Suppress("DEPRECATION")
            startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null)))
        } catch (e: Exception) {
            toast("Can't uninstall this app")
        }
    }

    private fun addToEdge(c: ComponentName) {
        val list = prefs.edgeApps
        if (c in list) {
            toast("Already in the Apps edge panel")
            return
        }
        prefs.edgeApps = list + c
        toast(if (prefs.edgeEnabled) "Added to the Apps edge panel" else "Added. Turn on Edge panels in settings to use it")
    }

    private fun addToHome(a: AppInfo) {
        if (store.containsApp(a.component)) {
            toast("Already on the Home screen")
            return
        }
        val item = HomeItem(store.newId(), HomeItem.TYPE_APP, HomeItem.DESKTOP, component = a.component, title = a.label)
        if (store.placeOnDesktop(item, cols, rows, workspace.currentPage)) {
            store.commit()
            toast("Added to the Home screen")
        }
    }

    private fun hideApp(a: AppInfo) {
        prefs.hiddenApps = prefs.hiddenApps + a.key
        store.removeComponent(a.component)
        store.commit()
        toast("${a.label} hidden. Unhide it in Home screen settings")
    }

    fun removeItem(item: HomeItem) {
        val gone = store.remove(item)
        gone.filter { it.type == HomeItem.TYPE_WIDGET && it.widgetId >= 0 }.forEach { host.deleteAppWidgetId(it.widgetId) }
        store.commit()
    }

    private fun confirmRemoveFolder(item: HomeItem) {
        AlertDialog.Builder(this)
            .setTitle("Remove folder?")
            .setMessage("The folder and its shortcuts are removed from the Home screen. The apps stay installed.")
            .setPositiveButton("Remove") { _, _ -> removeItem(item) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------ folders

    private fun openFolder(item: HomeItem) {
        if (folderView != null) return
        dismissPopup()
        val fv = FolderView(this, item)
        folderView = fv
        root.addView(fv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fv.open()
    }

    fun onFolderClosed(fv: FolderView) {
        if (folderView === fv) folderView = null
        root.removeView(fv)
    }

    // ------------------------------------------------------------ Apps screen (drawer)

    fun setDrawerProgress(p: Float) {
        drawerProgress = p.coerceIn(0f, 1f)
        if (drawerProgress > 0f && drawer.visibility != View.VISIBLE) drawer.visibility = View.VISIBLE
        drawer.alpha = drawerProgress
        drawer.translationY = (1f - drawerProgress) * dpf(160f)
        homeContainer.alpha = 1f - drawerProgress
        val s = 1f - 0.06f * drawerProgress
        homeContainer.scaleX = s
        homeContainer.scaleY = s
        if (drawerProgress == 0f) drawer.visibility = View.INVISIBLE
    }

    fun animateDrawer(open: Boolean) {
        drawerAnim?.cancel()
        if (open && prefs.homeOnly) return
        dismissPopup()
        val target = if (open) 1f else 0f
        drawerOpen = open
        val a = ValueAnimator.ofFloat(drawerProgress, target)
        a.duration = 260
        a.interpolator = DecelerateInterpolator(2f)
        a.addUpdateListener { setDrawerProgress(it.animatedValue as Float) }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (!open && drawerProgress == 0f) {
                    drawer.reset()
                    hideKeyboard()
                }
            }
        })
        drawerAnim = a
        a.start()
    }

    fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        imm.hideSoftInputFromWindow(root.windowToken, 0)
    }

    // ------------------------------------------------------------ edit mode

    private fun enterEditMode() {
        if (editMode || drawerOpen || folderView != null || currentDrag != null) return
        editMode = true
        dismissPopup()
        workspace.animate().scaleX(0.84f).scaleY(0.84f).setDuration(220).start()
        for (i in 0 until workspace.childCount) (workspace.getChildAt(i) as CellLayout).outline = true
        dock.animate().alpha(0f).setDuration(180).start()
        editBar.alpha = 0f
        editBar.visibility = View.VISIBLE
        editBar.animate().alpha(1f).setDuration(200).start()
        if (!prefs.lockLayout) pageTools.visibility = View.VISIBLE
    }

    private fun exitEditMode() {
        if (!editMode) return
        editMode = false
        workspace.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        for (i in 0 until workspace.childCount) (workspace.getChildAt(i) as CellLayout).outline = false
        dock.animate().alpha(1f).setDuration(180).start()
        editBar.visibility = View.GONE
        pageTools.visibility = View.GONE
    }

    private fun addPageFromEdit() {
        store.pageCount++
        store.save()
        ensurePages()
        (workspace.getChildAt(workspace.childCount - 1) as CellLayout).outline = true
        workspace.post { workspace.snapTo(store.pageCount - 1) }
    }

    private fun deleteCurrentPage() {
        if (store.pageCount <= 1) {
            toast("You need at least one Home screen page")
            return
        }
        val page = workspace.currentPage
        val doDelete = {
            val gone = store.deletePage(page)
            gone.filter { it.type == HomeItem.TYPE_WIDGET && it.widgetId >= 0 }.forEach { host.deleteAppWidgetId(it.widgetId) }
            store.commit()
        }
        if (store.isPageEmpty(page)) {
            doDelete()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Delete this page?")
                .setMessage("Everything on this page will be removed from the Home screen.")
                .setPositiveButton("Delete") { _, _ -> doDelete() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun openWallpaperPicker() {
        exitEditMode()
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpapers"))
        } catch (e: Exception) {
            toast("No wallpaper app found")
        }
    }

    private fun openWidgetPicker() {
        exitEditMode()
        startActivityForResult(Intent(this, WidgetPickerActivity::class.java), REQ_PICK_WIDGET)
    }

    fun openSettings() {
        exitEditMode()
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    // ------------------------------------------------------------ widgets

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_PICK_WIDGET -> if (resultCode == RESULT_OK) {
                val provider = data?.getStringExtra(WidgetPickerActivity.EXTRA_PROVIDER)?.let { ComponentName.unflattenFromString(it) }
                if (provider != null) startAddWidget(provider)
            }
            REQ_BIND -> if (resultCode == RESULT_OK) onWidgetBound() else cancelPendingWidget()
            REQ_CONFIGURE -> if (resultCode == RESULT_OK) placePendingWidget() else cancelPendingWidget()
        }
    }

    private fun startAddWidget(provider: ComponentName) {
        val info = widgetManager.installedProviders.firstOrNull { it.provider == provider } ?: return
        val id = host.allocateAppWidgetId()
        prefs.pendingWidgetId = id
        val allowed = try {
            widgetManager.bindAppWidgetIdIfAllowed(id, info.profile, provider, null)
        } catch (e: Exception) {
            false
        }
        if (allowed) {
            onWidgetBound()
        } else {
            val i = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            try {
                startActivityForResult(i, REQ_BIND)
            } catch (e: Exception) {
                cancelPendingWidget()
            }
        }
    }

    private fun onWidgetBound() {
        val id = prefs.pendingWidgetId
        val info = widgetManager.getAppWidgetInfo(id)
        if (info == null) {
            cancelPendingWidget()
            return
        }
        if (info.configure != null) {
            try {
                host.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIGURE, null)
                return
            } catch (e: Exception) {
                // fall through and place it unconfigured
            }
        }
        placePendingWidget()
    }

    private fun placePendingWidget() {
        val id = prefs.pendingWidgetId
        prefs.pendingWidgetId = -1
        if (id < 0) return
        val info = widgetManager.getAppWidgetInfo(id)
        if (info == null) {
            host.deleteAppWidgetId(id)
            return
        }
        placeWidget(id, info)
    }

    private fun cancelPendingWidget() {
        val id = prefs.pendingWidgetId
        prefs.pendingWidgetId = -1
        if (id >= 0) host.deleteAppWidgetId(id)
    }

    private fun spansFor(info: AppWidgetProviderInfo): Pair<Int, Int> {
        var sx = 0
        var sy = 0
        if (Build.VERSION.SDK_INT >= 31) {
            sx = info.targetCellWidth
            sy = info.targetCellHeight
        }
        if (sx <= 0 || sy <= 0) {
            val page = workspace.getChildAt(0) as? CellLayout
            val screen = screenSize()
            val cw = page?.cellW?.takeIf { it > 0 } ?: (screen.x / cols)
            val ch = page?.cellH?.takeIf { it > 0 } ?: (screen.y * 2 / 3 / rows)
            sx = ceil(info.minWidth.toFloat() / cw).toInt()
            sy = ceil(info.minHeight.toFloat() / ch).toInt()
        }
        return Pair(sx.coerceIn(1, cols), sy.coerceIn(1, rows))
    }

    private fun placeWidget(id: Int, info: AppWidgetProviderInfo) {
        val (sx, sy) = spansFor(info)
        val item = HomeItem(
            store.newId(), HomeItem.TYPE_WIDGET, HomeItem.DESKTOP,
            spanX = sx, spanY = sy, component = info.provider, widgetId = id
        )
        if (!store.placeOnDesktop(item, cols, rows, workspace.currentPage)) {
            host.deleteAppWidgetId(id)
            toast("No room for this widget")
            return
        }
        store.commit()
        val page = item.page
        workspace.post { workspace.snapTo(page) }
    }

    private fun resizeWidget(item: HomeItem) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), 0)
        }
        fun column(title: String, picker: NumberPicker): View = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(TextView(context).apply {
                text = title
                gravity = Gravity.CENTER
            })
            addView(picker)
            setPadding(dp(16), 0, dp(16), 0)
        }
        val wp = NumberPicker(this).apply {
            minValue = 1
            maxValue = cols
            value = item.spanX.coerceIn(1, cols)
        }
        val hp = NumberPicker(this).apply {
            minValue = 1
            maxValue = rows
            value = item.spanY.coerceIn(1, rows)
        }
        box.addView(column("Width", wp))
        box.addView(column("Height", hp))
        AlertDialog.Builder(this)
            .setTitle("Resize widget")
            .setView(box)
            .setPositiveButton("Apply") { _, _ ->
                val sx = wp.value
                val sy = hp.value
                val grid = store.occupied(HomeItem.DESKTOP, item.page, cols, rows, item.id)
                var x = item.x
                var y = item.y
                if (!store.isFree(grid, x, y, sx, sy, cols, rows)) {
                    val p = store.findNearestFree(HomeItem.DESKTOP, item.page, x, y, sx, sy, cols, rows, item.id)
                    if (p == null) {
                        toast("Not enough space on this page")
                        return@setPositiveButton
                    }
                    x = p.x
                    y = p.y
                }
                item.x = x
                item.y = y
                item.spanX = sx
                item.spanY = sy
                store.commit()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------ drag and drop

    private fun startItemDrag(v: View) {
        val item = itemOf(v) ?: return
        val ic = (v as? IconView)?.icon
        val shadow: View.DragShadowBuilder =
            if (ic != null) IconShadow(ic, (iconSizePx() * 1.15f).toInt()) else View.DragShadowBuilder(v)
        beginDrag(v, DragInfo(DragInfo.SRC_HOME, item, null, v), shadow)
    }

    fun startDrawerDrag(v: IconView, a: AppInfo) {
        if (prefs.lockLayout) {
            toast("Home screen layout is locked")
            return
        }
        if (prefs.homeOnly) return
        val ic = v.icon ?: return
        if (beginDrag(v, DragInfo(DragInfo.SRC_DRAWER, null, a, null), IconShadow(ic, (iconSizePx() * 1.15f).toInt()))) {
            animateDrawer(false)
        }
    }

    fun startFolderDrag(v: IconView, item: HomeItem) {
        val ic = v.icon ?: return
        if (beginDrag(v, DragInfo(DragInfo.SRC_FOLDER, item, null, null), IconShadow(ic, (iconSizePx() * 1.15f).toInt()))) {
            folderView?.close(false)
        }
    }

    private fun beginDrag(v: View, info: DragInfo, shadow: View.DragShadowBuilder): Boolean {
        dismissPopup()
        currentDrag = info
        val ok = try {
            v.startDragAndDrop(ClipData.newPlainText("cogco-home", ""), shadow, info, View.DRAG_FLAG_OPAQUE)
        } catch (e: Exception) {
            false
        }
        if (!ok) currentDrag = null
        return ok
    }

    private fun offsetOf(v: View): PointF {
        val a = IntArray(2)
        val b = IntArray(2)
        v.getLocationInWindow(a)
        root.getLocationInWindow(b)
        return PointF((a[0] - b[0]).toFloat(), (a[1] - b[1]).toFloat())
    }

    private fun hit(v: View, x: Float, y: Float): Boolean {
        if (v.visibility != View.VISIBLE || !v.isShown) return false
        val o = offsetOf(v)
        return x >= o.x && x <= o.x + v.width && y >= o.y && y <= o.y + v.height
    }

    private class Target(val kind: Int, val page: Int = 0, val x: Int = 0, val y: Int = 0)

    private fun computeTarget(info: DragInfo, x: Float, y: Float): Target {
        if (dropBar.visibility == View.VISIBLE && hit(dropBar, x, y)) {
            if (hit(dropRemove, x, y)) return Target(T_REMOVE)
            if (hit(dropUninstall, x, y)) return Target(T_UNINSTALL)
            return Target(T_NONE)
        }
        if (!info.isWidget && hit(dock, x, y)) {
            val o = offsetOf(dock)
            val c = dock.cellAt(x - o.x, y - o.y, 1, 1)
            return Target(T_DOCK, 0, c.x, 0)
        }
        val cl = workspace.getChildAt(workspace.currentPage) as? CellLayout ?: return Target(T_NONE)
        if (hit(cl, x, y)) {
            val o = offsetOf(cl)
            val c = cl.cellAt(x - o.x, y - o.y, info.spanX, info.spanY)
            return Target(T_DESKTOP, workspace.currentPage, c.x, c.y)
        }
        return Target(T_NONE)
    }

    private fun clearHighlights() {
        for (i in 0 until workspace.childCount) (workspace.getChildAt(i) as CellLayout).clearHighlight()
        dock.clearHighlight()
        dropRemove.background = roundRect(0x66000000, dpf(20f))
        dropUninstall.background = roundRect(0x66000000, dpf(20f))
    }

    private fun onDragEvent(e: DragEvent): Boolean {
        val info = (e.localState as? DragInfo) ?: currentDrag ?: return false
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                onDragStarted(info)
                return true
            }
            DragEvent.ACTION_DRAG_LOCATION -> onDragMove(info, e.x, e.y)
            DragEvent.ACTION_DROP -> return onDrop(info, e.x, e.y)
            DragEvent.ACTION_DRAG_EXITED -> {
                clearHighlights()
                cancelEdge()
            }
            DragEvent.ACTION_DRAG_ENDED -> onDragEnded(info)
        }
        return true
    }

    private fun onDragStarted(info: DragInfo) {
        currentDrag = info
        if (editMode) exitEditMode()
        if (info.source == DragInfo.SRC_HOME) info.view?.visibility = View.INVISIBLE
        val c = info.component
        dropRemove.visibility = if (info.source == DragInfo.SRC_DRAWER) View.GONE else View.VISIBLE
        dropUninstall.visibility = if (info.isApp && c != null && !repo.isSystemApp(c)) View.VISIBLE else View.GONE
        if (dropRemove.visibility == View.VISIBLE || dropUninstall.visibility == View.VISIBLE) {
            dropBar.alpha = 0f
            dropBar.visibility = View.VISIBLE
            dropBar.animate().alpha(1f).setDuration(150).start()
        }
    }

    private fun onDragMove(info: DragInfo, x: Float, y: Float) {
        val t = computeTarget(info, x, y)
        clearHighlights()
        val ignore = info.item?.id ?: -1L
        when (t.kind) {
            T_REMOVE -> dropRemove.background = roundRect(0xCCE5484D.toInt(), dpf(20f))
            T_UNINSTALL -> dropUninstall.background = roundRect(0xCCE5484D.toInt(), dpf(20f))
            T_DESKTOP -> {
                val cl = workspace.getChildAt(t.page) as CellLayout
                val existing = store.itemAt(HomeItem.DESKTOP, t.page, t.x, t.y, ignore)
                if (info.isApp && existing != null && existing.type != HomeItem.TYPE_WIDGET) {
                    cl.setHighlight(existing.x, existing.y, 1, 1, true)
                } else {
                    cl.setHighlight(t.x, t.y, info.spanX, info.spanY, false)
                }
            }
            T_DOCK -> {
                val existing = store.itemAt(HomeItem.DOCK, 0, t.x, 0, ignore)
                dock.setHighlight(t.x, 0, 1, 1, info.isApp && existing != null)
            }
        }
        // page over when hovering near the screen edge
        val zone = dpf(26f)
        val overWorkspace = hit(workspace, x, y)
        val dir = when {
            !overWorkspace -> 0
            x < zone -> -1
            x > root.width - zone -> 1
            else -> 0
        }
        if (dir != edgeDir) {
            root.removeCallbacks(edgeRunnable)
            edgeDir = dir
            if (dir != 0) root.postDelayed(edgeRunnable, 550)
        }
    }

    private val edgeRunnable = object : Runnable {
        override fun run() {
            if (edgeDir == 0 || currentDrag == null) return
            val next = workspace.currentPage + edgeDir
            if (next < 0) return
            if (next >= workspace.childCount) {
                // only one fresh page per drag, and not after an already-empty page
                if (pageAddedForDrag >= 0 || store.isPageEmpty(workspace.currentPage)) return
                store.pageCount++
                pageAddedForDrag = store.pageCount - 1
                ensurePages()
            }
            workspace.snapTo(next)
            root.postDelayed(this, 900)
        }
    }

    private fun cancelEdge() {
        edgeDir = 0
        root.removeCallbacks(edgeRunnable)
    }

    private fun onDrop(info: DragInfo, x: Float, y: Float): Boolean {
        cancelEdge()
        val t = computeTarget(info, x, y)
        return when (t.kind) {
            T_REMOVE -> {
                val item = info.item
                if (item != null && info.source != DragInfo.SRC_DRAWER) {
                    removeItem(item)
                    true
                } else false
            }
            T_UNINSTALL -> {
                info.component?.let { uninstall(it.packageName) }
                false
            }
            T_DOCK -> dropInto(info, HomeItem.DOCK, 0, t.x, 0, dockCount, 1)
            T_DESKTOP -> dropInto(info, HomeItem.DESKTOP, t.page, t.x, t.y, cols, rows)
            else -> false
        }
    }

    private fun dropInto(info: DragInfo, container: Long, page: Int, cx: Int, cy: Int, gCols: Int, gRows: Int): Boolean {
        val dragged: HomeItem = info.item ?: run {
            val a = info.app ?: return false
            HomeItem(store.newId(), HomeItem.TYPE_APP, container, component = a.component, title = a.label)
        }
        if (container == HomeItem.DOCK && dragged.type == HomeItem.TYPE_WIDGET) return false
        val existing = store.itemAt(container, page, cx, cy, dragged.id)
        if (existing != null && dragged.type == HomeItem.TYPE_APP) {
            if (existing.type == HomeItem.TYPE_APP) {
                store.createFolder(existing, dragged)
                store.cleanupFolders()
                store.commit()
                return true
            }
            if (existing.type == HomeItem.TYPE_FOLDER) {
                store.addToFolder(existing, dragged)
                store.cleanupFolders()
                store.commit()
                return true
            }
        }
        var tx = cx
        var ty = cy
        val grid = store.occupied(container, page, gCols, gRows, dragged.id)
        if (!store.isFree(grid, tx, ty, dragged.spanX, dragged.spanY, gCols, gRows)) {
            val p = store.findNearestFree(container, page, tx, ty, dragged.spanX, dragged.spanY, gCols, gRows, dragged.id)
            if (p == null) {
                toast(if (container == HomeItem.DOCK) "The dock is full" else "No space on this page")
                return false
            }
            tx = p.x
            ty = p.y
        }
        dragged.container = container
        dragged.page = if (container == HomeItem.DOCK) 0 else page
        dragged.x = tx
        dragged.y = ty
        if (!store.items.contains(dragged)) store.items += dragged
        store.cleanupFolders()
        store.commit()
        return true
    }

    private fun onDragEnded(info: DragInfo) {
        if (currentDrag == null) return // already handled
        currentDrag = null
        cancelEdge()
        clearHighlights()
        dropBar.visibility = View.GONE
        info.view?.visibility = View.VISIBLE
        if (pageAddedForDrag >= 0) {
            val p = pageAddedForDrag
            pageAddedForDrag = -1
            if (p < store.pageCount && store.isPageEmpty(p) && store.pageCount > 1) store.deletePage(p)
            store.commit()
        }
    }

    // ------------------------------------------------------------ misc

    @SuppressLint("WrongConstant")
    private fun expandNotifications() {
        try {
            val sbm = getSystemService("statusbar") ?: return
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sbm)
        } catch (e: Exception) {
            // not supported on this device
        }
    }

    companion object {
        private const val REQ_PICK_WIDGET = 11
        private const val REQ_BIND = 12
        private const val REQ_CONFIGURE = 13
        private const val REQ_RECONFIGURE = 14

        private const val T_NONE = 0
        private const val T_REMOVE = 1
        private const val T_UNINSTALL = 2
        private const val T_DOCK = 3
        private const val T_DESKTOP = 4
    }
}

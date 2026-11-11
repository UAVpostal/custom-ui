package com.cogco.home

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Collator
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.min

// ---------------------------------------------------------------- installed apps

class AppRepository(private val context: Context) {
    interface Listener {
        fun onAppsChanged() {}
        fun onPackageAdded(pkg: String) {}
        fun onPackageRemoved(pkg: String) {}
    }

    private val launcherApps: LauncherApps = context.getSystemService(LauncherApps::class.java)
    private val user: UserHandle = Process.myUserHandle()
    private val infos = ConcurrentHashMap<ComponentName, LauncherActivityInfo>()
    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile
    var apps: List<AppInfo> = emptyList()
        private set

    @Volatile
    var loaded = false
        private set

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            reload { listeners.forEach { it.onPackageRemoved(packageName) } }
        }

        override fun onPackageAdded(packageName: String, user: UserHandle) {
            reload { listeners.forEach { it.onPackageAdded(packageName) } }
        }

        override fun onPackageChanged(packageName: String, user: UserHandle) {
            reload()
        }

        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) {
            reload()
        }

        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) {
            reload()
        }
    }

    init {
        launcherApps.registerCallback(callback, Async.main)
    }

    fun addListener(l: Listener) {
        listeners += l
    }

    fun removeListener(l: Listener) {
        listeners -= l
    }

    @Synchronized
    fun loadSync() {
        val list = try {
            launcherApps.getActivityList(null, user)
        } catch (e: Exception) {
            emptyList<LauncherActivityInfo>()
        }
        val collator = Collator.getInstance()
        val map = HashMap<ComponentName, LauncherActivityInfo>()
        val result = ArrayList<AppInfo>(list.size)
        for (info in list) {
            val cn = info.componentName
            map[cn] = info
            result += AppInfo(cn, info.label?.toString() ?: cn.packageName, user)
        }
        result.sortWith { a, b -> collator.compare(a.label, b.label) }
        infos.clear()
        infos.putAll(map)
        apps = result
        loaded = true
    }

    fun reload(after: (() -> Unit)? = null) {
        Async.io {
            loadSync()
            Async.ui {
                listeners.forEach { it.onAppsChanged() }
                after?.invoke()
            }
        }
    }

    fun info(c: ComponentName): LauncherActivityInfo? = infos[c]
    fun find(c: ComponentName): AppInfo? = apps.firstOrNull { it.component == c }

    fun label(c: ComponentName): String {
        find(c)?.let { return it.label }
        return try {
            val pm = context.packageManager
            pm.getActivityInfo(c, 0).loadLabel(pm).toString()
        } catch (e: Exception) {
            c.packageName
        }
    }

    fun isSystemApp(c: ComponentName): Boolean {
        val ai = info(c)?.applicationInfo ?: return true
        return (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
    }
}

// ---------------------------------------------------------------- home layout (JSON on disk)

class LayoutStore(context: Context) {
    private val file = File(context.filesDir, "home_layout.json")
    private val writer = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    val items = ArrayList<HomeItem>()
    var pageCount = 1
    var loaded = false
        private set
    private var nextId = 1L

    fun load() {
        items.clear()
        pageCount = 1
        if (file.exists()) {
            try {
                val root = JSONObject(file.readText())
                pageCount = root.optInt("pages", 1).coerceAtLeast(1)
                val arr = root.optJSONArray("items") ?: JSONArray()
                for (i in 0 until arr.length()) items += HomeItem.fromJson(arr.getJSONObject(i))
            } catch (e: Exception) {
                items.clear()
                pageCount = 1
            }
        }
        nextId = (items.maxOfOrNull { it.id } ?: 0L) + 1
        loaded = true
    }

    fun save() {
        val root = JSONObject()
        root.put("pages", pageCount)
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        root.put("items", arr)
        val text = root.toString()
        writer.execute {
            try {
                val tmp = File(file.path + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) file.writeText(text)
            } catch (e: Exception) {
                // disk full etc. - layout stays in memory
            }
        }
    }

    fun addListener(l: () -> Unit) {
        listeners += l
    }

    fun removeListener(l: () -> Unit) {
        listeners -= l
    }

    /** Save and tell the UI to re-bind. */
    fun commit() {
        save()
        Async.ui { listeners.forEach { it() } }
    }

    fun newId(): Long = nextId++
    fun byId(id: Long): HomeItem? = items.firstOrNull { it.id == id }
    fun topLevel(): List<HomeItem> = items.filter { it.container == HomeItem.DESKTOP || it.container == HomeItem.DOCK }
    fun onPage(page: Int): List<HomeItem> = items.filter { it.container == HomeItem.DESKTOP && it.page == page }
    fun folderChildren(folderId: Long): List<HomeItem> = items.filter { it.container == folderId }.sortedBy { it.rank }
    fun containsApp(c: ComponentName): Boolean = items.any { it.type == HomeItem.TYPE_APP && it.component == c }

    private fun inContainer(it: HomeItem, container: Long, page: Int) =
        it.container == container && (container == HomeItem.DOCK || it.page == page)

    fun occupied(container: Long, page: Int, cols: Int, rows: Int, ignore: Long = -1): Array<BooleanArray> {
        val grid = Array(cols.coerceAtLeast(1)) { BooleanArray(rows.coerceAtLeast(1)) }
        for (it in items) {
            if (!inContainer(it, container, page) || it.id == ignore) continue
            for (x in it.x until min(cols, it.x + it.spanX)) for (y in it.y until min(rows, it.y + it.spanY)) {
                if (x >= 0 && y >= 0) grid[x][y] = true
            }
        }
        return grid
    }

    fun isFree(grid: Array<BooleanArray>, x: Int, y: Int, sx: Int, sy: Int, cols: Int, rows: Int): Boolean {
        if (x < 0 || y < 0 || x + sx > cols || y + sy > rows) return false
        for (i in x until x + sx) for (j in y until y + sy) if (grid[i][j]) return false
        return true
    }

    fun findFree(container: Long, page: Int, sx: Int, sy: Int, cols: Int, rows: Int, ignore: Long = -1): android.graphics.Point? {
        val g = occupied(container, page, cols, rows, ignore)
        for (y in 0..rows - sy) for (x in 0..cols - sx) {
            if (isFree(g, x, y, sx, sy, cols, rows)) return android.graphics.Point(x, y)
        }
        return null
    }

    fun findNearestFree(
        container: Long, page: Int, cx: Int, cy: Int, sx: Int, sy: Int, cols: Int, rows: Int, ignore: Long
    ): android.graphics.Point? {
        val g = occupied(container, page, cols, rows, ignore)
        var best: android.graphics.Point? = null
        var bestD = Int.MAX_VALUE
        for (y in 0..rows - sy) for (x in 0..cols - sx) {
            if (isFree(g, x, y, sx, sy, cols, rows)) {
                val d = (x - cx) * (x - cx) + (y - cy) * (y - cy)
                if (d < bestD) {
                    bestD = d
                    best = android.graphics.Point(x, y)
                }
            }
        }
        return best
    }

    fun itemAt(container: Long, page: Int, x: Int, y: Int, ignore: Long = -1): HomeItem? = items.firstOrNull {
        inContainer(it, container, page) && it.id != ignore &&
            x >= it.x && x < it.x + it.spanX && y >= it.y && y < it.y + it.spanY
    }

    /** First free spot on any page from [startPage] on; adds pages when everything is full. */
    fun placeOnDesktop(item: HomeItem, cols: Int, rows: Int, startPage: Int = 0): Boolean {
        item.spanX = item.spanX.coerceIn(1, cols)
        item.spanY = item.spanY.coerceIn(1, rows)
        var p = startPage.coerceAtLeast(0)
        while (p < 60) {
            if (p >= pageCount) pageCount = p + 1
            val pt = findFree(HomeItem.DESKTOP, p, item.spanX, item.spanY, cols, rows, item.id)
            if (pt != null) {
                item.container = HomeItem.DESKTOP
                item.page = p
                item.x = pt.x
                item.y = pt.y
                if (!items.contains(item)) items += item
                return true
            }
            p++
        }
        return false
    }

    fun createFolder(target: HomeItem, dragged: HomeItem): HomeItem {
        val folder = HomeItem(
            newId(), HomeItem.TYPE_FOLDER, target.container, target.page, target.x, target.y,
            title = "Folder"
        )
        items += folder
        target.container = folder.id
        target.rank = 0
        dragged.container = folder.id
        dragged.rank = 1
        if (!items.contains(dragged)) items += dragged
        return folder
    }

    fun addToFolder(folder: HomeItem, item: HomeItem) {
        item.rank = folderChildren(folder.id).count { it.id != item.id }
        item.container = folder.id
        if (!items.contains(item)) items += item
    }

    /** Removes an item (and a folder's contents). Returns everything removed. */
    fun remove(item: HomeItem): List<HomeItem> {
        val gone = ArrayList<HomeItem>()
        gone += item
        if (item.type == HomeItem.TYPE_FOLDER) gone += items.filter { it.container == item.id }
        items.removeAll(gone.toSet())
        cleanupFolders()
        return gone
    }

    fun removeComponent(c: ComponentName): List<HomeItem> {
        val gone = items.filter { it.type == HomeItem.TYPE_APP && it.component == c }
        items.removeAll(gone.toSet())
        cleanupFolders()
        return gone
    }

    fun removePackage(pkg: String): List<HomeItem> {
        val gone = items.filter {
            (it.type == HomeItem.TYPE_APP || it.type == HomeItem.TYPE_WIDGET) && it.component?.packageName == pkg
        }
        items.removeAll(gone.toSet())
        cleanupFolders()
        return gone
    }

    /** Empty folders disappear; a folder with a single app turns back into that app. */
    fun cleanupFolders() {
        val folders = items.filter { it.type == HomeItem.TYPE_FOLDER }
        for (f in folders) {
            val kids = folderChildren(f.id)
            when {
                kids.isEmpty() -> items.remove(f)
                kids.size == 1 -> {
                    val k = kids[0]
                    k.container = f.container
                    k.page = f.page
                    k.x = f.x
                    k.y = f.y
                    k.spanX = 1
                    k.spanY = 1
                    items.remove(f)
                }
                else -> kids.forEachIndexed { i, k -> k.rank = i }
            }
        }
    }

    fun isPageEmpty(page: Int): Boolean = items.none { it.container == HomeItem.DESKTOP && it.page == page }

    fun deletePage(page: Int): List<HomeItem> {
        if (pageCount <= 1) return emptyList()
        val onIt = onPage(page)
        val gone = ArrayList<HomeItem>()
        for (i in onIt) gone += remove(i)
        items.filter { it.container == HomeItem.DESKTOP && it.page > page }.forEach { it.page-- }
        pageCount--
        return gone
    }

    /** Re-flows items after the grid size changed. */
    fun fitToGrid(cols: Int, rows: Int, dockCount: Int) {
        val misfits = ArrayList<HomeItem>()
        for (it in items) {
            if (it.container == HomeItem.DESKTOP) {
                if (it.x < 0 || it.y < 0 || it.x + it.spanX > cols || it.y + it.spanY > rows) misfits += it
            } else if (it.container == HomeItem.DOCK) {
                if (it.x >= dockCount) misfits += it
            }
        }
        if (misfits.isEmpty()) return
        // park them so they don't block each other while we look for space
        for (m in misfits) m.container = PARKED
        for (m in misfits) {
            m.spanX = m.spanX.coerceAtMost(cols)
            m.spanY = m.spanY.coerceAtMost(rows)
            placeOnDesktop(m, cols, rows, if (m.page >= 0) m.page else 0)
        }
    }

    companion object {
        private const val PARKED = -999L
    }
}

// ---------------------------------------------------------------- icons

object IconShapes {
    val ALL = listOf(
        "squircle" to "Squircle (One UI)",
        "circle" to "Circle",
        "rounded" to "Rounded square",
        "teardrop" to "Teardrop",
        "system" to "System default"
    )

    fun path(shape: String, s: Float): Path {
        val p = Path()
        val k = s / 100f
        when (shape) {
            "circle" -> p.addCircle(s / 2f, s / 2f, s / 2f, Path.Direction.CW)
            "rounded" -> p.addRoundRect(RectF(0f, 0f, s, s), s * 0.24f, s * 0.24f, Path.Direction.CW)
            "teardrop" -> {
                p.moveTo(50 * k, 0f)
                p.lineTo(88 * k, 0f)
                p.cubicTo(94.4f * k, 0f, 100 * k, 5.4f * k, 100 * k, 12 * k)
                p.lineTo(100 * k, 50 * k)
                p.cubicTo(100 * k, 77.6f * k, 77.6f * k, 100 * k, 50 * k, 100 * k)
                p.cubicTo(22.4f * k, 100 * k, 0f, 77.6f * k, 0f, 50 * k)
                p.cubicTo(0f, 22.4f * k, 22.4f * k, 0f, 50 * k, 0f)
                p.close()
            }
            else -> { // squircle, a little squarer than AOSP's
                p.moveTo(50 * k, 0f)
                p.cubicTo(8 * k, 0f, 0f, 8 * k, 0f, 50 * k)
                p.cubicTo(0f, 92 * k, 8 * k, 100 * k, 50 * k, 100 * k)
                p.cubicTo(92 * k, 100 * k, 100 * k, 92 * k, 100 * k, 50 * k)
                p.cubicTo(100 * k, 8 * k, 92 * k, 0f, 50 * k, 0f)
                p.close()
            }
        }
        return p
    }

    fun render(d: Drawable, size: Int, shape: String): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (shape != "system" && d is AdaptiveIconDrawable) {
            val layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val lc = Canvas(layer)
            val inset = size / 4 // adaptive layers are 1.5x the visible area
            d.background?.let {
                it.setBounds(-inset, -inset, size + inset, size + inset)
                it.draw(lc)
            }
            d.foreground?.let {
                it.setBounds(-inset, -inset, size + inset, size + inset)
                it.draw(lc)
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            paint.shader = BitmapShader(layer, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            c.drawPath(path(shape, size.toFloat()), paint)
        } else {
            val pad = if (shape == "system") 0 else size / 16
            d.setBounds(pad, pad, size - pad, size - pad)
            d.draw(c)
        }
        return bmp
    }

    fun folder(icons: List<Bitmap>, color: Int, size: Int, shape: String): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        bg.color = if (color == 0) 0x66F2F2F2 else ColorUtil.withAlpha(color, 0xEE)
        c.drawPath(path(if (shape == "system") "squircle" else shape, size.toFloat()), bg)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val pad = size * 0.17f
        val gap = size * 0.07f
        val cell = (size - 2 * pad - gap) / 2f
        icons.take(4).forEachIndexed { i, b ->
            val l = pad + (i % 2) * (cell + gap)
            val t = pad + (i / 2) * (cell + gap)
            c.drawBitmap(b, null, RectF(l, t, l + cell, t + cell), paint)
        }
        return bmp
    }
}

class IconCache(private val context: Context, private val repo: AppRepository, private val prefs: Prefs) {
    val iconPx: Int = context.dp(72).coerceIn(96, 288)
    private val cache = object : LruCache<String, Bitmap>(maxKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private fun maxKb(): Int = (Runtime.getRuntime().maxMemory() / 1024 / 10).toInt().coerceAtLeast(8 * 1024)

    private var shapeKey = prefs.iconShape

    fun peek(c: ComponentName): Bitmap? {
        val shape = prefs.iconShape
        if (shape != shapeKey) {
            // icon shape changed (maybe from another screen) - drop the old renders
            shapeKey = shape
            cache.evictAll()
        }
        return cache.get(c.flattenToString())
    }

    fun getSync(c: ComponentName): Bitmap {
        peek(c)?.let { return it }
        val density = context.resources.displayMetrics.densityDpi
        val d: Drawable = try {
            repo.info(c)?.getIcon(density) ?: context.packageManager.getActivityIcon(c)
        } catch (e: Exception) {
            context.packageManager.defaultActivityIcon
        }
        val bmp = IconShapes.render(d, iconPx, prefs.iconShape)
        cache.put(c.flattenToString(), bmp)
        return bmp
    }

    fun load(c: ComponentName, cb: (Bitmap) -> Unit) {
        val hit = peek(c)
        if (hit != null) {
            cb(hit)
            return
        }
        Async.io {
            val b = getSync(c)
            Async.ui { cb(b) }
        }
    }

    fun render(d: Drawable): Bitmap = IconShapes.render(d, iconPx, prefs.iconShape)

    fun clear() {
        cache.evictAll()
    }
}

// ---------------------------------------------------------------- notification badges

object Badges {
    @Volatile
    private var counts: Map<String, Int> = emptyMap()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun count(pkg: String?): Int = if (pkg == null) 0 else counts[pkg] ?: 0

    fun update(map: Map<String, Int>) {
        counts = map
        Async.ui { listeners.forEach { it() } }
    }

    fun addListener(l: () -> Unit) {
        listeners += l
    }

    fun removeListener(l: () -> Unit) {
        listeners -= l
    }

    fun hasAccess(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(ctx, BadgeListenerService::class.java)
        return flat.split(":").any { ComponentName.unflattenFromString(it) == me }
    }
}

class BadgeListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        recompute()
    }

    override fun onListenerDisconnected() {
        Badges.update(emptyMap())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        recompute()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        recompute()
    }

    private fun recompute() {
        val active: Array<StatusBarNotification> = try {
            activeNotifications ?: return
        } catch (e: Exception) {
            return
        }
        val map = HashMap<String, Int>()
        for (s in active) {
            val n = s.notification ?: continue
            if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) continue
            if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0) continue
            if (!s.isClearable) continue
            map[s.packageName] = (map[s.packageName] ?: 0) + 1
        }
        Badges.update(map)
    }
}

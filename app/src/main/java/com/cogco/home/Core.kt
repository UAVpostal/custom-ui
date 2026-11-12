package com.cogco.home

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Application singleton: owns the shared stores so the home screen and the edge service see the same data. */
class LauncherApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var repo: AppRepository
        private set
    lateinit var icons: IconCache
        private set
    lateinit var layout: LayoutStore
        private set
    lateinit var widgetHost: WidgetHost
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        repo = AppRepository(this)
        icons = IconCache(this, repo, prefs)
        layout = LayoutStore(this)
        layout.load()
        widgetHost = WidgetHost(this)
        repo.addListener(object : AppRepository.Listener {
            override fun onAppsChanged() {
                ensureHomeOnly()
            }

            override fun onPackageAdded(pkg: String) {
                onNewPackage(pkg)
            }

            override fun onPackageRemoved(pkg: String) {
                onPackageGone(pkg)
            }
        })
        repo.reload()
    }

    private fun newAppItem(a: AppInfo) =
        HomeItem(layout.newId(), HomeItem.TYPE_APP, HomeItem.DESKTOP, component = a.component, title = a.label)

    /** "Add new apps to Home screen" (and Home-screen-only mode). */
    fun onNewPackage(pkg: String) {
        if (!prefs.addNewApps && !prefs.homeOnly) return
        val hidden = prefs.hiddenApps
        var changed = false
        repo.apps.filter { it.component.packageName == pkg && it.key !in hidden }.forEach { a ->
            if (!layout.containsApp(a.component)) {
                layout.placeOnDesktop(newAppItem(a), prefs.gridCols, prefs.gridRows)
                changed = true
            }
        }
        if (changed) layout.commit()
    }

    fun onPackageGone(pkg: String) {
        if (repo.apps.any { it.component.packageName == pkg }) return // just an update
        val removed = layout.removePackage(pkg)
        removed.filter { it.type == HomeItem.TYPE_WIDGET && it.widgetId >= 0 }
            .forEach { widgetHost.deleteAppWidgetId(it.widgetId) }
        val edge = prefs.edgeApps
        val kept = edge.filter { it.packageName != pkg }
        if (kept.size != edge.size) prefs.edgeApps = kept
        if (removed.isNotEmpty()) layout.commit()
    }

    /** In "Home screen only" mode every visible app lives on a home page. */
    fun ensureHomeOnly() {
        if (!prefs.homeOnly || !layout.loaded || repo.apps.isEmpty()) return
        val hidden = prefs.hiddenApps
        var changed = false
        for (a in repo.apps) {
            if (a.key in hidden) continue
            if (!layout.containsApp(a.component)) {
                layout.placeOnDesktop(newAppItem(a), prefs.gridCols, prefs.gridRows)
                changed = true
            }
        }
        if (changed) layout.commit()
    }

    companion object {
        lateinit var instance: LauncherApp
            private set
    }
}

val Context.app: LauncherApp get() = applicationContext as LauncherApp

// ---------------------------------------------------------------- models

data class AppInfo(val component: ComponentName, val label: String, val user: UserHandle) {
    val key: String get() = component.flattenToString()
}

data class Contact(val name: String, val number: String)

/** One thing placed on the home screen, the dock, or inside a folder. */
class HomeItem(
    var id: Long,
    var type: Int,
    var container: Long,
    var page: Int = 0,
    var x: Int = 0,
    var y: Int = 0,
    var spanX: Int = 1,
    var spanY: Int = 1,
    /** App component, or the widget provider for widgets. */
    var component: ComponentName? = null,
    var title: String = "",
    var widgetId: Int = -1,
    /** Folder colour (0 = default translucent). */
    var color: Int = 0,
    var rank: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type)
        put("container", container)
        put("page", page)
        put("x", x)
        put("y", y)
        put("sx", spanX)
        put("sy", spanY)
        component?.let { put("cmp", it.flattenToString()) }
        put("title", title)
        put("wid", widgetId)
        put("color", color)
        put("rank", rank)
    }

    companion object {
        const val TYPE_APP = 0
        const val TYPE_FOLDER = 1
        const val TYPE_WIDGET = 2
        const val DESKTOP = -100L
        const val DOCK = -101L

        fun fromJson(o: JSONObject): HomeItem {
            val cmp = o.optString("cmp", "")
            return HomeItem(
                id = o.getLong("id"),
                type = o.getInt("type"),
                container = o.getLong("container"),
                page = o.optInt("page", 0),
                x = o.optInt("x", 0),
                y = o.optInt("y", 0),
                spanX = o.optInt("sx", 1).coerceAtLeast(1),
                spanY = o.optInt("sy", 1).coerceAtLeast(1),
                component = if (cmp.isEmpty()) null else ComponentName.unflattenFromString(cmp),
                title = o.optString("title", ""),
                widgetId = o.optInt("wid", -1),
                color = o.optInt("color", 0),
                rank = o.optInt("rank", 0)
            )
        }
    }
}

// ---------------------------------------------------------------- preferences

class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences("cogco_home", Context.MODE_PRIVATE)

    private class IntPref(val key: String, val def: Int) : ReadWriteProperty<Prefs, Int> {
        override fun getValue(thisRef: Prefs, property: KProperty<*>): Int = thisRef.sp.getInt(key, def)
        override fun setValue(thisRef: Prefs, property: KProperty<*>, value: Int) {
            thisRef.sp.edit().putInt(key, value).apply()
        }
    }

    private class BoolPref(val key: String, val def: Boolean) : ReadWriteProperty<Prefs, Boolean> {
        override fun getValue(thisRef: Prefs, property: KProperty<*>): Boolean = thisRef.sp.getBoolean(key, def)
        override fun setValue(thisRef: Prefs, property: KProperty<*>, value: Boolean) {
            thisRef.sp.edit().putBoolean(key, value).apply()
        }
    }

    private class StrPref(val key: String, val def: String) : ReadWriteProperty<Prefs, String> {
        override fun getValue(thisRef: Prefs, property: KProperty<*>): String = thisRef.sp.getString(key, def) ?: def
        override fun setValue(thisRef: Prefs, property: KProperty<*>, value: String) {
            thisRef.sp.edit().putString(key, value).apply()
        }
    }

    // ---- Home screen
    var gridCols by IntPref(K_GRID_COLS, 4)
    var gridRows by IntPref(K_GRID_ROWS, 5)
    var dockCount by IntPref(K_DOCK, 4)
    var appsCols by IntPref(K_APPS_COLS, 4)
    var appsRows by IntPref(K_APPS_ROWS, 6)
    var iconScale by IntPref(K_ICON_SCALE, 100)
    var showLabels by BoolPref(K_LABELS, true)
    var iconShape by StrPref(K_SHAPE, "squircle")
    var drawerLayout by StrPref(K_APPS_LAYOUT, "paged")
    var drawerBg by StrPref(K_APPS_BG, "dark")
    var homeOnly by BoolPref(K_HOME_ONLY, false)
    var addNewApps by BoolPref("add_new_apps", true)
    var lockLayout by BoolPref("lock_layout", false)
    var swipeDownNotif by BoolPref("swipe_down_notif", true)
    var badges by BoolPref(K_BADGES, true)
    var badgeNumbers by BoolPref(K_BADGE_NUM, false)
    var firstRunDone by BoolPref("first_run_done", false)
    var pendingWidgetId by IntPref("pending_widget", -1)

    var hiddenApps: Set<String>
        get() = sp.getStringSet(K_HIDDEN, null)?.toSet() ?: emptySet()
        set(v) {
            sp.edit().putStringSet(K_HIDDEN, HashSet(v)).apply()
        }

    // ---- Edge panels (all keys start with "edge_" so the service can watch them)
    var edgeEnabled by BoolPref("edge_enabled", false)
    var edgeSide by StrPref("edge_side", "right")
    var handlePos by IntPref("edge_handle_pos", 28)
    var handleHeight by IntPref("edge_handle_height", 18)
    var handleWidth by IntPref("edge_handle_width", 8)
    var handleColor by IntPref("edge_handle_color", 0xFFFFFFFF.toInt())
    var handleAlpha by IntPref("edge_handle_alpha", 55)
    var handleLocked by BoolPref("edge_handle_locked", false)
    var edgeHaptic by BoolPref("edge_haptic", true)
    var edgeBlur by BoolPref("edge_blur", false)
    var edgeDim by IntPref("edge_dim", 30)
    var panelSize by StrPref("edge_panel_size", "normal")
    var edgeItemsBottom by BoolPref("edge_items_bottom", true)
    var defaultScheme by StrPref("edge_scheme_default", "oneui_light")
    var tally by IntPref("edge_tally", 0)
    var customSchemesJson by StrPref("edge_custom_schemes", "[]")

    var panelOrder: List<String>
        get() {
            val saved = (sp.getString("edge_panel_order", null) ?: DEFAULT_PANELS).split(",").filter { it.isNotBlank() }
            // keep any panel added in a later version
            return saved + DEFAULT_PANELS.split(",").filter { it !in saved }
        }
        set(v) {
            sp.edit().putString("edge_panel_order", v.joinToString(",")).apply()
        }

    var panelsEnabled: Set<String>
        get() = sp.getStringSet("edge_panels_enabled", null)?.toSet() ?: DEFAULT_PANELS.split(",").toSet()
        set(v) {
            sp.edit().putStringSet("edge_panels_enabled", HashSet(v)).apply()
        }

    fun schemeFor(panel: String): String = sp.getString("edge_scheme_$panel", "") ?: ""
    fun setSchemeFor(panel: String, id: String) {
        sp.edit().putString("edge_scheme_$panel", id).apply()
    }

    var edgeApps: List<ComponentName>
        get() = (sp.getString("edge_apps", "") ?: "").split("\n")
            .mapNotNull { if (it.isBlank()) null else ComponentName.unflattenFromString(it) }
        set(v) {
            sp.edit().putString("edge_apps", v.joinToString("\n") { it.flattenToString() }).apply()
        }

    var tools: List<String>
        get() = (sp.getString("edge_tools", null) ?: DEFAULT_TOOLS).split(",").filter { it.isNotBlank() }
        set(v) {
            sp.edit().putString("edge_tools", v.joinToString(",")).apply()
        }

    var contacts: List<Contact>
        get() = try {
            val a = JSONArray(sp.getString("edge_contacts", null) ?: "[]")
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Contact(o.optString("name", ""), o.optString("number", ""))
            }
        } catch (e: Exception) {
            emptyList()
        }
        set(v) {
            val a = JSONArray()
            v.forEach { a.put(JSONObject().put("name", it.name).put("number", it.number)) }
            sp.edit().putString("edge_contacts", a.toString()).apply()
        }

    var notes: List<String>
        get() = try {
            val a = JSONArray(sp.getString("edge_notes", null) ?: "[]")
            (0 until a.length()).map { a.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
        set(v) {
            val a = JSONArray()
            v.forEach { a.put(it) }
            sp.edit().putString("edge_notes", a.toString()).apply()
        }

    companion object {
        const val K_GRID_COLS = "home_cols"
        const val K_GRID_ROWS = "home_rows"
        const val K_DOCK = "home_dock"
        const val K_APPS_COLS = "apps_cols"
        const val K_APPS_ROWS = "apps_rows"
        const val K_ICON_SCALE = "icon_scale"
        const val K_LABELS = "show_labels"
        const val K_SHAPE = "icon_shape"
        const val K_APPS_LAYOUT = "apps_layout"
        const val K_APPS_BG = "apps_bg"
        const val K_HOME_ONLY = "home_only"
        const val K_BADGES = "badges"
        const val K_BADGE_NUM = "badge_numbers"
        const val K_HIDDEN = "hidden_apps"
        const val DEFAULT_PANELS = "apps,people,tools,notes"
        const val DEFAULT_TOOLS = "flashlight,compass,counter,calculator,camera,alarm,wifi,volume"
        val ALL_TOOLS = listOf(
            "flashlight", "compass", "counter", "calculator", "camera",
            "alarm", "wifi", "volume", "bluetooth", "settings"
        )
    }
}

// ---------------------------------------------------------------- small helpers

object Async {
    val main = Handler(Looper.getMainLooper())
    private val pool: ExecutorService = Executors.newFixedThreadPool(3)

    fun io(block: () -> Unit) {
        pool.execute { block() }
    }

    fun ui(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density
fun Context.spf(v: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

fun View.dp(v: Int): Int = context.dp(v)
fun View.dpf(v: Float): Float = context.dpf(v)

fun Context.isNight(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

fun Context.toast(msg: String) {
    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

fun Context.vibrateTick(ms: Long = 14) {
    try {
        @Suppress("DEPRECATION")
        val v = getSystemService(Vibrator::class.java) ?: return
        if (!v.hasVibrator()) return
        v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    } catch (e: Exception) {
        // some devices throw without the vibrator service; haptics are optional
    }
}

fun Context.screenSize(): Point {
    return try {
        val wm = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            p
        }
    } catch (e: Exception) {
        val dm = resources.displayMetrics
        Point(dm.widthPixels, dm.heightPixels)
    }
}

object ColorUtil {
    fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    fun blend(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        val inv = 1f - u
        return Color.argb(
            (Color.alpha(a) * inv + Color.alpha(b) * u).toInt(),
            (Color.red(a) * inv + Color.red(b) * u).toInt(),
            (Color.green(a) * inv + Color.green(b) * u).toInt(),
            (Color.blue(a) * inv + Color.blue(b) * u).toInt()
        )
    }

    fun luminance(c: Int): Double =
        (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0

    /** Black or white, whichever reads better on [bg]. */
    fun onColor(bg: Int): Int = if (luminance(bg) > 0.62) 0xFF111111.toInt() else Color.WHITE

    fun toHex(c: Int): String = String.format("#%06X", c and 0xFFFFFF)

    fun parse(s: String): Int? {
        val t = s.trim()
        if (t.isEmpty()) return null
        return try {
            Color.parseColor(if (t.startsWith("#")) t else "#$t")
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

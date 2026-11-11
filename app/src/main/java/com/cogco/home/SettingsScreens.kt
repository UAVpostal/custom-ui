package com.cogco.home

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import kotlin.math.ceil

// ====================================================================== Home screen settings

class SettingsActivity : SettingsBase() {
    override val screenTitle = "Home screen"

    override fun build() {
        if (!isDefaultHome()) {
            section {
                action("Set Cogco Home as default", "Needed for the Home button, app shortcuts and widgets", true, Glyph.HOME) {
                    requestDefaultHome()
                }
            }
        }

        section("Layout") {
            choice(
                "Home screen layout",
                listOf("apps" to "Home and Apps screens", "home_only" to "Home screen only"),
                if (prefs.homeOnly) "home_only" else "apps", Glyph.HOME
            ) { prefs.homeOnly = it == "home_only" }
            choice("Home screen grid", HOME_GRIDS.map { it to it.replace("x", " × ") }, "${prefs.gridCols}x${prefs.gridRows}", Glyph.WIDGETS) {
                val (c, r) = it.split("x").map { n -> n.toInt() }
                prefs.gridCols = c
                prefs.gridRows = r
            }
            if (!prefs.homeOnly) {
                choice("Apps screen grid", APPS_GRIDS.map { it to it.replace("x", " × ") }, "${prefs.appsCols}x${prefs.appsRows}", Glyph.APPS) {
                    val (c, r) = it.split("x").map { n -> n.toInt() }
                    prefs.appsCols = c
                    prefs.appsRows = r
                }
                choice("Apps screen layout", listOf("paged" to "Swipe sideways (pages)", "vertical" to "Scroll up and down"), prefs.drawerLayout) {
                    prefs.drawerLayout = it
                }
                choice("Apps screen background", listOf("dark" to "Dark", "light" to "Light"), prefs.drawerBg) { prefs.drawerBg = it }
            }
            choice("Dock icons", listOf("4" to "4", "5" to "5", "6" to "6"), prefs.dockCount.toString()) { prefs.dockCount = it.toInt() }
        }

        section("Icons") {
            choice("Icon shape", IconShapes.ALL, prefs.iconShape, Glyph.PALETTE) { prefs.iconShape = it }
            slider("Icon size", 70, 130, prefs.iconScale, { "$it%" }) { prefs.iconScale = it }
            switch("Show app names", null, prefs.showLabels) { prefs.showLabels = it }
            val access = Badges.hasAccess(this@SettingsActivity)
            switch(
                "App icon badges",
                if (access) "Show a badge on apps with notifications" else "Needs notification access. Tap to allow it",
                prefs.badges && access
            ) { on ->
                prefs.badges = on
                if (on && !access) openNotificationAccess()
            }
            if (prefs.badges && access) {
                choice("Badge style", listOf("dot" to "Dot", "number" to "Number"), if (prefs.badgeNumbers) "number" else "dot") {
                    prefs.badgeNumbers = it == "number"
                }
            }
        }

        section("Behaviour") {
            switch("Add new apps to Home screen", null, prefs.addNewApps) { prefs.addNewApps = it }
            switch("Lock Home screen layout", "Stop items being moved or removed", prefs.lockLayout) { prefs.lockLayout = it }
            switch("Swipe down for notification panel", "Swipe down anywhere on the Home screen", prefs.swipeDownNotif) {
                prefs.swipeDownNotif = it
            }
            val hidden = prefs.hiddenApps.size
            action("Hide apps", if (hidden == 0) "No hidden apps" else "$hidden hidden", false, Glyph.HIDE) {
                startActivity(Intent(this@SettingsActivity, AppPickerActivity::class.java).putExtra(AppPickerActivity.EXTRA_MODE, AppPickerActivity.MODE_HIDE))
            }
        }

        section("Edge panels") {
            action("Edge panels", if (prefs.edgeEnabled) "On" else "Off", true, Glyph.EDGE) {
                startActivity(Intent(this@SettingsActivity, EdgeSettingsActivity::class.java))
            }
        }

        section("About") {
            action("Open the Home screen", "Try it without making it the default") {
                startActivity(Intent(this@SettingsActivity, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            text("Long-press an empty spot on the Home screen for wallpapers, widgets and these settings. Swipe up for the Apps screen.")
        }
    }

    @Suppress("DEPRECATION")
    private fun isDefaultHome(): Boolean {
        val ri = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        return ri?.activityInfo?.packageName == packageName
    }

    private fun requestDefaultHome() {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                try {
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_ROLE)
                    return
                } catch (e: Exception) {
                    // fall back to the system screen
                }
            }
        }
        openHomeSettings()
    }

    private fun openHomeSettings() {
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun openNotificationAccess() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            toast("Open Settings > Notifications > Notification access")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ROLE && resultCode != RESULT_OK && !isDefaultHome()) openHomeSettings()
    }

    companion object {
        private const val REQ_ROLE = 77
        val HOME_GRIDS = listOf("4x5", "4x6", "5x5", "5x6", "6x6", "6x7")
        val APPS_GRIDS = listOf("4x5", "4x6", "5x5", "5x6", "6x7")
    }
}

// ====================================================================== Edge panel settings

class EdgeSettingsActivity : SettingsBase() {
    override val screenTitle = "Edge panels"
    private var wantEnable = false

    override fun onResume() {
        if (wantEnable && Settings.canDrawOverlays(this)) {
            wantEnable = false
            prefs.edgeEnabled = true
            askNotificationPermission()
        }
        super.onResume()
        EdgePanelService.sync(this)
    }

    override fun build() {
        val canDraw = Settings.canDrawOverlays(this)
        section {
            switch(
                "Edge panels",
                if (prefs.edgeEnabled && canDraw) "Swipe the handle on the edge of the screen to open your panels" else "Off",
                prefs.edgeEnabled && canDraw, Glyph.EDGE
            ) { on ->
                if (on) enable() else {
                    prefs.edgeEnabled = false
                    EdgePanelService.stop(this@EdgeSettingsActivity)
                }
            }
        }
        if (!canDraw) content.addView(note("Edge panels need permission to display over other apps. You'll be asked for it when you turn them on."))

        content.addView(label("Panels"))
        val enabled = prefs.panelsEnabled
        val card = Card()
        for (id in prefs.panelOrder) card.add(panelRow(id, id in enabled))
        content.addView(card.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(note("Tap a panel to edit what's on it, change its colours or move it. Swipe left and right inside the open panel to switch between them."))

        section("Colours and style") {
            action("Default panel theme", Schemes.get(this@EdgeSettingsActivity, prefs, prefs.defaultScheme).name, true, Glyph.PALETTE) {
                openGallery("default")
            }
            action("Create your own theme", "Colours, gradient, transparency and corners", false, Glyph.PLUS) {
                startActivity(Intent(this@EdgeSettingsActivity, ThemeEditorActivity::class.java))
            }
            choice("Panel size", listOf("compact" to "Compact", "normal" to "Normal", "wide" to "Wide"), prefs.panelSize) { prefs.panelSize = it }
            slider("Background dim", 0, 80, prefs.edgeDim, { "$it%" }) { prefs.edgeDim = it }
            if (Build.VERSION.SDK_INT >= 31) {
                switch("Blur behind panel", "Works on devices that support window blur", prefs.edgeBlur) { prefs.edgeBlur = it }
            }
        }

        section("Handle") {
            choice("Position", listOf("right" to "Right edge", "left" to "Left edge"), prefs.edgeSide) { prefs.edgeSide = it }
            color("Handle colour", prefs.handleColor) { prefs.handleColor = it }
            slider("Handle visibility", 5, 100, prefs.handleAlpha, { "$it%" }, onLive = { prefs.handleAlpha = it }) { prefs.handleAlpha = it }
            slider("Handle size", 8, 50, prefs.handleHeight, { "$it% of screen" }, onLive = { prefs.handleHeight = it }) { prefs.handleHeight = it }
            slider("Handle width", 2, 24, prefs.handleWidth, { "$it dp" }, onLive = { prefs.handleWidth = it }) { prefs.handleWidth = it }
            slider("Vertical position", 0, 100, prefs.handlePos, { "$it%" }, onLive = { prefs.handlePos = it }) { prefs.handlePos = it }
            switch("Lock handle position", "When off, long-press the handle and drag to move it", prefs.handleLocked) { prefs.handleLocked = it }
            switch("Vibrate when opened", null, prefs.edgeHaptic) { prefs.edgeHaptic = it }
        }

        if (prefs.edgeEnabled && canDraw) {
            section {
                action("Open edge panel now", null, false, Glyph.EDGE) { EdgePanelService.start(this@EdgeSettingsActivity, open = true) }
            }
        }
    }

    private fun panelRow(id: String, on: Boolean): View {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.minimumHeight = dp(64)
        r.setPadding(dp(20), dp(12), dp(18), dp(12))
        r.background = ripple()
        val scheme = Schemes.forPanel(this, prefs, id)
        val sw = SchemePreview(this)
        sw.scheme = scheme
        r.addView(sw, LinearLayout.LayoutParams(dp(54), dp(44)).apply { marginEnd = dp(14) })
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.addView(TextView(this).apply {
            text = EdgePanels.title(id)
            setTextColor(cText)
            textSize = 17f
        })
        val own = prefs.schemeFor(id)
        texts.addView(TextView(this).apply {
            text = if (own.isEmpty()) "Default theme · ${scheme.name}" else scheme.name
            setTextColor(cSub)
            textSize = 13.5f
        })
        r.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val toggle = Switch(this)
        toggle.isChecked = on
        toggle.setOnCheckedChangeListener { _, checked ->
            val set = prefs.panelsEnabled.toMutableSet()
            if (checked) set += id else set -= id
            prefs.panelsEnabled = set
        }
        r.addView(toggle)
        r.setOnClickListener { panelOptions(id) }
        return r
    }

    private fun panelOptions(id: String) {
        val items = arrayOf("Edit content", "Colour theme", "Move up", "Move down")
        AlertDialog.Builder(this)
            .setTitle(EdgePanels.title(id))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> editContent(id)
                    1 -> openGallery(id)
                    2 -> move(id, -1)
                    3 -> move(id, 1)
                }
            }
            .show()
    }

    private fun editContent(id: String) {
        when (id) {
            "apps" -> startActivity(Intent(this, AppPickerActivity::class.java).putExtra(AppPickerActivity.EXTRA_MODE, AppPickerActivity.MODE_EDGE))
            "people" -> peopleDialog()
            "tools" -> toolsDialog()
            "notes" -> notesDialog()
        }
    }

    private fun peopleDialog() {
        val contacts = prefs.contacts
        val labels = contacts.map { "${it.name}  ·  ${it.number}" } + "+ Add contact"
        AlertDialog.Builder(this)
            .setTitle("People")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == contacts.size) {
                    startActivity(Intent(this, ContactPickActivity::class.java).putExtra(ContactPickActivity.EXTRA_REOPEN, false))
                } else {
                    AlertDialog.Builder(this)
                        .setMessage("Remove ${contacts[which].name} from the People panel?")
                        .setPositiveButton("Remove") { _, _ ->
                            prefs.contacts = contacts.filterIndexed { i, _ -> i != which }
                            refresh()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .show()
    }

    private fun toolsDialog() {
        val all = Prefs.ALL_TOOLS
        val names = all.map { EdgePanels.TOOL_NAMES[it] ?: it }.toTypedArray()
        val current = prefs.tools
        val checked = BooleanArray(all.size) { all[it] in current }
        AlertDialog.Builder(this)
            .setTitle("Tools")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Save") { _, _ -> prefs.tools = all.filterIndexed { i, _ -> checked[i] } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun notesDialog() {
        val n = prefs.notes.size
        AlertDialog.Builder(this)
            .setTitle("Quick notes")
            .setMessage(if (n == 0) "No notes yet. Add them from the edge panel." else "You have $n note${if (n == 1) "" else "s"}. Clear them all?")
            .setPositiveButton(if (n == 0) "OK" else "Clear all") { _, _ -> if (n > 0) prefs.notes = emptyList() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun move(id: String, d: Int) {
        val order = prefs.panelOrder.toMutableList()
        val i = order.indexOf(id)
        val j = i + d
        if (i < 0 || j < 0 || j >= order.size) return
        order[i] = order[j]
        order[j] = id
        prefs.panelOrder = order
        refresh()
    }

    private fun openGallery(target: String) {
        startActivity(Intent(this, ThemeGalleryActivity::class.java).putExtra(ThemeGalleryActivity.EXTRA_TARGET, target))
    }

    private fun enable() {
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Display over other apps")
                .setMessage("Edge panels show a small handle on the edge of your screen, on top of other apps. On the next screen, allow Cogco Home to display over other apps, then come back.")
                .setPositiveButton("Continue") { _, _ ->
                    wantEnable = true
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    } catch (e: Exception) {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                    }
                }
                .setNegativeButton("Cancel") { _, _ -> refresh() }
                .setOnCancelListener { refresh() }
                .show()
            return
        }
        askNotificationPermission()
        prefs.edgeEnabled = true
        EdgePanelService.start(this)
    }

    /** Only so the "Edge panels are on" notification can show; panels work without it. */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5)
        }
    }
}

// ====================================================================== Theme gallery

class ThemeGalleryActivity : SettingsBase() {
    private val target: String get() = intent.getStringExtra(EXTRA_TARGET) ?: "default"

    override val screenTitle: String
        get() = if (target == "default") "Panel themes" else "${EdgePanels.title(target)} colours"

    override fun build() {
        val current = if (target == "default") prefs.defaultScheme else prefs.schemeFor(target)
        if (target != "default") {
            section {
                val def = Schemes.get(this@ThemeGalleryActivity, prefs, prefs.defaultScheme)
                action(
                    if (current.isEmpty()) "✓  Use the default theme" else "Use the default theme",
                    def.name, current.isEmpty(), Glyph.PALETTE
                ) { select("") }
            }
        }
        val all = Schemes.all(this, prefs)
        content.addView(label("Presets"))
        grid(all.filter { it.builtin }, current)
        content.addView(label("Your themes"))
        val mine = all.filter { !it.builtin }
        if (mine.isEmpty()) content.addView(note("Themes you create show up here. Long-press a preset to start from it."))
        else grid(mine, current)
        section {
            action("Create new theme", "Pick colours, gradient, transparency and corners", false, Glyph.PLUS) {
                startActivity(
                    Intent(this@ThemeGalleryActivity, ThemeEditorActivity::class.java)
                        .putExtra(ThemeEditorActivity.EXTRA_BASE, current.ifEmpty { prefs.defaultScheme })
                        .putExtra(ThemeEditorActivity.EXTRA_TARGET, target)
                )
            }
        }
    }

    private fun grid(list: List<PanelScheme>, current: String) {
        list.chunked(2).forEach { pair ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (s in pair) row.addView(cell(s, s.id == current), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            content.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            })
        }
        content.addView(View(this), LinearLayout.LayoutParams(1, dp(12)))
    }

    private fun cell(s: PanelScheme, chosen: Boolean): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(5), 0, dp(5), 0)
        val p = SchemePreview(this)
        p.scheme = s
        p.chosen = chosen
        p.ringColor = cAccent
        col.addView(p, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)))
        col.addView(TextView(this).apply {
            text = if (chosen) "✓ ${s.name}" else s.name
            setTextColor(if (chosen) cAccent else cText)
            textSize = 14f
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(0, dp(6), 0, 0)
        })
        col.setOnClickListener { select(s.id) }
        col.setOnLongClickListener {
            options(s)
            true
        }
        return col
    }

    private fun select(id: String) {
        if (target == "default") prefs.defaultScheme = id else prefs.setSchemeFor(target, id)
        refresh()
    }

    private fun options(s: PanelScheme) {
        val editor = Intent(this, ThemeEditorActivity::class.java).putExtra(ThemeEditorActivity.EXTRA_TARGET, target)
        if (s.builtin) {
            AlertDialog.Builder(this)
                .setTitle(s.name)
                .setItems(arrayOf("Duplicate and edit")) { _, _ ->
                    startActivity(editor.putExtra(ThemeEditorActivity.EXTRA_BASE, s.id))
                }
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle(s.name)
                .setItems(arrayOf("Edit", "Duplicate", "Delete")) { _, which ->
                    when (which) {
                        0 -> startActivity(editor.putExtra(ThemeEditorActivity.EXTRA_EDIT, s.id))
                        1 -> startActivity(editor.putExtra(ThemeEditorActivity.EXTRA_BASE, s.id))
                        2 -> {
                            Schemes.deleteCustom(prefs, s.id)
                            refresh()
                        }
                    }
                }
                .show()
        }
    }

    companion object {
        const val EXTRA_TARGET = "target"
    }
}

// ====================================================================== Theme editor

class ThemeEditorActivity : SettingsBase() {
    override val screenTitle = "Theme editor"
    private lateinit var draft: PanelScheme
    private var isNew = true
    private var preview: SchemePreview? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val editId = intent.getStringExtra(EXTRA_EDIT)
        val existing = editId?.let { id -> Schemes.custom(prefs).firstOrNull { it.id == id } }
        if (existing != null) {
            draft = existing
            isNew = false
        } else {
            val base = Schemes.get(this, prefs, intent.getStringExtra(EXTRA_BASE) ?: prefs.defaultScheme)
            draft = base.copy(
                id = Schemes.newId(),
                name = if (base.builtin) "My ${base.name}" else "${base.name} copy",
                builtin = false
            )
        }
    }

    override val headerAction: Pair<String, () -> Unit>
        get() = Pair("Save") { save() }

    private fun livePreview() {
        preview?.scheme = draft
    }

    override fun build() {
        val p = SchemePreview(this)
        p.scheme = draft
        preview = p
        content.addView(p, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(210)).apply { bottomMargin = dp(18) })

        section("Name") {
            action(draft.name, "Tap to rename", false, Glyph.EDIT) { rename() }
        }
        section("Background") {
            color(if (draft.gradient) "Top colour" else "Background colour", draft.bgTop) { c ->
                draft = if (draft.gradient) draft.copy(bgTop = c) else draft.copy(bgTop = c, bgBottom = c)
            }
            switch("Gradient", "Blend into a second colour towards the bottom", draft.gradient) { on ->
                draft = if (on) draft.copy(bgBottom = shade(draft.bgTop)) else draft.copy(bgBottom = draft.bgTop)
            }
            if (draft.gradient) color("Bottom colour", draft.bgBottom) { c -> draft = draft.copy(bgBottom = c) }
            slider("Opacity", 10, 100, draft.opacity, { "$it%" }, onLive = {
                draft = draft.copy(opacity = it)
                livePreview()
            }) { draft = draft.copy(opacity = it) }
        }
        section("Content") {
            color("Accent colour", draft.accent) { c -> draft = draft.copy(accent = c) }
            color("Text and icon colour", draft.text) { c -> draft = draft.copy(text = c) }
            action("Pick text colour automatically", "Black or white, whichever reads best on the background") {
                draft = draft.copy(text = ColorUtil.onColor(ColorUtil.blend(draft.bgTop, draft.bgBottom, 0.5f)))
                refresh()
            }
            slider("Corner roundness", 0, 44, draft.radius, { "$it dp" }, onLive = {
                draft = draft.copy(radius = it)
                livePreview()
            }) { draft = draft.copy(radius = it) }
        }
        section {
            action("Save theme", null, false, Glyph.CHECK) { save() }
            if (!isNew) action("Delete theme", null, false, Glyph.TRASH) { delete() }
        }
    }

    private fun shade(c: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[0] = (hsv[0] + 28f) % 360f
        hsv[2] = (hsv[2] * 0.75f).coerceAtLeast(0.15f)
        return Color.HSVToColor(hsv)
    }

    private fun rename() {
        val input = EditText(this)
        input.setText(draft.name)
        input.isSingleLine = true
        input.setSelection(input.text.length)
        val box = LinearLayout(this)
        box.setPadding(dp(22), dp(8), dp(22), 0)
        box.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(this)
            .setTitle("Theme name")
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) draft = draft.copy(name = t)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun save() {
        Schemes.saveCustom(prefs, draft)
        val target = intent.getStringExtra(EXTRA_TARGET)
        if (isNew && target != null) {
            if (target == "default") prefs.defaultScheme = draft.id else prefs.setSchemeFor(target, draft.id)
        }
        toast(if (isNew && target == null) "Theme saved. Pick it under Default panel theme" else "Theme saved")
        finish()
    }

    private fun delete() {
        AlertDialog.Builder(this)
            .setTitle("Delete ${draft.name}?")
            .setPositiveButton("Delete") { _, _ ->
                Schemes.deleteCustom(prefs, draft.id)
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        const val EXTRA_BASE = "base"
        const val EXTRA_EDIT = "edit"
        const val EXTRA_TARGET = "target"
    }
}

// ====================================================================== App picker (hide apps, Apps edge, folders)

class AppPickerActivity : SettingsBase() {
    private val mode: String get() = intent.getStringExtra(EXTRA_MODE) ?: MODE_HIDE
    private val selected = LinkedHashSet<ComponentName>()
    private var query = ""
    private var listCard: LinearLayout? = null

    override val screenTitle: String
        get() = when (mode) {
            MODE_EDGE -> "Apps edge"
            MODE_FOLDER -> "Add apps"
            else -> "Hide apps"
        }

    override val headerAction: Pair<String, () -> Unit>
        get() = Pair("Done") { done() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (mode) {
            MODE_HIDE -> prefs.hiddenApps.mapNotNull { ComponentName.unflattenFromString(it) }.forEach { selected += it }
            MODE_EDGE -> selected += prefs.edgeApps
            MODE_FOLDER -> {
                val fid = intent.getLongExtra(EXTRA_FOLDER, -1L)
                app.layout.folderChildren(fid).mapNotNull { it.component }.forEach { selected += it }
            }
        }
        if (!app.repo.loaded) app.repo.reload { refresh() }
    }

    override fun build() {
        content.addView(note(
            when (mode) {
                MODE_EDGE -> "Choose the apps for your Apps edge panel. They appear in the order you pick them."
                MODE_FOLDER -> "Choose the apps that belong in this folder."
                else -> "Hidden apps don't show on the Home or Apps screens. They stay installed."
            }
        ))
        val search = EditText(this)
        search.hint = "Search apps"
        search.isSingleLine = true
        search.setText(query)
        search.setTextColor(cText)
        search.setHintTextColor(cSub)
        search.background = roundRect(cCard, dpf(24f))
        search.setPadding(dp(20), dp(12), dp(20), dp(12))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString() ?: ""
                renderList()
            }
        })
        content.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14)
        })
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = roundRect(cCard, dpf(26f))
        card.clipToOutline = true
        card.setPadding(0, dp(4), 0, dp(4))
        listCard = card
        content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        renderList()
    }

    private fun renderList() {
        val card = listCard ?: return
        card.removeAllViews()
        val q = query.trim().lowercase()
        var apps = app.repo.apps
        if (mode != MODE_HIDE) {
            val hidden = prefs.hiddenApps
            apps = apps.filter { it.key !in hidden }
        }
        if (q.isNotEmpty()) apps = apps.filter { it.label.lowercase().contains(q) }
        // selected first, then the rest
        val sorted = apps.filter { it.component in selected } + apps.filter { it.component !in selected }
        for (a in sorted) card.addView(appRow(a))
        if (sorted.isEmpty()) {
            card.addView(TextView(this).apply {
                text = if (app.repo.loaded) "No apps found" else "Loading apps…"
                setTextColor(cSub)
                setPadding(dp(20), dp(18), dp(20), dp(18))
            })
        }
    }

    private fun appRow(a: AppInfo): View {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(dp(16), dp(8), dp(14), dp(8))
        r.background = ripple()
        val iv = ImageView(this)
        app.icons.load(a.component) { iv.setImageBitmap(it) }
        r.addView(iv, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(14) })
        r.addView(TextView(this).apply {
            text = a.label
            setTextColor(cText)
            textSize = 16f
            maxLines = 1
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val cb = CheckBox(this)
        cb.isChecked = a.component in selected
        cb.isClickable = false
        cb.isFocusable = false
        r.addView(cb)
        r.setOnClickListener {
            if (a.component in selected) selected -= a.component else selected += a.component
            cb.isChecked = a.component in selected
        }
        return r
    }

    private fun done() {
        val store = app.layout
        when (mode) {
            MODE_HIDE -> {
                val before = prefs.hiddenApps
                val now = selected.map { it.flattenToString() }.toSet()
                prefs.hiddenApps = now
                val newlyHidden = now - before
                if (newlyHidden.isNotEmpty()) {
                    newlyHidden.mapNotNull { ComponentName.unflattenFromString(it) }.forEach { store.removeComponent(it) }
                    store.commit()
                }
                app.ensureHomeOnly()
            }
            MODE_EDGE -> prefs.edgeApps = selected.toList()
            MODE_FOLDER -> {
                val fid = intent.getLongExtra(EXTRA_FOLDER, -1L)
                val folder = store.byId(fid)
                if (folder != null) {
                    val kids = store.folderChildren(fid)
                    kids.filter { it.component !in selected }.forEach { store.items.remove(it) }
                    val have = kids.mapNotNull { it.component }.toSet()
                    for (c in selected) {
                        if (c in have) continue
                        store.addToFolder(folder, HomeItem(store.newId(), HomeItem.TYPE_APP, fid, component = c, title = app.repo.label(c)))
                    }
                    store.cleanupFolders()
                    store.commit()
                }
            }
        }
        finish()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_FOLDER = "folder"
        const val MODE_HIDE = "hide"
        const val MODE_EDGE = "edge"
        const val MODE_FOLDER = "folder"
    }
}

// ====================================================================== Widget picker

class WidgetPickerActivity : SettingsBase() {
    override val screenTitle = "Widgets"

    override fun build() {
        val am = AppWidgetManager.getInstance(this)
        val providers: List<AppWidgetProviderInfo> = try {
            am.installedProviders
        } catch (e: Exception) {
            emptyList()
        }
        if (providers.isEmpty()) {
            content.addView(note("No widgets are installed."))
            return
        }
        val pm = packageManager
        val groups = providers.groupBy { it.provider.packageName }
            .map { (pkg, list) -> Pair(appLabel(pkg), list) }
            .sortedBy { it.first.lowercase() }
        for ((label, list) in groups) {
            section(label) {
                for (info in list.sortedBy { it.loadLabel(pm)?.toString() ?: "" }) add(widgetRow(info))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun appLabel(pkg: String): String = try {
        val ai = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(ai).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun sizeText(info: AppWidgetProviderInfo): String {
        val cols = prefs.gridCols
        val rows = prefs.gridRows
        var w = 0
        var h = 0
        if (Build.VERSION.SDK_INT >= 31) {
            w = info.targetCellWidth
            h = info.targetCellHeight
        }
        if (w <= 0 || h <= 0) {
            val screen = screenSize()
            w = ceil(info.minWidth / (screen.x / cols.toFloat())).toInt()
            h = ceil(info.minHeight / (screen.y * 0.7f / rows)).toInt()
        }
        return "${w.coerceIn(1, cols)} × ${h.coerceIn(1, rows)}"
    }

    private fun widgetRow(info: AppWidgetProviderInfo): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(18), dp(14), dp(18), dp(14))
        col.background = ripple()
        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        col.addView(iv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)))
        val density = resources.displayMetrics.densityDpi
        Async.io {
            val d: Drawable? = try {
                info.loadPreviewImage(this, 0) ?: info.loadIcon(this, density)
            } catch (e: Exception) {
                null
            }
            Async.ui { iv.setImageDrawable(d) }
        }
        col.addView(TextView(this).apply {
            text = info.loadLabel(packageManager)?.toString() ?: "Widget"
            setTextColor(cText)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
        col.addView(TextView(this).apply {
            text = sizeText(info)
            setTextColor(cSub)
            textSize = 13f
            gravity = Gravity.CENTER
        })
        col.setOnClickListener {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_PROVIDER, info.provider.flattenToString()))
            finish()
        }
        return col
    }

    companion object {
        const val EXTRA_PROVIDER = "provider"
    }
}

// ====================================================================== Contact picker trampoline (People edge)

class ContactPickActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        try {
            startActivityForResult(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), REQ_PICK)
        } catch (e: Exception) {
            toast("No contacts app found")
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && uri != null) {
            try {
                val proj = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
                contentResolver.query(uri, proj, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val name = c.getString(0) ?: ""
                        val number = c.getString(1) ?: ""
                        if (number.isNotEmpty()) {
                            val p = app.prefs
                            if (p.contacts.none { it.number == number }) p.contacts = p.contacts + Contact(name, number)
                            toast("${name.ifEmpty { number }} added to the People panel")
                        }
                    }
                }
            } catch (e: Exception) {
                toast("Couldn't read that contact")
            }
            if (intent.getBooleanExtra(EXTRA_REOPEN, true) && app.prefs.edgeEnabled) EdgePanelService.start(this, open = true)
        }
        finish()
    }

    companion object {
        const val EXTRA_REOPEN = "reopen"
        private const val REQ_PICK = 3
    }
}

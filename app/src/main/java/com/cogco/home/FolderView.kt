package com.cogco.home

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** An open folder: editable name, One UI style colour dots and a grid of its apps. */
@SuppressLint("ViewConstructor")
class FolderView(private val home: HomeActivity, private val folder: HomeItem) : FrameLayout(home) {
    private val store = home.app.layout
    private val prefs = home.app.prefs
    private val icons = home.app.icons
    private val repo = home.app.repo

    private val scrim = View(home)
    private val card = LinearLayout(home)
    private val title = EditText(home)
    private val colors = LinearLayout(home)
    private val scroll = ScrollView(home)
    private val grid = CellLayout(home, 4, 1)
    private val addButton = TextView(home)
    private val iconViews = ArrayList<IconView>()
    private var closing = false
    private var fg = Color.WHITE

    init {
        scrim.setBackgroundColor(0x80000000.toInt())
        scrim.setOnClickListener { close(true) }
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(16), dp(18), dp(16), dp(10))
        card.isClickable = true

        title.setText(folder.title)
        title.hint = "Folder name"
        title.textSize = 20f
        title.gravity = Gravity.CENTER
        title.background = null
        title.isSingleLine = true
        title.imeOptions = EditorInfo.IME_ACTION_DONE
        title.setOnEditorActionListener { v, _, _ ->
            v.clearFocus()
            home.hideKeyboard()
            saveTitle()
            true
        }
        card.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        colors.orientation = LinearLayout.HORIZONTAL
        colors.gravity = Gravity.CENTER
        card.addView(colors, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)).apply { bottomMargin = dp(8) })

        grid.fixedCellHeight = home.iconSizePx() + dp(42)
        scroll.addView(grid, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        addButton.text = "+  Add apps"
        addButton.textSize = 14f
        addButton.gravity = Gravity.CENTER
        addButton.setPadding(0, dp(12), 0, dp(8))
        addButton.setOnClickListener {
            saveTitle()
            home.startActivity(
                Intent(home, AppPickerActivity::class.java)
                    .putExtra(AppPickerActivity.EXTRA_MODE, AppPickerActivity.MODE_FOLDER)
                    .putExtra(AppPickerActivity.EXTRA_FOLDER, folder.id)
            )
        }
        if (!prefs.lockLayout) card.addView(addButton)

        val width = min(home.screenSize().x - dp(40), dp(420))
        addView(card, LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        applyColor()
        refresh()
    }

    private fun applyColor() {
        val c = folder.color
        val bgColor = if (c == 0) 0xF0303034.toInt() else ColorUtil.withAlpha(c, 0xF2)
        fg = if (c == 0) Color.WHITE else ColorUtil.onColor(c)
        card.background = roundRect(bgColor, dpf(28f))
        title.setTextColor(fg)
        title.setHintTextColor(ColorUtil.withAlpha(fg, 0x88))
        addButton.setTextColor(ColorUtil.withAlpha(fg, 0xDD))
        iconViews.forEach { it.labelColor = fg }
        colors.removeAllViews()
        for (opt in FOLDER_COLORS) {
            val dot = View(home)
            val fill = if (opt == 0) 0x66F2F2F2 else opt
            val ring = if (opt == c) fg else 0
            dot.background = oval(fill).apply { if (ring != 0) setStroke(dp(2), ring) }
            dot.setOnClickListener {
                folder.color = opt
                applyColor()
                store.commit()
            }
            colors.addView(dot, LinearLayout.LayoutParams(dp(24), dp(24)).apply { setMargins(dp(5), 0, dp(5), 0) })
        }
    }

    fun refresh() {
        if (closing) return
        if (store.byId(folder.id) == null) {
            close(false)
            return
        }
        val kids = store.folderChildren(folder.id)
        val cols = 4
        grid.cols = cols
        grid.rows = max(1, ceil(kids.size / cols.toFloat()).toInt())
        grid.removeAllViews()
        iconViews.clear()
        kids.forEachIndexed { i, k ->
            val iv = makeIcon(k)
            iconViews += iv
            grid.addView(iv, CellLayout.LP(i % cols, i / cols))
        }
        val maxH = (home.screenSize().y * 0.5f).toInt()
        val lp = scroll.layoutParams as LinearLayout.LayoutParams
        lp.height = min(grid.rows * grid.fixedCellHeight, maxH)
        scroll.layoutParams = lp
        updateBadges()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeIcon(k: HomeItem): IconView {
        val iv = IconView(home)
        iv.item = k
        iv.iconSize = home.iconSizePx()
        iv.labelShadow = false
        iv.labelColor = fg
        val c = k.component
        iv.label = c?.let { repo.find(it)?.label } ?: k.title
        if (c != null) icons.load(c) { b -> iv.icon = b }
        val drag: (() -> Unit)? = if (prefs.lockLayout) null else ({ home.startFolderDrag(iv, k) })
        val g = ItemGesture(
            iv, true,
            onClick = {
                if (c != null) home.launchApp(c, iv)
                close(false)
            },
            onLongPress = { home.showFolderAppPopup(iv, k) },
            onDragStart = drag
        )
        iv.setOnTouchListener { _, ev -> g.onTouch(ev) }
        return iv
    }

    fun updateBadges() {
        for (iv in iconViews) {
            iv.badgeNumbers = prefs.badgeNumbers
            iv.badgeCount = if (prefs.badges) Badges.count(iv.item?.component?.packageName) else 0
        }
    }

    fun open() {
        card.scaleX = 0.85f
        card.scaleY = 0.85f
        card.alpha = 0f
        scrim.alpha = 0f
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(200).start()
        scrim.animate().alpha(1f).setDuration(200).start()
    }

    private fun saveTitle() {
        val t = title.text?.toString()?.trim().orEmpty().ifEmpty { "Folder" }
        if (t != folder.title) folder.title = t
    }

    fun close(animate: Boolean) {
        if (closing) return
        closing = true
        home.dismissPopup()
        home.hideKeyboard()
        saveTitle()
        val finish = {
            home.onFolderClosed(this)
            store.commit()
        }
        if (animate) {
            card.animate().scaleX(0.9f).scaleY(0.9f).alpha(0f).setDuration(160).start()
            scrim.animate().alpha(0f).setDuration(160).withEndAction { finish() }.start()
        } else {
            finish()
        }
    }

    companion object {
        val FOLDER_COLORS = listOf(
            0,
            0xFF4C8DF6.toInt(),
            0xFF43B581.toInt(),
            0xFFF4C542.toInt(),
            0xFFF28B30.toInt(),
            0xFFE5534B.toInt(),
            0xFF9B59D0.toInt(),
            0xFF5F6368.toInt()
        )
    }
}

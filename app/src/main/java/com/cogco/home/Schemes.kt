package com.cogco.home

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** A colour scheme for an edge panel. Colours are opaque RGB; [opacity] (0-100) is applied to the background. */
data class PanelScheme(
    val id: String,
    val name: String,
    val bgTop: Int,
    val bgBottom: Int,
    val opacity: Int,
    val accent: Int,
    val text: Int,
    val radius: Int,
    val builtin: Boolean = false
) {
    val gradient: Boolean get() = (bgTop and 0xFFFFFF) != (bgBottom and 0xFFFFFF)

    fun topArgb(): Int = ColorUtil.withAlpha(bgTop, opacity * 255 / 100)
    fun bottomArgb(): Int = ColorUtil.withAlpha(bgBottom, opacity * 255 / 100)

    /** Translucent version of the text colour, for tiles/cards inside the panel. */
    fun subtle(alpha: Int = 0x24): Int = ColorUtil.withAlpha(text, alpha)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name)
        .put("top", bgTop).put("bottom", bgBottom).put("opacity", opacity)
        .put("accent", accent).put("text", text).put("radius", radius)

    companion object {
        fun fromJson(o: JSONObject) = PanelScheme(
            o.getString("id"),
            o.optString("name", "My theme"),
            o.optInt("top", 0xFFFFFFFF.toInt()),
            o.optInt("bottom", 0xFFFFFFFF.toInt()),
            o.optInt("opacity", 90).coerceIn(0, 100),
            o.optInt("accent", 0xFF3E91FF.toInt()),
            o.optInt("text", 0xFF111111.toInt()),
            o.optInt("radius", 28).coerceIn(0, 48)
        )
    }
}

object Schemes {
    private fun c(v: Long): Int = v.toInt()

    private val STATIC = listOf(
        PanelScheme("oneui_light", "One UI Light", c(0xFFF7F7F9), c(0xFFF7F7F9), 94, c(0xFF3E91FF), c(0xFF1B1B1D), 28, true),
        PanelScheme("oneui_dark", "One UI Dark", c(0xFF1E1E20), c(0xFF1E1E20), 92, c(0xFF5CA4FF), c(0xFFF5F5F7), 28, true),
        PanelScheme("frosted", "Frosted Glass", c(0xFFFFFFFF), c(0xFFFFFFFF), 30, c(0xFFFFFFFF), c(0xFFFFFFFF), 30, true),
        PanelScheme("midnight", "Midnight", c(0xFF0F2027), c(0xFF2C5364), 95, c(0xFF4FC3F7), c(0xFFFFFFFF), 28, true),
        PanelScheme("sunset", "Sunset", c(0xFFFF7E5F), c(0xFFFEB47B), 96, c(0xFFFFFFFF), c(0xFFFFFFFF), 28, true),
        PanelScheme("aurora", "Aurora", c(0xFF43CEA2), c(0xFF185A9D), 95, c(0xFFE0FFF4), c(0xFFFFFFFF), 28, true),
        PanelScheme("lavender", "Lavender", c(0xFFE0C3FC), c(0xFF8EC5FC), 95, c(0xFF5E35B1), c(0xFF2A1B3D), 28, true),
        PanelScheme("neon", "Neon Night", c(0xFF0B0B12), c(0xFF1A0B2E), 95, c(0xFF39FF14), c(0xFFE8FFE8), 24, true),
        PanelScheme("rose", "Rose Gold", c(0xFFF7D9D3), c(0xFFE8B4B8), 96, c(0xFFB76E79), c(0xFF3D2327), 28, true),
        PanelScheme("forest", "Forest", c(0xFF134E5E), c(0xFF71B280), 95, c(0xFFFFF59D), c(0xFFFFFFFF), 28, true),
        PanelScheme("ocean", "Deep Ocean", c(0xFF1A2980), c(0xFF26D0CE), 94, c(0xFFFFFFFF), c(0xFFFFFFFF), 28, true),
        PanelScheme("mono", "Monochrome", c(0xFF000000), c(0xFF000000), 82, c(0xFFFFFFFF), c(0xFFFFFFFF), 20, true)
    )

    /** Material You colours pulled from the wallpaper (Android 12+). */
    private fun dynamic(ctx: Context): List<PanelScheme> {
        if (Build.VERSION.SDK_INT < 31) return emptyList()
        return try {
            listOf(
                PanelScheme(
                    "dynamic_light", "Wallpaper colours (light)",
                    ctx.getColor(android.R.color.system_accent1_50), ctx.getColor(android.R.color.system_accent1_100), 95,
                    ctx.getColor(android.R.color.system_accent1_600), ctx.getColor(android.R.color.system_neutral1_900), 28, true
                ),
                PanelScheme(
                    "dynamic_dark", "Wallpaper colours (dark)",
                    ctx.getColor(android.R.color.system_neutral1_900), ctx.getColor(android.R.color.system_accent1_800), 94,
                    ctx.getColor(android.R.color.system_accent1_200), ctx.getColor(android.R.color.system_neutral1_50), 28, true
                )
            )
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun presets(ctx: Context): List<PanelScheme> = STATIC + dynamic(ctx)

    fun custom(prefs: Prefs): List<PanelScheme> = try {
        val a = JSONArray(prefs.customSchemesJson)
        (0 until a.length()).map { PanelScheme.fromJson(a.getJSONObject(it)) }
    } catch (e: Exception) {
        emptyList()
    }

    fun all(ctx: Context, prefs: Prefs): List<PanelScheme> = presets(ctx) + custom(prefs)

    fun get(ctx: Context, prefs: Prefs, id: String): PanelScheme =
        all(ctx, prefs).firstOrNull { it.id == id } ?: STATIC[0]

    fun forPanel(ctx: Context, prefs: Prefs, panelId: String): PanelScheme {
        val own = prefs.schemeFor(panelId)
        return get(ctx, prefs, own.ifEmpty { prefs.defaultScheme })
    }

    fun saveCustom(prefs: Prefs, scheme: PanelScheme) {
        val list = custom(prefs).toMutableList()
        val i = list.indexOfFirst { it.id == scheme.id }
        val s = scheme.copy(builtin = false)
        if (i >= 0) list[i] = s else list += s
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        prefs.customSchemesJson = a.toString()
    }

    fun deleteCustom(prefs: Prefs, id: String) {
        val a = JSONArray()
        custom(prefs).filter { it.id != id }.forEach { a.put(it.toJson()) }
        prefs.customSchemesJson = a.toString()
        if (prefs.defaultScheme == id) prefs.defaultScheme = "oneui_light"
        for (p in Prefs.DEFAULT_PANELS.split(",")) if (prefs.schemeFor(p) == id) prefs.setSchemeFor(p, "")
    }

    fun newId(): String = "custom_" + System.currentTimeMillis()
}

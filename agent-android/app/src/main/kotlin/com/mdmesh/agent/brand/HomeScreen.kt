package com.mdmesh.agent.brand

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import com.mdmesh.agent.R
import java.util.Locale

/** The quick settings a kiosk configuration can offer (payload keys `wifi`, `brightness`, `volume`). */
enum class QuickTab(val key: String) {
    WIFI("wifi"),
    BRIGHTNESS("brightness"),
    VOLUME("volume"),
    ;

    companion object {
        fun of(keys: List<String>): List<QuickTab> = entries.filter { it.key in keys }
    }
}

/** What the home screen shows. [main] is the configuration's main app (the hero card). */
data class HomeModel(
    val main: LauncherApp?,
    val others: List<LauncherApp>,
    val quick: List<QuickTab>,
    val wifiName: String?,
    val brightness: Int,
    val volume: Int,
)

/**
 * MeinConnect fork: the kiosk home, built portrait-first for phones — date and big clock, the site
 * title, the main app as a hero card with a gradient "Open" button, further apps as rows, and the
 * enabled quick settings as tiles at the bottom. On tablets the column is capped and centred.
 */
class HomeScreen(
    private val parts: BrandParts,
    private val onLaunch: (String) -> Unit,
    private val onQuick: (QuickTab) -> Unit,
) {
    private val activity = parts.activity
    private val theme = parts.theme
    private val kit = parts.kit

    fun build(model: HomeModel): FrameLayout {
        val root = parts.root()
        val page = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        parts.insetSystemBars(page)
        parts.brandBar()?.let { page.addView(it, LinearLayout.LayoutParams(MATCH, kit.dp(BAR_DP))) }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(PAD_DP), 0, kit.dp(PAD_DP), kit.dp(PAD_DP))
            addView(header())
            val main = model.main
            if (main == null) addView(empty()) else addView(hero(main))
            model.others.forEach { addView(row(it)) }
        }
        val scroll = ScrollView(activity).apply { addView(limit(content)) }
        page.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        if (model.quick.isNotEmpty()) page.addView(limit(quickBar(model)))
        footer()?.let { page.addView(limit(it)) }
        root.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
        return root
    }

    private fun header(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(kit.dp(HEADER_PAD_H_DP), kit.dp(HEADER_TOP_DP), kit.dp(HEADER_PAD_H_DP), 0)
        val top = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        parts.logo(full = false)?.let { top.addView(it, LinearLayout.LayoutParams(WRAP, kit.dp(LOGO_DP))) }
        top.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        val datePattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM")
        top.addView(clock(datePattern, datePattern, DATE_SP, theme.muted, BrandFonts.regular(activity)))
        addView(top, LinearLayout.LayoutParams(MATCH, kit.dp(TOP_ROW_DP)))
        val time = clock("HH:mm", "h:mm", TIME_SP, theme.text, BrandFonts.semibold(activity)).apply {
            letterSpacing = TIME_TRACKING
        }
        addView(time, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(TIME_GAP_DP) })
        theme.title?.let { title ->
            val kicker = kit.text(
                activity.getString(R.string.kiosk_location).uppercase(Locale.getDefault()),
                KICKER_SP,
                theme.muted,
                BrandFonts.medium(activity),
            ).apply { letterSpacing = KICKER_TRACKING }
            addView(kicker, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(KICKER_GAP_DP) })
            val headline = kit.text(title, HEADLINE_SP, theme.text, BrandFonts.semibold(activity)).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            addView(headline, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(HEADLINE_GAP_DP) })
        }
    }

    /** The main app: big icon + name, and a full-width gradient "Open" button. */
    private fun hero(app: LauncherApp): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(kit.dp(HERO_PAD_DP), kit.dp(HERO_PAD_TOP_DP), kit.dp(HERO_PAD_DP), kit.dp(HERO_PAD_DP))
        parts.card(this, HERO_RADIUS_DP, aurora = true)
        contentDescription = app.label
        setOnClickListener { onLaunch(app.pkg) }
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(activity).apply { setImageDrawable(app.icon) }
        head.addView(icon, LinearLayout.LayoutParams(kit.dp(HERO_ICON_DP), kit.dp(HERO_ICON_DP)))
        val name = kit.text(app.label, HERO_NAME_SP, theme.text, BrandFonts.semibold(activity)).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        head.addView(name, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = kit.dp(HERO_GAP_DP) })
        addView(head)
        val cta = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = parts.ctaBackground()
            setPadding(0, kit.dp(CTA_PAD_V_DP), 0, kit.dp(CTA_PAD_V_DP))
            isClickable = true
            setOnClickListener { onLaunch(app.pkg) }
            val label = activity.getString(R.string.kiosk_open)
            addView(kit.text(label, CTA_SP, theme.onAccent, BrandFonts.medium(activity)))
            val arrow = parts.glyph(R.drawable.ic_mc_arrow, theme.onAccent)
            addView(arrow, LinearLayout.LayoutParams(kit.dp(CTA_ICON_DP), kit.dp(CTA_ICON_DP)).apply {
                marginStart = kit.dp(CTA_ICON_GAP_DP)
            })
        }
        addView(cta, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(CTA_GAP_DP) })
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(SECTION_GAP_DP) }
    }

    /** A further app: icon, name, round arrow button. */
    private fun row(app: LauncherApp): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(kit.dp(ROW_PAD_H_DP), kit.dp(ROW_PAD_V_DP), kit.dp(ROW_PAD_H_DP), kit.dp(ROW_PAD_V_DP))
        parts.card(this, HERO_RADIUS_DP)
        contentDescription = app.label
        setOnClickListener { onLaunch(app.pkg) }
        val icon = ImageView(activity).apply { setImageDrawable(app.icon) }
        addView(icon, LinearLayout.LayoutParams(kit.dp(ROW_ICON_DP), kit.dp(ROW_ICON_DP)))
        val name = kit.text(app.label, ROW_NAME_SP, theme.text, BrandFonts.semibold(activity)).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        addView(name, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = kit.dp(ROW_GAP_DP) })
        val ring = FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(kit.dp(1) + 1, theme.line)
            }
            val arrow = parts.glyph(R.drawable.ic_mc_arrow, theme.text)
            addView(arrow, FrameLayout.LayoutParams(kit.dp(ARROW_DP), kit.dp(ARROW_DP), Gravity.CENTER))
        }
        addView(ring, LinearLayout.LayoutParams(kit.dp(RING_DP), kit.dp(RING_DP)))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(ROW_TOP_DP) }
    }

    /** One tile per enabled quick setting, sharing the width. */
    private fun quickBar(model: HomeModel): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(kit.dp(PAD_DP), kit.dp(QUICK_TOP_DP), kit.dp(PAD_DP), kit.dp(QUICK_BOTTOM_DP))
        model.quick.forEachIndexed { i, tab ->
            val (icon, label, sub) = when (tab) {
                QuickTab.WIFI -> Triple(
                    R.drawable.ic_mc_wifi,
                    R.string.qs_wifi,
                    model.wifiName ?: activity.getString(R.string.qs_not_connected),
                )
                QuickTab.BRIGHTNESS -> Triple(
                    R.drawable.ic_mc_sun,
                    R.string.qs_brightness,
                    activity.getString(R.string.qs_percent, model.brightness),
                )
                QuickTab.VOLUME -> Triple(
                    R.drawable.ic_mc_volume,
                    R.string.qs_volume,
                    activity.getString(R.string.qs_percent, model.volume),
                )
            }
            val highlight = tab == QuickTab.WIFI && model.wifiName != null
            val tile = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(kit.dp(TILE_PAD_H_DP), kit.dp(TILE_PAD_V_DP), kit.dp(TILE_PAD_H_DP), kit.dp(TILE_PAD_V_DP))
                background = kit.pressable(kit.rounded(theme.card, TILE_RADIUS_DP, theme.line), TILE_RADIUS_DP)
                setOnClickListener { onQuick(tab) }
                val glyph = parts.glyph(icon, if (highlight) theme.accent else theme.text)
                addView(glyph, LinearLayout.LayoutParams(kit.dp(TILE_ICON_DP), kit.dp(TILE_ICON_DP)))
                val title = kit.text(activity.getString(label), TILE_SP, theme.text, BrandFonts.medium(activity))
                addView(title, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(TILE_GAP_DP) })
                val detail = kit.text(sub, TILE_SUB_SP, theme.muted, BrandFonts.regular(activity)).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                addView(detail, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(TILE_SUB_GAP_DP) })
            }
            val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
            if (i > 0) lp.marginStart = kit.dp(TILE_SPACING_DP)
            addView(tile, lp)
        }
    }

    /** "Managed with MeinConnect" — only with the MeinConnect logo, never on a white-label kiosk. */
    private fun footer(): View? = if (theme.logoUrl != null) {
        null
    } else {
        kit.text(activity.getString(R.string.agent_managed_by), FOOTER_SP, theme.muted, BrandFonts.regular(activity))
            .apply {
                gravity = Gravity.CENTER
                setPadding(0, kit.dp(FOOTER_TOP_DP), 0, kit.dp(FOOTER_BOTTOM_DP))
            }
    }

    private fun empty(): View {
        val message = activity.getString(R.string.kiosk_empty)
        return kit.text(message, EMPTY_SP, theme.muted, BrandFonts.regular(activity)).apply {
            setPadding(kit.dp(PAD_DP), kit.dp(PAD_DP), kit.dp(PAD_DP), kit.dp(PAD_DP))
            parts.card(this, HERO_RADIUS_DP)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(SECTION_GAP_DP) }
        }
    }

    /** Caps [view] at a phone-sized column, centred (tablets, landscape). */
    private fun limit(view: View): View = FrameLayout(activity).apply {
        val width = minOf(kit.screenWidth(), kit.dp(MAX_WIDTH_DP))
        addView(view, FrameLayout.LayoutParams(width, WRAP, Gravity.CENTER_HORIZONTAL))
    }

    private fun clock(pattern24: String, pattern12: String, sizeSp: Float, color: Int, face: Typeface) =
        TextClock(activity).apply {
            format24Hour = pattern24
            format12Hour = pattern12
            textSize = sizeSp
            setTextColor(color)
            typeface = face
            includeFontPadding = false
        }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        const val BAR_DP = 4
        const val PAD_DP = 20
        const val MAX_WIDTH_DP = 560

        const val HEADER_PAD_H_DP = 4
        const val HEADER_TOP_DP = 20
        const val TOP_ROW_DP = 28
        const val LOGO_DP = 20
        const val DATE_SP = 13f
        const val TIME_SP = 64f
        const val TIME_TRACKING = -0.03f
        const val TIME_GAP_DP = 14
        const val KICKER_SP = 11f
        const val KICKER_TRACKING = 0.18f
        const val KICKER_GAP_DP = 14
        const val HEADLINE_SP = 26f
        const val HEADLINE_GAP_DP = 2

        const val SECTION_GAP_DP = 22
        const val HERO_PAD_DP = 24
        const val HERO_PAD_TOP_DP = 26
        const val HERO_RADIUS_DP = 24
        const val HERO_ICON_DP = 84
        const val HERO_GAP_DP = 18
        const val HERO_NAME_SP = 28f
        const val CTA_PAD_V_DP = 15
        const val CTA_GAP_DP = 22
        const val CTA_SP = 17f
        const val CTA_ICON_DP = 18
        const val CTA_ICON_GAP_DP = 10

        const val ROW_TOP_DP = 14
        const val ROW_PAD_H_DP = 18
        const val ROW_PAD_V_DP = 16
        const val ROW_ICON_DP = 56
        const val ROW_GAP_DP = 16
        const val ROW_NAME_SP = 19f
        const val RING_DP = 40
        const val ARROW_DP = 18

        const val QUICK_TOP_DP = 12
        const val QUICK_BOTTOM_DP = 4
        const val TILE_SPACING_DP = 10
        const val TILE_RADIUS_DP = 18
        const val TILE_PAD_H_DP = 8
        const val TILE_PAD_V_DP = 14
        const val TILE_ICON_DP = 24
        const val TILE_GAP_DP = 8
        const val TILE_SUB_GAP_DP = 2
        const val TILE_SP = 13f
        const val TILE_SUB_SP = 12f

        const val FOOTER_SP = 12f
        const val FOOTER_TOP_DP = 10
        const val FOOTER_BOTTOM_DP = 14
        const val EMPTY_SP = 15f
    }
}

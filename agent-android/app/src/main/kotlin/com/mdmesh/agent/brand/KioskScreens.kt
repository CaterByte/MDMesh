package com.mdmesh.agent.brand

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import com.mdmesh.agent.R
import com.mdmesh.kiosk.brand.KioskBrand
import com.mdmesh.kiosk.brand.KioskBrandTheme

/** An allowlisted app as the launcher grid shows it. */
data class LauncherApp(val pkg: String, val label: String, val icon: Drawable)

/** Remote images resolved for the current theme (null = not configured, not loaded yet, or failed). */
data class BrandAssets(val logo: Bitmap? = null, val background: Bitmap? = null)

/**
 * MeinConnect fork: builds the kiosk surfaces in the MeinConnect CI — signature bar on top, logo + clock
 * header, app tiles as cards, Poppins throughout. Everything is driven by [theme], so a configuration can
 * re-skin it (colours, title, logo, bar) without a new agent build.
 */
class KioskScreens(
    private val activity: Activity,
    private val theme: KioskBrandTheme,
    private val assets: BrandAssets,
) {
    private val kit = ViewKit(activity, theme)

    /** The launcher home: header, then the app grid (or an empty-state hint). */
    fun launcher(apps: List<LauncherApp>, onLaunch: (String) -> Unit): FrameLayout {
        val root = root()
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        brandBar()?.let { column.addView(it, LinearLayout.LayoutParams(MATCH, kit.dp(BAR_DP))) }
        column.addView(header())
        val body = if (apps.isEmpty()) emptyState() else grid(apps, onLaunch)
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            addView(body)
        }
        column.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        return root
    }

    /** Centred logo screen used for the splash, the idle "managed device" screen and crash recovery. */
    fun status(title: String?, body: String?, progress: Boolean = false, alert: Boolean = false): FrameLayout {
        val root = root()
        brandBar()?.let { root.addView(it, FrameLayout.LayoutParams(MATCH, kit.dp(BAR_DP), Gravity.TOP)) }
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(kit.dp(PAD_DP), 0, kit.dp(PAD_DP), 0)
        }
        val logoWidth = minOf((kit.screenWidth() * STATUS_LOGO_SHARE).toInt(), kit.dp(STATUS_LOGO_MAX_DP))
        logoView(full = true)?.let { col.addView(it, LinearLayout.LayoutParams(logoWidth, WRAP)) }
        title?.let {
            val color = if (alert) KioskBrand.ALERT else theme.text
            col.addView(kit.centered(kit.text(it, TITLE_SP, color, BrandFonts.semibold(activity)), STATUS_GAP_DP))
        }
        body?.let {
            val text = kit.text(it, BODY_SP, theme.muted, BrandFonts.regular(activity)).apply {
                maxWidth = kit.dp(BODY_MAX_DP)
                setLineSpacing(0f, LINE_SPACING)
            }
            col.addView(kit.centered(text, BODY_GAP_DP))
        }
        if (progress) {
            val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleSmall).apply {
                indeterminateTintList = ColorStateList.valueOf(theme.accent)
            }
            col.addView(bar, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(STATUS_GAP_DP) })
        }
        root.addView(col, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        return root
    }

    /** Pill button in the accent colour (used for the visible "exit kiosk" affordance). */
    fun pillButton(label: String, onClick: () -> Unit): View =
        kit.text(label, BUTTON_SP, theme.onAccent, BrandFonts.medium(activity)).apply {
            background = kit.pressable(kit.rounded(theme.accent, PILL_DP), PILL_DP)
            setPadding(kit.dp(PILL_PAD_H_DP), kit.dp(PILL_PAD_V_DP), kit.dp(PILL_PAD_H_DP), kit.dp(PILL_PAD_V_DP))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun root(): FrameLayout = FrameLayout(activity).apply {
        setBackgroundColor(theme.background)
        layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        assets.background?.let { bmp ->
            val image = ImageView(activity).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            addView(image, FrameLayout.LayoutParams(MATCH, MATCH))
        }
    }

    private fun brandBar(): View? =
        if (theme.brandBar.isEmpty()) null else BrandBarView(activity, theme.brandBar)

    private fun header(): View {
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(kit.dp(PAD_DP), kit.dp(HEADER_TOP_DP), kit.dp(PAD_DP), kit.dp(HEADER_BOTTOM_DP))
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        logoView(full = false)?.let { row.addView(it, LinearLayout.LayoutParams(WRAP, kit.dp(HEADER_LOGO_DP))) }
        row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        val clock = TextClock(activity).apply {
            format24Hour = "HH:mm"
            format12Hour = "h:mm a"
            setTextColor(theme.muted)
            textSize = CLOCK_SP
            typeface = BrandFonts.medium(activity)
        }
        row.addView(clock)
        header.addView(row, LinearLayout.LayoutParams(MATCH, kit.dp(HEADER_ROW_DP)))
        theme.title?.let {
            val title = kit.text(it, HEADLINE_SP, theme.text, BrandFonts.semibold(activity)).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            header.addView(title, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(TITLE_GAP_DP) })
        }
        return header
    }

    /**
     * The configured logo, else the bundled MeinConnect logo; null while a configured logo is still
     * loading (or failed), so a white-label kiosk never flashes the MeinConnect mark.
     */
    private fun logoView(full: Boolean): ImageView? {
        val custom = assets.logo
        if (custom == null && theme.logoUrl != null) return null
        return ImageView(activity).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (custom != null) {
                setImageBitmap(custom)
            } else {
                setImageResource(if (full) R.drawable.mc_logo else R.drawable.mc_wordmark)
                // CI rule: the colour logo sits on a white chip on dark surfaces.
                if (theme.isDark) {
                    background = kit.rounded(KioskBrand.PAPER, CHIP_RADIUS_DP)
                    val padH = kit.dp(CHIP_PAD_H_DP)
                    val padV = kit.dp(CHIP_PAD_V_DP)
                    setPadding(padH, padV, padH, padV)
                }
            }
        }
    }

    /**
     * App grid. With only a few apps (the usual kitchen tablet: MeinConnect + scale) the tiles become
     * large touch targets, centred in the free space, instead of a thin row of small icons.
     */
    private fun grid(apps: List<LauncherApp>, onLaunch: (String) -> Unit): View {
        val few = apps.size <= FEW_APPS
        val iconPx = kit.dp(if (few) maxOf(theme.iconSizeDp, FEW_ICON_DP) else theme.iconSizeDp)
        val gap = kit.dp(TILE_GAP_DP)
        val usable = kit.screenWidth() - kit.dp(GRID_PAD_DP) * 2
        val tileMin = if (few) kit.dp(FEW_TILE_MIN_DP) else iconPx + kit.dp(TILE_EXTRA_DP) + gap * 2
        val byWidth = (usable / tileMin).coerceIn(1, MAX_COLS)
        val cols = if (few) apps.size.coerceIn(1, byWidth) else byWidth.coerceAtLeast(MIN_COLS)
        val grid = GridLayout(activity).apply {
            columnCount = cols
            setPadding(kit.dp(GRID_PAD_DP), kit.dp(GRID_TOP_DP), kit.dp(GRID_PAD_DP), kit.dp(GRID_BOTTOM_DP))
        }
        // Pad the last row with invisible cells so every tile keeps the same width.
        val cells = apps.size + (cols - apps.size % cols) % cols
        for (i in 0 until cells) {
            val cell = apps.getOrNull(i)?.let { tile(it, iconPx, few, onLaunch) } ?: View(activity)
            val lp = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
            ).apply {
                width = 0
                setMargins(gap, gap, gap, gap)
            }
            grid.addView(cell, lp)
        }
        val width = if (few) minOf(usable + kit.dp(GRID_PAD_DP) * 2, kit.dp(FEW_MAX_WIDTH_DP)) else MATCH
        val gravity = if (few) Gravity.CENTER else Gravity.TOP
        return FrameLayout(activity).apply { addView(grid, FrameLayout.LayoutParams(width, WRAP, gravity)) }
    }

    private fun tile(app: LauncherApp, iconPx: Int, large: Boolean, onLaunch: (String) -> Unit): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val padH = kit.dp(TILE_PAD_H_DP)
            val padTop = kit.dp(if (large) FEW_PAD_TOP_DP else TILE_PAD_TOP_DP)
            val padBottom = kit.dp(if (large) FEW_PAD_BOTTOM_DP else TILE_PAD_BOTTOM_DP)
            setPadding(padH, padTop, padH, padBottom)
            background = kit.pressable(kit.rounded(theme.card, CARD_RADIUS_DP, theme.line), CARD_RADIUS_DP)
            isClickable = true
            isFocusable = true
            contentDescription = app.label
            val icon = ImageView(activity).apply { setImageDrawable(app.icon) }
            addView(icon, LinearLayout.LayoutParams(iconPx, iconPx))
            val size = if (large) FEW_LABEL_SP else LABEL_SP
            val label = kit.text(app.label, size, theme.text, BrandFonts.medium(activity)).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            val labelGap = kit.dp(if (large) FEW_LABEL_GAP_DP else LABEL_GAP_DP)
            addView(label, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = labelGap })
            setOnClickListener { onLaunch(app.pkg) }
        }

    private fun emptyState(): View {
        val message = activity.getString(R.string.kiosk_empty)
        val card = kit.text(message, BODY_SP, theme.muted, BrandFonts.regular(activity))
        card.background = kit.rounded(theme.card, CARD_RADIUS_DP, theme.line)
        card.setPadding(kit.dp(PAD_DP), kit.dp(PAD_DP), kit.dp(PAD_DP), kit.dp(PAD_DP))
        return FrameLayout(activity).apply {
            setPadding(kit.dp(PAD_DP), kit.dp(GRID_TOP_DP), kit.dp(PAD_DP), 0)
            addView(card, FrameLayout.LayoutParams(MATCH, WRAP))
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        const val BAR_DP = 4
        const val PAD_DP = 24
        const val HEADER_TOP_DP = 20
        const val HEADER_BOTTOM_DP = 8
        const val HEADER_ROW_DP = 40
        const val HEADER_LOGO_DP = 30
        const val TITLE_GAP_DP = 14
        const val CHIP_RADIUS_DP = 10
        const val CHIP_PAD_H_DP = 10
        const val CHIP_PAD_V_DP = 6

        const val GRID_PAD_DP = 16
        const val GRID_TOP_DP = 8
        const val GRID_BOTTOM_DP = 32
        const val TILE_GAP_DP = 8
        const val TILE_EXTRA_DP = 64
        const val TILE_PAD_H_DP = 12
        const val TILE_PAD_TOP_DP = 20
        const val TILE_PAD_BOTTOM_DP = 16
        const val LABEL_GAP_DP = 12
        const val CARD_RADIUS_DP = 16
        const val MIN_COLS = 2
        const val MAX_COLS = 6
        const val FEW_APPS = 4
        const val FEW_ICON_DP = 88
        const val FEW_TILE_MIN_DP = 200
        const val FEW_MAX_WIDTH_DP = 960
        const val FEW_PAD_TOP_DP = 32
        const val FEW_PAD_BOTTOM_DP = 28
        const val FEW_LABEL_GAP_DP = 16
        const val FEW_LABEL_SP = 17f

        const val PILL_DP = 999
        const val PILL_PAD_H_DP = 22
        const val PILL_PAD_V_DP = 12

        const val STATUS_LOGO_SHARE = 0.6f
        const val STATUS_LOGO_MAX_DP = 360
        const val STATUS_GAP_DP = 28
        const val BODY_GAP_DP = 10
        const val BODY_MAX_DP = 420
        const val LINE_SPACING = 1.25f

        const val HEADLINE_SP = 26f
        const val TITLE_SP = 22f
        const val BODY_SP = 15f
        const val LABEL_SP = 14f
        const val BUTTON_SP = 15f
        const val CLOCK_SP = 20f
    }
}

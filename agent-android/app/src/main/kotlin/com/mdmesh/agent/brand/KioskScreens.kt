package com.mdmesh.agent.brand

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.mdmesh.kiosk.brand.KioskBrand

/** An allowlisted app as the launcher shows it. */
data class LauncherApp(val pkg: String, val label: String, val icon: Drawable)

/** Remote images resolved for the current theme (null = not configured, not loaded yet, or failed). */
data class BrandAssets(val logo: Bitmap? = null, val background: Bitmap? = null)

/**
 * MeinConnect fork: the kiosk surfaces in the MeinConnect CI — the home screen (see [HomeScreen]) and the
 * centred logo screen used for splash, idle and crash recovery. Everything is driven by the theme, so a
 * configuration can re-skin it (colours, title, logo, bar) without a new agent build.
 */
class KioskScreens(private val parts: BrandParts) {

    private val activity = parts.activity
    private val theme = parts.theme
    private val kit = parts.kit

    /** The launcher home; [onQuick] opens a quick-settings panel. */
    fun launcher(model: HomeModel, onLaunch: (String) -> Unit, onQuick: (QuickTab) -> Unit): FrameLayout =
        HomeScreen(parts, onLaunch, onQuick).build(model)

    /** Centred logo screen used for the splash, the idle "managed device" screen and crash recovery. */
    fun status(title: String?, body: String?, progress: Boolean = false, alert: Boolean = false): FrameLayout {
        val root = parts.root()
        val frame = FrameLayout(activity)
        parts.insetSystemBars(frame)
        parts.brandBar()?.let { frame.addView(it, FrameLayout.LayoutParams(MATCH, kit.dp(BAR_DP), Gravity.TOP)) }
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(kit.dp(PAD_DP), 0, kit.dp(PAD_DP), 0)
        }
        val logoWidth = minOf((kit.screenWidth() * STATUS_LOGO_SHARE).toInt(), kit.dp(STATUS_LOGO_MAX_DP))
        parts.logo(full = true)?.let { col.addView(it, LinearLayout.LayoutParams(logoWidth, WRAP)) }
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
        frame.addView(col, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        root.addView(frame, FrameLayout.LayoutParams(MATCH, MATCH))
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

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        const val BAR_DP = 4
        const val PAD_DP = 24
        const val PILL_DP = 999
        const val PILL_PAD_H_DP = 22
        const val PILL_PAD_V_DP = 12

        const val STATUS_LOGO_SHARE = 0.6f
        const val STATUS_LOGO_MAX_DP = 360
        const val STATUS_GAP_DP = 28
        const val BODY_GAP_DP = 10
        const val BODY_MAX_DP = 420
        const val LINE_SPACING = 1.25f

        const val TITLE_SP = 22f
        const val BODY_SP = 15f
        const val BUTTON_SP = 15f
    }
}

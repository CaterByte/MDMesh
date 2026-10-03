package com.mdmesh.agent.brand

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mdmesh.agent.R
import com.mdmesh.kiosk.brand.KioskBrand
import com.mdmesh.kiosk.brand.KioskBrandTheme

/**
 * MeinConnect fork: the shared building blocks of every kiosk surface — page root with the optional
 * background image, the gradient brand bar, the logo, cards, the call-to-action fill and stroke glyphs.
 */
class BrandParts(
    val activity: Activity,
    val theme: KioskBrandTheme,
    private val assets: BrandAssets,
) {
    val kit = ViewKit(activity, theme)

    /** Page root: background colour, then the configured background image (if loaded). */
    fun root(): FrameLayout = FrameLayout(activity).apply {
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

    fun brandBar(): View? = if (theme.brandBar.isEmpty()) null else BrandBarView(activity, theme.brandBar)

    /**
     * The configured logo, else the bundled MeinConnect logo; null while a configured logo is still
     * loading (or failed), so a white-label kiosk never flashes the MeinConnect mark.
     */
    fun logo(full: Boolean): ImageView? {
        val custom = assets.logo
        if (custom == null && theme.logoUrl != null) return null
        return ImageView(activity).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_START
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
     * Card surface with tap ripple. [aurora] adds the soft blue glow of the CI (radial, top right).
     * Light themes float on a blue-tinted shadow; dark themes get a hairline instead.
     */
    fun card(view: View, radiusDp: Int, aurora: Boolean = false) {
        val base = kit.rounded(theme.card, radiusDp, if (theme.isDark) theme.line else null)
        val fill: Drawable = if (aurora && !theme.isDark) {
            val glow = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                colors = intArrayOf(AURORA_BLUE, AURORA_VIOLET, AURORA_CLEAR)
                gradientRadius = kit.dp(AURORA_RADIUS_DP).toFloat()
                setGradientCenter(1f, 0f)
                cornerRadius = kit.dp(radiusDp).toFloat()
            }
            LayerDrawable(arrayOf(base, glow))
        } else {
            base
        }
        view.background = kit.pressable(fill, radiusDp)
        view.clipToOutline = true
        if (!theme.isDark) {
            view.elevation = kit.dp(CARD_ELEVATION_DP).toFloat()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                view.outlineSpotShadowColor = SHADOW_BLUE
                view.outlineAmbientShadowColor = SHADOW_BLUE
            }
        }
    }

    /** Pill fill for the main call to action: the MeinConnect gradient, or the solid accent when re-skinned. */
    fun ctaBackground(): Drawable {
        val pill = if (theme.accent == KioskBrand.BLUE) {
            GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(KioskBrand.BLUE, KioskBrand.VIOLET, KioskBrand.BERRY),
            ).apply { cornerRadius = kit.dp(PILL_DP).toFloat() }
        } else {
            kit.rounded(theme.accent, PILL_DP)
        }
        return kit.pressable(pill, PILL_DP)
    }

    /** A stroke glyph from res/drawable/ic_mc_*, tinted. */
    fun glyph(res: Int, color: Int): ImageView = ImageView(activity).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
    }

    /** Pads [view] by the system bars so content clears the status/navigation bar (edge-to-edge, API 35). */
    fun insetSystemBars(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val CHIP_RADIUS_DP = 10
        const val CHIP_PAD_H_DP = 10
        const val CHIP_PAD_V_DP = 6
        const val PILL_DP = 999
        const val CARD_ELEVATION_DP = 3
        const val AURORA_RADIUS_DP = 260
        const val AURORA_BLUE = 0x293B82F6
        const val AURORA_VIOLET = 0x128B5CF6
        const val AURORA_CLEAR = 0x00FFFFFF
        const val SHADOW_BLUE = 0xFF0957C3.toInt()
    }
}

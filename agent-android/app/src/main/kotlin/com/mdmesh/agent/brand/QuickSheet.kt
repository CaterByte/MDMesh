package com.mdmesh.agent.brand

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.mdmesh.agent.R

/**
 * MeinConnect fork: the quick-settings sheet of the kiosk home. Only the settings the configuration
 * enabled appear (Wi-Fi picker, brightness, volume); Android Settings themselves stay locked.
 */
class QuickSheet(
    private val parts: BrandParts,
    wifi: WifiControl,
    private val light: LightAndSound,
    private val tabs: List<QuickTab>,
    private val window: Window,
    private val onClosed: () -> Unit,
) {
    private val activity = parts.activity
    private val theme = parts.theme
    private val kit = parts.kit
    private val slider = QuickSlider(parts)
    private val frame = SheetFrame(parts) { close(notify = true) }
    private val wifiPanel = WifiPanel(parts, wifi) { if (isOpen) render() }
    private var tab = tabs.firstOrNull() ?: QuickTab.WIFI

    val isOpen: Boolean get() = frame.isOpen

    fun open(host: FrameLayout, start: QuickTab) {
        tab = start
        frame.open(host)
        if (tab == QuickTab.WIFI) wifiPanel.start()
        render()
    }

    fun close(notify: Boolean) {
        if (!frame.isOpen) return
        wifiPanel.stop()
        frame.close()
        if (notify) onClosed()
    }

    private fun render() {
        val body = frame.body
        body.removeAllViews()
        val (title, hint) = when (tab) {
            QuickTab.WIFI -> R.string.qs_wifi to R.string.qs_wifi_sub
            QuickTab.BRIGHTNESS -> R.string.qs_brightness to R.string.qs_brightness_hint
            QuickTab.VOLUME -> R.string.qs_volume to R.string.qs_volume_hint
        }
        body.addView(header(activity.getString(title), activity.getString(hint)))
        if (tabs.size > 1 && !wifiPanel.isEditing) body.addView(tabChips())
        val content = when (tab) {
            QuickTab.WIFI -> wifiPanel.build()
            QuickTab.BRIGHTNESS -> slider.build(SliderSpec.brightness(light.brightnessPercent())) {
                light.setBrightnessPercent(it, window)
            }
            QuickTab.VOLUME -> slider.build(SliderSpec.volume(light.volumePercent())) { light.setVolumePercent(it) }
        }
        body.addView(content, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(CONTENT_GAP_DP) })
    }

    /** Title + subtitle, with the Wi-Fi refresh button on the right. */
    private fun header(title: String, hint: String): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(kit.text(title, TITLE_SP, theme.text, BrandFonts.semibold(activity)))
            val sub = kit.text(hint, SUB_SP, theme.muted, BrandFonts.regular(activity))
            addView(sub, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(SUB_GAP_DP) })
        }
        addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (tab == QuickTab.WIFI) wifiPanel.headerAction()?.let { addView(it) }
    }

    private fun tabChips(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        tabs.forEachIndexed { i, t ->
            val label = when (t) {
                QuickTab.WIFI -> R.string.qs_wifi
                QuickTab.BRIGHTNESS -> R.string.qs_brightness
                QuickTab.VOLUME -> R.string.qs_volume
            }
            val on = t == tab
            val chip = kit.text(activity.getString(label), CHIP_SP, if (on) theme.onAccent else theme.text,
                BrandFonts.medium(activity)).apply {
                background = if (on) {
                    kit.rounded(theme.accent, PILL_DP)
                } else {
                    kit.rounded(theme.card, PILL_DP, theme.line)
                }
                setPadding(kit.dp(CHIP_PAD_H_DP), kit.dp(CHIP_PAD_V_DP), kit.dp(CHIP_PAD_H_DP), kit.dp(CHIP_PAD_V_DP))
                setOnClickListener { select(t) }
            }
            val lp = LinearLayout.LayoutParams(WRAP, WRAP)
            if (i > 0) lp.marginStart = kit.dp(CHIP_GAP_DP)
            addView(chip, lp)
        }
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(CHIPS_GAP_DP) }
    }

    private fun select(t: QuickTab) {
        if (t == tab) return
        if (tab == QuickTab.WIFI) wifiPanel.stop()
        tab = t
        if (t == QuickTab.WIFI) wifiPanel.start()
        render()
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val TITLE_SP = 22f
        const val SUB_SP = 14f
        const val SUB_GAP_DP = 2
        const val CHIPS_GAP_DP = 16
        const val CHIP_GAP_DP = 8
        const val CHIP_SP = 13f
        const val CHIP_PAD_H_DP = 14
        const val CHIP_PAD_V_DP = 8
        const val PILL_DP = 999
        const val CONTENT_GAP_DP = 12
    }
}

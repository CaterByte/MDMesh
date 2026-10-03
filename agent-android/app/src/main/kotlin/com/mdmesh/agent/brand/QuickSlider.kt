package com.mdmesh.agent.brand

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import com.mdmesh.agent.R

/** What a quick-settings slider shows: current [value] in percent, end icons and end labels. */
data class SliderSpec(val value: Int, val lowIcon: Int, val highIcon: Int, val lowText: Int, val highText: Int) {
    companion object {
        fun brightness(value: Int) =
            SliderSpec(value, R.drawable.ic_mc_sun_small, R.drawable.ic_mc_sun, R.string.qs_dark, R.string.qs_bright)

        fun volume(value: Int) =
            SliderSpec(value, R.drawable.ic_mc_volume_low, R.drawable.ic_mc_volume, R.string.qs_quiet, R.string.qs_loud)
    }
}

/** MeinConnect fork: the 0–100 % slider of the quick-settings sheet, in the accent colour. */
class QuickSlider(private val parts: BrandParts) {

    private val activity = parts.activity
    private val theme = parts.theme
    private val kit = parts.kit

    fun build(spec: SliderSpec, onChange: (Int) -> Unit): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val percent = kit.text(activity.getString(R.string.qs_percent, spec.value), LABEL_SP, theme.text,
            BrandFonts.medium(activity))
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(parts.glyph(spec.lowIcon, theme.muted), LinearLayout.LayoutParams(kit.dp(ICON_DP), kit.dp(ICON_DP)))
            val seek = SeekBar(activity).apply {
                max = PERCENT
                progress = spec.value
                progressTintList = ColorStateList.valueOf(theme.accent)
                thumbTintList = ColorStateList.valueOf(theme.accent)
                progressBackgroundTintList = ColorStateList.valueOf(theme.line)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        onChange(p)
                        percent.text = activity.getString(R.string.qs_percent, p)
                    }

                    override fun onStartTrackingTouch(bar: SeekBar) = Unit

                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                })
            }
            addView(seek, LinearLayout.LayoutParams(0, kit.dp(SEEK_H_DP), 1f))
            addView(parts.glyph(spec.highIcon, theme.text), LinearLayout.LayoutParams(kit.dp(ICON_DP), kit.dp(ICON_DP)))
        }
        addView(row)
        val labels = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            val low = kit.text(activity.getString(spec.lowText), LABEL_SP, theme.muted, BrandFonts.regular(activity))
            addView(low, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(percent.apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, WRAP, 1f))
            val high = kit.text(activity.getString(spec.highText), LABEL_SP, theme.muted, BrandFonts.regular(activity))
            addView(high.apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        addView(labels, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP).apply {
            topMargin = kit.dp(LABELS_GAP_DP)
        })
    }

    private companion object {
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val PERCENT = 100
        const val ICON_DP = 22
        const val SEEK_H_DP = 48
        const val LABEL_SP = 13f
        const val LABELS_GAP_DP = 6
    }
}

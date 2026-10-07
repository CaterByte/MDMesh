package com.mdmesh.agent.brand

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * MeinConnect fork: the bottom sheet every kiosk panel uses — scrim, rounded card with grab handle, capped width on
 * tablets. The panel rides above the on-screen keyboard (IME insets), so a password field never ends up hidden.
 */
class SheetFrame(private val parts: BrandParts, private val onDismiss: () -> Unit) {
    private val activity = parts.activity
    private val kit = parts.kit
    private var overlay: FrameLayout? = null

    /** Content container of the open sheet; children are laid out vertically. */
    val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    val isOpen: Boolean get() = overlay != null

    @Suppress("DEPRECATION") // ADJUST_RESIZE: pre-Android-11 fallback; newer releases go through the IME insets below
    fun open(host: FrameLayout) {
        activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val scrim = FrameLayout(activity).apply {
            setBackgroundColor(SCRIM)
            isClickable = true
            setOnClickListener { onDismiss() }
        }
        val pad = kit.dp(SHEET_PAD_DP)
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            background = GradientDrawable().apply {
                setColor(parts.theme.card)
                val r = kit.dp(SHEET_RADIUS_DP).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            val grab = View(activity).apply { background = kit.rounded(parts.theme.line, GRAB_H_DP) }
            addView(grab, LinearLayout.LayoutParams(kit.dp(GRAB_W_DP), kit.dp(GRAB_H_DP)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = kit.dp(GRAB_GAP_DP)
            })
            addView(body)
        }
        // Bottom padding = navigation bar or keyboard, whichever is taller.
        ViewCompat.setOnApplyWindowInsetsListener(panel) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            v.setPadding(pad, kit.dp(GRAB_TOP_DP), pad, kit.dp(SHEET_BOTTOM_DP) + maxOf(nav, ime))
            insets
        }
        val width = minOf(kit.screenWidth(), kit.dp(MAX_WIDTH_DP))
        scrim.addView(panel, FrameLayout.LayoutParams(width, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        host.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        overlay = scrim
        ViewCompat.requestApplyInsets(panel)
    }

    fun close() {
        val o = overlay ?: return
        (o.parent as? ViewGroup)?.removeView(o)
        overlay = null
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val SCRIM = 0x61111418
        const val MAX_WIDTH_DP = 560
        const val SHEET_RADIUS_DP = 26
        const val SHEET_PAD_DP = 22
        const val SHEET_BOTTOM_DP = 28
        const val GRAB_TOP_DP = 12
        const val GRAB_W_DP = 40
        const val GRAB_H_DP = 5
        const val GRAB_GAP_DP = 16
    }
}

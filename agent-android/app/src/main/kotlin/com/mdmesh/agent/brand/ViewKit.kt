package com.mdmesh.agent.brand

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.mdmesh.kiosk.brand.KioskBrandTheme

/** MeinConnect fork: tiny helpers for the programmatic (XML-free) launcher views. */
class ViewKit(private val context: Context, private val theme: KioskBrandTheme) {

    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun screenWidth(): Int = context.resources.displayMetrics.widthPixels

    /** Centres [view]'s text and gives it full width with [topDp] spacing above (vertical LinearLayout). */
    fun centered(view: TextView, topDp: Int): View {
        view.gravity = Gravity.CENTER
        view.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(topDp) }
        return view
    }

    fun text(value: CharSequence, sizeSp: Float, color: Int, face: Typeface): TextView =
        TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            typeface = face
            includeFontPadding = false
        }

    /** Rounded rectangle fill with an optional hairline border. */
    fun rounded(fill: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    /** Wraps [content] in an accent-tinted ripple so taps get visible feedback. */
    fun pressable(content: Drawable, radiusDp: Int): Drawable {
        val pressed = (RIPPLE_ALPHA shl ALPHA_SHIFT) or (theme.accent and RGB_MASK)
        val mask = rounded(fill = theme.text, radiusDp = radiusDp)
        return RippleDrawable(ColorStateList.valueOf(pressed), content, mask)
    }

    private companion object {
        const val RIPPLE_ALPHA = 0x33
        const val ALPHA_SHIFT = 24
        const val RGB_MASK = 0x00FFFFFF
    }
}

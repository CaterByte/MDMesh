package com.mdmesh.agent.brand

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.mdmesh.agent.R
import com.mdmesh.kiosk.brand.KioskBrand

/**
 * MeinConnect fork: inline password entry of the Wi-Fi panel — network card, rounded field with an accent outline
 * while focused, show/hide toggle, an error line, and a full-width "Connect" that only enables for a valid WPA key
 * (8–63 characters). "Done" on the keyboard connects as well.
 */
class WifiPasswordForm(
    private val parts: BrandParts,
    private val onBack: () -> Unit,
    private val onSubmit: (WifiNetwork, String) -> Unit,
) {
    private val activity = parts.activity
    private val theme = parts.theme
    private val kit = parts.kit

    fun build(net: WifiNetwork, error: String?): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(backLink())
        addView(networkCard(net), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(GAP_DP) })
        val input = input()
        addView(field(input), LinearLayout.LayoutParams(MATCH, kit.dp(FIELD_HEIGHT_DP)).apply {
            topMargin = kit.dp(GAP_DP)
        })
        if (error != null) {
            addView(kit.text(error, SMALL_SP, KioskBrand.ALERT, BrandFonts.regular(activity)),
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(ERROR_GAP_DP) })
        }
        addView(connectButton(net, input), LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = kit.dp(CTA_GAP_DP)
        })
        val hint = kit.text(activity.getString(R.string.qs_password_hint), HINT_SP, theme.muted,
            BrandFonts.regular(activity)).apply { gravity = Gravity.CENTER }
        addView(hint, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(ERROR_GAP_DP) })
        input.post { showKeyboard(input) }
    }

    private fun input(): EditText = EditText(activity).apply {
        background = null
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        hint = activity.getString(R.string.qs_password)
        setHintTextColor(theme.muted)
        setTextColor(theme.text)
        textSize = FIELD_SP
        typeface = BrandFonts.regular(activity)
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        setPadding(kit.dp(FIELD_PAD_DP), 0, 0, 0)
        highlightColor = withAlpha(theme.accent, SELECTION_ALPHA)
    }

    /** Gradient "Connect": enabled for a valid key; also fired by the keyboard's Done/Enter. */
    private fun connectButton(net: WifiNetwork, input: EditText): TextView {
        val label = activity.getString(R.string.qs_connect)
        val cta = kit.text(label, BUTTON_SP, theme.onAccent, BrandFonts.medium(activity))
        cta.gravity = Gravity.CENTER
        cta.background = parts.ctaBackground()
        cta.setPadding(0, kit.dp(CTA_PAD_V_DP), 0, kit.dp(CTA_PAD_V_DP))
        val submit = {
            if (valid(input.text)) {
                hideKeyboard(input)
                onSubmit(net, input.text.toString())
            }
        }
        cta.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, action, event ->
            val done = action == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (done) submit()
            done
        }
        val refresh = {
            val ok = valid(input.text)
            cta.isEnabled = ok
            cta.alpha = if (ok) 1f else DISABLED_ALPHA
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        refresh()
        return cta
    }

    private fun backLink(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = kit.dp(BACK_PAD_DP)
        setPadding(0, pad, pad, pad)
        addView(parts.glyph(R.drawable.ic_mc_back, theme.accent),
            LinearLayout.LayoutParams(kit.dp(BACK_ICON_DP), kit.dp(BACK_ICON_DP)))
        val label = kit.text(activity.getString(R.string.qs_all_networks), SMALL_SP, theme.accent,
            BrandFonts.medium(activity))
        addView(label, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = kit.dp(BACK_GAP_DP) })
        setOnClickListener {
            hideKeyboard(this)
            onBack()
        }
    }

    private fun networkCard(net: WifiNetwork): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val disc = FrameLayout(activity).apply {
            background = kit.rounded(withAlpha(theme.accent, TINT_ALPHA), PILL_DP)
            addView(parts.glyph(R.drawable.ic_mc_wifi, theme.accent),
                FrameLayout.LayoutParams(kit.dp(ICON_DP), kit.dp(ICON_DP), Gravity.CENTER))
        }
        addView(disc, LinearLayout.LayoutParams(kit.dp(DISC_DP), kit.dp(DISC_DP)))
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(kit.text(net.ssid, NAME_SP, theme.text, BrandFonts.semibold(activity)).apply { maxLines = 1 })
            val sec = if (net.security == WifiSecurity.SAE) R.string.qs_security_wpa3 else R.string.qs_security_wpa2
            addView(kit.text(activity.getString(sec), SMALL_SP, theme.muted, BrandFonts.regular(activity)),
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(SUB_GAP_DP) })
        }
        addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = kit.dp(DISC_GAP_DP) })
    }

    /** Rounded field with an accent outline while focused and an eye toggle to reveal the password. */
    private fun field(input: EditText): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val idle = kit.rounded(theme.background, FIELD_RADIUS_DP, theme.line)
        val focused = kit.rounded(theme.background, FIELD_RADIUS_DP).apply { setStroke(kit.dp(2), theme.accent) }
        background = idle
        input.setOnFocusChangeListener { _, has -> background = if (has) focused else idle }
        addView(input, LinearLayout.LayoutParams(0, MATCH, 1f))
        var visible = false
        val eye = parts.glyph(R.drawable.ic_mc_eye, theme.muted).apply {
            val pad = kit.dp(EYE_PAD_DP)
            setPadding(pad, pad, pad, pad)
            contentDescription = activity.getString(R.string.qs_show_password)
            setOnClickListener {
                visible = !visible
                val cursor = input.selectionEnd.coerceAtLeast(0)
                val variation = if (visible) {
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                } else {
                    InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                input.inputType = InputType.TYPE_CLASS_TEXT or variation
                input.typeface = BrandFonts.regular(activity)
                input.setSelection(cursor)
                setImageResource(if (visible) R.drawable.ic_mc_eye_off else R.drawable.ic_mc_eye)
            }
        }
        addView(eye, LinearLayout.LayoutParams(kit.dp(EYE_DP), kit.dp(EYE_DP)).apply {
            marginEnd = kit.dp(EYE_MARGIN_DP)
        })
    }

    private fun valid(text: CharSequence?): Boolean = (text?.length ?: 0) in MIN_PSK..MAX_PSK

    private fun showKeyboard(view: View) {
        view.requestFocus()
        activity.getSystemService(InputMethodManager::class.java)?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard(view: View) {
        activity.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val PILL_DP = 999
        const val GAP_DP = 14
        const val ERROR_GAP_DP = 8
        const val CTA_GAP_DP = 18
        const val CTA_PAD_V_DP = 14
        const val BUTTON_SP = 16f
        const val SMALL_SP = 13f
        const val HINT_SP = 12f
        const val NAME_SP = 18f
        const val SUB_GAP_DP = 2
        const val ICON_DP = 22
        const val DISC_DP = 44
        const val DISC_GAP_DP = 14
        const val TINT_ALPHA = 0x1F
        const val SELECTION_ALPHA = 0x40
        const val BACK_PAD_DP = 4
        const val BACK_ICON_DP = 18
        const val BACK_GAP_DP = 6
        const val FIELD_HEIGHT_DP = 54
        const val FIELD_RADIUS_DP = 14
        const val FIELD_PAD_DP = 16
        const val FIELD_SP = 16f
        const val EYE_DP = 44
        const val EYE_PAD_DP = 11
        const val EYE_MARGIN_DP = 4
        const val DISABLED_ALPHA = 0.45f
        const val MIN_PSK = 8
        const val MAX_PSK = 63
    }
}

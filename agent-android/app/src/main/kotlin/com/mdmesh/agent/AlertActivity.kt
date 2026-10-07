package com.mdmesh.agent

import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import com.mdmesh.agent.brand.BrandAssets
import com.mdmesh.agent.brand.BrandFonts
import com.mdmesh.agent.brand.BrandParts
import com.mdmesh.core.action.AlertNotifier
import com.mdmesh.kiosk.brand.KioskBrand

/**
 * MeinConnect fork: shows an MDM message (`device.alert`) as a card on top of whatever is on screen — also over the
 * kiosk app and the lock screen. A notification alone is easy to miss on a kiosk phone, and invisible while the
 * kiosk has notifications switched off. Launched by [AlertNotifier]; the Device Owner may start activities from the
 * background. Its own task, so closing it returns to the app that was in front.
 */
class AlertActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        render()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render()
    }

    private fun render() {
        val title = intent.getStringExtra(AlertNotifier.EXTRA_TITLE).orEmpty()
        val body = intent.getStringExtra(AlertNotifier.EXTRA_BODY).orEmpty()
        val theme = KioskBrand.resolve(null, null, null, null, null, null, null, null)
        val parts = BrandParts(this, theme, BrandAssets())
        val kit = parts.kit
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = kit.dp(CARD_PAD_DP)
            setPadding(pad, pad, pad, pad)
            parts.card(this, CARD_RADIUS_DP, aurora = true)
            isClickable = true
            val kicker = kit.text(getString(R.string.alert_kicker).uppercase(), KICKER_SP, theme.muted,
                BrandFonts.medium(this@AlertActivity)).apply { letterSpacing = KICKER_TRACKING }
            addView(kicker)
            if (title.isNotBlank()) {
                addView(kit.text(title, TITLE_SP, theme.text, BrandFonts.semibold(this@AlertActivity)),
                    LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(TITLE_TOP_DP) })
            }
            if (body.isNotBlank()) {
                addView(kit.text(body, BODY_SP, theme.text, BrandFonts.regular(this@AlertActivity)).apply {
                    setLineSpacing(0f, LINE_SPACING)
                }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(BODY_TOP_DP) })
            }
            val ok = kit.text(getString(R.string.alert_ok), BUTTON_SP, theme.onAccent,
                BrandFonts.medium(this@AlertActivity)).apply {
                gravity = Gravity.CENTER
                background = parts.ctaBackground()
                setPadding(0, kit.dp(BUTTON_PAD_DP), 0, kit.dp(BUTTON_PAD_DP))
                setOnClickListener { finish() }
            }
            addView(ok, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(BUTTON_TOP_DP) })
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(SCRIM)
            val width = minOf(kit.screenWidth() - kit.dp(MARGIN_DP * 2), kit.dp(MAX_WIDTH_DP))
            addView(card, FrameLayout.LayoutParams(width, WRAP, Gravity.CENTER))
        }
        setContentView(root)
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val SCRIM = 0x8C111418.toInt()
        const val MARGIN_DP = 20
        const val MAX_WIDTH_DP = 480
        const val CARD_PAD_DP = 24
        const val CARD_RADIUS_DP = 24
        const val KICKER_SP = 11f
        const val KICKER_TRACKING = 0.16f
        const val TITLE_SP = 22f
        const val TITLE_TOP_DP = 8
        const val BODY_SP = 16f
        const val BODY_TOP_DP = 10
        const val LINE_SPACING = 1.25f
        const val BUTTON_SP = 16f
        const val BUTTON_PAD_DP = 14
        const val BUTTON_TOP_DP = 22
    }
}

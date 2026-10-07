package com.mdmesh.agent.brand

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import com.mdmesh.agent.R
import com.mdmesh.kiosk.brand.KioskBrand
import java.util.Date

/** What the Wi-Fi list shows; [networks] is null while Wi-Fi is switched off. */
data class WifiListState(
    val networks: List<WifiNetwork>?,
    val scanning: Boolean,
    val updatedAt: Long,
    val connecting: String?,
    val error: String?,
)

/** [color] with the alpha byte replaced by [alpha] (0..255). */
internal fun withAlpha(color: Int, alpha: Int): Int = (alpha shl ALPHA_SHIFT) or (color and RGB_MASK)

private const val ALPHA_SHIFT = 24
private const val RGB_MASK = 0x00FFFFFF

/**
 * MeinConnect fork: draws the network list of the Wi-Fi panel — status line, rows with signal, lock and
 * "connecting" spinner, the refresh button — from a [WifiListState]. No state of its own.
 */
class WifiListView(
    private val parts: BrandParts,
    private val onPick: (WifiNetwork) -> Unit,
    private val onRefresh: () -> Unit,
    private val onEnable: () -> Unit,
) {
    private val activity = parts.activity
    private val theme = parts.theme
    private val kit = parts.kit

    /** Round refresh button; spins while a scan is running. */
    fun refreshButton(scanning: Boolean): View {
        val icon = parts.glyph(R.drawable.ic_mc_refresh, theme.text)
        val pad = kit.dp(ACTION_PAD_DP)
        icon.setPadding(pad, pad, pad, pad)
        icon.background = kit.pressable(kit.rounded(theme.card, PILL_DP, theme.line), PILL_DP)
        icon.contentDescription = activity.getString(R.string.qs_refresh)
        icon.setOnClickListener { onRefresh() }
        icon.layoutParams = LinearLayout.LayoutParams(kit.dp(ACTION_DP), kit.dp(ACTION_DP))
        if (scanning) {
            val spin = ObjectAnimator.ofFloat(icon, View.ROTATION, 0f, FULL_TURN).apply {
                duration = SPIN_MS
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
            // The panel re-renders often: stop spinning as soon as this button leaves the screen.
            icon.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) = spin.cancel()
            })
        }
        return icon
    }

    fun build(state: WifiListState): View {
        val networks = state.networks ?: return wifiOff()
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        box.addView(statusLine(state))
        if (networks.isEmpty()) {
            val msg = if (state.scanning) R.string.qs_scanning else R.string.qs_no_networks
            box.addView(kit.text(activity.getString(msg), ROW_SP, theme.muted, BrandFonts.regular(activity)),
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(LIST_GAP_DP) })
        } else {
            val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            networks.forEach { rows.addView(row(it, state.connecting)) }
            val height = if (networks.size > VISIBLE_ROWS) kit.dp(ROW_HEIGHT_DP * VISIBLE_ROWS) else WRAP
            box.addView(ScrollView(activity).apply { addView(rows) },
                LinearLayout.LayoutParams(MATCH, height).apply { topMargin = kit.dp(LIST_GAP_DP) })
        }
        return box
    }

    /** "Searching …" / "Updated 14:32", or the last error, above the list. */
    private fun statusLine(state: WifiListState): View {
        val err = state.error
        val text = when {
            err != null -> err
            state.scanning && !state.networks.isNullOrEmpty() -> activity.getString(R.string.qs_updating)
            state.updatedAt > 0 -> activity.getString(
                R.string.qs_updated_at,
                DateFormat.getTimeFormat(activity).format(Date(state.updatedAt)),
            )
            else -> activity.getString(R.string.qs_wifi_hint)
        }
        val color = if (err != null) KioskBrand.ALERT else theme.muted
        return kit.text(text, STATUS_SP, color, BrandFonts.regular(activity))
    }

    private fun row(net: WifiNetwork, connecting: String?): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = kit.dp(ROW_HEIGHT_DP)
        val pad = kit.dp(ROW_PAD_DP)
        setPadding(pad, 0, pad, 0)
        val fill = if (net.connected) withAlpha(theme.accent, CONNECTED_ALPHA) else theme.card
        background = kit.pressable(kit.rounded(fill, ROW_RADIUS_DP), ROW_RADIUS_DP)
        val icon = parts.glyph(R.drawable.ic_mc_wifi, if (net.connected) theme.accent else theme.text).apply {
            alpha = SIGNAL_ALPHA_MIN + (1f - SIGNAL_ALPHA_MIN) * net.level / MAX_LEVEL
        }
        addView(icon, LinearLayout.LayoutParams(kit.dp(ROW_ICON_DP), kit.dp(ROW_ICON_DP)))
        val face = if (net.connected) BrandFonts.medium(activity) else BrandFonts.regular(activity)
        val name = kit.text(net.ssid, ROW_SP, theme.text, face).apply { maxLines = 1 }
        addView(name, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = kit.dp(ROW_GAP_DP) })
        trailing(net, connecting == net.ssid).forEach { addView(it) }
        if (!net.connected && connecting == null) setOnClickListener { onPick(net) }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(ROW_SPACING_DP) }
    }

    /** Right side of a row: spinner while joining, "Connected", or a lock for secured networks. */
    private fun trailing(net: WifiNetwork, joining: Boolean): List<View> = when {
        joining -> listOf(
            kit.text(
                activity.getString(R.string.qs_connecting_short),
                STATE_SP,
                theme.muted,
                BrandFonts.regular(activity),
            ),
            ProgressBar(activity).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(theme.accent)
                layoutParams = LinearLayout.LayoutParams(kit.dp(LOCK_DP), kit.dp(LOCK_DP)).apply {
                    marginStart = kit.dp(STATE_GAP_DP)
                }
            },
        )
        net.connected -> listOf(
            kit.text(activity.getString(R.string.qs_connected), STATE_SP, theme.accent, BrandFonts.medium(activity)),
        )
        net.security != WifiSecurity.OPEN -> listOf(
            parts.glyph(R.drawable.ic_mc_lock, theme.muted).apply {
                layoutParams = LinearLayout.LayoutParams(kit.dp(LOCK_DP), kit.dp(LOCK_DP))
            },
        )
        else -> emptyList()
    }

    private fun wifiOff(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(kit.text(activity.getString(R.string.qs_wifi_off), ROW_SP, theme.text, BrandFonts.regular(activity)))
        val on = kit.text(activity.getString(R.string.qs_wifi_turn_on), BUTTON_SP, theme.onAccent,
            BrandFonts.medium(activity)).apply {
            background = parts.ctaBackground()
            setPadding(kit.dp(CTA_PAD_H_DP), kit.dp(CTA_PAD_V_DP), kit.dp(CTA_PAD_H_DP), kit.dp(CTA_PAD_V_DP))
            setOnClickListener { onEnable() }
        }
        addView(on, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(OFF_GAP_DP) })
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val PILL_DP = 999
        const val ACTION_DP = 40
        const val ACTION_PAD_DP = 9
        const val FULL_TURN = 360f
        const val SPIN_MS = 900L
        const val LIST_GAP_DP = 6
        const val ROW_HEIGHT_DP = 58
        const val ROW_SPACING_DP = 6
        const val VISIBLE_ROWS = 5
        const val ROW_PAD_DP = 14
        const val ROW_RADIUS_DP = 14
        const val ROW_ICON_DP = 22
        const val ROW_GAP_DP = 14
        const val ROW_SP = 16f
        const val STATE_SP = 13f
        const val STATE_GAP_DP = 8
        const val STATUS_SP = 13f
        const val LOCK_DP = 16
        const val SIGNAL_ALPHA_MIN = 0.4f
        const val MAX_LEVEL = 4
        const val CONNECTED_ALPHA = 0x1F
        const val BUTTON_SP = 16f
        const val CTA_PAD_H_DP = 22
        const val CTA_PAD_V_DP = 14
        const val OFF_GAP_DP = 14
    }
}

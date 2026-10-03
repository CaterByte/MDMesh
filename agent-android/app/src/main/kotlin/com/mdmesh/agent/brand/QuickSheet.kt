package com.mdmesh.agent.brand

import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mdmesh.agent.R

/**
 * MeinConnect fork: the quick-settings sheet of the kiosk home. Only the settings the configuration
 * enabled appear (Wi-Fi picker, brightness, volume); Android Settings themselves stay locked.
 */
class QuickSheet(
    private val parts: BrandParts,
    private val wifi: WifiControl,
    private val light: LightAndSound,
    private val tabs: List<QuickTab>,
    private val window: Window,
    private val onClosed: () -> Unit,
) {
    private val activity = parts.activity
    private val theme = parts.theme
    private val kit = parts.kit
    private val handler = Handler(Looper.getMainLooper())
    private val slider = QuickSlider(parts)
    private val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private var overlay: FrameLayout? = null
    private var tab = tabs.firstOrNull() ?: QuickTab.WIFI
    private var scans = 0
    private var pending: String? = null

    val isOpen: Boolean get() = overlay != null

    fun open(host: FrameLayout, start: QuickTab) {
        tab = start
        val scrim = FrameLayout(activity).apply {
            setBackgroundColor(SCRIM)
            isClickable = true
            setOnClickListener { close(notify = true) }
        }
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            background = GradientDrawable().apply {
                setColor(theme.card)
                val r = kit.dp(SHEET_RADIUS_DP).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            val navBar = ViewCompat.getRootWindowInsets(host)
                ?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: 0
            val pad = kit.dp(SHEET_PAD_DP)
            setPadding(pad, kit.dp(GRAB_TOP_DP), pad, kit.dp(SHEET_BOTTOM_DP) + navBar)
            val grab = View(activity).apply { background = kit.rounded(theme.line, GRAB_H_DP) }
            addView(grab, LinearLayout.LayoutParams(kit.dp(GRAB_W_DP), kit.dp(GRAB_H_DP)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = kit.dp(GRAB_GAP_DP)
            })
            addView(body)
        }
        val width = minOf(kit.screenWidth(), kit.dp(MAX_WIDTH_DP))
        scrim.addView(panel, FrameLayout.LayoutParams(width, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        host.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        overlay = scrim
        if (tab == QuickTab.WIFI) wifi.startScan()
        render()
    }

    fun close(notify: Boolean) {
        handler.removeCallbacksAndMessages(null)
        val o = overlay ?: return
        (o.parent as? ViewGroup)?.removeView(o)
        overlay = null
        if (notify) onClosed()
    }

    private fun render() {
        body.removeAllViews()
        val (title, hint) = when (tab) {
            QuickTab.WIFI -> R.string.qs_wifi to R.string.qs_wifi_hint
            QuickTab.BRIGHTNESS -> R.string.qs_brightness to R.string.qs_brightness_hint
            QuickTab.VOLUME -> R.string.qs_volume to R.string.qs_volume_hint
        }
        body.addView(kit.text(activity.getString(title), TITLE_SP, theme.text, BrandFonts.semibold(activity)))
        val sub = kit.text(activity.getString(hint), SUB_SP, theme.muted, BrandFonts.regular(activity))
        body.addView(sub, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(SUB_GAP_DP) })
        if (tabs.size > 1) body.addView(tabChips())
        val content = when (tab) {
            QuickTab.WIFI -> wifiBody()
            QuickTab.BRIGHTNESS -> slider.build(SliderSpec.brightness(light.brightnessPercent())) {
                light.setBrightnessPercent(it, window)
            }
            QuickTab.VOLUME -> slider.build(SliderSpec.volume(light.volumePercent())) { light.setVolumePercent(it) }
        }
        body.addView(content, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(CONTENT_GAP_DP) })
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
                setOnClickListener {
                    tab = t
                    if (t == QuickTab.WIFI) wifi.startScan()
                    render()
                }
            }
            val lp = LinearLayout.LayoutParams(WRAP, WRAP)
            if (i > 0) lp.marginStart = kit.dp(CHIP_GAP_DP)
            addView(chip, lp)
        }
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(CHIPS_GAP_DP) }
    }

    private fun wifiBody(): View {
        val networks = if (wifi.isEnabled()) wifi.networks() else null
        return when {
            networks == null -> wifiOff()
            networks.isEmpty() -> {
                if (scans < MAX_EMPTY_SCANS) refreshSoon()
                val msg = if (scans < MAX_EMPTY_SCANS) R.string.qs_scanning else R.string.qs_no_networks
                kit.text(activity.getString(msg), ROW_SP, theme.muted, BrandFonts.regular(activity))
            }
            else -> {
                trackPending(networks)
                val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                networks.forEach { list.addView(networkRow(it)) }
                val height = if (networks.size > VISIBLE_ROWS) kit.dp(ROW_HEIGHT_DP * VISIBLE_ROWS) else WRAP
                ScrollView(activity).apply {
                    addView(list)
                    layoutParams = LinearLayout.LayoutParams(MATCH, height)
                }
            }
        }
    }

    private fun wifiOff(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(kit.text(activity.getString(R.string.qs_wifi_off), ROW_SP, theme.text, BrandFonts.regular(activity)))
        val label = activity.getString(R.string.qs_wifi_turn_on)
        val on = kit.text(label, CHIP_SP, theme.onAccent, BrandFonts.medium(activity)).apply {
            background = parts.ctaBackground()
            setPadding(kit.dp(CTA_PAD_H_DP), kit.dp(CHIP_PAD_V_DP), kit.dp(CTA_PAD_H_DP), kit.dp(CHIP_PAD_V_DP))
            setOnClickListener {
                wifi.enable()
                wifi.startScan()
                refreshSoon()
            }
        }
        addView(on, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(CHIPS_GAP_DP) })
    }

    private fun networkRow(net: WifiNetwork): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = kit.dp(ROW_HEIGHT_DP)
        background = kit.pressable(kit.rounded(theme.card, ROW_RADIUS_DP), ROW_RADIUS_DP)
        val icon = parts.glyph(R.drawable.ic_mc_wifi, if (net.connected) theme.accent else theme.text).apply {
            alpha = SIGNAL_ALPHA_MIN + (1f - SIGNAL_ALPHA_MIN) * net.level / MAX_LEVEL
        }
        addView(icon, LinearLayout.LayoutParams(kit.dp(ROW_ICON_DP), kit.dp(ROW_ICON_DP)))
        val name = kit.text(net.ssid, ROW_SP, theme.text, BrandFonts.regular(activity)).apply { maxLines = 1 }
        addView(name, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = kit.dp(ROW_GAP_DP) })
        when {
            net.connected -> addView(kit.text(activity.getString(R.string.qs_connected), STATE_SP, theme.accent,
                BrandFonts.medium(activity)))
            net.security != WifiSecurity.OPEN -> addView(parts.glyph(R.drawable.ic_mc_lock, theme.muted),
                LinearLayout.LayoutParams(kit.dp(LOCK_DP), kit.dp(LOCK_DP)))
        }
        if (!net.connected) setOnClickListener { pick(net) }
    }

    private fun pick(net: WifiNetwork) {
        fun connect(password: String?) {
            Toast.makeText(activity, activity.getString(R.string.qs_connecting, net.ssid), Toast.LENGTH_SHORT).show()
            wifi.connect(net, password)
            pending = net.ssid
            scans = 0
            refreshSoon()
        }
        when (net.security) {
            WifiSecurity.UNSUPPORTED -> Toast.makeText(activity, R.string.qs_enterprise, Toast.LENGTH_LONG).show()
            WifiSecurity.OPEN -> connect(null)
            else -> {
                val input = EditText(activity).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    hint = activity.getString(R.string.qs_password)
                }
                AlertDialog.Builder(activity)
                    .setTitle(net.ssid)
                    .setView(input)
                    .setPositiveButton(R.string.qs_connect) { _, _ -> connect(input.text.toString()) }
                    .setNegativeButton(R.string.kiosk_exit_cancel, null)
                    .show()
            }
        }
    }

    /** After "connect", keep re-reading until the network shows as connected (or give up). */
    private fun trackPending(networks: List<WifiNetwork>) {
        val joined = networks.any { it.connected && it.ssid == pending }
        if (pending != null && !joined && scans < MAX_CONNECT_CHECKS) refreshSoon() else pending = null
    }

    /** Re-reads the scan a little later (Android delivers results asynchronously). */
    private fun refreshSoon() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            scans++
            if (overlay != null && tab == QuickTab.WIFI) render()
        }, REFRESH_MS)
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
        const val TITLE_SP = 22f
        const val SUB_SP = 14f
        const val SUB_GAP_DP = 2
        const val CHIPS_GAP_DP = 16
        const val CHIP_GAP_DP = 8
        const val CHIP_SP = 13f
        const val CHIP_PAD_H_DP = 14
        const val CHIP_PAD_V_DP = 8
        const val CTA_PAD_H_DP = 22
        const val PILL_DP = 999
        const val CONTENT_GAP_DP = 10
        const val ROW_HEIGHT_DP = 56
        const val VISIBLE_ROWS = 5
        const val ROW_RADIUS_DP = 12
        const val ROW_ICON_DP = 22
        const val ROW_GAP_DP = 14
        const val ROW_SP = 16f
        const val STATE_SP = 13f
        const val LOCK_DP = 16
        const val SIGNAL_ALPHA_MIN = 0.4f
        const val MAX_LEVEL = 4
        const val MAX_EMPTY_SCANS = 3
        const val MAX_CONNECT_CHECKS = 5
        const val REFRESH_MS = 3_000L
    }
}

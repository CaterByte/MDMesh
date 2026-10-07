package com.mdmesh.agent.brand

import android.content.ClipData
import android.content.ClipboardManager
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.mdmesh.agent.R

/** One label/value line of the device info sheet. */
data class InfoRow(val label: String, val value: String)

/** A titled group of rows. */
data class InfoSection(val title: String, val rows: List<InfoRow>)

/** Everything the device info sheet shows; [name] is the name given in MeinConnect (null = unnamed). */
data class DeviceInfo(val name: String?, val subtitle: String, val sections: List<InfoSection>)

/**
 * MeinConnect fork: the kiosk's device info — name, number, customer, configuration, model, Android, serial/IMEI,
 * network and management state — opened from the (i) on the home screen. Long-press a value to copy it, e.g. the
 * IMEI for a support call. [onRefresh] asks the agent to check in right away (picks up a renamed device).
 */
class InfoSheet(
    private val parts: BrandParts,
    private val onRefresh: () -> Unit,
    private val onClosed: () -> Unit,
) {
    private val activity = parts.activity
    private val theme = parts.theme
    private val kit = parts.kit
    private val frame = SheetFrame(parts) { close(notify = true) }

    val isOpen: Boolean get() = frame.isOpen

    fun open(host: FrameLayout, info: DeviceInfo) {
        frame.open(host)
        show(info)
    }

    /** Re-draws the open sheet with fresh values (after a refresh). */
    fun show(info: DeviceInfo) {
        if (!frame.isOpen) return
        val body = frame.body
        body.removeAllViews()
        body.addView(header(info))
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        info.sections.filter { it.rows.isNotEmpty() }.forEach { list.addView(section(it)) }
        val maxHeight = (activity.resources.displayMetrics.heightPixels * MAX_HEIGHT_SHARE).toInt()
        val scroll = object : ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
            }
        }.apply { addView(list) }
        body.addView(scroll, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(SECTION_TOP_DP) })
        val hint = kit.text(activity.getString(R.string.info_copy_hint), HINT_SP, theme.muted,
            BrandFonts.regular(activity))
        hint.gravity = Gravity.CENTER
        body.addView(hint, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(HINT_TOP_DP) })
    }

    fun close(notify: Boolean) {
        if (!frame.isOpen) return
        frame.close()
        if (notify) onClosed()
    }

    private fun header(info: DeviceInfo): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val kicker = kit.text(activity.getString(R.string.info_title).uppercase(), KICKER_SP, theme.muted,
                BrandFonts.medium(activity)).apply { letterSpacing = KICKER_TRACKING }
            addView(kicker)
            val name = info.name
            val title = kit.text(name ?: activity.getString(R.string.info_unnamed), NAME_SP,
                if (name != null) theme.text else theme.muted, BrandFonts.semibold(activity)).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            addView(title, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(NAME_TOP_DP) })
            addView(kit.text(info.subtitle, SUB_SP, theme.muted, BrandFonts.regular(activity)),
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(SUB_TOP_DP) })
        }
        addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        val refresh = parts.glyph(R.drawable.ic_mc_refresh, theme.text).apply {
            val pad = kit.dp(ACTION_PAD_DP)
            setPadding(pad, pad, pad, pad)
            background = kit.pressable(kit.rounded(theme.card, PILL_DP, theme.line), PILL_DP)
            contentDescription = activity.getString(R.string.info_refresh)
            setOnClickListener {
                onRefresh()
                Toast.makeText(activity, R.string.info_refreshing, Toast.LENGTH_SHORT).show()
            }
        }
        addView(refresh, LinearLayout.LayoutParams(kit.dp(ACTION_DP), kit.dp(ACTION_DP)))
    }

    private fun section(s: InfoSection): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val title = kit.text(s.title.uppercase(), KICKER_SP, theme.muted, BrandFonts.medium(activity)).apply {
            letterSpacing = KICKER_TRACKING
        }
        addView(title, LinearLayout.LayoutParams(WRAP, WRAP).apply {
            topMargin = kit.dp(SECTION_GAP_DP)
            marginStart = kit.dp(CARD_PAD_DP)
        })
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = kit.rounded(theme.background, CARD_RADIUS_DP, if (theme.isDark) theme.line else null)
            s.rows.forEachIndexed { i, r ->
                if (i > 0) addView(View(activity).apply { setBackgroundColor(theme.line) },
                    LinearLayout.LayoutParams(MATCH, kit.dp(1)).apply { marginStart = kit.dp(CARD_PAD_DP) })
                addView(row(r))
            }
        }
        addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(TITLE_GAP_DP) })
    }

    private fun row(r: InfoRow): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = kit.dp(ROW_MIN_DP)
        val padV = kit.dp(ROW_PAD_V_DP)
        setPadding(kit.dp(CARD_PAD_DP), padV, kit.dp(CARD_PAD_DP), padV)
        background = kit.pressable(kit.rounded(0x00000000, CARD_RADIUS_DP), CARD_RADIUS_DP)
        addView(kit.text(r.label, LABEL_SP, theme.muted, BrandFonts.regular(activity)),
            LinearLayout.LayoutParams(WRAP, WRAP))
        val value = kit.text(r.value, VALUE_SP, theme.text, BrandFonts.medium(activity)).apply {
            gravity = Gravity.END
            maxLines = VALUE_LINES
            ellipsize = TextUtils.TruncateAt.END
        }
        addView(value, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = kit.dp(ROW_GAP_DP) })
        setOnLongClickListener {
            val clip = activity.getSystemService(ClipboardManager::class.java)
            clip?.setPrimaryClip(ClipData.newPlainText(r.label, r.value))
            Toast.makeText(activity, activity.getString(R.string.info_copied, r.label), Toast.LENGTH_SHORT).show()
            true
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val MAX_HEIGHT_SHARE = 0.62f
        const val PILL_DP = 999
        const val ACTION_DP = 40
        const val ACTION_PAD_DP = 9
        const val KICKER_SP = 11f
        const val KICKER_TRACKING = 0.16f
        const val NAME_SP = 24f
        const val NAME_TOP_DP = 4
        const val SUB_SP = 14f
        const val SUB_TOP_DP = 2
        const val SECTION_TOP_DP = 6
        const val SECTION_GAP_DP = 16
        const val TITLE_GAP_DP = 6
        const val CARD_RADIUS_DP = 16
        const val CARD_PAD_DP = 16
        const val ROW_MIN_DP = 46
        const val ROW_PAD_V_DP = 10
        const val ROW_GAP_DP = 16
        const val LABEL_SP = 14f
        const val VALUE_SP = 14f
        const val VALUE_LINES = 3
        const val HINT_SP = 12f
        const val HINT_TOP_DP = 14
    }
}

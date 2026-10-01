package com.mdmesh.kiosk.brand

/**
 * MeinConnect fork: launcher branding, resolved from the (all-optional) kiosk theme fields.
 *
 * Pure Kotlin (no Android types) so the parsing and colour maths are unit-tested on the JVM. Colours are
 * packed ARGB ints, exactly what `android.graphics.Color` uses, so the app module can use them directly.
 */

/** One hard-stop segment of the brand bar: [argb] fills from the previous segment's end up to [end] (0..1]. */
data class BarStop(val argb: Int, val end: Float)

/** Everything the launcher needs to draw itself; produced by [KioskBrand.resolve]. */
data class KioskBrandTheme(
    val background: Int,
    val text: Int,
    val muted: Int,
    val card: Int,
    val line: Int,
    val accent: Int,
    val onAccent: Int,
    val isDark: Boolean,
    val brandBar: List<BarStop>,
    val iconSizeDp: Int,
    val title: String?,
    val logoUrl: String?,
    val backgroundImageUrl: String?,
)

object KioskBrand {

    // MeinConnect CI (design/ci/tokens): paper/soft/ink base, blue accent, signature bar with hard stops.
    const val PAPER: Int = 0xFFFFFFFF.toInt()
    const val SOFT: Int = 0xFFF4F5F7.toInt()
    const val INK: Int = 0xFF111418.toInt()
    const val BLUE: Int = 0xFF0957C3.toInt()
    const val VIOLET: Int = 0xFF593C90.toInt()
    const val BERRY: Int = 0xFFAA205D.toInt()
    const val RED: Int = 0xFFFA052A.toInt()
    const val ALERT: Int = 0xFFC6261D.toInt()

    private const val STOP_BLUE = 0.40f
    private const val STOP_VIOLET = 0.64f
    private const val STOP_BERRY = 0.84f
    private const val PERCENT = 100f

    /** The MeinConnect signature bar (topbar/footer of the portal): blue-led, hard stops. */
    val SIGNATURE_BAR: List<BarStop> = listOf(
        BarStop(BLUE, STOP_BLUE),
        BarStop(VIOLET, STOP_VIOLET),
        BarStop(BERRY, STOP_BERRY),
        BarStop(RED, 1f),
    )

    private const val ICON_SMALL_DP = 56
    private const val ICON_MEDIUM_DP = 72
    private const val ICON_LARGE_DP = 96

    private const val MUTED_MIX = 0.38f
    private const val LINE_MIX = 0.12f
    private const val DARK_CARD_MIX = 0.08f
    private const val LIGHT_CARD_MIX = 0.9f
    private const val DARK_LUMINANCE = 0.4
    private const val ON_ACCENT_LUMINANCE = 0.55

    /**
     * Resolves the theme. Every argument is the raw value from the kiosk payload; null, blank or
     * unparseable values fall back to the MeinConnect defaults (light, soft background, ink text).
     */
    @Suppress("LongParameterList") // mirrors the flat theme DTO one-to-one
    fun resolve(
        backgroundColor: String?,
        textColor: String?,
        accentColor: String?,
        brandBar: String?,
        iconSize: String?,
        title: String?,
        logoUrl: String?,
        backgroundImageUrl: String?,
    ): KioskBrandTheme {
        val bg = parseColor(backgroundColor) ?: SOFT
        val dark = luminance(bg) < DARK_LUMINANCE
        val fg = parseColor(textColor) ?: if (dark) PAPER else INK
        val accent = parseColor(accentColor) ?: BLUE
        return KioskBrandTheme(
            background = bg,
            text = fg,
            muted = blend(fg, bg, MUTED_MIX),
            card = if (dark) blend(bg, fg, DARK_CARD_MIX) else blend(bg, PAPER, LIGHT_CARD_MIX),
            line = blend(bg, fg, LINE_MIX),
            accent = accent,
            onAccent = if (luminance(accent) > ON_ACCENT_LUMINANCE) INK else PAPER,
            isDark = dark,
            brandBar = parseBar(brandBar),
            iconSizeDp = iconSizeDp(iconSize),
            title = title.clean(),
            logoUrl = logoUrl.clean()?.takeIf { it.isHttps() },
            backgroundImageUrl = backgroundImageUrl.clean()?.takeIf { it.isHttps() },
        )
    }

    fun iconSizeDp(size: String?): Int = when (size?.trim()?.uppercase()) {
        "LARGE" -> ICON_LARGE_DP
        "MEDIUM" -> ICON_MEDIUM_DP
        else -> ICON_SMALL_DP
    }

    /**
     * Parses `#RGB`, `#RRGGBB` or `#AARRGGBB` (leading `#` optional) into a packed ARGB int.
     * Returns null for anything else, so callers can fall back to a default.
     */
    fun parseColor(value: String?): Int? {
        val hex = value?.trim()?.removePrefix("#").orEmpty()
        val valid = hex.isNotEmpty() && hex.all { it.digitToIntOrNull(radix = 16) != null }
        val argb = when {
            !valid -> null
            hex.length == SHORT_HEX -> "FF" + hex.map { "$it$it" }.joinToString("")
            hex.length == RGB_HEX -> "FF$hex"
            hex.length == ARGB_HEX -> hex
            else -> null
        }
        return argb?.toLong(radix = 16)?.toInt()
    }

    /**
     * Brand bar spec: null/blank → [SIGNATURE_BAR]; `none` → no bar; otherwise comma-separated
     * `#RRGGBB:END%` hard stops with strictly increasing ends (the last one is stretched to 100%).
     * `#RRGGBB` without ends on every segment gives equal widths. Anything malformed falls back to
     * the signature bar rather than drawing something half-parsed.
     */
    fun parseBar(spec: String?): List<BarStop> {
        val s = spec?.trim().orEmpty()
        return when {
            s.isEmpty() -> SIGNATURE_BAR
            s.equals("none", ignoreCase = true) -> emptyList()
            else -> parseStops(s) ?: SIGNATURE_BAR
        }
    }

    private fun parseStops(spec: String): List<BarStop>? {
        val parts = spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val colors = parts.mapNotNull { parseColor(it.substringBefore(':')) }
        val ends = if (parts.any { ':' in it }) {
            parts.mapNotNull { endOf(it) }
        } else {
            List(parts.size) { (it + 1f) / parts.size }
        }
        val ok = parts.isNotEmpty() && colors.size == parts.size && ends.size == parts.size &&
            ends.first() > 0f && ends.zipWithNext().all { (a, b) -> b > a }
        return if (ok) {
            colors.mapIndexed { i, c -> BarStop(c, if (i == colors.lastIndex) 1f else ends[i].coerceAtMost(1f)) }
        } else {
            null
        }
    }

    private fun endOf(part: String): Float? =
        if (':' in part) part.substringAfter(':').trim().removeSuffix("%").toFloatOrNull()?.div(PERCENT) else null

    /** Relative luminance (sRGB, WCAG) in 0..1. */
    fun luminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and BYTE) / BYTE.toDouble()
            return if (c <= SRGB_KNEE) c / SRGB_LINEAR else Math.pow((c + SRGB_OFFSET) / SRGB_SCALE, SRGB_GAMMA)
        }
        return LUM_R * channel(SHIFT_R) + LUM_G * channel(SHIFT_G) + LUM_B * channel(0)
    }

    /** Linear mix of two opaque colours: t=0 → [from], t=1 → [to]. */
    fun blend(from: Int, to: Int, t: Float): Int {
        fun mix(shift: Int): Int {
            val a = (from shr shift) and BYTE
            val b = (to shr shift) and BYTE
            return (a + (b - a) * t).toInt().coerceIn(0, BYTE)
        }
        return (BYTE shl SHIFT_A) or (mix(SHIFT_R) shl SHIFT_R) or (mix(SHIFT_G) shl SHIFT_G) or mix(0)
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private fun String.isHttps(): Boolean = startsWith("https://", ignoreCase = true)

    private const val SHORT_HEX = 3
    private const val RGB_HEX = 6
    private const val ARGB_HEX = 8
    private const val BYTE = 0xFF
    private const val SHIFT_A = 24
    private const val SHIFT_R = 16
    private const val SHIFT_G = 8
    private const val LUM_R = 0.2126
    private const val LUM_G = 0.7152
    private const val LUM_B = 0.0722
    private const val SRGB_KNEE = 0.03928
    private const val SRGB_LINEAR = 12.92
    private const val SRGB_OFFSET = 0.055
    private const val SRGB_SCALE = 1.055
    private const val SRGB_GAMMA = 2.4
}

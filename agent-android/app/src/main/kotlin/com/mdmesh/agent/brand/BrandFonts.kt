package com.mdmesh.agent.brand

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.mdmesh.agent.R

/**
 * MeinConnect fork: the CI typeface (Poppins, SIL OFL 1.1 — see third_party/poppins/OFL.txt), bundled as
 * font resources and loaded once per process. Falls back to the system font if a resource fails to load.
 */
object BrandFonts {

    private class Faces(val regular: Typeface, val medium: Typeface, val semibold: Typeface)

    @Volatile
    private var cache: Faces? = null

    fun regular(context: Context): Typeface = load(context).regular

    fun medium(context: Context): Typeface = load(context).medium

    fun semibold(context: Context): Typeface = load(context).semibold

    private fun load(context: Context): Faces = cache ?: Faces(
        regular = font(context, R.font.poppins_regular, Typeface.DEFAULT),
        medium = font(context, R.font.poppins_medium, Typeface.DEFAULT),
        semibold = font(context, R.font.poppins_semibold, Typeface.DEFAULT_BOLD),
    ).also { cache = it }

    private fun font(context: Context, id: Int, fallback: Typeface): Typeface =
        runCatching { ResourcesCompat.getFont(context, id) }.getOrNull() ?: fallback
}

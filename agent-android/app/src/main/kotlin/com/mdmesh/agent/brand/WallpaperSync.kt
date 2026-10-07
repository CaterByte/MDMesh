package com.mdmesh.agent.brand

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MeinConnect fork: makes the kiosk background image the system wallpaper too — home AND lock screen — so the
 * lock screen shown after the power button carries the same look. Applied once per image (URL + content
 * fingerprint), never on every redraw; any failure keeps the current wallpaper.
 */
object WallpaperSync {

    private const val PREFS = "mc_wallpaper"
    private const val KEY = "applied"
    private const val PROBE_PX = 16

    suspend fun apply(context: Context, url: String, image: Bitmap) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "$url|${fingerprint(image)}"
        if (prefs.getString(KEY, null) == key) return@withContext
        runCatching {
            val wm = WallpaperManager.getInstance(context)
            if (wm.isWallpaperSupported && wm.isSetWallpaperAllowed) {
                wm.setBitmap(image, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
                prefs.edit().putString(KEY, key).apply()
            }
        }
    }

    /** Cheap content fingerprint: a re-uploaded image under the same URL still gets applied. */
    private fun fingerprint(image: Bitmap): Int {
        val probe = Bitmap.createScaledBitmap(image, PROBE_PX, PROBE_PX, true)
        val px = IntArray(PROBE_PX * PROBE_PX)
        probe.getPixels(px, 0, PROBE_PX, 0, 0, PROBE_PX, PROBE_PX)
        if (probe !== image) probe.recycle()
        return listOf(px.contentHashCode(), image.width, image.height).hashCode()
    }
}

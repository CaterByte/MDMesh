package com.mdmesh.kiosk.brand

/**
 * MeinConnect fork: turns brand-bar segments into one smooth gradient (the MeinConnect bar is a Verlauf,
 * not separate colour blocks). Each colour sits at the middle of its segment; the first and last colours
 * are held to the edges. Returns the `colors` / `positions` arrays for `android.graphics.LinearGradient`.
 */
fun brandGradient(stops: List<BarStop>): Pair<IntArray, FloatArray> {
    if (stops.isEmpty()) return IntArray(0) to FloatArray(0)
    val colors = ArrayList<Int>()
    val positions = ArrayList<Float>()
    colors += stops.first().argb
    positions += 0f
    var start = 0f
    for (stop in stops) {
        colors += stop.argb
        positions += (start + stop.end) / 2f
        start = stop.end
    }
    colors += stops.last().argb
    positions += 1f
    return colors.toIntArray() to positions.toFloatArray()
}

package com.mdmesh.agent.brand

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import com.mdmesh.kiosk.brand.BarStop

/**
 * MeinConnect fork: the signature brand bar — solid segments with hard stops (no blending between
 * colours), drawn edge to edge. Mirrors the 4px bar under the portal's top bar.
 */
class BrandBarView(context: Context, private val stops: List<BarStop>) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        var start = 0f
        for (stop in stops) {
            val end = width * stop.end
            paint.color = stop.argb
            canvas.drawRect(start, 0f, end, height.toFloat(), paint)
            start = end
        }
    }
}

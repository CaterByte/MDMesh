package com.mdmesh.agent.brand

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import com.mdmesh.kiosk.brand.BarStop
import com.mdmesh.kiosk.brand.brandGradient

/**
 * MeinConnect fork: the signature brand bar, drawn edge to edge as one smooth gradient through the
 * configured colours (see [brandGradient]). Mirrors the bar under the portal's top bar.
 */
class BrandBarView(context: Context, stops: List<BarStop>) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val colors: IntArray
    private val positions: FloatArray

    init {
        val (c, p) = brandGradient(stops)
        colors = c
        positions = p
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        paint.shader = if (colors.size >= 2) {
            LinearGradient(0f, 0f, w.toFloat(), 0f, colors, positions, Shader.TileMode.CLAMP)
        } else {
            null
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (paint.shader != null) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}

package com.neuroflow.cam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** Dibuja el sector y los recuadros de personas. Coordenadas normalizadas 0..1. */
class ZoneOverlay(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    val zone = mutableListOf<PointF>()
    var drawing = false
        set(value) { field = value; invalidate() }
    var showZone = true
        set(value) { field = value; invalidate() }
    private var boxes: List<RectF> = emptyList()

    private val fill = Paint().apply { color = Color.argb(70, 255, 23, 68); style = Paint.Style.FILL }
    private val line = Paint().apply { color = Color.rgb(255, 23, 68); style = Paint.Style.STROKE; strokeWidth = 5f; isAntiAlias = true }
    private val dot = Paint().apply { color = Color.rgb(255, 23, 68); isAntiAlias = true }
    private val box = Paint().apply { color = Color.rgb(0, 229, 255); style = Paint.Style.STROKE; strokeWidth = 5f }
    private val label = Paint().apply { color = Color.rgb(0, 229, 255); textSize = 36f; isAntiAlias = true }

    fun setBoxes(b: List<RectF>) { boxes = b; postInvalidate() }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!drawing) return false
        if (e.action == MotionEvent.ACTION_UP) {
            zone.add(PointF(e.x / width, e.y / height))
            invalidate()
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (showZone && zone.isNotEmpty()) {
            val p = Path()
            zone.forEachIndexed { i, pt ->
                if (i == 0) p.moveTo(pt.x * w, pt.y * h) else p.lineTo(pt.x * w, pt.y * h)
            }
            if (zone.size >= 3) { p.close(); c.drawPath(p, fill) }
            c.drawPath(p, line)
            zone.forEach { c.drawCircle(it.x * w, it.y * h, 12f, dot) }
        }
        boxes.forEach {
            c.drawRect(it.left * w, it.top * h, it.right * w, it.bottom * h, box)
            c.drawText("Persona", it.left * w + 6, it.top * h - 8, label)
        }
    }

    /** ¿El punto (pies de la persona) está dentro del sector? Sin sector = toda la imagen. */
    fun contains(x: Float, y: Float): Boolean {
        if (zone.size < 3) return true
        var inside = false
        var j = zone.size - 1
        for (i in zone.indices) {
            val a = zone[i]; val b = zone[j]
            if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    fun zoneToString() = zone.joinToString(";") { "${it.x},${it.y}" }

    fun zoneFromString(s: String) {
        zone.clear()
        s.split(";").filter { it.contains(",") }.forEach {
            val (x, y) = it.split(",")
            zone.add(PointF(x.toFloat(), y.toFloat()))
        }
        invalidate()
    }
}

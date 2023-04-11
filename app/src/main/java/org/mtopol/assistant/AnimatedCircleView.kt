package org.mtopol.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import java.lang.IllegalArgumentException

private const val MIN_RADIUS = 50
private const val MAX_GROW = 300

class AnimatedCircleView(context: Context, attrs: AttributeSet) : View(context, attrs) {

    fun alignWithView(view: View) {
        view.getLocationInWindow(location)
        centerX = location[0] + view.width / 2f
        centerY = location[1] + view.height / 2f
        this.getLocationInWindow(location)
        centerX -= location[0]
        centerY -= location[1]
    }

    var volume: Float = 0f
        set(value) {
            if (value < 0.0 || value > 1.0) {
                throw IllegalArgumentException("Radius must be normalized to [0..1]")
            }
            field = MIN_RADIUS + value * MAX_GROW
            invalidate()
        }

    private val paint = Paint().apply {
        color = context.getColorCompat(R.color.send_message)
        alpha = 0x66
        isAntiAlias = true
    }

    private var centerX: Float = 0f
    private var centerY: Float = 0f

    private val rect = RectF()
    private val location = IntArray(2)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        rect.set(centerX - volume, centerY - volume, centerX + volume, centerY + volume)
        canvas.drawOval(rect, paint)
    }
}

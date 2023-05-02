/*
 * Copyright (C) 2023 Marko Topolnik
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.mtopol.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.lang.IllegalArgumentException

private const val MIN_GLOW_RADIUS_DP = 25
private const val MAX_GROW_DP = 150

class RecordingGlowView(context: Context, attrs: AttributeSet) : View(context, attrs) {
    private val pixelDensity = context.resources.displayMetrics.density

    private var centerX: Float = 0f
    private var centerY: Float = 0f
    private var glowRadiusDp: Float = 0f

    // Temporary storage
    private val location = IntArray(2)

    fun alignWithView(view: View) {
        view.getLocationInWindow(location)
        centerX = location[0] + view.width / 2f
        centerY = location[1] + view.height / 2f
        this.getLocationInWindow(location)
        centerX -= location[0]
        centerY -= location[1]
    }
    
    fun setVolume(volume: Float) {
        if (volume < 0.0 || volume > 1.0) {
            throw IllegalArgumentException("Radius must be normalized to [0..1]")
        }
        glowRadiusDp = MIN_GLOW_RADIUS_DP + volume * MAX_GROW_DP
        invalidate()
    }

    private val paint = Paint().apply {
        color = context.getColorCompat(R.color.primary)
        alpha = 0x66
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.strokeWidth = pixelDensity * (glowRadiusDp - MIN_GLOW_RADIUS_DP)
        canvas.drawCircle(centerX, centerY, pixelDensity * (glowRadiusDp + MIN_GLOW_RADIUS_DP) / 2f, paint)
    }
}

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
import com.google.android.material.button.MaterialButton
import java.lang.IllegalArgumentException

private const val MAX_GROW_DP = 150

class RecordingGlowView(context: Context, attrs: AttributeSet) : View(context, attrs) {

    private var centerX: Float = 0f
    private var centerY: Float = 0f
    private var glowRadius: Float = 0f
    private var minGlowRadius: Int = 0

    // Temporary storage
    private val location = IntArray(2)

    fun alignWithButton(button: MaterialButton) {
        button.getLocationInWindow(location)
        centerX = location[0] + button.width / 2f
        centerY = location[1] + button.height / 2f
        this.getLocationInWindow(location)
        centerX -= location[0]
        centerY -= location[1]
        minGlowRadius = button.iconSize * 3 / 5
    }
    
    fun setVolume(volume: Float) {
        if (volume < 0.0 || volume > 1.0) {
            throw IllegalArgumentException("Radius must be normalized to [0..1]")
        }
        glowRadius = minGlowRadius + (volume * MAX_GROW_DP).dp
        invalidate()
    }

    private val paint = Paint().apply {
        color = context.getColorCompat(R.color.recording_glow)
        alpha = 0x66
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.strokeWidth = glowRadius - minGlowRadius
        canvas.drawCircle(centerX, centerY, (glowRadius + minGlowRadius) / 2f, paint)
    }
}

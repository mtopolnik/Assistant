package org.mtopol.assistant

import android.app.Application
import android.content.Context
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat

lateinit var openAi: OpenAI

class AssistantApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        openAi = OpenAI(applicationContext)
    }
}

fun Context.getColorCompat(id: Int) = ContextCompat.getColor(this, id)

fun tintDrawable(drawable: Drawable, color: Int): Drawable {
    val wrappedDrawable = DrawableCompat.wrap(drawable).mutate()
    DrawableCompat.setTint(wrappedDrawable, color)
    DrawableCompat.setTintMode(wrappedDrawable, PorterDuff.Mode.SRC_IN)
    return wrappedDrawable
}

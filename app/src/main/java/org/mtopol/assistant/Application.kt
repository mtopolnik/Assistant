package org.mtopol.assistant

import android.app.Application
import android.content.Context
import androidx.core.content.ContextCompat

lateinit var openAi: OpenAI

class AssistantApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        openAi = OpenAI(applicationContext)
    }
}

fun Context.getColorCompat(id: Int) = ContextCompat.getColor(this, id)

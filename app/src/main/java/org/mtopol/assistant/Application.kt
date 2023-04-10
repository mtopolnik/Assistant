package org.mtopol.assistant

import android.app.Application
import android.content.Context
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

lateinit var openAi: Lazy<OpenAI>

class AssistantApplication : Application() {

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        openAi = lazy { OpenAI(applicationContext) }
        GlobalScope.launch(Dispatchers.IO) { openAi.value }
    }
}

fun Context.getColorCompat(id: Int) = ContextCompat.getColor(this, id)

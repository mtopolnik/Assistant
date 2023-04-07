package org.mtopol.assistant

import android.app.Application

lateinit var openAi: OpenAI

class AssistantApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        openAi = OpenAI(applicationContext)
    }
}

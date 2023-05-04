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

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager

private const val KEY_OPENAI_API_KEY = "openai_api_key"
private const val KEY_SYSTEM_PROMPT = "system_prompt"
private const val KEY_SPEECH_RECOG_LANGUAGE = "speech_recognition_language"
private const val KEY_IS_MUTED = "is_muted"
private const val KEY_IS_GPT4 = "is_gpt4"

lateinit var appContext: Context

class ChatApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
    }
}

val pixelDensity get() = appContext.resources.displayMetrics.density

fun Context.getColorCompat(id: Int) = ContextCompat.getColor(this, id)

val Context.mainPrefs: SharedPreferences get() = PreferenceManager.getDefaultSharedPreferences(this)

inline fun SharedPreferences.applyUpdate(block: SharedPreferences.Editor.() -> Unit) {
    with (edit()) {
        try {
            block()
        } finally {
            apply()
        }
    }
}

val SharedPreferences.openaiApiKey: String get() = getString(KEY_OPENAI_API_KEY, "")!!

fun SharedPreferences.Editor.setOpenaiApiKey(apiKey: String): SharedPreferences.Editor =
    putString(KEY_OPENAI_API_KEY, apiKey)

val SharedPreferences.systemPrompt: String get() = getString(KEY_SYSTEM_PROMPT,
    appContext.getString(R.string.system_prompt_default))!!

fun SharedPreferences.Editor.setSystemPrompt(systemPrompt: String?): SharedPreferences.Editor =
    putString(KEY_SYSTEM_PROMPT, systemPrompt)

val SharedPreferences.speechRecogLanguage: String? get() = getString(KEY_SPEECH_RECOG_LANGUAGE, null)

fun SharedPreferences.Editor.setSpeechRecogLanguage(language: String?): SharedPreferences.Editor =
    putString(KEY_SPEECH_RECOG_LANGUAGE, language)

val SharedPreferences.isMuted: Boolean get() = getBoolean(KEY_IS_MUTED, false)

fun SharedPreferences.Editor.setIsMuted(value: Boolean): SharedPreferences.Editor =
    putBoolean(KEY_IS_MUTED, value)

val SharedPreferences.isGpt4: Boolean get() = getBoolean(KEY_IS_GPT4, false)

fun SharedPreferences.Editor.setIsGpt4(value: Boolean): SharedPreferences.Editor =
    putBoolean(KEY_IS_GPT4, value)

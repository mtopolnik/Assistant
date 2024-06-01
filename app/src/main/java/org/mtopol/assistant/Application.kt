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

import android.annotation.SuppressLint
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.net.toFile
import androidx.core.os.LocaleListCompat
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.*
import kotlin.math.min

const val FILE_PROVIDER_AUTHORITY = "org.mtopol.assistant.fileprovider"

private const val KEY_OPENAI_API_KEY = "openai_api_key"
private const val KEY_SYSTEM_PROMPT = "system_prompt"
private const val KEY_SPEECH_RECOG_LANGUAGE = "speech_recognition_language"
private const val KEY_LANGUAGES = "languages"
private const val KEY_IS_MUTED = "is_muted"
private const val KEY_SELECTED_MODEL = "selected_model"
private const val KEY_SELECTED_VOICE = "selected_voice"

lateinit var appContext: Context
lateinit var imageCache: File

class ChatApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        imageCache = File(applicationContext.externalCacheDir, "images")
        imageCache.mkdir()
    }
}

@SuppressLint("QueryPermissionsNeeded") // Play Store is visible automatically
fun Context.visitOnPlayStore() {
    val rateIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
    packageManager.queryIntentActivities(rateIntent, 0)
        .map { it.activityInfo }
        .find { it.applicationInfo.packageName == "com.android.vending" }
        ?.also {
            rateIntent.component = ComponentName(it.applicationInfo.packageName, it.name)
            rateIntent.addFlags(
                // don't open Play Store in the stack of our activity
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                        // make sure Play Store opens our app page, whatever it was doing before
                        or Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            startActivity(rateIntent)
        }
    // Play Store app not installed, open in web browser
        ?: startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
        )
}

// Locale.getDefault() gives the locale used to localize the app
// LocaleListCompat.getDefault().get(0) gives the default locale configured on system level
fun defaultLocale() = LocaleListCompat.getDefault().get(0)!!

fun systemLanguages(): List<String> {
    val localeList: LocaleListCompat = LocaleListCompat.getDefault()
    return (0 until localeList.size()).map { localeList.get(it)!!.language }
}

fun String.capitalizeFirstLetter() =
    replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

fun String.toDisplayLanguage() =
    Locale.forLanguageTag(this).getDisplayLanguage(defaultLocale()).capitalizeFirstLetter()

val pixelDensity: Float get() = appContext.resources.displayMetrics.density

val Int.dp: Int get() = toFloat().dp.toInt()

val Float.dp: Float get() = pixelDensity * this

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

val SharedPreferences.selectedModel: OpenAiModel get() =
    getString(KEY_SELECTED_MODEL, OpenAiModel.GPT_3.name).let { OpenAiModel.valueOf(it!!)
}

fun SharedPreferences.Editor.setSelectedModel(value: OpenAiModel): SharedPreferences.Editor =
    putString(KEY_SELECTED_MODEL, value.name)

val SharedPreferences.selectedVoice: Voice get() =
    getString(KEY_SELECTED_VOICE, Voice.BUILT_IN.name).let { Voice.valueOf(it!!)
}

fun SharedPreferences.Editor.setSelectedVoice(value: Voice): SharedPreferences.Editor =
    putString(KEY_SELECTED_VOICE, value.name)

fun SharedPreferences.configuredLanguages(): List<String> =
    getStringSet(KEY_LANGUAGES, null)?.let {
        it.map { str ->
            val parts = str.split(" ")
            Pair(parts[0].toInt(), parts[1])
        }
            .sortedBy { (index, _) -> index }
            .map { (_, language) -> language }
    } ?: systemLanguages()
fun SharedPreferences.Editor.setConfiguredLanguages(languages: List<String>?): SharedPreferences.Editor {
    if (languages != null && languages.isEmpty()) {
        throw IllegalArgumentException("Can't configure empty list of locales")
    }
    return putStringSet(KEY_LANGUAGES, languages?.toStringSet())
}

private fun List<String>.toStringSet(): Set<String> = mapIndexed { i, language -> "$i $language" }.toSet()

fun ImageView?.bitmapSize(p: Point) =
    p.also { this?.drawable
        ?.apply { it.set(intrinsicWidth, intrinsicHeight) }
        ?: it.set(0, 0)
    }.takeIf { it.x > 0 && it.y > 0 }

fun ImageView?.bitmapSize(p: PointF) =
    p.also { this?.drawable
        ?.apply { it.set(intrinsicWidth.toFloat(), intrinsicHeight.toFloat()) }
        ?: it.set(0f, 0f)
    }.takeIf { it.x > 0 && it.y > 0 }

operator fun Point.component1() = x
operator fun Point.component2() = y
operator fun PointF.component1() = x
operator fun PointF.component2() = y

operator fun RectF.component1() = left
operator fun RectF.component2() = top
operator fun RectF.component3() = right
operator fun RectF.component4() = bottom

fun Uri.inputStream(): InputStream =
    when (scheme) {
        "file" -> FileInputStream(toFile())
        "content" -> appContext.contentResolver.openInputStream(this)!!
        else -> throw IllegalArgumentException("URI scheme $scheme not supported")
    }

fun scaleAndSave(uri: Uri, widthLimit: Int, heightLimit: Int): File? {

    fun loadAsBitmap(uri: Uri, bitmapOptions: BitmapFactory.Options): Bitmap? {
        return when (val scheme = uri.scheme) {
            "file" -> BitmapFactory.decodeFile(uri.toFile().path, bitmapOptions)
            "content" -> appContext.contentResolver.openInputStream(uri).use {
                Log.i("client", "Decoding bitmap at $uri")
                BitmapFactory.decodeStream(it, null, bitmapOptions)
            }
            else -> {
                Log.e("client", "URI scheme not supported: $scheme")
                null
            }
        }
    }

    fun determineSampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        var sampleSize = 1
        while (width / (sampleSize * 2) >= targetWidth && height / (sampleSize * 2) >= targetHeight) {
            sampleSize *= 2
        }
        return sampleSize
    }

    fun determineTargetDimensions(
        bitmapOptions: BitmapFactory.Options, widthLimit: Int, heightLimit: Int
    ): Pair<Int, Int> {
        val (width: Int, height: Int) = Pair(bitmapOptions.outWidth, bitmapOptions.outHeight)
        val widthRatio = widthLimit.toDouble() / bitmapOptions.outWidth
        val heightRatio = heightLimit.toDouble() / bitmapOptions.outHeight
        val scaleFactor = min(widthRatio, heightRatio).coerceAtMost(1.0)
        return Pair(
            (width * scaleFactor).toInt().coerceAtMost(widthLimit),
            (height * scaleFactor).toInt().coerceAtMost(heightLimit)
        )
    }

    var bitmapOptions = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    loadAsBitmap(uri, bitmapOptions)
    if (bitmapOptions.outWidth == 0 || bitmapOptions.outHeight == 0) {
        return null
    }
    val (width: Int, height: Int) = Pair(bitmapOptions.outWidth, bitmapOptions.outHeight)
    bitmapOptions = BitmapFactory.Options().apply {
        inSampleSize = determineSampleSize(width, height, 512, 512)
    }
    val bitmap = loadAsBitmap(uri, bitmapOptions)!!
    val (targetWidth, targetHeight) = determineTargetDimensions(bitmapOptions, widthLimit, heightLimit)
    val scaledBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    val (compressFormat, fileSuffix) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Pair(Bitmap.CompressFormat.WEBP_LOSSY, ".webp")
        else Pair(Bitmap.CompressFormat.JPEG, ".jpeg")
    return File.createTempFile("shared-", fileSuffix, imageCache).also { imageFile ->
        FileOutputStream(imageFile).use { scaledBitmap.compress(compressFormat, 85, it) }
    }
}

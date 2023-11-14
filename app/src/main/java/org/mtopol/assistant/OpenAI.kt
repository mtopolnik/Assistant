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
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.net.toFile
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.ANDROID
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.forms.FormBuilder
import io.ktor.client.request.forms.append
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.headers
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.HttpStatement
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.reflect.typeInfo
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.core.writeFully
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.security.MessageDigest
import kotlin.math.min


val openAi get() = openAiLazy.value

private const val OPENAI_URL = "https://api.openai.com/v1/"
private const val GPT_3 = "gpt-3.5-turbo"
private const val GPT_4 = "gpt-4-1106-preview"
private const val GPT_4_VISION = "gpt-4-vision-preview"
private const val DEMO_API_KEY = "demo"

private lateinit var openAiLazy: Lazy<OpenAI>

fun resetOpenAi(context: Context): Lazy<OpenAI> {
    if (::openAiLazy.isInitialized) {
        openAiLazy.value.close()
    }
    return lazy { OpenAI(context) }.also { openAiLazy = it }
}

class OpenAI(
    private val context: Context
) {
    private val httpClient = createHttpClient(context.mainPrefs.openaiApiKey)

    private val demoMode = context.mainPrefs.openaiApiKey.trim().lowercase() == DEMO_API_KEY

    private val mockRecognizedSpeech = context.getString(R.string.demo_recognized_speech)
    private val mockResponse = context.getString(R.string.demo_response)

    suspend fun chatCompletions(history: List<Exchange>, useGpt4: Boolean): Flow<String> {
        if (demoMode) {
            return mockResponse.toCharArray().asList().chunked(4).asFlow().map { delay(120); it.joinToString("") }
        }
        val gptModel = if (useGpt4) {
            if (history.any { it.promptImageUris.isNotEmpty() }) {
                GPT_4_VISION
            } else
                GPT_4
        } else {
            GPT_3
        }
        Log.i("client", "Model: $gptModel")
        val chatCompletionRequest = ChatCompletionRequest(
            model = gptModel,
            messages = systemPrompt() + history.toDto().dropLast(1),
            max_tokens = 4096
        )
        return chatCompletions(chatCompletionRequest)
    }

    fun translation(targetLanguage: String, text: String, useGpt4: Boolean): Flow<String> {
        if (demoMode) {
            return flowOf("Demo mode is on. You asked to translate this:\n$text")
        }
        val systemPrompt = "You are a translator." +
                " I will write in a language of my choice, and you will translate it to $targetLanguage."
        val chatCompletionRequest = ChatCompletionRequest(
            model = if (useGpt4) GPT_4 else GPT_3,
            messages = listOf(
                ChatMessage("system", systemPrompt),
                ChatMessage("user", text)
            )
        )
        return chatCompletions(chatCompletionRequest)
    }

    private fun chatCompletions(request: ChatCompletionRequest): Flow<String> {
        return flow<ChatCompletionChunk> {
            val builder = HttpRequestBuilder().apply {
                method = HttpMethod.Post
                url(path = "chat/completions")
                setBody(streamRequestOf(request))
                contentType(ContentType.Application.Json)
                accept(ContentType.Text.EventStream)
                headers {
                    append(HttpHeaders.CacheControl, "no-cache")
                    append(HttpHeaders.Connection, "keep-alive")
                }
            }
            HttpStatement(builder, httpClient).execute { streamEventsFrom(it) }
        }
            .map { chunk -> chunk.choices[0].delta.content }
            .filterNotNull()
    }

    suspend fun transcription(language: String?, prompt: String, audioPathname: String): String {
        Log.i("client", "Transcription language: $language, prompt context:\n$prompt")
        if (demoMode) {
            delay(2000)
            return mockRecognizedSpeech
        }
        val response = httpClient.submitFormWithBinaryData(
            url = "audio/transcriptions",
            formData = formData {
                append("model", "whisper-1")
                if (language != null) {
                    append("language", language)
                } else {
                    append("prompt", prompt)
                }
                append("response_format", "text")
                appendFile("file", audioPathname)
            })
        return response.body<String>(typeInfo<String>()).replace("\n", "")
    }

    fun close() {
        httpClient.close()
    }

    private fun systemPrompt(): List<ChatMessage> {
        return context.mainPrefs.systemPrompt
            .takeIf { it.isNotBlank() }
            ?.let { listOf(ChatMessage("system", it)) }
            ?: emptyList()
    }

    private suspend fun List<Exchange>.toDto() = flatMap { pAndR ->
        listOf(
            ChatMessage(
                "user",
                listOf(ContentPart.Text(pAndR.promptText.toString())) +
                        pAndR.promptImageUris.map { imgUri -> ContentPart.Image(readContentToDataUri(imgUri)) }
            ),
            ChatMessage("assistant", pAndR.response.toString()),
        )
    }

    private suspend fun readContentToDataUri(uri: Uri): String = withContext(Dispatchers.Default) {
        var bitmapOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        loadAsBitmap(uri, bitmapOptions)
        if (bitmapOptions.outWidth == 0 || bitmapOptions.outHeight == 0) {
            return@withContext "data:image/gif;base64,R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw=="
        }
        val (width: Int, height: Int) = Pair(bitmapOptions.outWidth, bitmapOptions.outHeight)
        bitmapOptions = BitmapFactory.Options().apply {
            inSampleSize = determineSampleSize(width, height, 512, 512)
        }
        val bitmap = loadAsBitmap(uri, bitmapOptions)!!
        val (targetWidth, targetHeight) = determineTargetDimensions(bitmapOptions, 512, 512)
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        val (compressFormat, urlFormat) =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Pair(CompressFormat.WEBP_LOSSY, "webp")
            else Pair(CompressFormat.JPEG, "jpeg")
        val bytes = ByteArrayOutputStream().also { scaledBitmap.compress(compressFormat, 85, it) }.toByteArray()
        "data:image/$urlFormat;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun loadAsBitmap(uri: Uri, bitmapOptions: BitmapFactory.Options): Bitmap? {
        return when (val scheme = uri.scheme) {
            "file" -> BitmapFactory.decodeFile(uri.toFile().path, bitmapOptions)
            "content" -> context.contentResolver.openInputStream(uri).use {
                Log.i("client", "Decoding bitmap at $uri")
                BitmapFactory.decodeStream(it, null, bitmapOptions)
            }
            else -> {
                Log.e("client", "URI scheme not supported: $scheme")
                null
            }
        }
    }

    private fun determineSampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        var sampleSize = 1
        while (width / (sampleSize * 2) >= targetWidth && height / (sampleSize * 2) >= targetHeight) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun determineTargetDimensions(
        bitmapOptions: BitmapFactory.Options, targetWidth: Int, targetHeight: Int
    ): Pair<Int, Int> {
        val (width: Int, height: Int) = Pair(bitmapOptions.outWidth, bitmapOptions.outHeight)
        val widthRatio = targetWidth.toDouble() / bitmapOptions.outWidth
        val heightRatio = targetHeight.toDouble() / bitmapOptions.outHeight
        val scaleFactor = min(widthRatio, heightRatio).coerceAtMost(1.0)
        return Pair(
            (width * scaleFactor).toInt().coerceAtMost(targetWidth),
            (height * scaleFactor).toInt().coerceAtMost(targetHeight)
        )
    }
}

private fun createHttpClient(apiKey: String): HttpClient {
    return HttpClient {
        install(Auth) {
            bearer {
                loadTokens {
                    BearerTokens(accessToken = apiKey, refreshToken = "")
                }
            }
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 4000
            socketTimeoutMillis = 4000
            requestTimeoutMillis = 180_000
        }
        install(HttpRequestRetry) {
            retryIf { _, response -> response.status.value == 429 } // == Too Many Requests
            maxRetries = 2
            exponentialDelay(2.0, 2000)
        }
        install(ContentNegotiation) {
            json(jsonLenient)
        }
        install(Logging) {
            level = LogLevel.ALL
            logger = Logger.ANDROID
        }
        defaultRequest {
            url(OPENAI_URL)
        }
        expectSuccess = true
    }
}

private val GPT3_ONLY_KEY_HASHES = hashSetOf(
    "fg15RZXuK/smtuoB/R0sV3KF1aJmU3HHZlwxx9MLp8U=",
    "DIkQ9HIwN3Ky+t53aMHyojOYAsXBFBnZQvnhbU2oyPs=",
)

fun String.isGpt3OnlyKey() = GPT3_ONLY_KEY_HASHES.contains(apiKeyHash(this))

private fun apiKeyHash(apiKey: String): String =
    apiKey.toByteArray(Charsets.UTF_8).let {
        MessageDigest.getInstance("SHA-256").digest(it)
    }.let {
        Base64.encodeToString(it, Base64.NO_WRAP)
    }.also {
        Log.i("hash", "API key hash: $it")
    }

private const val DATA_LINE_PREFIX = "data:"
private const val DONE_TOKEN = "$DATA_LINE_PREFIX [DONE]"

private inline fun <reified T> streamRequestOf(serializable: T): JsonElement =
    jsonLenient.encodeToJsonElement(serializable).jsonObject.toMutableMap()
        .also { it += "stream" to JsonPrimitive(true) }
        .let { JsonObject(it) }

private suspend inline fun <reified T> FlowCollector<T>.streamEventsFrom(response: HttpResponse) {
    val channel: ByteReadChannel = response.body()
    while (!channel.isClosedForRead) {
        val line = channel.readUTF8Line() ?: continue
        val value: T = when {
            line.startsWith(DONE_TOKEN) -> break
            line.startsWith(DATA_LINE_PREFIX) -> jsonLenient.decodeFromString(line.removePrefix(DATA_LINE_PREFIX))
            else -> continue
        }
        emit(value)
    }
}

private val jsonLenient = Json {
    isLenient = true
    ignoreUnknownKeys = true
}

private fun FormBuilder.appendFile(key: String, pathname: String) {
    append(key, pathname, ContentType.Application.OctetStream) {
        FileInputStream(pathname).use { inputStream ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = inputStream.read(buffer)
                if (count == -1) {
                    break
                }
                writeFully(buffer, 0, count)
            }
        }
    }
}

@Serializable
class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val max_tokens: Int? = null
)

@Serializable
class ChatMessage(
    val role: String,
    val content: List<ContentPart> = listOf()
)

fun ChatMessage(role: String, content: String): ChatMessage = ChatMessage(role, listOf(ContentPart.Text(content)))

@Serializable
sealed class ContentPart {
    @Serializable
    @SerialName("text")
    class Text(val text: String) : ContentPart()

    @Serializable
    @SerialName("image_url")
    class Image(val image_url: ImageUrl) : ContentPart()

    companion object {
        fun Image(imgUrl: String) = Image(ImageUrl(imgUrl))
    }
}

@Serializable
class ImageUrl(
    val url: String
)

@Serializable
class ChatCompletionChunk(
    val choices: List<ChatChunk>,
)

@Serializable
class ChatChunk(
    val delta: ChatDelta,
)

@Serializable
class ChatDelta(
    val content: String? = null
)

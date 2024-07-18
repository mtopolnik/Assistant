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

import android.net.Uri
import android.text.Editable
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.net.toFile
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.ANDROID
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.FormBuilder
import io.ktor.client.request.forms.append
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.HttpStatement
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

val openAi get() = openAiLazy.value

private const val OPENAI_URL = "https://api.openai.com/v1/"

const val MODEL_ID_GPT_4_MINI = "gpt-4o-mini"
const val MODEL_ID_GPT_4 = "gpt-4o"

private lateinit var openAiLazy: Lazy<OpenAI>

fun resetOpenAi(): Lazy<OpenAI> {
    if (::openAiLazy.isInitialized) {
        openAiLazy.value.close()
    }
    return lazy { OpenAI() }.also { openAiLazy = it }
}

class OpenAI {
    private val apiClient = createOpenAiClient(appContext.mainPrefs.openaiApiKey)
    private val blobClient = createBlobClient()

    private val demoMode = appContext.mainPrefs.openaiApiKey.isDemoKey()

    private val mockRecognizedSpeech = appContext.getString(R.string.demo_recognized_speech)
    private val mockResponse = appContext.getString(R.string.demo_response)

    suspend fun chatCompletions(history: List<Exchange>, model: AiModel): Flow<String> {
        if (demoMode) {
            return mockResponse.toCharArray().asList().chunked(4).asFlow().map { delay(120); it.joinToString("") }
        }
        Log.i("client", "Model: ${model.apiId}")
        return chatCompletions(
            ChatCompletionRequest(
                model = model.apiId,
                messages = systemPrompt() + history.toDto().dropLast(1),
            )
        )
    }

    fun translation(targetLanguage: String, text: String, model: AiModel): Flow<String> {
        if (demoMode) {
            return flowOf("Demo mode is on. You asked to translate this:\n$text")
        }
        val systemPrompt = "You are a translator." +
                " The user will write in a language of their choice, and you will translate it to $targetLanguage."
        val chatCompletionRequest = ChatCompletionRequest(
            model = model.apiId,
            messages = listOf(
                ChatMessage("system", systemPrompt),
                ChatMessage("user", text)
            )
        )
        return chatCompletions(chatCompletionRequest)
    }

    suspend fun summarizing(chat: List<Exchange>): String {
        val systemPrompt = "Your task is summarizing a conversation between a User and an Assistant." +
                " Your prompt will be the entire conversation, and you will respond with a short" +
                " summary, suitable as the title of the conversation. It must be up to five words long." +
                " Do not add any other text except the summary itself!"

        val text = chat.map { exchange ->
            "User:\n\n${exchange.promptText()}\n\nAssistant:\n\n${exchange.replyMarkdown}"
        }.joinToString("\n\n")

        val chatCompletionRequest = ChatCompletionRequest(
            model = AiModel.GPT_4_MINI.apiId,
            messages = listOf(
                ChatMessage("system", systemPrompt),
                ChatMessage("user", text)
            ),
            max_tokens = 40,
            stream = false
        )
        return try {
            HttpStatement(chatCompletionsHttpRequestBuilder(chatCompletionRequest), apiClient)
                .execute { response ->
                    response.body<ChatCompletionResponse>().choices[0].message.content
                        .also {
                            Log.i("chats", "full chat: $text")
                            Log.i("chats", "chat summary: $it")
                        }
                }
        } catch (e: Exception) {
            ""
        }
    }

    private fun chatCompletions(request: ChatCompletionRequest): Flow<String> {
        return flow<ChatCompletionChunk> {
            HttpStatement(chatCompletionsHttpRequestBuilder(request), apiClient).execute { emitStreamingResponse(it) }
        }
            .map { chunk -> chunk.choices[0].delta.content }
            .filterNotNull()
    }

    private fun chatCompletionsHttpRequestBuilder(request: ChatCompletionRequest) =
        HttpRequestBuilder().apply {
            method = HttpMethod.Post
            url(path = "chat/completions")
            contentType(ContentType.Application.Json)
            setBody(jsonCodec.encodeToJsonElement(request))
        }


    suspend fun transcription(language: String?, prompt: String, audioPathname: String): String {
        Log.i("client", "Transcription language: $language, prompt context:\n$prompt")
        if (demoMode) {
            delay(2000)
            return mockRecognizedSpeech
        }
        if (appContext.mainPrefs.openaiApiKey.isBlank()) {
            Toast.makeText(appContext, "Your OpenAI key is missing", Toast.LENGTH_LONG).show()
            return ""
        }
        val response = apiClient.submitFormWithBinaryData(
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
        return response.body<String>().replace("\n", "")
    }

    @OptIn(UnstableApi::class)
    suspend fun speak(
        text: CharSequence,
        voice: String,
        exoPlayer: ExoPlayer
    ) {
        Log.i("speech", "Speak: $text")
        if (!appContext.mainPrefs.openaiApiKey.allowsTts()) {
            Toast.makeText(appContext, "Your API key doesn't allow text-to-speech", Toast.LENGTH_LONG).show()
            return
        }
        val request = TextToSpeechRequest(
            input = text.toString(), voice = voice,
            response_format = "opus", model = "tts-1"
        )
        val builder = HttpRequestBuilder().apply {
            method = HttpMethod.Post
            url(path = "audio/speech")
            contentType(ContentType.Application.Json)
            setBody(jsonCodec.encodeToJsonElement(request))
        }

        HttpStatement(builder, apiClient).execute() { httpResponse ->
            val channel = httpResponse.body<ByteReadChannel>()

            class DsFac : DataSource.Factory {
                override fun createDataSource() = object : DataSource {
                    override fun open(dataSpec: DataSpec): Long = C.LENGTH_UNSET.toLong()

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = runBlocking {
                        channel.readAvailable(buffer, offset, length)
                    }

                    override fun getUri() = Uri.EMPTY
                    override fun close() {}

                    override fun addTransferListener(transferListener: TransferListener) {
                        Log.i("speech", "addTransferListener() called, will have no effect: $transferListener")
                    }
                }
            }

            exoPlayer.addMediaSource(
                ProgressiveMediaSource.Factory(DsFac())
                    .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0))
                    .createMediaSource(MediaItem.fromUri(Uri.EMPTY))
            )
            while (!channel.isClosedForRead) {
                delay(100)
            }
        }
    }

    private var _a = 'a'
    private var _e = 'e'
    private var _g = 'g'
    private var _r = 'r'
    private var _s = 's'
    private var _t = 't'

    suspend fun imageGeneration(prompt: CharSequence, model: AiModel, console: Editable): List<Uri> {
        val artist = ARTIST_LAZY.value
        if (!appContext.mainPrefs.openaiApiKey.allowsArtist()) {
            Toast.makeText(appContext, "Your API key doesn't allow $artist", Toast.LENGTH_LONG).show()
            return listOf()
        }
        val request = ImageGenerationRequest(prompt.toString(), model.apiId, "natural", "url")
        val builder = HttpRequestBuilder().apply {
            method = HttpMethod.Post
            url(path = "im" + _a + _g + _e + _s + '/' + "gene" + _r + _a + _t + "ions")
            contentType(ContentType.Application.Json)
            setBody(jsonCodec.encodeToJsonElement(request))
        }
        console.append("$artist is handling your prompt...\n")
        return try {
            val imageObjects = HttpStatement(builder, apiClient).execute().body<ImageGenerationResponse>().data
            console.clear()
            imageObjects.firstOrNull()?.revised_prompt?.takeIf { it.isNotBlank() }?.also { revisedPrompt ->
                console.append("$artist revised your prompt to:\n\n$revisedPrompt\n")
            }
            downloadToCache(imageObjects.map { it.url })
        } catch (e: ResponseException) {
            console.append("Error: ${e.message}")
            listOf()
        }
    }

    fun close() {
        apiClient.close()
        blobClient.close()
    }

    private fun systemPrompt(): List<ChatMessage> {
        return appContext.mainPrefs.systemPrompt
            .takeIf { it.isNotBlank() }
            ?.let { listOf(ChatMessage("system", it)) }
            ?: emptyList()
    }

    private suspend fun List<Exchange>.toDto() = flatMap { exchange ->
        listOf(
            ChatMessage("user", exchange.promptParts.map { it.toContentPart() }),
            ChatMessage("assistant", exchange.replyMarkdown.toString()),
        )
    }

    private suspend fun PromptPart.toContentPart() = withContext(Dispatchers.IO) {

        fun read(mediaType: String, uri: Uri): String {
            val bytes = uri.inputStream().use { input ->
                ByteArrayOutputStream().also { input.copyTo(it) }.toByteArray()
            }
            return "data:$mediaType/${uri.toFile().extension};base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }

        when (this@toContentPart) {
            is PromptPart.Text -> ContentPart.Text(text.toString())
            is PromptPart.Image -> ContentPart.Image(
                try { read("image", uri) }
                catch (e: Exception) { "data:image/gif;base64,R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw==" }
            )
            is PromptPart.Audio -> ContentPart.Audio(
                try { read("audio", uri) }
                catch (e: Exception) { "data:audio/pcm;base64,bXAzIGJ5dGVzIGhlcmUK" }
            )
        }
    }

    private suspend fun downloadToCache(imageUrls: List<String>): List<Uri> = withContext(Dispatchers.IO) {
        imageUrls.map { imageUrl ->
            val imageFile = File.createTempFile("dalle-", ".jpg", imageCache)
            FileOutputStream(imageFile).use { fos ->
                blobClient.get(imageUrl).body<InputStream>().copyTo(fos)
            }
            Uri.fromFile(imageFile)
        }
    }

    @Serializable
    class ChatCompletionRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val max_tokens: Int? = null,
        val stream: Boolean
    )

    private fun ChatCompletionRequest(model: String, messages: List<ChatMessage>) =
        ChatCompletionRequest(
            model,
            messages,
            max_tokens = 4096,
            stream = true)

    @Serializable
    class ChatMessage(
        val role: String,
        val content: List<ContentPart> = listOf()
    )

    private fun ChatMessage(role: String, content: String): ChatMessage = ChatMessage(role, listOf(ContentPart.Text(content)))

    @Serializable
    sealed class ContentPart {
        @Serializable
        @SerialName("text")
        class Text(val text: String) : ContentPart()

        @Serializable
        @SerialName("image_url")
        class Image(val image_url: DataUrl) : ContentPart()

        @Serializable
        @SerialName("audio_url")
        class Audio(val audio_url: DataUrl) : ContentPart()

        companion object {
            fun Image(imgUrl: String) = Image(DataUrl(imgUrl))
            fun Audio(audioUrl: String) = Audio(DataUrl(audioUrl))
        }
    }

    @Serializable
    class DataUrl(
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

    @Serializable
    class ChatCompletionResponse(
        val choices: List<CompletionChoice>
    )

    @Serializable
    class CompletionChoice(
        val message: CompletionMessage
    )

    @Serializable
    class CompletionMessage(
        val content: String
    )

    @Serializable
    class ImageGenerationRequest(
        val prompt: String,
        val model: String,
        val style: String,
        val response_format: String
    )

    @Serializable
    class ImageObject(
        val url: String,
        val revised_prompt: String = ""
    )

    @Serializable
    class ImageGenerationResponse(
        val data: List<ImageObject>
    )

    @Serializable
    class TextToSpeechRequest(
        val input: String,
        val response_format: String,
        val voice: String,
        val model: String,
    )
}

private fun createOpenAiClient(apiKey: OpenAiKey) = HttpClient(OkHttp) {
    defaultRequest {
        url(OPENAI_URL)
    }
    install(Auth) {
        bearer {
            loadTokens {
                BearerTokens(accessToken = apiKey.text, refreshToken = "")
            }
        }
    }
    commonApiClientSetup()
}

private fun createBlobClient(): HttpClient = HttpClient(OkHttp) {
    install(ContentEncoding)
    install(Logging) {
        level = LogLevel.HEADERS
        logger = Logger.ANDROID
    }
    expectSuccess = true
}

private const val DATA_LINE_PREFIX = "data:"
private const val DONE_TOKEN = "$DATA_LINE_PREFIX [DONE]"

private suspend inline fun <reified T> FlowCollector<T>.emitStreamingResponse(response: HttpResponse) {
    val channel: ByteReadChannel = response.body()
    while (!channel.isClosedForRead) {
        val line = channel.readUTF8Line() ?: break
        Log.i("http", line)
        if (line.startsWith(DONE_TOKEN)) {
             break
        }
        if (line.startsWith(DATA_LINE_PREFIX)) {
            emit(jsonCodec.decodeFromString(line.removePrefix(DATA_LINE_PREFIX)))
            yield()
        }
    }
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

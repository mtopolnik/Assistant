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

import android.media.AudioTrack
import android.net.Uri
import android.text.Editable
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.core.net.toFile
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
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
import io.ktor.client.request.headers
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.HttpStatement
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
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
import kotlinx.coroutines.yield
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min


val openAi get() = openAiLazy.value

private val ARTIST_LAZY = lazy {
    Log.e("lifecycle", "lazy.value", Exception("diagnostic exception"))
    val deltas = listOf(0, 29, 11, 0, -63, 24)
    val b = StringBuilder()
    var prev = 'D'
    for (delta in deltas) {
        prev += delta
        b.append(prev)
    }
    b.toString()
}

private const val OPENAI_URL = "https://api.openai.com/v1/"
private const val ANTHROPIC_URL = "https://api.anthropic.com/v1/"
private const val MODEL_ID_GPT_3 = "gpt-3.5-turbo"
private const val MODEL_ID_GPT_4 = "gpt-4o"
private const val MODEL_ID_SONNET_3_5 = "claude-3-5-sonnet-20240620"
private const val ANTHROPIC_VERSION = "2023-06-01"
private const val DEMO_API_KEY = "demo"

private lateinit var openAiLazy: Lazy<OpenAI>

enum class OpenAiModel(
    private val apiIdLazy: Lazy<String>,
    private val uiIdLazy: Lazy<String>
) {
    GPT_3(lazy { MODEL_ID_GPT_3 }, lazy { "GPT-3.5" }),
    GPT_4(lazy { MODEL_ID_GPT_4 }, lazy { "GPT-4o" }),
    CLAUDE_3_5_SONNET(lazy { MODEL_ID_SONNET_3_5 }, lazy { "Sonnet" }),
    ARTIST_3(lazy { "${ARTIST_LAZY.value.lowercase()}-3" }, ARTIST_LAZY);

    val apiId get() = apiIdLazy.value
    val uiId get() = uiIdLazy.value
}

val chatModels = listOf(OpenAiModel.GPT_3, OpenAiModel.GPT_4, OpenAiModel.CLAUDE_3_5_SONNET)

fun OpenAiModel.isGptModel() = this in chatModels
fun OpenAiModel.isImageModel() = !isGptModel()

fun resetOpenAi(): Lazy<OpenAI> {
    if (::openAiLazy.isInitialized) {
        openAiLazy.value.close()
    }
    return lazy { OpenAI() }.also { openAiLazy = it }
}

class OpenAI {
    private val apiClient = createOpenAiClient(appContext.mainPrefs.openaiApiKey)
    private val anthropicClient = createAnthropicClient(appContext.mainPrefs.anthropicApiKey)
    private val blobClient = createBlobClient()

    private val demoMode = appContext.mainPrefs.openaiApiKey.trim().lowercase() == DEMO_API_KEY

    private val mockRecognizedSpeech = appContext.getString(R.string.demo_recognized_speech)
    private val mockResponse = appContext.getString(R.string.demo_response)

    suspend fun chatCompletions(history: List<Exchange>, model: OpenAiModel): Flow<String> {
        if (demoMode) {
            return mockResponse.toCharArray().asList().chunked(4).asFlow().map { delay(120); it.joinToString("") }
        }
        Log.i("client", "Model: ${model.apiId}")
        return if (model == OpenAiModel.CLAUDE_3_5_SONNET) claudeCompletions(
            ClaudeCompletionRequest(
                system = appContext.mainPrefs.systemPrompt,
                messages = history.toClaudeDto().dropLast(1),
            )
        ) else chatCompletions(
            ChatCompletionRequest(
                model = model.apiId,
                messages = systemPrompt() + history.toDto().dropLast(1),
            )
        )
    }

    fun translation(targetLanguage: String, text: String, model: OpenAiModel): Flow<String> {
        if (demoMode) {
            return flowOf("Demo mode is on. You asked to translate this:\n$text")
        }
        val systemPrompt = "You are a translator." +
                " I will write in a language of my choice, and you will translate it to $targetLanguage."
        val chatCompletionRequest = ChatCompletionRequest(
            model = model.apiId,
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
                contentType(ContentType.Application.Json)
                setBody(jsonCodec.encodeToJsonElement(request))
            }
            HttpStatement(builder, apiClient).execute { emitStreamingResponse(it) }
        }
            .map { chunk -> chunk.choices[0].delta.content }
            .filterNotNull()
    }

    private fun claudeCompletions(request: ClaudeCompletionRequest): Flow<String> {
        return flow<ClaudeResponseChunk> {
            val builder = HttpRequestBuilder().apply {
                method = HttpMethod.Post
                url(path = "messages")
                contentType(ContentType.Application.Json)
                setBody(jsonCodec.encodeToJsonElement(request))
            }
            HttpStatement(builder, anthropicClient).execute { emitClaudeStream(it) }
        }
            .map { chunk -> chunk.delta.text }
            .filterNotNull()
    }

    suspend fun transcription(language: String?, prompt: String, audioPathname: String): String {
        Log.i("client", "Transcription language: $language, prompt context:\n$prompt")
        if (demoMode) {
            delay(2000)
            return mockRecognizedSpeech
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

    suspend fun speak(text: CharSequence, audioTrack: AudioTrack, voice: String) {
        Log.i("speech", "Speak: $text")
        if (appContext.mainPrefs.openaiApiKey.isGptOnlyKey()) {
            Toast.makeText(appContext, "Your API key doesn't allow text-to-speech", Toast.LENGTH_LONG).show()
            return
        }
        val frameLen = Short.SIZE_BYTES // PCM-16 MONO has 1 short per frame
        val request = TextToSpeechRequest(
            input = text.toString(), voice = voice,
            response_format = "pcm", model = "tts-1"
        )
        val builder = HttpRequestBuilder().apply {
            method = HttpMethod.Post
            url(path = "audio/speech")
            contentType(ContentType.Application.Json)
            setBody(jsonCodec.encodeToJsonElement(request))
        }
        HttpStatement(builder, apiClient).execute() { httpResponse ->
            val channel = httpResponse.body<ByteReadChannel>()
            val audioBuf = ByteBuffer.allocate(16.shl(10))

            suspend fun read(): Boolean {
                var minRemaining = 0
                while (true) {
                    Log.i("speech", "channel.read() minRemaining $minRemaining")
                    channel.read(minRemaining) { responseBuf ->
                        Log.i("speech", "before copy: responseBuf ${responseBuf.remaining()} audioBuf ${audioBuf.remaining()}")
                        val limitBackup = responseBuf.limit()
                        val safeToCopy = min(
                            responseBuf.remaining() - responseBuf.remaining() % frameLen,
                            audioBuf.remaining()
                        )
                        responseBuf.limit(responseBuf.position() + safeToCopy)
                        audioBuf.put(responseBuf)
                        responseBuf.limit(limitBackup)
                        Log.i("speech", "after copy: responseBuf ${responseBuf.remaining()} audioBuf ${audioBuf.remaining()}")
                        minRemaining = responseBuf.remaining() + responseBuf.remaining() % 2
                    }
                    if (audioBuf.position() > 0) {
                        audioBuf.flip()
                        return true
                    }
                    if (channel.isClosedForRead) {
                        Log.i("speech", "receive channel closed for read")
                        audioBuf.flip()
                        return false
                    }
                }
            }

            // prime the audioTrack's buffer before calling play()
            while (read()) {
                Log.i("speech", "prime audioTrack, audioBuf ${audioBuf.remaining()}")
                val written = audioTrack.write(audioBuf, audioBuf.remaining(), AudioTrack.WRITE_NON_BLOCKING)
                Log.i("speech", "audioTrack.write() $written, audioBuf ${audioBuf.remaining()}")
                audioBuf.compact()
                if (audioBuf.position() > 0) {
                    Log.i("speech", "audioTrack primed")
                    break
                }
            }
            if (!appContext.mainPrefs.isMuted) {
                Log.i("speech", "audioTrack.play()")
                audioTrack.play()
            }
            withContext(Dispatchers.IO) {
                while (read()) {
                    Log.i("speech", "blocking write, audioBuf ${audioBuf.remaining()}")
                    while (true) {
                        // audioTrack.write() won't fully drain the buffer if the track gets paused
                        val result = audioTrack.write(audioBuf, audioBuf.remaining(), AudioTrack.WRITE_BLOCKING)
                        Log.i("speech", "audioTrack.write() $result, audioBuf ${audioBuf.remaining()}")
                        if (result < 0) {
                            Log.e("speech", "audioTrack.write() returned $result, audioBuf ${audioBuf.remaining()}")
                            break
                        }
                        if (audioBuf.remaining() == 0) {
                            audioBuf.clear()
                            break
                        }
                        delay(100)
                    }
                }
            }
        }
    }

    private var _a = 'a'
    private var _e = 'e'
    private var _g = 'g'
    private var _r = 'r'
    private var _s = 's'
    private var _t = 't'

    suspend fun imageGeneration(prompt: CharSequence, model: OpenAiModel, console: Editable): List<Uri> {
        val artist = ARTIST_LAZY.value
        if (!appContext.mainPrefs.openaiApiKey.allowsImageGeneration()) {
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
            console.append("$artist is done, fetching the result...\n")
            downloadToCache(imageObjects.map { it.url }).also {
                console.clear()
                imageObjects.firstOrNull()?.revised_prompt?.takeIf { it.isNotBlank() }?.also { revisedPrompt ->
                    console.append("$artist revised your prompt to:\n\n$revisedPrompt\n")
                }
            }
        } catch (e: ResponseException) {
            console.append("Error: ${e.message}")
            listOf()
        }
    }

    fun close() {
        apiClient.close()
        anthropicClient.close()
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
            ChatMessage(
                "user",
                listOf(ContentPart.Text(exchange.promptText.toString())) +
                        exchange.promptImageUris.map { imgUri -> ContentPart.Image(readContentToDataUri(imgUri)) }
            ),
            ChatMessage("assistant", exchange.replyMarkdown.toString()),
        )
    }

    private fun List<Exchange>.toClaudeDto() = flatMap { exchange ->
        listOf(
            ClaudeMessage("user", exchange.promptText.toString()),
            ClaudeMessage("assistant", exchange.replyMarkdown.toString()),
        )
    }

    private suspend fun readContentToDataUri(uri: Uri): String = withContext(Dispatchers.IO) {
        try {
            val bytes = uri.inputStream().use { input ->
                ByteArrayOutputStream().also { input.copyTo(it) }.toByteArray()
            }
            "data:image/${uri.toFile().extension};base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            "data:image/gif;base64,R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw=="
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
}

private fun createOpenAiClient(apiKey: String) = HttpClient(OkHttp) {
    defaultRequest {
        url(OPENAI_URL)
    }
    install(Auth) {
        bearer {
            loadTokens {
                BearerTokens(accessToken = apiKey, refreshToken = "")
            }
        }
    }
    commonApiClientSetup()
}

private fun createAnthropicClient(apiKey: String) = HttpClient(OkHttp) {
    defaultRequest {
        url(ANTHROPIC_URL)
        headers {
            append("x-api-key", apiKey)
            append("anthropic-version", ANTHROPIC_VERSION)
        }
    }
    commonApiClientSetup()
}

private fun HttpClientConfig<OkHttpConfig>.commonApiClientSetup() {
    install(HttpTimeout) {
        connectTimeoutMillis = 4000
        socketTimeoutMillis = 30_000
        requestTimeoutMillis = 180_000
    }
    install(HttpRequestRetry) {
        retryIf { _, response -> response.status.value == 429 } // == Too Many Requests
        maxRetries = 2
        exponentialDelay(2.0, 2000)
    }
    install(ContentNegotiation) {
        json(jsonCodec)
    }
    install(ContentEncoding)
    install(Logging) {
        level = LogLevel.ALL
        logger = Logger.ANDROID
    }
    expectSuccess = true
}

private fun createBlobClient(): HttpClient = HttpClient(OkHttp) {
    install(ContentEncoding)
    install(Logging) {
        level = LogLevel.HEADERS
        logger = Logger.ANDROID
    }
    expectSuccess = true
}

private val GPT3_ONLY_KEY_HASHES = hashSetOf(
    "DIkQ9HIwN3Ky+t53aMHyojOYAsXBFBnZQvnhbU2oyPs=",
)

private val GPT_ONLY_KEY_HASHES = hashSetOf(
    "Ej1/kPkeX2/5AVBalQHV+Fg/5QSo9UjK+XgDWFhOQ10="
)

private val CHAT_ONLY_KEY_HASHES = hashSetOf(
    "fg15RZXuK/smtuoB/R0sV3KF1aJmU3HHZlwxx9MLp8U=",
    "gK2ssryPn0jfcgUXiYLE03eKLx2RM6h9n9eJUoyDpV0=",
)

private val IMAGE_KEY_HASHES = hashSetOf(
    "WlYejPDJf0ba5LefiDKy2gqb4PeXKIO36iejO7y5NuE=",
)

fun String.allowsOnlyGpt3() = isGpt3OnlyKey()
fun String.allowsOnlyGpt() = isGpt3OnlyKey() || isGptOnlyKey()
fun String.allowsImageGeneration() = !allowsOnlyGpt() && isImageGenKey() // && !isChatOnlyKey()

fun String.isGpt3OnlyKey() = setContainsHashMemoized(this, GPT3_ONLY_KEY_HASHES, keyToIsGpt3Only)
private val keyToIsGpt3Only = ConcurrentHashMap<String, Boolean>()

fun String.isGptOnlyKey() = setContainsHashMemoized(this, GPT_ONLY_KEY_HASHES, keyToIsGptOnly)
private val keyToIsGptOnly = ConcurrentHashMap<String, Boolean>()

fun String.isChatOnlyKey() = setContainsHashMemoized(this, CHAT_ONLY_KEY_HASHES, keyToIsChatOnly)
private val keyToIsChatOnly = ConcurrentHashMap<String, Boolean>()

fun String.isImageGenKey() = setContainsHashMemoized(this, IMAGE_KEY_HASHES, keyToIsImageGen)
private val keyToIsImageGen = ConcurrentHashMap<String, Boolean>()

private fun setContainsHashMemoized(key: String, set: Set<String>, cache: MutableMap<String, Boolean>): Boolean {
    cache[key]?.also {
        return it
    }
    val hash = apiKeyHash(key)
    return set.contains(hash).also {
        cache[key] = it
    }
}

 fun apiKeyHash(apiKey: String): String =
    apiKey.toByteArray(Charsets.UTF_8).let {
        MessageDigest.getInstance("SHA-256").digest(it)
    }.let {
        Base64.encodeToString(it, Base64.NO_WRAP)
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

private const val EVENT_LINE_PREFIX = "event:"
private val EVENT_MESSAGE_STOP = "message_stop"
private val EVENT_CONTENT_BLOCK_DELTA = "content_block_delta"

private suspend inline fun <reified T> FlowCollector<T>.emitClaudeStream(response: HttpResponse) {
    val channel: ByteReadChannel = response.body()
    var event = ""
    while (!channel.isClosedForRead) {
        val line = channel.readUTF8Line() ?: break
        if (line.startsWith(EVENT_LINE_PREFIX)) {
            event = line.removePrefix(EVENT_LINE_PREFIX).trim()
            if (event == EVENT_MESSAGE_STOP) {
                break
            }
        } else if (line.startsWith(DATA_LINE_PREFIX) && event == EVENT_CONTENT_BLOCK_DELTA) {
            emit(jsonCodec.decodeFromString(line.removePrefix(DATA_LINE_PREFIX)))
            yield()
        }
    }
}

private val jsonCodec = Json {
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
    val max_tokens: Int? = null,
    val stream: Boolean
)

fun ChatCompletionRequest(model: String, messages: List<ChatMessage>) =
    ChatCompletionRequest(
        model,
        messages,
        max_tokens = if (model == MODEL_ID_GPT_3) null else 4096,
        stream = true)

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

@Serializable
class ClaudeCompletionRequest(
    val model: String,
    val system: String,
    val messages: List<ClaudeMessage>,
    val max_tokens: Int,
    val stream: Boolean,
)

fun ClaudeCompletionRequest(system: String, messages: List<ClaudeMessage>) = ClaudeCompletionRequest(
    model = MODEL_ID_SONNET_3_5,
    system = system,
    messages = messages,
    max_tokens = 4096,
    stream = true
)

@Serializable
class ClaudeMessage(
    val role: String,
    val content: String,
)

@Serializable
class ClaudeResponseChunk(
    val delta: ClaudeDelta
)

@Serializable
class ClaudeDelta(
    val text: String
)

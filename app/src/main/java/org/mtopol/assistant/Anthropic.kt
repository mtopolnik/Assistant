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

import android.util.Base64
import android.util.Log
import androidx.core.net.toFile
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.HttpStatement
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement
import java.io.ByteArrayOutputStream

const val MODEL_ID_SONNET_3_5 = "claude-3-5-sonnet-latest"
const val MODEL_ID_GROK = "grok-beta"

val anthropic get() = anthropicLazy.value

private const val ANTHROPIC_URL = "https://api.anthropic.com/v1/"
private const val XAI_URL = "https://api.x.ai/v1/"
private const val ANTHROPIC_VERSION = "2023-06-01"

private lateinit var anthropicLazy: Lazy<Anthropic>

fun resetAnthropic(): Lazy<Anthropic> {
    if (::anthropicLazy.isInitialized) {
        anthropicLazy.value.close()
    }
    return lazy { Anthropic() }.also { anthropicLazy = it }
}

class Anthropic {
    private val anthropicClient = createAnthropicClient(ANTHROPIC_URL, appContext.mainPrefs.anthropicApiKey)
    private val xaiClient = createAnthropicClient(XAI_URL, appContext.mainPrefs.xaiApiKey)

    suspend fun messages(history: List<Exchange>, model: AiModel): Flow<String> {
        Log.i("client", "Model: ${model.apiId}")
        val client = if (model == AiModel.CLAUDE_3_5_SONNET) anthropicClient else xaiClient
        val request = MessageRequest(
            model = model.apiId,
            system = appContext.mainPrefs.systemPrompt,
            messages = history.toDto().dropLast(1),
        )
        return flow<ResponseChunk> {
            val builder = HttpRequestBuilder().apply {
                method = HttpMethod.Post
                url(path = "messages")
                contentType(ContentType.Application.Json)
                setBody(jsonCodec.encodeToJsonElement<MessageRequest>(request))
            }
            HttpStatement(builder, client).execute { emitStreamingResponse(it) }
        }
            .map { chunk -> chunk.delta.text }
            .filterNotNull()
    }

    fun close() {
        anthropicClient.close()
    }

    private suspend fun List<Exchange>.toDto() = flatMap { exchange ->
        listOf(
            Message("user", exchange.promptParts.mapNotNull { it.toContentPart() }),
            Message("assistant", listOf(ContentPart.Text(exchange.replyMarkdown.toString()))),
        )
    }

    private suspend fun PromptPart.toContentPart(): ContentPart? = withContext(Dispatchers.IO) {
        when (this@toContentPart) {
            is PromptPart.Text -> ContentPart.Text(text.toString())
            is PromptPart.Image -> try {
                val bytes = uri.inputStream().use { input ->
                    ByteArrayOutputStream().also { input.copyTo(it) }.toByteArray()
                }
                ContentPart.Image("image/${uri.toFile().extension}", Base64.encodeToString(bytes, Base64.NO_WRAP))
            } catch (e: Exception) {
                ContentPart.Image("image/gif", "R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw==")
            }
            is PromptPart.Audio -> null
        }
    }

    @Serializable
    class Message(
        val role: String,
        val content: List<ContentPart>,
    )

    @Serializable
    sealed class ContentPart {
        @Serializable
        @SerialName("text")
        class Text(val text: String) : ContentPart()

        @Serializable
        @SerialName("image")
        class Image(val source: ImageSource) : ContentPart()

        companion object {
            fun Image(format: String, data: String) = Image(
                ImageSource(
                    type = "base64",
                    media_type = format,
                    data = data)
            )
        }
    }

    @Serializable
    class ImageSource(
        val type: String,
        val media_type: String,
        val data: String
    )

    @Serializable
    class ResponseChunk(
        val delta: Delta
    )

    @Serializable
    class Delta(
        val text: String
    )

    @Serializable
    class MessageRequest(
        val model: String,
        val system: String,
        val messages: List<Message>,
        val max_tokens: Int,
        val stream: Boolean,
    )

    private fun MessageRequest(model: String, system: String, messages: List<Message>) = MessageRequest(
        model = model,
        system = system,
        messages = messages,
        max_tokens = 4096,
        stream = true
    )
}

private fun createAnthropicClient(baseUrl: String, apiKey: String) = HttpClient(OkHttp) {
    defaultRequest {
        url(baseUrl)
        headers {
            append("x-api-key", apiKey)
            append("anthropic-version", ANTHROPIC_VERSION)
        }
    }
    commonApiClientSetup()
}

private const val DATA_LINE_PREFIX = "data:"
private const val EVENT_LINE_PREFIX = "event:"
private val EVENT_MESSAGE_STOP = "message_stop"
private val EVENT_CONTENT_BLOCK_DELTA = "content_block_delta"

private suspend inline fun <reified T> FlowCollector<T>.emitStreamingResponse(response: HttpResponse) {
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


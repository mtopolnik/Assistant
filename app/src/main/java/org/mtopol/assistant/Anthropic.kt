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

import android.util.Log
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement

const val MODEL_ID_SONNET_3_5 = "claude-3-5-sonnet-20240620"

val anthropic get() = anthropicLazy.value

private const val ANTHROPIC_URL = "https://api.anthropic.com/v1/"
private const val ANTHROPIC_VERSION = "2023-06-01"

private lateinit var anthropicLazy: Lazy<Anthropic>

fun resetAnthropic(): Lazy<Anthropic> {
    if (::anthropicLazy.isInitialized) {
        anthropicLazy.value.close()
    }
    return lazy { Anthropic() }.also { anthropicLazy = it }
}

class Anthropic {
    private val anthropicClient = createAnthropicClient(appContext.mainPrefs.anthropicApiKey)

    suspend fun messages(history: List<Exchange>, model: AiModel): Flow<String> {
        Log.i("client", "Model: ${model.apiId}")
        val request = ClaudeMessageRequest(
            system = appContext.mainPrefs.systemPrompt,
            messages = history.toDto().dropLast(1),
        )
        return flow<ClaudeResponseChunk> {
            val builder = HttpRequestBuilder().apply {
                method = HttpMethod.Post
                url(path = "messages")
                contentType(ContentType.Application.Json)
                setBody(jsonCodec.encodeToJsonElement<ClaudeMessageRequest>(request))
            }
            HttpStatement(builder, anthropicClient).execute { emitStreamingResponse(it) }
        }
            .map { chunk -> chunk.delta.text }
            .filterNotNull()
    }

    fun close() {
        anthropicClient.close()
    }

    private fun List<Exchange>.toDto() = flatMap { exchange ->
        listOf(
            ClaudeMessage("user", exchange.promptText.toString()),
            ClaudeMessage("assistant", exchange.replyMarkdown.toString()),
        )
    }
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

@Serializable
private class ClaudeMessageRequest(
    val model: String,
    val system: String,
    val messages: List<ClaudeMessage>,
    val max_tokens: Int,
    val stream: Boolean,
)

private fun ClaudeMessageRequest(system: String, messages: List<ClaudeMessage>) = ClaudeMessageRequest(
    model = MODEL_ID_SONNET_3_5,
    system = system,
    messages = messages,
    max_tokens = 4096,
    stream = true
)

@Serializable
private class ClaudeMessage(
    val role: String,
    val content: String,
)

@Serializable
private class ClaudeResponseChunk(
    val delta: ClaudeDelta
)

@Serializable
private class ClaudeDelta(
    val text: String
)

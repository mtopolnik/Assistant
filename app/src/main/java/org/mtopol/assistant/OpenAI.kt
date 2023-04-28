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
import android.util.Base64
import android.util.Log
import com.aallam.openai.api.BetaOpenAI
import com.aallam.openai.api.audio.TranscriptionRequest
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.file.FileSource
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.RetryStrategy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import okio.source
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.time.Duration.Companion.seconds
import com.aallam.openai.client.OpenAI as OpenAIClient

lateinit var openAi: Lazy<OpenAI>

fun resetOpenAi(context: Context): Lazy<OpenAI> {
    if (::openAi.isInitialized) {
        openAi.value.close()
    }
    return lazy { OpenAI(context) }.also { openAi = it }
}

@OptIn(BetaOpenAI::class)
class OpenAI(
    private val context: Context
) {
    private val client = OpenAIClient(
        OpenAIConfig(
            token = context.mainPrefs.openaiApiKey,
            timeout = Timeout(connect = 5.seconds, socket = 5.seconds, request = 180.seconds),
            retry = RetryStrategy(0, 2.0, 2.seconds),
        )
    )

    private val mockResponse = "Slijedi prijevod na engleski. Bok! Zdravo! Frende!"

    @Throws(IOException::class)
    fun chatCompletions(history: List<PromptAndResponse>, useGpt4: Boolean): Flow<String> {
        val gptModel = if (useGpt4) "gpt-4" else "gpt-3.5-turbo"
        Log.i("gpt", "Model: $gptModel")
//        return mockResponse.split("(?<= )".toRegex()).also { Log.i("speech", "Tokens: $it") }.asFlow()
        val chatCompletionRequest = ChatCompletionRequest(
            model = ModelId(gptModel),
            messages = systemPrompt() + history.toDto().dropLast(1)
        )
        return client.chatCompletions(chatCompletionRequest)
            .map { chunk -> chunk.choices[0].delta?.content }
            .filterNotNull()
    }

    suspend fun getTranscription(promptContext: String, audioPathname: String): String {
        Log.i("speech", "promptContext $promptContext")
        return client.transcription(
            TranscriptionRequest(
                audio = FileSource(audioPathname, File(audioPathname).source()),
                prompt = promptContext,
                model = ModelId("whisper-1"),
                temperature = 0.2,
                responseFormat = "text"
        )).text.replace("\n", "")
    }

    fun close() {
        client.close()
    }

    private fun systemPrompt(): List<ChatMessage> {
        return context.mainPrefs.systemPrompt
            .takeIf { it.isNotBlank() }
            ?.let { listOf(ChatMessage(ChatRole.System, it)) }
            ?: emptyList()
    }

    private fun List<PromptAndResponse>.toDto() = flatMap {
        listOf(
            ChatMessage(role = ChatRole.User, content = it.prompt.toString()),
            ChatMessage(role = ChatRole.Assistant, content = it.response.toString()),
        )
    }
}

private const val GPT3_ONLY_KEY_HASH = "fg15RZXuK/smtuoB/R0sV3KF1aJmU3HHZlwxx9MLp8U="

fun String.isGpt3OnlyKey() = apiKeyHash(this) == GPT3_ONLY_KEY_HASH

private fun apiKeyHash(apiKey: String): String =
    apiKey.toByteArray(Charsets.UTF_8).let {
        MessageDigest.getInstance("SHA-256").digest(it)
    }.let {
        Base64.encodeToString(it, Base64.NO_WRAP)
    }

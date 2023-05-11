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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import okio.source
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.time.Duration.Companion.seconds
import com.aallam.openai.client.OpenAI as OpenAIClient

val openAi get() = openAiLazy.value

private const val DEMO_API_KEY = "demo"
private lateinit var openAiLazy: Lazy<OpenAI>

fun resetOpenAi(context: Context): Lazy<OpenAI> {
    if (::openAiLazy.isInitialized) {
        openAiLazy.value.close()
    }
    return lazy { OpenAI(context) }.also { openAiLazy = it }
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

    private val demoMode = context.mainPrefs.openaiApiKey.trim().lowercase() == DEMO_API_KEY

    private val mockRecognizedSpeech = context.getString(R.string.demo_recognized_speech)
    private val mockResponse = context.getString(R.string.demo_response)

    @Throws(IOException::class)
    fun chatCompletions(history: List<PromptAndResponse>, useGpt4: Boolean): Flow<String> {
        if (demoMode) {
            return mockResponse.toCharArray().asList().chunked(4).asFlow().map { delay(120); it.joinToString("") }
        }
        val gptModel = if (useGpt4) "gpt-4" else "gpt-3.5-turbo"
        Log.i("gpt", "Model: $gptModel")
        val chatCompletionRequest = ChatCompletionRequest(
            model = ModelId(gptModel),
            messages = systemPrompt() + history.toDto().dropLast(1)
        )
        return client.chatCompletions(chatCompletionRequest)
            .map { chunk -> chunk.choices[0].delta?.content }
            .filterNotNull()
    }

    fun translation(targetLanguage: String, text: String, useGpt4: Boolean): Flow<String> {
        if (demoMode) {
            return flowOf("Demo mode is on. You asked to translate this:\n$text")
        }
        val systemPrompt = "You are a translator." +
                " I will write in a language of my choice, and you will translate it to $targetLanguage."
        val chatCompletionRequest = ChatCompletionRequest(
            model = ModelId(if (useGpt4) "gpt-4" else "gpt-3.5-turbo"),
            messages = listOf(
                ChatMessage(ChatRole.System, systemPrompt),
                ChatMessage(ChatRole.User, text)
            )
        )
        return client.chatCompletions(chatCompletionRequest)
            .map { chunk -> chunk.choices[0].delta?.content }
            .filterNotNull()
    }

    suspend fun transcription(language: String?, prompt: String, audioPathname: String): String {
        Log.i("speech", "Transcription language: $language, prompt context:\n$prompt")
        if (demoMode) {
            delay(2000)
            return mockRecognizedSpeech
        }
        return client.transcription(
            TranscriptionRequest(
                language = language,
                prompt = prompt,
                audio = FileSource(audioPathname, File(audioPathname).source()),
                model = ModelId("whisper-1"),
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

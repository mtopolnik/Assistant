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
import com.aallam.openai.api.BetaOpenAI
import com.aallam.openai.api.audio.TranscriptionRequest
import com.aallam.openai.api.chat.ChatCompletionChunk
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.file.FileSource
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.RetryStrategy
import kotlinx.coroutines.flow.Flow
import okio.source
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import com.aallam.openai.client.OpenAI as OpenAIClient

@OptIn(BetaOpenAI::class)
class OpenAI(context: Context) {
    private val client = OpenAIClient(
        OpenAIConfig(
            token = context.getString(R.string.openai_api_key),
            timeout = Timeout(connect = 5.seconds, socket = 5.seconds, request = 180.seconds),
            retry = RetryStrategy(1, 2.0, 2.seconds),
        )
    )

    @Throws(IOException::class)
    fun chatCompletions(history: List<MessageModel>, useGpt4: Boolean): Flow<ChatCompletionChunk> {
        val gptModel = if (useGpt4) "gpt-4" else "gpt-3.5-turbo"
        val chatCompletionRequest = ChatCompletionRequest(
            model = ModelId(gptModel),
            messages = history.toDto()
        )
        return client.chatCompletions(chatCompletionRequest)
    }

    suspend fun getTranscription(audioPathname: String): String {
        return client.transcription(
            TranscriptionRequest(
                audio = FileSource(audioPathname, File(audioPathname).source()),
                language = "",
                model = ModelId("whisper-1"),
                temperature = 0.2,
                responseFormat = "text"
        )).text.replace("\n", "")
    }

    private fun List<MessageModel>.toDto() = map { ChatMessage(role = it.author.toDto(), content = it.text.toString()) }

    private fun Role.toDto() = when(this) {
        Role.USER -> ChatRole.User
        Role.GPT -> ChatRole.Assistant
    }
}

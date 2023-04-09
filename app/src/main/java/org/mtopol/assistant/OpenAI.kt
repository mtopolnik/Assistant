package org.mtopol.assistant

import android.content.Context
import android.util.Log
import com.aallam.openai.api.BetaOpenAI
import com.aallam.openai.api.chat.ChatCompletionChunk
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAIConfig
import kotlinx.coroutines.flow.Flow
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import com.aallam.openai.client.OpenAI as OpenAIClient

@OptIn(BetaOpenAI::class)
class OpenAI(context: Context) {
    private val client: OpenAIClient

    init {
        client = OpenAIClient(
            OpenAIConfig(
                token = context.getString(R.string.openai_api_key),
                timeout = Timeout(socket = 10.seconds, connect = 10.seconds, request = 90.seconds)
            )
        )
    }

    @Throws(IOException::class)
    fun getResponseFlow(history: List<MessageModel>, useGpt4: Boolean): Flow<ChatCompletionChunk> {
        val gptModel = if (useGpt4) "gpt-4" else "gpt-3.5-turbo"
        Log.i("", "Using $gptModel")
        val chatCompletionRequest = ChatCompletionRequest(
            model = ModelId(gptModel),
            messages = history.toDto()
        )
        return client.chatCompletions(chatCompletionRequest)
    }

    private fun List<MessageModel>.toDto() = map { ChatMessage(role = it.author.toDto(), content = it.text.toString()) }

    private fun Role.toDto() = when(this) {
        Role.USER -> ChatRole.User
        Role.GPT -> ChatRole.Assistant
    }
}

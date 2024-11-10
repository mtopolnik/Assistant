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

import android.media.AudioRecord
import android.media.AudioRecord.RECORDSTATE_STOPPED
import android.media.MediaCodec
import android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM
import android.media.MediaCodec.CONFIGURE_FLAG_ENCODE
import android.media.MediaFormat.KEY_BIT_RATE
import android.media.MediaFormat.KEY_MAX_INPUT_SIZE
import android.media.MediaFormat.MIMETYPE_AUDIO_OPUS
import android.media.MediaFormat.createAudioFormat
import android.net.Uri
import android.text.Editable
import android.util.Base64
import android.util.Base64.NO_WRAP
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.core.view.children
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
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
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.FormBuilder
import io.ktor.client.request.forms.append
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import io.ktor.websocket.send as wsend

val openAi get() = openAiLazy.value

const val MODEL_ID_GPT_4O_MINI = "gpt-4o-mini"
const val MODEL_ID_GPT_4O = "gpt-4o"
const val MODEL_ID_GPT_4O_AUDIO = "gpt-4o-audio-preview"
const val MODEL_ID_GPT_4O_REALTIME = "gpt-4o-realtime-preview"

private const val OPENAI_URL = "https://api.openai.com/v1/"
private val AUDIORECORD_STOP_GRACE_PERIOD_NS = TimeUnit.MILLISECONDS.toNanos(100L)

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
        val modelId =
            if (model == AiModel.GPT_4O && history.find { it.hasAudioPrompt() } != null) MODEL_ID_GPT_4O_AUDIO
            else model.apiId

        Log.i("client", "Model: $modelId")
        return chatCompletions(
            ChatCompletionRequest(
                model = modelId,
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
        val systemPrompt = "Your task is to create a title for a conversation between a User and an Assistant." +
                " Your prompt will be the entire conversation, and you will respond with a title that summarizes" +
                " it, up to 30 characters in length. Do not add any other text except the title itself."

        val text = chat.map { exchange ->
            "User:\n\n${exchange.promptText()}\n\nAssistant:\n\n${exchange.replyMarkdown}"
        }.joinToString("\n\n")

        val chatCompletionRequest = ChatCompletionRequest(
            model = MODEL_ID_GPT_4O_MINI,
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
                    }.trim().removeSurrounding("\"")
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

        HttpStatement(builder, apiClient).execute { httpResponse ->
            val channel = httpResponse.body<ByteReadChannel>()
            exoPlayer.addMediaSource(
                ProgressiveMediaSource.Factory(SpeakDsFactory(channel))
                    .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0))
                    .createMediaSource(MediaItem.fromUri(Uri.EMPTY))
            )
            while (!channel.isClosedForRead) {
                delay(100)
            }
        }
    }

    @OptIn(UnstableApi::class)
    suspend fun realtime(
        sendBuf: ByteBuffer,
        audioRecord: AudioRecord,
        exoPlayer: ExoPlayer,
        vmodel: ChatFragmentModel
    ) {
        apiClient.webSocket({
            method = HttpMethod.Get
            url(scheme = "wss", host = "api.openai.com", path = "v1/realtime?model=${AiModel.GPT_4O_REALTIME.apiId}")
            header("OpenAI-Beta", "realtime=v1")
        }) {

            suspend fun sendWs(msg: String) {
                wsend(msg)
                val logEvent =
                    if (msg.contains("input_audio_buffer.append")) msg.substring(0, 50.coerceAtMost(msg.length))
                    else msg
                Log.i("speech", "Send $logEvent")
            }

            suspend fun sendWs(event: RealtimeEvent) {
                sendWs(event.encodeToString())
            }

            val dsFac = ExoplayerWebsocketDsFactory()
            val mediaFactory = ProgressiveMediaSource.Factory(dsFac, ExoplayerPcmExtractorsFactory(24000))
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0))

            fun addMediaSource() {
                exoPlayer.addMediaSource(mediaFactory.createMediaSource(MediaItem.fromUri(Uri.EMPTY)))
            }

            fun saveDiagnosticWav(promptAudio: ByteArray) {
                launch(IO) {
                    try {
                        val wavFile = File(appContext.externalCacheDir, "prompt.wav")
                        convertAopusToWav(promptAudio, wavFile)
                        Log.i("speech", "Diagnostic WAV saved: ${wavFile.length()} bytes in ${wavFile.path}")
                    } catch (e: Exception) {
                        Log.e("speech", "Decoding Aopus bytes failed", e)
                    }
                }
            }

            sendWs(
                """
                {
                    "type": "session.update",
                    "session": {
                        "modalities":["audio","text"],
                        "voice": "${appContext.mainPrefs.selectedRtVoice.name.lowercase()}",
                        "input_audio_format": "pcm16",
                        "output_audio_format": "pcm16",
                        "input_audio_transcription": { "model": "whisper-1" },
                        "turn_detection": null,
                        "temperature": 0.8,
                        "max_response_output_tokens": 4096,
                        "instructions": "${appContext.getString(R.string.realtime_instructions)}"
                    }
                }
                """.trimIndent()
            )
            for (exchange in vmodel.chatContent) {
//                exchange.audioPrompt()
//                    ?.let { it.uri.inputStream()?.use { it.readBytes() } }
//                    ?.also { opusBytes -> saveDiagnosticWav(opusBytes) }
                sendWs(
                    RealtimeEvent.ConversationItemCreate(
                        RealtimeEvent.ClientConversationItem(
                            type = "message",
                            role = "user",
                            content = listOf(
                                RealtimeEvent.ContentPart.InputAudio(
                                    transcript = exchange.promptText()?.toString() ?: "",
//                                    audio =
//                                    exchange.audioPrompt()
//                                        ?.let { it.uri.inputStream()?.use { it.readBytes() } }
//                                        ?.let { opusBytes -> Base64.encodeToString(convertAopusToPcm(opusBytes), NO_WRAP) }
                                )
                            )
                        )
                    )
                )
                sendWs(
                    RealtimeEvent.ConversationItemCreate(
                        RealtimeEvent.ClientConversationItem(
                            type = "message",
                            role = "assistant",
                            content = listOf(RealtimeEvent.ContentPart.Text(exchange.replyMarkdown.toString()))
                        )
                    )
                )
            }
            val bytesFor50ms = REALTIME_RECORD_SAMPLE_RATE / 10
            val bytesFor100ms = 2 * bytesFor50ms
            val responseId = AtomicReference<String>(null)
            val promptAudioChannel = Channel<ByteArray>(UNLIMITED)
            launch {
                val bufInfo = MediaCodec.BufferInfo()
                val opusWriter = PacketWriter()
                var encoder = MediaCodec.createEncoderByType(MIMETYPE_AUDIO_OPUS)
                var encoderDone = false

                fun startEncoder() {
                    encoder.configure(createAudioFormat(MIMETYPE_AUDIO_OPUS, audioRecord.sampleRate, 1).apply {
                        setInteger(KEY_BIT_RATE, 12000)
                        setInteger(KEY_MAX_INPUT_SIZE, 2 * audioRecord.sampleRate)
                    }, null, null, CONFIGURE_FLAG_ENCODE)
                    encoder.start()
                    encoderDone = false
                }

                fun restartEncoder() {
                    encoder.apply { stop(); release() }
                    encoder = MediaCodec.createEncoderByType(MIMETYPE_AUDIO_OPUS)
                    startEncoder()
                }

                fun dequeueInputBuf(): Int {
                    val bufIndex = encoder.dequeueInputBuffer(1000)
                    if (bufIndex < 0) {
                        throw IOException("Failed to obtain an input buffer from encoder")
                    }
                    return bufIndex
                }

                suspend fun drainEncoder(drainFully: Boolean) {
                    if (encoderDone) {
                        return
                    }
                    while (true) {
                        val bufIndex = encoder.dequeueOutputBuffer(bufInfo, 0)
                        if (bufIndex < 0) {
                            if (drainFully) {
                                delay(1)
                                continue
                            } else {
                                return
                            }
                        }
                        val outBuf = encoder.getOutputBuffer(bufIndex)!!
                        opusWriter.writePacket(outBuf, presentationTimeUsToGranulePos(bufInfo.presentationTimeUs))
                        encoder.releaseOutputBuffer(bufIndex, false)
                        if ((bufInfo.flags and BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encoderDone = true
                            return
                        }
                    }
                }

                try {
                    startEncoder()
                    var promptInProgress = false
                    var bytesSent = 0
                    var stopTimestamp = Long.MAX_VALUE
                    while (true) {
                        drainEncoder(false)
                        if (promptInProgress && audioRecord.recordingState == RECORDSTATE_STOPPED && stopTimestamp == Long.MAX_VALUE) {
                            stopTimestamp = System.nanoTime()
                        }
                        val allAudioReceived = System.nanoTime() - stopTimestamp > AUDIORECORD_STOP_GRACE_PERIOD_NS
                        val audioBase64 = synchronized(sendBuf) {
                            val bytesAvailable = sendBuf.position()
                            if (bytesAvailable >= bytesFor50ms || allAudioReceived && bytesAvailable > 0) {
                                sendBuf.flip()
                                while (sendBuf.remaining() > 0) {
                                    val bufIndex = dequeueInputBuf()
                                    val encBuf = encoder.getInputBuffer(bufIndex)!!
                                    sendBuf.limit().also { limitBackup ->
                                        sendBuf.limit(sendBuf.position() + sendBuf.remaining().coerceAtMost(encBuf.remaining()))
                                        encBuf.put(sendBuf)
                                        sendBuf.limit(limitBackup)
                                    }
                                    encoder.queueInputBuffer(bufIndex, 0, encBuf.position(), 0, 0)
                                }
                                Base64.encodeToString(sendBuf.array(), 0, bytesAvailable, NO_WRAP).also {
                                    sendBuf.clear()
                                    bytesSent += bytesAvailable
                                }
                            } else {
                                ""
                            }
                        }
                        if (audioBase64.isEmpty()) {
                            if (promptInProgress && allAudioReceived) {
                                promptInProgress = false
                                stopTimestamp = Long.MAX_VALUE
                                encoder.queueInputBuffer(dequeueInputBuf(), 0, 0, 0, BUFFER_FLAG_END_OF_STREAM)
                                if (bytesSent >= bytesFor100ms) {
                                    sendWs("""{ "type": "input_audio_buffer.commit" }""")
                                    sendWs("""{ "type": "response.create" }""")
                                    drainEncoder(true)
                                    promptAudioChannel.send(opusWriter.consumeContent())
                                } else {
                                    sendWs("""{ "type": "input_audio_buffer.clear" } """)
                                    Log.i("speech", "Short prompt discarded")
                                }
                                bytesSent = 0
                                restartEncoder()
                            } else if (promptInProgress) {
                                delay(20)
                            } else {
                                delay(100)
                            }
                            continue
                        }
                        if (!promptInProgress) {
                            promptInProgress = true
                            Log.i("speech", "Prompt now in progress, reset ExoPlayer")
                            val responseDuration = withContext(Main) {
                                val currentPosition = exoPlayer.run {
                                    if (isPlaying) currentPosition
                                    else -1
                                }
                                exoPlayer.apply {
                                    pause()
                                    dsFac.reset()
                                    clearMediaItems()
                                    addMediaSource()
                                    prepare()
                                }
                                currentPosition
                            }
                            responseId.get()?.also { itemId ->
                                if (responseDuration >= 0) {
                                    Log.i("speech", "Cancel Response, truncate to $responseDuration ms")
                                    sendWs(
                                        RealtimeEvent.ConversationItemTruncate(
                                            item_id = itemId,
                                            content_index = 0,
                                            audio_end_ms = responseDuration
                                        )
                                    )
                                    sendWs("""{ "type": "response.cancel" }""")
                                }
                                responseId.set(null)
                            }
                        }
                        sendWs(RealtimeEvent.AudioBufferAppend(audioBase64))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("speech", "Error in send coroutine", e)
                } finally {
                    Log.i("speech", "Done sending audio")
                }
            }
            var currentExchange: Exchange? = null
            var currentPromptView: TextView? = null

            suspend fun withFragmentSync(task: (ChatFragment) -> Unit) {
                val cd = CompletableDeferred<Unit>()
                vmodel.withFragment {
                    task(it)
                    cd.complete(Unit)
                }
                cd.await()
            }

            suspend fun setResponseText(text: String) {
                currentExchange!!.replyMarkdown = text
                val markwon = vmodel.markwon
                withContext(Default) {
                    text.let { markwon.parse(it) }
                        .let { markwon.render(it) }
                }.let { markwon.setParsedMarkdown(vmodel.replyTextView!!, it) }
                withFragmentSync { it.onLayoutScrollToBottom() }
            }

            suspend fun handleEvent(event: RealtimeEvent) {
                when (event) {
                    is RealtimeEvent.ResponseAudioDelta -> {
                        if (responseId.get() != null) {
                            val data = Base64.decode(event.delta, Base64.DEFAULT)
                            dsFac.addData(data)
                            exoPlayer.play()
                        }
                    }
                    is RealtimeEvent.ConversationItemCreated -> {
                        val contentPart = event.item.content.firstOrNull()
                        if (contentPart is RealtimeEvent.ContentPart.InputText
                            || contentPart is RealtimeEvent.ContentPart.Text
                        ) {
                            return
                        }
                        when (event.item.role) {
                            "user" -> {
                                val promptAudio = promptAudioChannel.receive()
                                withFragmentSync { fragment ->
                                    Log.i("speech", "Create new exchange")
                                    val chatId = vmodel.chatId
                                    val chatContent = vmodel.chatContent
                                    val promptId = chatContent.size
                                    val audioUri =
                                        File(appContext.filesDir, promptAudioFilename(chatId, promptId))
                                        .also { audioFile ->
                                            audioFile.outputStream().use { it.write(promptAudio) }
                                            Log.i("speech", "created audio file $audioFile")
                                        }
                                        .toUri()
                                    val exchange = fragment.prepareNewExchange(
                                        fragment.requireContext(), PromptPart.Text("<recorded audio>"))
                                    exchange.promptParts.add(PromptPart.Audio(audioUri))
                                    if (promptId == 0) {
                                        saveChatContent(chatId, chatContent)
                                    }
                                    currentExchange = exchange
                                    currentPromptView = fragment.lastMessageContainer().children.first() as TextView
                                }
                            }
                            "assistant" -> {
                                responseId.set(event.item.id)
                            }
                        }
                    }
                    is RealtimeEvent.InputTranscriptionCompleted -> {
                        val prompt = event.transcript.trim()
                        if (prompt.isNotBlank()) {
                            withContext(Main) {
                                currentExchange!!.textPrompt()!!.text = prompt
                                currentPromptView!!.text = prompt
                            }
                        }
                    }
                    is RealtimeEvent.ResponseCreated -> {
                        withFragmentSync { fragment ->
                            vmodel.replyTextView =
                                fragment.addTextResponseToView(fragment.requireContext(), currentExchange!!)
                            fragment.onLayoutScrollToBottom()
                        }
                    }
                    is RealtimeEvent.ResponseTextDone -> {
                        setResponseText(event.text)
                    }
                    is RealtimeEvent.ResponseAudioTranscriptDone -> {
                        setResponseText(event.transcript)
                    }
                    else -> {}
                }
            }

            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) {
                        Log.e("speech", "unhandled WebSocket frame type ${frame.frameType}")
                        continue
                    }
                    val rawEventText = frame.readText()
                    try {
                        val event = jsonCodec.decodeFromString<RealtimeEvent>(rawEventText)
                        Log.i(
                            "speech", "Received " +
                                    if (event is RealtimeEvent.ResponseAudioDelta) "ResponseAudioDelta"
                                    else event.toString()
                        )
                        handleEvent(event)
                    } catch (e: Exception) {
                        Log.e("speech", "Error processing WebSocket event $rawEventText", e)
                    }
                }
            } finally {
                vmodel.replyTextView = null
                Log.i("speech", "Websocket done")
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
            console.append("Something went wrong while $artist was handling your prompt:\n\n${e.message}")
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

    private suspend fun PromptPart.toContentPart() = withContext(IO) {

        fun readBase64(uri: Uri): String {
            val bytes = uri.inputStream()!!.use { input ->
                ByteArrayOutputStream().also { input.copyTo(it) }.toByteArray()
            }
            return Base64.encodeToString(bytes, NO_WRAP)
        }

        when (this@toContentPart) {
            is PromptPart.Text -> ContentPart.Text(text.toString())
            is PromptPart.Image -> ContentPart.Image(
                try { "data:image/${uri.toFile().extension};base64," + readBase64(uri) }
                catch (e: Exception) { "data:image/gif;base64,R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw==" }
            )
            is PromptPart.Audio -> ContentPart.Audio(
                try { InputAudio(readBase64(uri), uri.toFile().extension) }
                catch (e: Exception) { InputAudio("UklGRvj///9XQVZFZm10IBAAAAABAAEAwF0AAIC7AAACABAAZGF0YdT///8=", "wav") }
            )
        }
    }

    private suspend fun downloadToCache(imageUrls: List<String>): List<Uri> = withContext(IO) {
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
            max_tokens = if (model == MODEL_ID_GPT_4O_MINI) 8192 else 4096,
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
        @SerialName("input_audio")
        class Audio(
            val input_audio: InputAudio
        ) : ContentPart()

        companion object {
            fun Image(imgUrl: String) = Image(DataUrl(imgUrl))
        }
    }

    @Serializable
    class InputAudio(
        val data: String,
        val format: String
    )

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


    @Serializable
    sealed class RealtimeEvent {

        fun encodeToString() = Json.encodeToString(serializer(), this)

        // Client events

        @Serializable
        @SerialName("input_audio_buffer.append")
        data class AudioBufferAppend(val audio: String) : RealtimeEvent()

        @Serializable
        @SerialName("conversation.item.create")
        data class ConversationItemCreate(val item: ClientConversationItem) : RealtimeEvent()

        @Serializable
        data class ClientConversationItem(
            val type: String,
            val role: String,
            val content: List<ContentPart>
        )

        @Serializable
        sealed class ContentPart {
            @Serializable
            @SerialName("input_audio")
            data class InputAudio(
                val transcript: String? = null,
                val audio: String? = null
            ) : ContentPart()

            @Serializable
            @SerialName("audio")
            data class Audio(
                val transcript: String?
            ) : ContentPart()

            @Serializable
            @SerialName("input_text")
            data class InputText(
                val text: String
            ) : ContentPart()

            @Serializable
            @SerialName("text")
            data class Text(
                val text: String
            ) : ContentPart()
        }

        @Serializable
        @SerialName("conversation.item.truncate")
        data class ConversationItemTruncate(
            val item_id: String,
            val content_index: Int,
            val audio_end_ms: Long
        ) : RealtimeEvent()


        // Server events

        @Serializable
        @SerialName("error")
        data class Error(val error: JsonObject) : RealtimeEvent()

        @Serializable
        @SerialName("session.created")
        data class SessionCreated(val session: Session) : RealtimeEvent()

        @Serializable
        @SerialName("session.updated")
        data class SessionUpdated(val session: Session) : RealtimeEvent()

        @Serializable
        data class Session 
        @kotlin.OptIn(ExperimentalSerializationApi::class)
        constructor(
            val modalities: Array<String>,
            val voice: String,
            val input_audio_format: String,
            val output_audio_format: String,
            @EncodeDefault(EncodeDefault.Mode.NEVER)
            val turn_detection: TurnDetection? = null,
            @EncodeDefault(EncodeDefault.Mode.NEVER)
            val input_audio_transcription: Transcription? = null,
            val temperature: Float,
            val instructions: String,
        )

        @Serializable
        data class Transcription(
            val model: String
        )

        @Serializable
        data class TurnDetection(
            val type: String,
            val threshold: Float,
            val prefix_padding_ms: Int,
            val silence_duration_ms: Int
        )

        @Serializable
        @SerialName("conversation.updated")
        data class ConversationUpdated(val conversation: JsonObject) : RealtimeEvent()

        @Serializable
        @SerialName("input_audio_buffer.speech_started")
        data class SpeechStarted(val audio_start_ms: Int) : RealtimeEvent()

        @Serializable
        @SerialName("input_audio_buffer.speech_stopped")
        data class SpeechStopped(val audio_end_ms: Int) : RealtimeEvent()

        @Serializable
        @SerialName("input_audio_buffer.committed")
        data class AudioBufferCommitted(val previous_item_id: String?) : RealtimeEvent()

        @Serializable
        @SerialName("input_audio_buffer.cleared")
        data class AudioBufferCleared(val event_id: String?) : RealtimeEvent()

        @Serializable
        @SerialName("conversation.item.created")
        data class ConversationItemCreated(val item: ServerConversationItem) : RealtimeEvent()

        @Serializable
        data class ServerConversationItem(
            val id: String,
            val role: String,
            val content: List<ContentPart>
        )

        @Serializable
        @SerialName("conversation.item.truncated")
        data class ConversationItemTruncated(val item_id: String) : RealtimeEvent()

        @Serializable
        @SerialName("conversation.item.input_audio_transcription.completed")
        data class InputTranscriptionCompleted(val transcript: String) : RealtimeEvent()

        @Serializable
        @SerialName("conversation.item.input_audio_transcription.failed")
        data class InputTranscriptionFailed(val error: JsonObject) : RealtimeEvent()

        @Serializable
        @SerialName("response.created")
        data class ResponseCreated(val response: JsonObject) : RealtimeEvent()

        @Serializable
        @SerialName("response.done")
        data class ResponseDone(val response: JsonObject) : RealtimeEvent()

        @Serializable
        @SerialName("response.output_item.added")
        data class ResponseOutputItemAdded(val item: JsonObject) : RealtimeEvent()

        @Serializable
        @SerialName("response.output_item.done")
        data class ResponseOutputItemDone(val item: JsonObject) : RealtimeEvent()

        @Serializable
        @SerialName("response.content_part.added")
        data class ResponseContentPartAdded(val part: ContentPart) : RealtimeEvent()

        @Serializable
        @SerialName("response.content_part.done")
        data class ResponseContentPartDone(val part: ContentPart) : RealtimeEvent()

        @Serializable
        @SerialName("response.audio_transcript.delta")
        data class ResponseAudioTranscriptDelta(val delta: String) : RealtimeEvent()

        @Serializable
        @SerialName("response.audio_transcript.done")
        data class ResponseAudioTranscriptDone(val transcript: String) : RealtimeEvent()

        @Serializable
        @SerialName("response.audio.delta")
        data class ResponseAudioDelta(val delta: String) : RealtimeEvent()

        @Serializable
        @SerialName("response.audio.done")
        data class ResponseAudioDone(val output_index: Int) : RealtimeEvent()

        @Serializable
        @SerialName("response.text.delta")
        data class ResponseTextDelta(val delta: String) : RealtimeEvent()

        @Serializable
        @SerialName("response.text.done")
        data class ResponseTextDone(val text: String) : RealtimeEvent()

        @Serializable
        @SerialName("rate_limits.updated")
        data class RateLimitsUpdated(val rate_limits: JsonArray) : RealtimeEvent()
    }
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
    install(WebSockets)
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
            var audioSize = 0
            while (true) {
                val count = inputStream.read(buffer)
                if (count == -1) {
                    break
                }
                audioSize += count
                writeFully(buffer, 0, count)
            }
            Log.i("audio", "Audio size $audioSize bytes")
        }
    }
}

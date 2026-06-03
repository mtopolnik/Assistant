/*
 * Copyright (C) 2024 Marko Topolnik
 *
 * Streaming speech-to-text over the OpenAI Realtime API, using the
 * `gpt-realtime-whisper` model in a transcription-only session. Audio is
 * streamed (raw PCM16, base64) while the user speaks, so by the time recording
 * stops only the trailing audio remains to be transcribed. The final transcript
 * is then handed to a regular text LLM.
 *
 * This is deliberately self-contained: it shares only generic infrastructure
 * (the bearer-authenticated HTTP client setup and the JSON codec) with the rest
 * of the app, and does not depend on the realtime voice-conversation code.
 */
package org.mtopol.assistant

import android.media.AudioRecord
import android.media.AudioRecord.RECORDSTATE_STOPPED
import android.util.Base64
import android.util.Base64.NO_WRAP
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.url
import io.ktor.http.HttpMethod
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

private const val WHISPER_MODEL = "gpt-realtime-whisper"

// How much to lower latency at the cost of transcript quality.
// One of: minimal, low, medium, high, xhigh.
private const val WHISPER_DELAY = "xhigh"

// After AudioRecord stops, wait this long for the capture loop to drain the last
// samples into the send buffer before committing.
private val STOP_GRACE_PERIOD_NS = TimeUnit.MILLISECONDS.toNanos(80L)

// How long to wait for the final transcript after committing the audio buffer before
// giving up (so a protocol mismatch falls back to file transcription instead of hanging).
private const val POST_COMMIT_TIMEOUT_MS = 3_000L

class StreamingTranscriber {

    private val client = HttpClient(OkHttp) {
        defaultRequest { url(AiVendor.OPENAI.baseUrl) }
        install(Auth) {
            bearer {
                loadTokens {
                    BearerTokens(accessToken = appContext.mainPrefs.apiKey(AiVendor.OPENAI), refreshToken = "")
                }
            }
        }
        install(WebSockets)
        commonApiClientSetup()
    }

    fun close() = client.close()

    /**
     * Opens a transcription session, streams PCM16 from [sendBuf] while
     * [audioRecord] keeps recording, commits the buffer once recording stops,
     * and returns the final transcript. Partial transcripts are delivered to
     * [onPartial] as they arrive. Every PCM byte sent is also mirrored to
     * [teeOut] when non-null, so the caller can fall back to file-based
     * transcription if the stream fails.
     *
     * @return the final transcript, or "" if too little audio was recorded.
     * @throws Exception if the session reports an error.
     */
    suspend fun transcribe(
        sampleRate: Int,
        sendBuf: ByteBuffer,
        audioRecord: AudioRecord,
        language: String?,
        teeOut: OutputStream?,
        onPartial: (String) -> Unit,
    ): String {
        val bytesFor50ms = sampleRate / 10            // 50 ms of 16-bit mono == sampleRate/10 bytes
        val minPromptBytes = 2 * bytesFor50ms         // discard anything shorter than ~100 ms
        val result = CompletableDeferred<String>()
        val committed = CompletableDeferred<Unit>()
        val partial = StringBuilder()

        client.webSocket({
            method = HttpMethod.Get
            url(scheme = "wss", host = "api.openai.com", path = "v1/realtime?intent=transcription")
        }) {
            send(Frame.Text(sessionUpdateJson(sampleRate, language)))

            // Reads incoming events and resolves `result` with the transcript.
            val reader = launch {
                try {
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        handleEvent(frame.readText(), partial, onPartial, result)
                        if (result.isCompleted) break
                    }
                } finally {
                    // Stream closed without a `completed` event: fall back to whatever partials we got.
                    if (!result.isCompleted) result.complete(partial.toString().trim())
                }
            }

            // Streams captured audio, then commits once recording has stopped.
            val sender = launch {
                var bytesSent = 0
                var stopTimestamp = Long.MAX_VALUE
                try {
                    while (true) {
                        if (audioRecord.recordingState == RECORDSTATE_STOPPED && stopTimestamp == Long.MAX_VALUE) {
                            stopTimestamp = System.nanoTime()
                        }
                        val allAudioReceived =
                            stopTimestamp != Long.MAX_VALUE && System.nanoTime() - stopTimestamp > STOP_GRACE_PERIOD_NS
                        val chunk = synchronized(sendBuf) {
                            val available = sendBuf.position()
                            if (available >= bytesFor50ms || (allAudioReceived && available > 0)) {
                                ByteArray(available).also {
                                    System.arraycopy(sendBuf.array(), 0, it, 0, available)
                                    sendBuf.clear()
                                    bytesSent += available
                                }
                            } else null
                        }
                        if (chunk != null) {
                            teeOut?.write(chunk)
                            send(Frame.Text(appendJson(Base64.encodeToString(chunk, NO_WRAP))))
                            continue
                        }
                        if (allAudioReceived) {
                            if (bytesSent >= minPromptBytes) {
                                send(Frame.Text("""{ "type": "input_audio_buffer.commit" }"""))
                                committed.complete(Unit)
                            } else {
                                Log.i("speech", "Short prompt discarded")
                                send(Frame.Text("""{ "type": "input_audio_buffer.clear" }"""))
                                result.complete("")
                            }
                            break
                        }
                        delay(20)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("speech", "Error while streaming audio for transcription", e)
                    if (!result.isCompleted) result.completeExceptionally(e)
                }
            }

            // Once committed, the server has a bounded window to return the transcript;
            // otherwise we fail so the caller can fall back to file-based transcription.
            val watchdog = launch {
                committed.await()
                delay(POST_COMMIT_TIMEOUT_MS)
                if (!result.isCompleted) {
                    result.completeExceptionally(Exception("No transcript ${POST_COMMIT_TIMEOUT_MS}ms after commit"))
                }
            }

            try {
                result.await()
            } finally {
                watchdog.cancel()
                sender.cancel()
                reader.cancel()
            }
        }
        return result.await()
    }

    private fun handleEvent(
        rawEvent: String,
        partial: StringBuilder,
        onPartial: (String) -> Unit,
        result: CompletableDeferred<String>,
    ) {
        val event = try {
            jsonCodec.parseToJsonElement(rawEvent).jsonObject
        } catch (e: Exception) {
            Log.e("speech", "Unparseable transcription event: $rawEvent", e)
            return
        }
        when (val type = event["type"]?.jsonPrimitive?.contentOrNull) {
            "conversation.item.input_audio_transcription.delta" -> {
                event["delta"]?.jsonPrimitive?.contentOrNull?.let {
                    partial.append(it)
                    onPartial(partial.toString())
                }
            }
            "conversation.item.input_audio_transcription.completed" -> {
                val transcript = event["transcript"]?.jsonPrimitive?.contentOrNull ?: partial.toString()
                result.complete(transcript.trim())
            }
            "conversation.item.input_audio_transcription.failed", "error" -> {
                Log.e("speech", "Transcription error event: $rawEvent")
                if (!result.isCompleted) result.completeExceptionally(Exception("Transcription failed: $rawEvent"))
            }
            else -> Log.i("speech", "Transcription event: $type")
        }
    }

    private fun sessionUpdateJson(sampleRate: Int, language: String?): String =
        buildJsonObject {
            put("type", "session.update")
            putJsonObject("session") {
                put("type", "transcription")
                putJsonObject("audio") {
                    putJsonObject("input") {
                        putJsonObject("format") {
                            put("type", "audio/pcm")
                            put("rate", sampleRate)
                        }
                        putJsonObject("transcription") {
                            put("model", WHISPER_MODEL)
                            put("delay", WHISPER_DELAY)
                            if (language != null) put("language", language)
                        }
                        put("turn_detection", JsonNull)
                    }
                }
            }
        }.toString()

    private fun appendJson(audioBase64: String) =
        """{ "type": "input_audio_buffer.append", "audio": "$audioBase64" }"""
}

/** Writes a 44-byte PCM WAV header with placeholder sizes; patch with [patchWavSizes] once data is written. */
fun writeWavHeader(out: OutputStream, sampleRate: Int, channels: Int = 1, bitsPerSample: Int = 16) {
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray(Charsets.US_ASCII))
    header.putInt(36)                                  // chunk size, patched later
    header.put("WAVE".toByteArray(Charsets.US_ASCII))
    header.put("fmt ".toByteArray(Charsets.US_ASCII))
    header.putInt(16)                                  // PCM fmt chunk size
    header.putShort(1)                                 // audio format: PCM
    header.putShort(channels.toShort())
    header.putInt(sampleRate)
    header.putInt(byteRate)
    header.putShort(blockAlign.toShort())
    header.putShort(bitsPerSample.toShort())
    header.put("data".toByteArray(Charsets.US_ASCII))
    header.putInt(0)                                   // data size, patched later
    out.write(header.array())
}

/** Patches the RIFF/data size fields of a WAV file once its PCM payload has been fully written. */
fun patchWavSizes(file: java.io.File) {
    val dataLen = (file.length() - 44).toInt()
    if (dataLen <= 0) return
    fun intLe(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    RandomAccessFile(file, "rw").use { raf ->
        raf.seek(4); raf.write(intLe(36 + dataLen))
        raf.seek(40); raf.write(intLe(dataLen))
    }
}

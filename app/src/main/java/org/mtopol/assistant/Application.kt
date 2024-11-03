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

import android.annotation.SuppressLint
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.AudioAttributes
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.View
import android.widget.ImageView
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.net.toFile
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import androidx.media3.common.C
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import androidx.preference.PreferenceManager
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

const val FILE_PROVIDER_AUTHORITY = "org.mtopol.assistant.fileprovider"

private const val KEY_ANTHROPIC_API_KEY = "anthropic_api_key"
private const val KEY_XAI_API_KEY = "xai_api_key"
private const val KEY_OPENAI_API_KEY = "openai_api_key"
private const val KEY_SYSTEM_PROMPT = "system_prompt"
private const val KEY_SPEECH_RECOG_LANGUAGE = "speech_recognition_language"
private const val KEY_LANGUAGES = "languages"
private const val KEY_IS_MUTED = "is_muted"
private const val KEY_IS_SEND_AUDIO_PROMPT = "is_send_audio_prompt"
private const val KEY_SELECTED_MODEL = "selected_model"
private const val KEY_SELECTED_VOICE = "selected_voice"
private const val KEY_SELECTED_RT_VOICE = "selected_rt_voice"
private const val KEY_IS_VOICE_MODE_SELECTED = "selected_rt_ui_mode"
private const val WAV_HEADER_SIZE = 44


lateinit var appContext: Context
lateinit var imageCache: File

class ChatApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        imageCache = File(applicationContext.externalCacheDir, "images")
        imageCache.mkdir()
    }
}

@SuppressLint("QueryPermissionsNeeded") // Play Store is visible automatically
fun Context.visitOnPlayStore() {
    val rateIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
    packageManager.queryIntentActivities(rateIntent, 0)
        .map { it.activityInfo }
        .find { it.applicationInfo.packageName == "com.android.vending" }
        ?.also {
            rateIntent.component = ComponentName(it.applicationInfo.packageName, it.name)
            rateIntent.addFlags(
                // don't open Play Store in the stack of our activity
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                        // make sure Play Store opens our app page, whatever it was doing before
                        or Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            startActivity(rateIntent)
        }
    // Play Store app not installed, open in web browser
        ?: startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
        )
}

// Locale.getDefault() gives the locale used to localize the app
// LocaleListCompat.getDefault().get(0) gives the default locale configured on system level
fun defaultLocale() = LocaleListCompat.getDefault().get(0)!!

fun systemLanguages(): List<String> {
    val localeList: LocaleListCompat = LocaleListCompat.getDefault()
    return (0 until localeList.size()).map { localeList.get(it)!!.language }
}

fun String.capitalizeFirstLetter() =
    replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

fun String.toDisplayLanguage() =
    Locale.forLanguageTag(this).getDisplayLanguage(defaultLocale()).capitalizeFirstLetter()

val pixelDensity: Float get() = appContext.resources.displayMetrics.density

val Int.dp: Int get() = toFloat().dp.toInt()

val Float.dp: Float get() = pixelDensity * this

fun Context.getColorCompat(id: Int) = ContextCompat.getColor(this, id)

val Context.mainPrefs: SharedPreferences get() = PreferenceManager.getDefaultSharedPreferences(this)

inline fun SharedPreferences.applyUpdate(block: SharedPreferences.Editor.() -> Unit) {
    with (edit()) {
        try {
            block()
        } finally {
            apply()
        }
    }
}

val SharedPreferences.anthropicApiKey: String get() = getString(KEY_ANTHROPIC_API_KEY, "")!!

fun SharedPreferences.Editor.setAnthropicApiKey(apiKey: String): SharedPreferences.Editor =
    putString(KEY_ANTHROPIC_API_KEY, apiKey)

val SharedPreferences.xaiApiKey: String get() = getString(KEY_XAI_API_KEY, "")!!

fun SharedPreferences.Editor.setXaiApiKey(apiKey: String): SharedPreferences.Editor =
    putString(KEY_XAI_API_KEY, apiKey)

val SharedPreferences.openaiApiKey: OpenAiKey get() = OpenAiKey(getString(KEY_OPENAI_API_KEY, "")!!)

fun SharedPreferences.Editor.setOpenaiApiKey(apiKey: String): SharedPreferences.Editor =
    putString(KEY_OPENAI_API_KEY, apiKey)

val SharedPreferences.isSendAudioPrompt: Boolean get() = getBoolean(KEY_IS_SEND_AUDIO_PROMPT, false)

fun SharedPreferences.Editor.setIsSendAudioPrompt(value: Boolean): SharedPreferences.Editor =
    putBoolean(KEY_IS_SEND_AUDIO_PROMPT, value)

val SharedPreferences.isVoiceModeSelected: Boolean get() = getBoolean(KEY_IS_VOICE_MODE_SELECTED, true)

fun SharedPreferences.Editor.setIsVoiceModeSelected(value: Boolean): SharedPreferences.Editor =
    putBoolean(KEY_IS_VOICE_MODE_SELECTED, value)

val SharedPreferences.systemPrompt: String get() = getString(KEY_SYSTEM_PROMPT,
    appContext.getString(R.string.system_prompt_default))!!

fun SharedPreferences.Editor.setSystemPrompt(systemPrompt: String?): SharedPreferences.Editor =
    putString(KEY_SYSTEM_PROMPT, systemPrompt)

val SharedPreferences.speechRecogLanguage: String? get() = getString(KEY_SPEECH_RECOG_LANGUAGE, null)

fun SharedPreferences.Editor.setSpeechRecogLanguage(language: String?): SharedPreferences.Editor =
    putString(KEY_SPEECH_RECOG_LANGUAGE, language)

val SharedPreferences.isMuted: Boolean get() = getBoolean(KEY_IS_MUTED, false)

fun SharedPreferences.Editor.setIsMuted(value: Boolean): SharedPreferences.Editor =
    putBoolean(KEY_IS_MUTED, value)

val SharedPreferences.selectedModel: AiModel get() =
    try {
        getString(KEY_SELECTED_MODEL, AiModel.GPT_4O.name).let { AiModel.valueOf(it!!) }
    } catch (e: Exception) {
        AiModel.GPT_4O
    }

fun SharedPreferences.Editor.setSelectedModel(value: AiModel): SharedPreferences.Editor =
    putString(KEY_SELECTED_MODEL, value.name)

val SharedPreferences.selectedVoice: Voice get() =
    getString(KEY_SELECTED_VOICE, Voice.BUILT_IN.name).let { Voice.valueOf(it!!)
}

fun SharedPreferences.Editor.setSelectedVoice(value: Voice): SharedPreferences.Editor =
    putString(KEY_SELECTED_VOICE, value.name)

val SharedPreferences.selectedRtVoice: Voice get() =
    getString(KEY_SELECTED_RT_VOICE, Voice.ALLOY.name).let { Voice.valueOf(it!!)
}

fun SharedPreferences.Editor.setSelectedRtVoice(value: Voice): SharedPreferences.Editor =
    putString(KEY_SELECTED_RT_VOICE, value.name)

fun SharedPreferences.configuredLanguages(): List<String> =
    getStringSet(KEY_LANGUAGES, null)?.let {
        it.map { str ->
            val parts = str.split(" ")
            Pair(parts[0].toInt(), parts[1])
        }
            .sortedBy { (index, _) -> index }
            .map { (_, language) -> language }
    } ?: systemLanguages()
fun SharedPreferences.Editor.setConfiguredLanguages(languages: List<String>?): SharedPreferences.Editor {
    if (languages != null && languages.isEmpty()) {
        throw IllegalArgumentException("Can't configure empty list of locales")
    }
    return putStringSet(KEY_LANGUAGES, languages?.toStringSet())
}

private fun List<String>.toStringSet(): Set<String> = mapIndexed { i, language -> "$i $language" }.toSet()

fun ImageView?.bitmapSize(p: Point) =
    p.also { this?.drawable
        ?.apply { it.set(intrinsicWidth, intrinsicHeight) }
        ?: it.set(0, 0)
    }.takeIf { it.x > 0 && it.y > 0 }

fun ImageView?.bitmapSize(p: PointF) =
    p.also { this?.drawable
        ?.apply { it.set(intrinsicWidth.toFloat(), intrinsicHeight.toFloat()) }
        ?: it.set(0f, 0f)
    }.takeIf { it.x > 0 && it.y > 0 }

fun styleMessageContainer(box: View, backgroundFill: Int, backgroundBorder: Int) {
    (box.background as LayerDrawable).apply {
        findDrawableByLayerId(R.id.background_fill).setTint(
            appContext.getColorCompat(
                backgroundFill
            )
        )
        (findDrawableByLayerId(R.id.background_border) as GradientDrawable)
            .setStroke(1.dp, appContext.getColorCompat(backgroundBorder))
    }
}

operator fun Point.component1() = x
operator fun Point.component2() = y
operator fun PointF.component1() = x
operator fun PointF.component2() = y

operator fun RectF.component1() = left
operator fun RectF.component2() = top
operator fun RectF.component3() = right
operator fun RectF.component4() = bottom

fun Uri.inputStream(): InputStream =
    when (scheme) {
        "file" -> FileInputStream(toFile())
        "content" -> appContext.contentResolver.openInputStream(this)!!
        else -> throw IllegalArgumentException("URI scheme $scheme not supported")
    }

fun scaleAndSave(uri: Uri, widthLimit: Int, heightLimit: Int): File? {

    fun loadAsBitmap(uri: Uri, bitmapOptions: BitmapFactory.Options): Bitmap? {
        return when (val scheme = uri.scheme) {
            "file" -> BitmapFactory.decodeFile(uri.toFile().path, bitmapOptions)
            "content" -> appContext.contentResolver.openInputStream(uri).use {
                Log.i("client", "Decoding bitmap at $uri")
                BitmapFactory.decodeStream(it, null, bitmapOptions)
            }
            else -> {
                Log.e("client", "URI scheme not supported: $scheme")
                null
            }
        }
    }

    fun determineSampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        var sampleSize = 1
        while (width / (sampleSize * 2) >= targetWidth && height / (sampleSize * 2) >= targetHeight) {
            sampleSize *= 2
        }
        return sampleSize
    }

    fun determineTargetDimensions(
        bitmapOptions: BitmapFactory.Options, widthLimit: Int, heightLimit: Int
    ): Pair<Int, Int> {
        val (width: Int, height: Int) = Pair(bitmapOptions.outWidth, bitmapOptions.outHeight)
        val widthRatio = widthLimit.toDouble() / bitmapOptions.outWidth
        val heightRatio = heightLimit.toDouble() / bitmapOptions.outHeight
        val scaleFactor = min(widthRatio, heightRatio).coerceAtMost(1.0)
        return Pair(
            (width * scaleFactor).toInt().coerceAtMost(widthLimit),
            (height * scaleFactor).toInt().coerceAtMost(heightLimit)
        )
    }

    var bitmapOptions = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    loadAsBitmap(uri, bitmapOptions)
    if (bitmapOptions.outWidth == 0 || bitmapOptions.outHeight == 0) {
        return null
    }
    val (width: Int, height: Int) = Pair(bitmapOptions.outWidth, bitmapOptions.outHeight)
    bitmapOptions = BitmapFactory.Options().apply {
        inSampleSize = determineSampleSize(width, height, 512, 512)
    }
    val bitmap = loadAsBitmap(uri, bitmapOptions)!!
    val (targetWidth, targetHeight) = determineTargetDimensions(bitmapOptions, widthLimit, heightLimit)
    val scaledBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    val (compressFormat, fileSuffix) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Pair(Bitmap.CompressFormat.WEBP_LOSSY, ".webp")
        else Pair(Bitmap.CompressFormat.JPEG, ".jpeg")
    return File.createTempFile("shared-", fileSuffix, imageCache).also { imageFile ->
        FileOutputStream(imageFile).use { scaledBitmap.compress(compressFormat, 85, it) }
    }
}

fun vibrate() {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    val durationMilis = 20L
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        vibrator.vibrate(
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK),
            VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_MEDIA).build()
        )
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        @Suppress("DEPRECATION")
        vibrator.vibrate(
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        )
    } else
        @Suppress("DEPRECATION")
        vibrator.vibrate(
            VibrationEffect.createOneShot(durationMilis, VibrationEffect.DEFAULT_AMPLITUDE),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        )
}

suspend fun convertAopusToPcm(inputBytes: ByteArray): ByteArray = withContext(IO) {
    val input = PacketReader(inputBytes)
    val outputStream = ByteArrayOutputStream()
    var outputBuf = ByteBuffer.allocate(32768).order(LITTLE_ENDIAN)
    pushThroughDecoder(
        MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, 1),
        { buf -> input.readPacket(buf).let { if (buf.position() > 0) buf.position() else -1 } },
        { buf ->
            downsample(buf, outputBuf).let { newOutputBuf ->
                outputBuf = newOutputBuf
                newOutputBuf.apply { outputStream.write(array(), position(), remaining()) }
            }
        }
    )
    outputStream.toByteArray()
}

suspend fun convertAopusToWav(inputBytes: ByteArray, outputFile: File) = withContext(IO) {
    val input = PacketReader(inputBytes)
    var outputBuf = ByteBuffer.allocate(32768).order(LITTLE_ENDIAN)
    RandomAccessFile(outputFile, "rw").use { raf ->
        raf.setLength(0)
        raf.seek(WAV_HEADER_SIZE.toLong()) // size of WAV header, to be written in the end
        pushThroughDecoder(
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, 1),
            { buf -> input.readPacket(buf).let { if (buf.position() > 0) buf.position() else -1 } },
            { buf ->
                downsample(buf, outputBuf).let { newOutputBuf ->
                    outputBuf = newOutputBuf
                    raf.channel.write(newOutputBuf)
                }
            }
        )
        val headerBuf = buildWavHeader(raf.length(),
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_RAW, 24000, 1))
        raf.seek(0)
        raf.channel.write(headerBuf)
    }
}

private fun downsample(input: ByteBuffer, output: ByteBuffer) : ByteBuffer {
    val neededCapacity = input.remaining() / 2
    val downsampledBuf =
        if (output.capacity() >= neededCapacity) output
        else ByteBuffer.allocate(neededCapacity)
    downsampledBuf.clear()
    val shortBuf = input.asShortBuffer()
    val downsampledShortBuf = downsampledBuf.asShortBuffer()
    while (shortBuf.remaining() >= 2) {
        val sample1 = shortBuf.get()
        val sample2 = shortBuf.get()
        downsampledShortBuf.put(((sample1 + sample2) / 2).toShort())
    }
    if (shortBuf.remaining() > 0) {
        downsampledShortBuf.put(shortBuf.get())
    }
    downsampledBuf.limit(2 * downsampledShortBuf.position())
    return downsampledBuf
}

suspend fun convertToWav(inputPath: String, outputPath: String) = withContext(IO) {
    val extractor = MediaExtractor()
    val raf = RandomAccessFile(outputPath, "rw")
    try {
        raf.setLength(0)
        raf.seek(WAV_HEADER_SIZE.toLong()) // size of WAV header, to be written in the end
        extractor.setDataSource(inputPath)
        val trackIndex = (0..< extractor.trackCount).first {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        extractor.selectTrack(trackIndex)
        val mediaFormat = extractor.getTrackFormat(trackIndex)
        pushThroughDecoder(
            mediaFormat,
            { buf -> extractor.readSampleData(buf, 0).also { if (it >= 0) extractor.advance() } },
            { buf -> raf.channel.write(buf) }
        )
        val headerBuf = buildWavHeader(raf.length(), mediaFormat)
        raf.seek(0)
        raf.channel.write(headerBuf)
    } finally {
        extractor.release()
        raf.close()
    }
}

private suspend fun pushThroughDecoder(
    format: MediaFormat,
    fillInputBuf: suspend (ByteBuffer) -> Int,
    drainOutputBuf: suspend (ByteBuffer) -> Unit
) {
    val bytesPerSample = Short.SIZE_BYTES // assuming 16 bits per sample

    val mimeType = format.getString(MediaFormat.KEY_MIME)!!
    val samplesPerSec = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    val microsPerByte = (1_000_000.0 / bytesPerSample / samplesPerSec / channelCount).toLong()
    Log.i("speech", "Create decoder for $mimeType")
    val bufferInfo = MediaCodec.BufferInfo()
    val codec = MediaCodec.createDecoderByType(mimeType)
    try {
        Log.i("speech", "Configure decoder with $format")
        val outcome = CompletableDeferred<Exception?>()
        codec.setCallback(object : MediaCodec.Callback() {
            private var totalBytesRead = 0
            private var sawInputEos = false
            private val pendingBufferCount = AtomicInteger()

            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                if (sawInputEos) {
                    return
                }
                val inputBuffer = codec.getInputBuffer(index)!!
                val bytesRead = runBlocking { fillInputBuf(inputBuffer) }
                pendingBufferCount.incrementAndGet()
                if (bytesRead >= 0) {
                    codec.queueInputBuffer(index, 0, bytesRead, totalBytesRead * microsPerByte, 0)
                } else {
                    codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    sawInputEos = true
                }
                totalBytesRead += bytesRead
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                val pendingBuffers = pendingBufferCount.decrementAndGet()
                runBlocking { drainOutputBuf(codec.getOutputBuffer(index)!!) }
                codec.releaseOutputBuffer(index, false)
                if (isEos || sawInputEos && pendingBuffers == 0) {
                    outcome.complete(null)
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                outcome.complete(e)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
        })
        codec.configure(format, null, null, 0)
        codec.start()
        outcome.await()?.also { throw it }
        codec.stop()
    } catch (e: Exception) {
        Log.e("speech", "Error while pushing through decoder", e)
    } finally {
        codec.release()
    }
}

private fun buildWavHeader(fileSize: Long, mediaFormat: MediaFormat): ByteBuffer {
    val riffHeaderSize = 8
    val bytesPerSample = 2

    val riffChunkSize = (fileSize - riffHeaderSize).toInt()
    val subChunk1Size = 16 // subchunk 1 size when AudioFormat = PCM
    val audioFormat = 1.toShort() // PCM audio format
    val channelCount = mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).toShort()
    val sampleRate = mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    val byteRate = sampleRate * channelCount * bytesPerSample
    val blockAlign = (channelCount * bytesPerSample).toShort()
    val bitsPerSample = (8 * bytesPerSample).toShort()
    val subChunk2Size = (fileSize - WAV_HEADER_SIZE).toInt()

    return ByteBuffer.allocate(WAV_HEADER_SIZE).order(LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray())
        putInt(riffChunkSize)
        put("WAVEfmt ".toByteArray()) // RIFF chunk, type WAVE; WAVE subchunk 1, type fmt
        putInt(subChunk1Size)
        putShort(audioFormat)
        putShort(channelCount)
        putInt(sampleRate)
        putInt(byteRate)
        putShort(blockAlign)
        putShort(bitsPerSample)
        put("data".toByteArray()) // WAVE subchunk 2, type data
        putInt(subChunk2Size)
        flip()
    }
}

@OptIn(UnstableApi::class)
class ExoplayerWebsocketDsFactory : DataSource.Factory {
    private var byteBuf = ByteBuffer.allocate(65536)

    fun addData(data: ByteArray) {
        synchronized(byteBuf) {
            if (byteBuf.remaining() < data.size) {
                val newSize = byteBuf.capacity() + data.size
                Log.w("speech", "Expanding bytebuf to $newSize bytes")
                byteBuf.flip()
                ByteBuffer.allocate(newSize).also { newBuf ->
                    newBuf.put(byteBuf)
                    byteBuf = newBuf
                }
            }
            byteBuf.put(data)
        }
    }

    fun reset() {
        synchronized(byteBuf) {
            byteBuf.clear()
        }
    }

    override fun createDataSource() = object : DataSource {

        override fun open(dataSpec: DataSpec): Long {
            return C.LENGTH_UNSET.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            synchronized(byteBuf) {
                byteBuf.flip()
                val available = byteBuf.remaining()
                if (available == 0) {
                    byteBuf.clear()
                    return 0
                }
                val bytesToRead = minOf(available, length)
                byteBuf.apply {
                    get(buffer, offset, bytesToRead)
                    compact()
                }
                return bytesToRead
            }
        }
        override fun getUri() = Uri.EMPTY
        override fun close() {}
        override fun addTransferListener(transferListener: TransferListener) {
            Log.i("speech", "addTransferListener() called, will have no effect: $transferListener")
        }
    }
}

@OptIn(UnstableApi::class)
class ExoplayerPcmExtractorsFactory(
    val sampleRate: Int
) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> {
        return arrayOf(object : Extractor {
            private val buffer = ParsableByteArray(4096)
            private lateinit var trackOutput: TrackOutput

            override fun init(output: ExtractorOutput) {
                trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)
                trackOutput.format(Util.getPcmFormat(C.ENCODING_PCM_16BIT, 1, sampleRate))
                output.seekMap(object : SeekMap {
                    override fun isSeekable() = false
                    override fun getDurationUs() = C.TIME_UNSET
                    override fun getSeekPoints(timeUs: Long) = SeekMap.SeekPoints(SeekPoint(0, 0))
                })
                output.endTracks()
            }

            override fun read(input: ExtractorInput, positionHolder: PositionHolder): Int {
                buffer.reset(buffer.data)
                val bytesRead = input.read(buffer.data, 0, buffer.limit())
                if (bytesRead == C.RESULT_END_OF_INPUT) {
                    return Extractor.RESULT_END_OF_INPUT
                }
                if (bytesRead > 0) {
                    buffer.position = 0
                    buffer.setLimit(bytesRead)
                    trackOutput.sampleData(buffer, bytesRead)
                    val timeUs = input.position * 1_000_000L / sampleRate / 2
                    trackOutput.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, bytesRead, 0, null)
                }
                return Extractor.RESULT_CONTINUE
            }

            override fun sniff(input: ExtractorInput) = true
            override fun seek(position: Long, timeUs: Long) {
                Log.i("speech", "Extractor seek $position")
            }

            override fun release() {}
        })
    }
}

@OptIn(UnstableApi::class)
class SpeakDsFactory(
    private val channel: ByteReadChannel
) : DataSource.Factory {
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

suspend fun Fragment.awaitContext(): Context {
    while (true) {
        context?.also {
            return it
        }
        awaitFrame()
    }
}

fun <E> MutableList<E>.removeLastItem(): E {
    if (isEmpty()) {
        throw NoSuchElementException()
    }
    return removeAt(size - 1)
}

enum class MessageType {
    PROMPT, RESPONSE
}

enum class Voice(val itemId: Int) {
    BUILT_IN(R.id.voice_builtin),
    ALLOY(R.id.voice_alloy), ECHO(R.id.voice_echo), FABLE(R.id.voice_fable),
    NOVA(R.id.voice_nova), ONYX(R.id.voice_onyx), SHIMMER(R.id.voice_shimmer);

    companion object {
        val REALTIME_ITEM_IDS = listOf(ALLOY, ECHO, SHIMMER).map { it.itemId }.toSet()
    }
}

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

import android.Manifest.permission
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.VIBRATOR_SERVICE
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.content.res.ColorStateList
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.View.*
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat.checkSelfPermission
import androidx.core.os.ConfigurationCompat
import androidx.core.os.LocaleListCompat
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.navigation.fragment.findNavController
import com.aallam.openai.api.BetaOpenAI
import com.aallam.openai.api.exception.OpenAIAPIException
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.languageid.LanguageIdentifier.UNDETERMINED_LANGUAGE_TAG
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.mtopol.assistant.databinding.FragmentMainBinding
import java.io.File
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.Result.Companion.failure
import kotlin.Result.Companion.success
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.math.log2
import kotlin.math.roundToLong
import kotlin.math.sin

private val quadratic = TimeInterpolator { t ->
    if (t <= 0.5) 2 * t * t
    else (1 - 2 * (1 - t) * (1 - t))
}

class ChatFragmentModel : ViewModel() {
    var binding: FragmentMainBinding? = null
    val chatHistory = mutableListOf<MessageModel>()
    var receiveResponseJob: Job? = null
    var autoscrollEnabled: Boolean = true
    var lastPromptLanguage: String? = null
    var replyEditable: Editable? = null
    lateinit var promptEditable: Editable
}

@OptIn(BetaOpenAI::class)
@SuppressLint("ClickableViewAccessibility")
class ChatFragment : Fragment(), MenuProvider {

    private val punctuationRegex = """(?<=\D\.'?)\s+|(?<=[;!?]'?)\s+|\n+""".toRegex()
    private val whitespaceRegex = """\s+""".toRegex()
    private lateinit var vmodel: ChatFragmentModel
    private lateinit var audioPathname: String
    private lateinit var systemLanguages: List<String>
    private lateinit var languageIdentifier: LanguageIdentifier
    private var pixelDensity = 0f

    private var _mediaRecorder: MediaRecorder? = null
    private var _recordingGlowJob: Job? = null

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it.isNotEmpty()) Log.i("", "User granted us the requested permissions")
        else Log.w("", "User did not grant us the requested permissions")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("lifecycle", "onCreate ChatFragment")
        (requireActivity() as AppCompatActivity).addMenuProvider(this, this)
        vmodel = ViewModelProvider(this)[ChatFragmentModel::class.java].apply {
            addCloseable {
                Log.i("lifecycle", "Destroy ViewModel")
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        Log.i("lifecycle", "onCreateView ChatFragment")
        val binding = FragmentMainBinding.inflate(inflater, container, false).also {
            vmodel.binding = it
        }
        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        val context: Context = activity

        GlobalScope.launch(IO) { openAi.value }

        if (savedInstanceState != null) {
            binding.edittextPrompt.setText(vmodel.promptEditable.toString())
            var newestHistoryEditable: Editable? = null
            var newestHistoryMessage: MessageModel? = null
            for (message in vmodel.chatHistory) {
                newestHistoryMessage = message
                newestHistoryEditable = addMessageView(message)
            }
            if (vmodel.replyEditable != null) {
                newestHistoryEditable!!.also {
                    vmodel.replyEditable = it
                    newestHistoryMessage!!.text = it
                }
            }
        }
        vmodel.promptEditable = binding.edittextPrompt.editableText
        if (vmodel.promptEditable.isNotEmpty()) {
            Log.i("lifecycle", "promptEditable: ${vmodel.promptEditable}")
            switchToTyping(false)
        }
        if (vmodel.receiveResponseJob != null) {
            binding.buttonStopResponding.visibility = VISIBLE
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, insets.bottom)
            windowInsets
        }

        pixelDensity = context.resources.displayMetrics.density
        systemLanguages = run {
            val localeList: LocaleListCompat = ConfigurationCompat.getLocales(context.resources.configuration)
            (0 until localeList.size()).map { localeList.get(it)!!.language }
        }
        languageIdentifier = LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.2f).build()
        )
        audioPathname = File(context.cacheDir, "prompt.mp4").absolutePath

        binding.scrollviewChat.apply {
            setOnScrollChangeListener { view, _, _, _, _ ->
                vmodel.autoscrollEnabled = binding.viewChat.bottom <= view.height + view.scrollY
            }
            viewTreeObserver.addOnGlobalLayoutListener {
                scrollToBottom()
            }
        }
        binding.buttonRecord.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    vibrate(); startRecordingPrompt(); true
                }
                MotionEvent.ACTION_UP -> {
                    if (_mediaRecorder != null) {
                        vibrate()
                        showRecordedPrompt()
                    }
                    true
                }
                else -> false
            }
        }
        binding.buttonKeyboard.onClickWithVibrate { switchToTyping(true) }
        binding.buttonSend.onClickWithVibrate { sendPromptAndReceiveResponse() }
        binding.buttonStopResponding.onClickWithVibrate { vmodel.receiveResponseJob?.cancel() }
        binding.edittextPrompt.apply {
            addTextChangedListener(object : TextWatcher {
                private var hadTextLastTime = text.isNotEmpty()

                override fun afterTextChanged(editable: Editable) {
                    if (editable.isEmpty() && hadTextLastTime) {
                        viewScope.launch {
                            switchToVoice()
                        }
                    }
                    hadTextLastTime = editable.isNotEmpty()
                }

                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.i("lifecycle", "onDestroyView ChatFragment")
        vmodel.binding = null
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        Log.i("lifecycle", "onCreateMenu")
        menuInflater.inflate(R.menu.menu_main, menu)
        val toggleItem = menu.findItem(R.id.action_gpt_toggle)
        val toggleButton = toggleItem.actionView!!.findViewById<TextView>(R.id.textview_gpt_toggle)

        toggleButton.setOnClickListener {
            it.isSelected = !it.isSelected
            toggleButton.text = getString(if (it.isSelected) R.string.gpt_4 else R.string.gpt_3_5)
        }
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_clear_chat_history -> { clearChat(); true }
            R.id.action_delete_openai_key -> {
                requireContext().mainPrefs.applyUpdate {
                    setOpenaiApiKey("")
                    resetOpenAi(requireContext())
                    findNavController().navigate(R.id.fragment_api_key)
                }
                true
            }
            else -> false
        }
    }

    override fun onPause() {
        super.onPause()
        stopRecording()
    }

    private fun sendPromptAndReceiveResponse() {
        val binding = vmodel.binding ?: return
        val prompt = binding.edittextPrompt.text.toString()
        if (prompt.isEmpty()) {
            return
        }
        switchToVoice()
        MessageModel(Role.USER, prompt).also { promptMessage ->
            vmodel.chatHistory.add(promptMessage)
            addMessageView(promptMessage)
        }
        val replyMessage = MessageModel(Role.GPT, "").also { replyMessage ->
            val editable = addMessageView(replyMessage)
            replyMessage.text = editable
            vmodel.replyEditable = editable
            vmodel.chatHistory.add(replyMessage)
        }
        vmodel.autoscrollEnabled = true
        scrollToBottom()
        binding.buttonStopResponding.visibility = VISIBLE
        var lastSpokenPos = 0
        vmodel.receiveResponseJob = vmodel.viewModelScope.launch {
            try {
                val sentenceFlow: Flow<String> = channelFlow {
                    openAi.value.chatCompletions(vmodel.chatHistory, isGpt4Selected())
                        .onEach { chunk ->
                            chunk.choices[0].delta?.content?.also { token ->
                                val replyEditable = vmodel.replyEditable!!
                                replyEditable.append(token)
                                val fullSentences = replyEditable
                                    .substring(lastSpokenPos, replyEditable.length)
                                    .dropLastIncompleteSentence()
                                if (wordCount(fullSentences) >= 3) {
                                    channel.send(fullSentences.trim())
                                    lastSpokenPos += fullSentences.length
                                }
                            }
                            scrollToBottom()
                        }
                        .onCompletion { exception ->
                            val replyEditable = vmodel.replyEditable!!
                            exception?.also {
                                when {
                                    it is CancellationException -> {}
                                    (it.message ?: "").endsWith("does not exist") -> {
                                        replyEditable.append(getString(R.string.gpt4_unavailable))
                                        scrollToBottom()
                                    }
                                    else -> {
                                        Log.e("lifecycle", "Error in chatCompletions flow", exception)
                                        Toast.makeText(
                                            appContext,
                                            "Something went wrong while GPT was talking", Toast.LENGTH_SHORT
                                        )
                                            .show()
                                    }
                                }
                            }
                            if (lastSpokenPos < replyEditable.length) {
                                channel.send(replyEditable.substring(lastSpokenPos, replyEditable.length))
                            }
                            replyMessage.text = replyEditable.toString()
                            vmodel.replyEditable = null
                        }
                        .launchIn(this)
                }
                sentenceFlow.onCompletion { exception ->
                    exception?.also {
                        Log.e("speech", it.message ?: it.toString())
                    }
                }

                val voiceFileFlow: Flow<File> = channelFlow {
                    val tts = newTextToSpeech()
                    var nextUtteranceId = 0L
                    var lastIdentifiedLanguage = UNDETERMINED_LANGUAGE_TAG
                    sentenceFlow
                        .onEach { sentence ->
                            Log.i("speech", "Speak: $sentence")
                            if (!systemLanguages.contains(lastIdentifiedLanguage)) {
                                identifyLanguage(sentence).also {
                                    lastIdentifiedLanguage = it
                                }
                            }
                            tts.setSpokenLanguage(lastIdentifiedLanguage)
                            channel.send(tts.speakToFile(sentence, nextUtteranceId++))
                        }
                        .onCompletion {
                            tts.apply { stop(); shutdown() }
                        }
                        .launchIn(this)
                }

                val mediaPlayer = MediaPlayer()
                var cancelled = false
                voiceFileFlow
                    .onCompletion {
                        mediaPlayer.apply { stop(); release() }
                    }
                    .collect {
                        try {
                            if (!cancelled) {
                                mediaPlayer.play(it)
                            }
                        } catch (e: CancellationException) {
                            cancelled = true
                        } finally {
                            it.delete()
                        }
                    }
            } catch (e: Exception) {
                Log.e("lifecycle", "Error in receiveResponseJob", e)
            } finally {
                vmodel.binding?.buttonStopResponding?.visibility = GONE
                vmodel.receiveResponseJob = null
            }
        }
    }

    private suspend fun MediaPlayer.play(file: File) {
        reset()
        setDataSource(file.absolutePath)
        prepare()
        suspendCancellableCoroutine { continuation ->
            setOnCompletionListener {
                Log.i("speech", "complete playing ${file.name}")
                continuation.resumeWith(success(Unit))
            }
            Log.i("speech", "start playing ${file.name}")
            start()
        }
    }

    private suspend fun newTextToSpeech(): TextToSpeech = suspendCancellableCoroutine { continuation ->
        Log.i("speech", "Create new TextToSpeech")
        val tts = AtomicReference<TextToSpeech?>()
        tts.set(TextToSpeech(requireContext()) { status ->
            Log.i("speech", "TextToSpeech initialized")
            continuation.resumeWith(
                if (status == TextToSpeech.SUCCESS) {
                    success(tts.get()!!)
                } else {
                    failure(Exception("Speech init failed with status code $status"))
                }
            )
        })
    }

    private suspend fun TextToSpeech.speakToFile(sentence: String, utteranceIdNumeric: Long): File {
        val utteranceId = utteranceIdNumeric.toString()
        return suspendCancellableCoroutine { continuation: Continuation<File> ->
            val utteranceFile = File(appContext.cacheDir, "utterance-$utteranceId.wav")
            @Suppress("ThrowableNotThrown")
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {}
                override fun onDone(doneUutteranceId: String) {
                    if (doneUutteranceId != utteranceId) {
                        Log.e("speech", "unexpected utteranceId in onDone: $doneUutteranceId != $utteranceId")
                    }
                    continuation.resumeWith(success(utteranceFile))
                }
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    continuation.resumeWith(failure(CancellationException()))
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    Log.e("speech", "Error while speaking, error code: $errorCode")
                    continuation.resumeWith(failure(Exception("TextToSpeech error code $errorCode")))
                }
                @Deprecated("", ReplaceWith("Can't replace, it's an abstract method!"))
                override fun onError(utteranceId: String) {
                    onError(utteranceId, TextToSpeech.ERROR)
                }
            })
            synthesizeToFile(sentence, Bundle(), utteranceFile, utteranceId)
        }
    }

    private fun startRecordingPrompt() {
        val context = requireActivity()
        // don't extract to fun, IDE inspection for permission checks will complain
        if (checkSelfPermission(context, permission.RECORD_AUDIO) != PERMISSION_GRANTED) {
            permissionRequest.launch(arrayOf(permission.RECORD_AUDIO, permission.WRITE_EXTERNAL_STORAGE))
            return
        }
        try {
            val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(requireContext())
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.also {
                _mediaRecorder = it
            }
            mediaRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(audioPathname)
                prepare()
                start()
            }
            animateRecordingGlow()
        } catch (e: Exception) {
            Log.e("speech", "Voice recording error", e)
            Toast.makeText(
                requireContext(),
                "Something went wrong while we were recording your voice",
                Toast.LENGTH_SHORT
            ).show()
            removeRecordingGlow()
            lifecycleScope.launch {
                withContext(IO) {
                    stopRecording()
                }
                vmodel.binding?.buttonRecord?.setActive(true)
            }
        }
    }

    private fun showRecordedPrompt() {
        val binding = vmodel.binding ?: return
        binding.buttonRecord.setActive(false)
        viewScope.launch {
            try {
                val recordingSuccess = withContext(IO) { stopRecording() }
                if (!recordingSuccess) {
                    return@launch
                }
                val transcription = openAi.value.getTranscription(audioPathname)
                if (transcription.text.isEmpty()) {
                    return@launch
                }
                binding.edittextPrompt.editableText.apply {
                    clear()
                    append(transcription.text)
                    Log.i("speech", "transcription.language: ${transcription.language}")
                    vmodel.lastPromptLanguage = transcription.language
                }
                switchToTyping(false)
            } catch (e: Exception) {
                Log.e("speech", "Text-to-speech error", e)
                if (e is OpenAIAPIException) {
                    Toast.makeText(
                        requireContext(),
                        if (e.statusCode == 401) "Invalid OpenAI API key. Delete it and enter a new one."
                        else "OpenAI error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        "Something went wrong while OpenAI was listening to you",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                binding.buttonRecord.setActive(true)
                removeRecordingGlow()
            }
        }
    }

    private fun stopRecording(): Boolean {
        val mediaRecorder = _mediaRecorder ?: return false
        _mediaRecorder = null
        return try {
            mediaRecorder.stop(); true
        } catch (e: Exception) {
            File(audioPathname).delete(); false
        } finally {
            mediaRecorder.release()
        }
    }

    private fun ImageButton.onClickWithVibrate(pointerUpAction: () -> Unit) {
        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { vibrate(); true }
                MotionEvent.ACTION_UP -> { pointerUpAction(); true }
                else -> false
            }
        }
    }

    private fun ImageButton.setActive(newActive: Boolean) {
        imageAlpha = if (newActive) 255 else 128
        isEnabled = newActive
    }

    private fun switchToTyping(bringUpKeyboard: Boolean) {
        val binding = vmodel.binding ?: return
        binding.buttonKeyboard.visibility = GONE
        binding.buttonRecord.visibility = GONE
        binding.buttonSend.visibility = VISIBLE
        binding.edittextPrompt.apply {
            visibility = VISIBLE
            requestFocus()
        }
        if (bringUpKeyboard) {
            (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(binding.edittextPrompt, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun switchToVoice() {
        val binding = vmodel.binding ?: return
        binding.edittextPrompt.editableText.clear()
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.root.windowToken, 0)
        viewScope.launch {
            delay(100)
            binding.buttonKeyboard.visibility = VISIBLE
            binding.buttonRecord.visibility = VISIBLE
            binding.buttonSend.visibility = GONE
            binding.edittextPrompt.apply {
                visibility = GONE
                clearFocus()
            }
        }
    }

    @SuppressLint("Recycle")
    private fun animateRecordingGlow() {
        val binding = vmodel.binding ?: return
        _recordingGlowJob = viewScope.launch {
            binding.recordingGlow.apply {
                alignWithView(binding.buttonRecord)
                visibility = VISIBLE
            }

            fun nanosToSeconds(nanos: Long): Float = nanos.toFloat() / 1_000_000_000

            try {
                val recordingStart = System.nanoTime()
                var lastPeak = 0f
                var lastPeakTime = 0L
                var lastRecordingVolume = 0f
                while (true) {
                    val frameTime = awaitFrame()
                    val mediaRecorder = _mediaRecorder ?: break
                    val initialGrowthCap = (1.5f * nanosToSeconds(frameTime - recordingStart)).coerceAtMost(1f)
                    val soundVolume = (log2(mediaRecorder.maxAmplitude.toDouble()) / 15).toFloat()
                        .coerceAtLeast(0f).coerceAtMost(initialGrowthCap)
                    val decayingPeak = lastPeak * (1f - 2 * nanosToSeconds(frameTime - lastPeakTime))
                    lastRecordingVolume = if (decayingPeak > soundVolume) {
                        decayingPeak
                    } else {
                        lastPeak = soundVolume
                        lastPeakTime = frameTime
                        soundVolume
                    }
                    binding.recordingGlow.setVolume(lastRecordingVolume)
                }

                fun waitingVolume(time: Long) = (3.5f + 1.5f * sin(4 * nanosToSeconds(time))) / 20

                val targetVolume = waitingVolume(0)
                ValueAnimator.ofFloat(lastRecordingVolume, targetVolume).apply {
                    duration = 500
                    interpolator = quadratic
                    addUpdateListener { anim ->
                        binding.recordingGlow.setVolume(anim.animatedValue as Float)
                    }
                    run()
                }
                val waitingStart = System.nanoTime()
                while (true) {
                    val frameTime = awaitFrame()
                    binding.recordingGlow.setVolume(waitingVolume(frameTime - waitingStart))
                }
            } finally {
                binding.recordingGlow.visibility = INVISIBLE
            }
        }
    }

    private fun removeRecordingGlow() {
        _recordingGlowJob?.cancel()
        _recordingGlowJob = null
    }

    private suspend fun ValueAnimator.run() {
        suspendCancellableCoroutine { cont ->
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    cont.resume(Unit)
                }
            })
            start()
        }
    }

    private fun addMessageView(message: MessageModel): Editable {
        val context = requireContext()
        val chatView = vmodel.binding!!.viewChat
        val messageView = LayoutInflater.from(requireContext())
            .inflate(R.layout.chat_message_item, chatView, false) as TextView
        messageView.setTextColor(
            when (message.author) {
                Role.USER -> context.getColorCompat(R.color.user_text_foreground)
                Role.GPT -> context.getColorCompat(R.color.gpt_text_foreground)
            }
        )
        messageView.backgroundTintList = ColorStateList.valueOf(
            when (message.author) {
                Role.USER -> context.getColorCompat(R.color.user_text_background)
                Role.GPT -> context.getColorCompat(R.color.gpt_text_background)
            }
        )
        messageView.text = message.text
        chatView.addView(messageView)
        return messageView.editableText
    }

    private fun clearChat() {
        Log.i("lifecycle", "clearChat")
        val binding = vmodel.binding ?: return
        Log.i("lifecycle", "clearChat: binding is not null")
        vmodel.receiveResponseJob?.cancel()
        binding.viewChat.removeAllViews()
        vmodel.chatHistory.clear()
        binding.edittextPrompt.editableText.clear()
    }

    private fun scrollToBottom() {
        val binding = vmodel.binding ?: return
        binding.scrollviewChat.post {
            if (!vmodel.autoscrollEnabled || !binding.scrollviewChat.canScrollVertically(1)) {
                return@post
            }
            binding.appbarLayout.setExpanded(false, true)
            binding.scrollviewChat.smoothScrollTo(0, binding.scrollviewChat.getChildAt(0).bottom)
        }
    }

    private suspend fun identifyLanguage(text: String): String {
        val languagesWithConfidence = languageIdentifier.identifyPossibleLanguages(text).await()
        val diagnosticFormat = languagesWithConfidence.joinToString {
            "${it.languageTag} ${(it.confidence * 100).roundToLong()}"
        }
        Log.i("speech", "Identified languages: $diagnosticFormat")
        val languages = languagesWithConfidence.map { it.languageTag }
        val chosenLanguage = languages.firstOrNull { systemLanguages.contains(it) }
        return when {
            chosenLanguage != null -> chosenLanguage
            wordCount(text) < 2 -> vmodel.lastPromptLanguage ?: systemLanguages.first()
            else -> languages.first()
        }.also {
            Log.i("speech", "Chosen language: $it")
        }
    }

    private fun vibrate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator().vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
        } else {
            @Suppress("DEPRECATION")
            vibrator().vibrate(20)
        }
    }

    private fun vibrator(): Vibrator {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (requireContext().getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            requireContext().getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        return vibrator
    }

    private fun TextToSpeech.setSpokenLanguage(tag: String) {
        when (setLanguage(Locale.forLanguageTag(tag))) {
            TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> {
                Log.i("", "Language not supported for text-to-speech: $tag")
                language = Locale.forLanguageTag("hr")
            }
        }
    }

    private val viewScope get() = viewLifecycleOwner.lifecycleScope

    private fun wordCount(sentence: String) = sentence.split(whitespaceRegex).size

    private fun String.dropLastIncompleteSentence(): String {
        val lastMatch = punctuationRegex.findAll(this).lastOrNull() ?: return ""
        return substring(0, lastMatch.range.last + 1)
    }

    private fun isGpt4Selected(): Boolean {
        return (vmodel.binding ?: return false).toolbar.menu.findItem(R.id.action_gpt_toggle).actionView!!
            .findViewById<TextView>(R.id.textview_gpt_toggle).isSelected
    }
}

data class MessageModel(
    val author: Role,
    var text: CharSequence
)

enum class Role {
    USER, GPT
}

data class Transcription(
    val text: String,
    val language: String?
)

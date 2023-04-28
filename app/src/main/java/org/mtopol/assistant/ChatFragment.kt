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
import android.os.Parcelable
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
import android.view.animation.LinearInterpolator
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
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.navigation.fragment.findNavController
import com.aallam.openai.api.BetaOpenAI
import com.aallam.openai.api.exception.OpenAIAPIException
import com.google.mlkit.nl.languageid.IdentifiedLanguage
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.languageid.LanguageIdentifier.UNDETERMINED_LANGUAGE_TAG
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import org.mtopol.assistant.databinding.FragmentChatBinding
import java.io.File
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.Result.Companion.failure
import kotlin.Result.Companion.success
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.math.log2
import kotlin.math.roundToLong

private const val KEY_CHAT_HISTORY = "chat_history"
private const val KEY_IS_MUTED = "is_muted"
private const val KEY_IS_GPT4 = "is_gpt4"

private const val MAX_RECORDING_TIME_MILLIS = 60_000L

class ChatFragmentModel(
    savedState: SavedStateHandle
) : ViewModel() {
    val withFragmentLiveData = MutableLiveData<(ChatFragment) -> Unit>()

    fun withFragment(task: (ChatFragment) -> Unit) {
        withFragmentLiveData.value = task
    }

    private val _isMutedLiveData = savedState.getLiveData(KEY_IS_MUTED, false)
    var isMuted: Boolean
        get() = _isMutedLiveData.value!!
        set(value) { _isMutedLiveData.value = value }

    private val _isGpt4LiveData = savedState.getLiveData(KEY_IS_GPT4, false)
    var isGpt4: Boolean
        get() = _isGpt4LiveData.value!!
        set(value) { _isGpt4LiveData.value = value }

    val chatHistory = savedState.getLiveData<MutableList<PromptAndResponse>>(KEY_CHAT_HISTORY, mutableListOf()).value!!
    var handleResponseJob: Job? = null
    var recordingGlowJob: Job? = null
    var autoscrollEnabled: Boolean = true
    var replyEditable: Editable? = null
    var mediaPlayer: MediaPlayer? = null

    override fun onCleared() {
        Log.i("lifecycle", "Destroy ViewModel")
    }
}

@OptIn(BetaOpenAI::class)
@SuppressLint("ClickableViewAccessibility")
class ChatFragment : Fragment(), MenuProvider {

    @Suppress("RegExpUnnecessaryNonCapturingGroup")
    private val punctuationRegex = """(?<=\D[.!]'?)\s+|(?<=\d[.!]'?)\s+(?=\p{Lu})|(?<=.[;?]'?)\s+|\n+""".toRegex()
    private val whitespaceRegex = """\s+""".toRegex()
    private val vmodel: ChatFragmentModel by viewModels()
    private lateinit var binding: FragmentChatBinding
    private lateinit var audioPathname: String
    private lateinit var systemLanguages: List<String>
    private lateinit var languageIdentifier: LanguageIdentifier
    private var pixelDensity = 0f

    private var _mediaRecorder: MediaRecorder? = null

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it.isNotEmpty()) Log.i("", "User granted us the requested permissions")
        else Log.w("", "User did not grant us the requested permissions")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("lifecycle", "onCreate ChatFragment")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i("lifecycle", "onDestroy ChatFragment")
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        Log.i("lifecycle", "onCreateView ChatFragment")
        binding = FragmentChatBinding.inflate(inflater, container, false)
        vmodel.withFragmentLiveData.observe(viewLifecycleOwner) { it.invoke(this) }
        val context: Context = (requireActivity() as AppCompatActivity).also { activity ->
            activity.addMenuProvider(this, viewLifecycleOwner)
            activity.setSupportActionBar(binding.toolbar)
            activity.supportActionBar?.apply {
                setDisplayShowTitleEnabled(false)
            }
        }

        GlobalScope.launch(IO) { openAi.value }

        if (savedInstanceState != null) {
            var newestHistoryEditable: Editable? = null
            var newestHistoryMessagePair: PromptAndResponse? = null
            for (promptAndResponse in vmodel.chatHistory) {
                newestHistoryMessagePair = promptAndResponse
                newestHistoryEditable = addPromptAndResponseToView(promptAndResponse)
            }
            if (vmodel.replyEditable != null) {
                newestHistoryEditable!!.also {
                    vmodel.replyEditable = it
                    newestHistoryMessagePair!!.response = it
                }
            }
        }
        // Reduce the size of the scrollview when soft keyboard shown
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
        binding.root.doOnLayout {
            if (vmodel.recordingGlowJob != null) {
                binding.showRecordingGlow()
            }
        }

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
        binding.buttonKeyboard.onClickWithVibrate {
            switchToTyping()
            (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(binding.edittextPrompt, InputMethodManager.SHOW_IMPLICIT)
        }
        binding.buttonSend.onClickWithVibrate {
            val prompt = binding.edittextPrompt.text.toString()
            if (prompt.isNotEmpty()) {
                binding.edittextPrompt.editableText.clear()
                sendPromptAndReceiveResponse(prompt)
            }
        }
        binding.edittextPrompt.apply {
            addTextChangedListener(object : TextWatcher {
                private var hadTextLastTime = text.isNotEmpty()

                override fun afterTextChanged(editable: Editable) {
                    if (editable.isEmpty() && hadTextLastTime) {
                        switchToVoice()
                    } else if (editable.isNotEmpty() && !hadTextLastTime) {
                        switchToTyping()
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
        (requireActivity() as AppCompatActivity).removeMenuProvider(this)
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        Log.i("lifecycle", "onCreateMenu")
        menu.clear()
        menuInflater.inflate(R.menu.menu_main, menu)
        updateMuteItem(menu.findItem(R.id.action_sound_toggle))

        fun TextView.updateText() {
            text = getString(if (vmodel.isGpt4) R.string.gpt_4 else R.string.gpt_3_5)
        }
        if (requireContext().mainPrefs.openaiApiKey.isGpt3OnlyKey()) {
            vmodel.isGpt4 = false
        } else {
            menu.findItem(R.id.action_gpt_toggle).apply {
                isVisible = true
            }.actionView!!.findViewById<TextView>(R.id.textview_gpt_toggle).apply {
                updateText()
                setOnClickListener {
                    vmodel.isGpt4 = !vmodel.isGpt4
                    updateText()
                }
            }
        }
    }

    override fun onPrepareMenu(menu: Menu) {
        Log.i("lifecycle", "onPrepareMenu")
        val responding = vmodel.handleResponseJob != null
        val hasHistory = vmodel.chatHistory.isNotEmpty()
        menu.findItem(R.id.action_cancel).isVisible = responding
        menu.findItem(R.id.action_undo).isVisible = !responding
        menu.findItem(R.id.action_speak_again).isEnabled = !vmodel.isMuted && !responding && hasHistory
    }

    private fun updateMuteItem(item: MenuItem) {
        item.isChecked = vmodel.isMuted
        item.setIcon(if (vmodel.isMuted) R.drawable.baseline_volume_off_24 else R.drawable.baseline_volume_up_24)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_speak_again -> {
                val previousResponseJob = vmodel.handleResponseJob?.apply { cancel() }
                vmodel.handleResponseJob = vmodel.viewModelScope.launch {
                    try {
                        previousResponseJob?.join()
                        speakLastResponse()

                    } finally {
                        vmodel.handleResponseJob = null
                        vmodel.withFragment { it.activity?.invalidateOptionsMenu() }                    }
                }
                activity?.invalidateOptionsMenu()
                true
            }
            R.id.action_sound_toggle -> {
                vmodel.isMuted = !vmodel.isMuted
                updateMuteItem(item)
                updateMediaPlayerVolume()
                activity?.invalidateOptionsMenu()
                true
            }
            R.id.action_cancel -> {
                vmodel.handleResponseJob?.cancel()
                true
            }
            R.id.action_undo -> {
                viewScope.launch { undoLastPrompt() }
                true
            }
            R.id.action_clear_chat_history -> {
                clearChat()
                true
            }
            R.id.action_about -> {
                showAboutDialogFragment(requireActivity())
                true
            }
            R.id.action_edit_system_prompt -> {
                findNavController().navigate(R.id.fragment_system_prompt)
                true
            }
            R.id.action_delete_openai_key -> {
                requireContext().mainPrefs.applyUpdate {
                    setOpenaiApiKey("")
                }
                resetOpenAi(requireContext())
                findNavController().navigate(R.id.fragment_api_key)
                true
            }
            else -> false
        }
    }

    override fun onResume() {
        super.onResume()
        Log.i("lifecycle", "onResume")
    }

    override fun onPause() {
        super.onPause()
        Log.i("lifecycle", "onPause")
        runBlocking { stopRecording() }
        stopRecordingGlowAnimation()
    }

    private suspend fun undoLastPrompt() {
        if (vmodel.chatHistory.isEmpty()) {
            return
        }
        vmodel.handleResponseJob?.apply {
            cancel()
            join()
        }
        binding.viewChat.apply {
            repeat(2) { removeViewAt(childCount - 1) }
        }
        val prompt = vmodel.chatHistory.removeLast().prompt
        binding.edittextPrompt.editableText.apply {
            replace(0, length, prompt)
        }
    }

    private fun sendPromptAndReceiveResponse(prompt: CharSequence) {
        var lastSpokenPos = 0
        val previousResponseJob = vmodel.handleResponseJob?.apply { cancel() }
        vmodel.handleResponseJob = vmodel.viewModelScope.launch {
            try {
                previousResponseJob?.join()
                val promptAndResponse = PromptAndResponse(prompt, "")
                vmodel.chatHistory.add(promptAndResponse)
                val editable = addPromptAndResponseToView(promptAndResponse)
                promptAndResponse.response = editable
                vmodel.replyEditable = editable
                vmodel.autoscrollEnabled = true
                scrollToBottom()
                val sentenceFlow: Flow<String> = channelFlow {
                    openAi.value.chatCompletions(vmodel.chatHistory, vmodel.isGpt4)
                        .onEach { token ->
                            val replyEditable = vmodel.replyEditable!!
                            replyEditable.append(token)
                            val fullSentences = replyEditable
                                .substring(lastSpokenPos, replyEditable.length)
                                .dropLastIncompleteSentence()
                            fullSentences.takeIf { it.isNotBlank() }?.also {
                                Log.i("speech", "full sentences: $it")
                            }
                            if (wordCount(fullSentences) >= 3) {
                                channel.send(fullSentences)
                                lastSpokenPos += fullSentences.length
                            }
                            scrollToBottom()
                        }
                        .onCompletion { exception ->
                            val replyEditable = vmodel.replyEditable!!
                            exception?.also { e ->
                                when (e) {
                                    is CancellationException -> {}
                                    is OpenAIAPIException -> {
                                        Log.e("lifecycle", "OpenAI error in chatCompletions flow", e)
                                        if ((e.message ?: "").endsWith("does not exist")) {
                                            Toast.makeText(appContext,
                                                getString(R.string.gpt4_unavailable), Toast.LENGTH_SHORT)
                                                .show()
                                        } else {
                                            showApiErrorToast(e)
                                        }
                                    }
                                    else -> {
                                        Log.e("lifecycle", "Error in chatCompletions flow", e)
                                        Toast.makeText(appContext,
                                            getString(R.string.error_while_gpt_talking), Toast.LENGTH_SHORT)
                                            .show()
                                    }
                                }
                            }
                            if (lastSpokenPos < replyEditable.length) {
                                channel.send(replyEditable.substring(lastSpokenPos, replyEditable.length))
                            }
                            promptAndResponse.response = replyEditable.toString()
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
                    var previousLanguageTag = UNDETERMINED_LANGUAGE_TAG
                    sentenceFlow
                        .onEach { sentence ->
                            Log.i("speech", "Speak: $sentence")
                            previousLanguageTag = identifyLanguage(sentence, previousLanguageTag)
                            tts.setSpokenLanguage(previousLanguageTag)
                            channel.send(tts.speakToFile(sentence, nextUtteranceId++))
                        }
                        .onCompletion {
                            tts.apply { stop(); shutdown() }
                        }
                        .launchIn(this)
                }

                val mediaPlayer = MediaPlayer().also {
                    vmodel.mediaPlayer = it
                    updateMediaPlayerVolume()
                }
                var cancelled = false
                voiceFileFlow
                    .onCompletion {
                        mediaPlayer.apply {
                            stop()
                            release()
                            vmodel.mediaPlayer = null
                        }
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("lifecycle", "Error in receiveResponseJob", e)
            } finally {
                vmodel.handleResponseJob = null
                vmodel.withFragment { it.activity?.invalidateOptionsMenu() }
            }
        }
        activity?.invalidateOptionsMenu()
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
            setOnUtteranceProgressListener(UtteranceContinuationListener(utteranceId, continuation, utteranceFile))
            if (synthesizeToFile(sentence, Bundle(), utteranceFile, utteranceId) == TextToSpeech.ERROR) {
                continuation.resumeWith(failure(Exception("synthesizeToFile() failed to enqueue its request")))
            }
        }
    }

    private suspend fun speakLastResponse() {
        val response = vmodel.chatHistory.lastOrNull()?.response?.toString() ?: return
        val utteranceId = "speak_again"
        val tts = newTextToSpeech()
        try {
            tts.setSpokenLanguage(identifyLanguage(response, UNDETERMINED_LANGUAGE_TAG))
            suspendCancellableCoroutine { continuation ->
                tts.setOnUtteranceProgressListener(UtteranceContinuationListener(utteranceId, continuation, Unit))
                if (tts.speak(response, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.ERROR) {
                    continuation.resumeWith(failure(Exception("speak() failed to enqueue its request")))
                }
            }
        } finally {
            tts.apply { stop(); shutdown() }
        }
    }

    private fun startRecordingPrompt() {
        val activity = requireActivity() as MainActivity

        // don't extract to fun, IDE inspection for permission checks will complain
        if (checkSelfPermission(activity, permission.RECORD_AUDIO) != PERMISSION_GRANTED) {
            permissionRequest.launch(arrayOf(permission.RECORD_AUDIO, permission.WRITE_EXTERNAL_STORAGE))
            return
        }
        activity.lockOrientation()
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
            stopRecordingGlowAnimation()
            lifecycleScope.launch {
                stopRecording()
                vmodel.withFragment { it.binding.buttonRecord.setActive(true) }
            }
        } finally {
            activity.unlockOrientation()
        }
    }

    private suspend fun stopRecording(): Boolean {
        Log.i("recording", "stopRecording()")
        val mediaRecorder = _mediaRecorder ?: return false
        _mediaRecorder = null
        return withContext(IO) {
            try {
                mediaRecorder.stop(); true
            } catch (e: Exception) {
                File(audioPathname).delete(); false
            } finally {
                mediaRecorder.release()
            }
        }
    }

    @SuppressLint("Recycle")
    private fun animateRecordingGlow() {
        vmodel.recordingGlowJob = vmodel.viewModelScope.launch {
            vmodel.withFragment { it.binding.showRecordingGlow() }
            launch {
                delay(MAX_RECORDING_TIME_MILLIS)
                showRecordedPrompt()
            }
            try {
                var lastPeak = 0f
                var lastPeakTime = 0L
                var lastRecordingVolume = 0f
                while (true) {
                    val frameTime = awaitFrame()
                    val mediaRecorder = _mediaRecorder ?: break
                    val soundVolume = (log2(mediaRecorder.maxAmplitude.toDouble()) / 15).toFloat().coerceAtLeast(0f)
                    val decayingPeak = lastPeak * (1f - 2 * nanosToSeconds(frameTime - lastPeakTime))
                    lastRecordingVolume = if (decayingPeak > soundVolume) {
                        decayingPeak
                    } else {
                        lastPeak = soundVolume
                        lastPeakTime = frameTime
                        soundVolume
                    }
                    vmodel.withFragment { it.binding.recordingGlow.setVolume(lastRecordingVolume) }
                }

                fun ValueAnimator.connectWithGlow() {
                    addUpdateListener { anim ->
                        vmodel.withFragment { it.binding.recordingGlow.setVolume(anim.animatedValue as Float) }
                    }
                }
                val low = 0.125f
                val high = 0.25f
                ValueAnimator.ofFloat(lastRecordingVolume, high).apply {
                    duration = 300
                    interpolator = LinearInterpolator()
                    connectWithGlow()
                    run()
                }
                while (true) {
                    ValueAnimator.ofFloat(high, low).apply {
                        duration = 600
                        interpolator = LinearInterpolator()
                        connectWithGlow()
                        run()
                    }
                    ValueAnimator.ofFloat(low, high).apply {
                        duration = 300
                        interpolator = android.view.animation.DecelerateInterpolator()
                        connectWithGlow()
                        run()
                    }
                }
            } finally {
                vmodel.withFragment { it.binding.recordingGlow.visibility = INVISIBLE }
            }
        }
    }

    private fun showRecordedPrompt() {
        binding.buttonRecord.setActive(false)
        vmodel.viewModelScope.launch {
            try {
                val recordingSuccess = stopRecording()
                if (!recordingSuccess) {
                    return@launch
                }
                val promptContext = appContext.mainPrefs.systemPrompt + " " +
                        vmodel.chatHistory.joinToString(" ") { it.prompt.toString() }
                val transcription = openAi.value.getTranscription(promptContext, audioPathname)
                if (transcription.isEmpty()) {
                    return@launch
                }
                vmodel.withFragment {
                    it.binding.edittextPrompt.editableText.apply {
                        replace(0, length, transcription)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("speech", "Text-to-speech error", e)
                vmodel.withFragment {
                    if (e is OpenAIAPIException) {
                        showApiErrorToast(e)
                    } else {
                        Toast.makeText(
                            it.activity,
                            "Something went wrong while OpenAI was listening to you",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } finally {
                vmodel.withFragment { it.binding.buttonRecord.setActive(true) }
                stopRecordingGlowAnimation()
            }
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

    private fun switchToTyping() {
        binding.apply {
            buttonKeyboard.visibility = GONE
            buttonRecord.visibility = GONE
            buttonSend.visibility = VISIBLE
            edittextPrompt.apply {
                visibility = VISIBLE
                requestFocus()
            }
        }
    }

    private fun switchToVoice() {
        binding.edittextPrompt.editableText.clear()
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.root.windowToken, 0)
        viewScope.launch {
            delay(100)
            binding.apply {
                buttonKeyboard.visibility = VISIBLE
                buttonRecord.visibility = VISIBLE
                buttonSend.visibility = GONE
                edittextPrompt.apply {
                    visibility = GONE
                    clearFocus()
                }
            }
        }
    }

    private fun FragmentChatBinding.showRecordingGlow() {
        recordingGlow.apply {
            alignWithView(buttonRecord)
            visibility = VISIBLE
        }
    }

    private fun stopRecordingGlowAnimation() {
        vmodel.apply {
            recordingGlowJob?.cancel()
            recordingGlowJob = null
        }
    }

    private suspend fun ValueAnimator.run() {
        withContext(Main) {
            suspendCancellableCoroutine { cont ->
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: Animator) {
                        cont.resume(Unit)
                    }
                })
                cont.invokeOnCancellation {
                    cancel()
                }
                start()
            }
        }
    }

    private fun addPromptAndResponseToView(promptAndResponse: PromptAndResponse): Editable {
        val context = requireContext()
        val chatView = binding.viewChat

        fun addMessageView(textColor: Int, backgroundColor: Int, text: CharSequence): Editable {
            val messageView = LayoutInflater.from(requireContext())
                .inflate(R.layout.chat_message_item, chatView, false) as TextView
            messageView.setTextColor(context.getColorCompat(textColor))
            messageView.backgroundTintList = ColorStateList.valueOf(context.getColorCompat(backgroundColor))
            messageView.text = text
            chatView.addView(messageView)
            return messageView.editableText
        }

        addMessageView(R.color.user_text_foreground, R.color.user_text_background, promptAndResponse.prompt)
        return addMessageView(R.color.gpt_text_foreground, R.color.gpt_text_background, promptAndResponse.response)
    }

    private fun clearChat() {
        Log.i("lifecycle", "clearChat")
        vmodel.handleResponseJob?.cancel()
        if (vmodel.chatHistory.isNotEmpty()) {
            binding.viewChat.removeAllViews()
            vmodel.chatHistory.clear()
        }
        binding.edittextPrompt.editableText.clear()
        activity?.invalidateOptionsMenu()
    }

    private fun scrollToBottom() {
        binding.scrollviewChat.post {
            if (!vmodel.autoscrollEnabled || !binding.scrollviewChat.canScrollVertically(1)) {
                return@post
            }
            binding.appbarLayout.setExpanded(false, true)
            binding.scrollviewChat.smoothScrollTo(0, binding.scrollviewChat.getChildAt(0).bottom)
        }
    }

    private suspend fun identifyLanguage(text: String, previousLanguageTag: String): String {
        val languagesWithConfidence = languageIdentifier.identifyPossibleLanguages(text).await()
        val languagesWithAdjustedConfidence = languagesWithConfidence
            .map { lang ->
                if (systemLanguages.contains(lang.languageTag)) {
                    IdentifiedLanguage(lang.languageTag, (lang.confidence + 0.2f))
                } else lang
            }
            .sortedByDescending { it.confidence }
        run {
            val unadjusted = languagesWithConfidence.joinToString {
                "${it.languageTag} ${(it.confidence * 100).roundToLong()}"
            }
            val adjusted = languagesWithAdjustedConfidence.joinToString {
                "${it.languageTag} ${(it.confidence * 100).roundToLong()}"
            }
            Log.i("speech", "Identified languages: $unadjusted, adjusted: $adjusted")
        }
        val topLang = languagesWithAdjustedConfidence.first()
        val langTags = languagesWithAdjustedConfidence.map { it.languageTag }
        return when {
            topLang.languageTag != UNDETERMINED_LANGUAGE_TAG && topLang.confidence >= 0.8 -> langTags.first()
            else ->
                previousLanguageTag.takeIf { it != UNDETERMINED_LANGUAGE_TAG }
                    ?: langTags.firstOrNull { systemLanguages.contains(it) }
                    ?: systemLanguages.first()
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

    private fun updateMediaPlayerVolume() {
        val volume = if (vmodel.isMuted) 0f else 1f
        vmodel.mediaPlayer?.setVolume(volume, volume)
    }

    private fun showApiErrorToast(e: OpenAIAPIException) {
        Toast.makeText(
            requireActivity(),
            if (e.statusCode == 401) getString(R.string.message_incorrect_api_key)
            else "OpenAI error: ${e.message}",
            Toast.LENGTH_LONG
        ).show()
    }

    private val viewScope get() = viewLifecycleOwner.lifecycleScope

    private fun wordCount(text: String) = text.trim().split(whitespaceRegex).size

    private fun String.dropLastIncompleteSentence(): String {
        val lastMatch = punctuationRegex.findAll(this).lastOrNull() ?: return ""
        return substring(0, lastMatch.range.last + 1)
    }
}

class UtteranceContinuationListener<T>(
    private val utteranceId: String,
    private val continuation: Continuation<T>,
    private val successValue: T
) : UtteranceProgressListener() {
    override fun onStart(utteranceId: String) {}

    override fun onDone(doneUutteranceId: String) {
        if (doneUutteranceId != utteranceId) {
            Log.e("speech", "unexpected utteranceId in onDone: $doneUutteranceId != $utteranceId")
        }
        continuation.resumeWith(success(successValue))
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
}

private fun nanosToSeconds(nanos: Long): Float = nanos.toFloat() / 1_000_000_000

@Parcelize
data class PromptAndResponse(
    var prompt: CharSequence,
    var response: CharSequence
) : Parcelable

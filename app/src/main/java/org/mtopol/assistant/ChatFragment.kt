package org.mtopol.assistant

import android.Manifest.permission
import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.VIBRATOR_SERVICE
import android.content.pm.PackageManager.PERMISSION_GRANTED
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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat.checkSelfPermission
import androidx.core.os.ConfigurationCompat
import androidx.core.os.LocaleListCompat
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.aallam.openai.api.BetaOpenAI
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.languageid.LanguageIdentifier.UNDETERMINED_LANGUAGE_TAG
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
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
import kotlin.math.log2
import kotlin.math.sin

@OptIn(BetaOpenAI::class)
@SuppressLint("ClickableViewAccessibility")
class ChatFragment : Fragment(), MenuProvider {

    private val messages = mutableListOf<MessageModel>()
    private val punctuationRegex = """\D\.|[;!?\n]""".toRegex()
    private lateinit var audioPathname: String
    private lateinit var systemLanguages: List<String>
    private lateinit var languageIdentifier: LanguageIdentifier
    private var pixelDensity = 0f

    private var _binding: FragmentMainBinding? = null
    private var _mediaRecorder: MediaRecorder? = null
    private var _recordingGlowJob: Job? = null
    private var _receiveResponseJob: Job? = null
    private var _autoscrollEnabled: Boolean = true

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it.isNotEmpty()) Log.i("", "User granted us the requested permissions")
        else Log.w("", "User did not grant us the requested permissions")
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val binding = FragmentMainBinding.inflate(inflater, container, false).also {
            _binding = it
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, insets.bottom)
            windowInsets
        }
        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.addMenuProvider(this, viewLifecycleOwner)
        val context: Context = activity

        pixelDensity = context.resources.displayMetrics.density
        systemLanguages = run {
            val localeList: LocaleListCompat = ConfigurationCompat.getLocales(context.resources.configuration)
            (0 until localeList.size()).map { localeList.get(it)!!.language }
        }
        languageIdentifier = LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.2f).build()
        )
        audioPathname = File(context.cacheDir, "prompt.mp4").absolutePath

        if (binding.edittextPrompt.text.isEmpty()) {
            switchToVoice()
        }
        binding.scrollviewChat.apply {
            setOnScrollChangeListener { view, _, _, _, _ ->
                _autoscrollEnabled = binding.viewChat.bottom <= view.height + view.scrollY
            }
            viewTreeObserver.addOnGlobalLayoutListener {
                scrollToBottom()
            }
            setOnClickListener {
                switchToVoice()
            }
        }
        binding.buttonRecord.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    vibrate()
                    startRecordingPrompt()
                    true
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
        binding.buttonStopResponding.onClickWithVibrate { _receiveResponseJob?.cancel() }
        binding.edittextPrompt.apply {
//            text.append("Please generate 2 sentences of lorem ipsum.")
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(editable: Editable) {
                    syncButtonsWithEditText()
                }

                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_clear_chat_history -> {
                vibrate()
                clearChat()
                true
            }
            else -> false
        }
    }

    override fun onPause() {
        super.onPause()
        stopRecording()
        _binding!!.edittextPrompt.clearFocus()
    }

    override fun onStop() {
        super.onStop()
        _receiveResponseJob?.cancel()
    }

    private fun sendPromptAndReceiveResponse() {
        val binding = _binding ?: return
        val prompt = binding.edittextPrompt.text.toString()
        if (prompt.isEmpty()) {
            return
        }
        switchToVoice()
        addMessage(MessageModel(Role.USER, prompt))
        val gptReply = StringBuilder()
        val messageView = addMessage(MessageModel(Role.GPT, gptReply))
        _autoscrollEnabled = true
        scrollToBottom()
        binding.buttonStopResponding.visibility = VISIBLE
        var lastSpokenPos = 0
        _receiveResponseJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val sentenceFlow: Flow<String> = channelFlow {
                    openAi.value.chatCompletions(messages, isGpt4Selected())
                        .onEach { chunk ->
                            chunk.choices[0].delta?.content?.also { token ->
                                gptReply.append(token)
                                messageView.editableText.append(token)
                                if (token.contains(punctuationRegex)) {
                                    val text = gptReply.substring(lastSpokenPos, gptReply.length)
                                    channel.send(text)
                                    lastSpokenPos = gptReply.length + 1
                                }
                            }
                            scrollToBottom()
                        }
                        .onCompletion { exception ->
                            _receiveResponseJob = null
                            exception?.also {
                                when {
                                    it is CancellationException -> Unit
                                    (it.message ?: "").endsWith("does not exist") -> {
                                        gptReply.append("GPT-4 is not yet avaiable. Sorry.")
                                        scrollToBottom()
                                    }
                                    else -> Toast.makeText(
                                        requireContext(),
                                        "Something went wrong while GPT was talking", Toast.LENGTH_SHORT
                                    )
                                        .show()
                                }
                            }
                            gptReply.trimToSize()
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
                            if (!systemLanguages.contains(lastIdentifiedLanguage)) {
                                identifyLanguage(sentence).also {
                                    Log.i("speech", "Identified language: $it")
                                    lastIdentifiedLanguage = it
                                }
                            }
                            tts.setSpokenLanguage(lastIdentifiedLanguage)
                            channel.send(tts.speakToFile(sentence, nextUtteranceId++))
                        }
                        .onCompletion {
                            tts.stop()
                            tts.shutdown()
                        }
                        .launchIn(this)
                }
                val mediaPlayer = MediaPlayer()
                voiceFileFlow
                    .onEach {
                        try {
                            mediaPlayer.play(it)
                        } finally {
                            it.delete()
                        }
                    }
                    .onCompletion { exception ->
                        exception?.also {
                            Log.e("speech", it.message ?: it.toString())
                        }
                        mediaPlayer.release()
                    }
                    .launchIn(this)
            } finally {
                binding.buttonStopResponding.visibility = GONE
            }
        }
    }

    private suspend fun MediaPlayer.play(file: File) = suspendCancellableCoroutine<Unit> { continuation ->
        reset()
        setOnCompletionListener {
            continuation.resumeWith(success(Unit))
        }
        setDataSource(file.absolutePath)
        prepare()
        start()
    }

    private suspend fun newTextToSpeech(): TextToSpeech = suspendCancellableCoroutine { continuation ->
        val tts = AtomicReference<TextToSpeech?>()
        tts.set(TextToSpeech(requireContext()) { status ->
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
            val utteranceFile = File(requireContext().cacheDir, "utterance-$utteranceId.wav")
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
            Toast.makeText(requireContext(),
                "Something went wrong while we were recording your voice",
                Toast.LENGTH_SHORT).show()
            removeRecordingGlow()
            lifecycleScope.launch {
                withContext(IO) {
                    stopRecording()
                }
                _binding?.buttonRecord?.setActive(true)
            }
        }
    }

    private fun showRecordedPrompt() {
        val binding = _binding ?: return
        binding.buttonRecord.setActive(false)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val recordingSuccess = withContext(IO) {
                    stopRecording()
                }
                if (!recordingSuccess) {
                    return@launch
                }
                openAi.value.getTranscription(audioPathname).also { transcription ->
                    binding.edittextPrompt.editableText.apply {
                        clear()
                        append(transcription)
                    }
                    switchToTyping(false)
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(),
                    "Something went wrong while OpenAI was listening to you",
                    Toast.LENGTH_SHORT).show()
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
            mediaRecorder.stop()
            true
        } catch (e: Exception) {
            File(audioPathname).delete()
            false
        } finally {
            mediaRecorder.release()
        }
    }

    private fun ImageButton.onClickWithVibrate(pointerUpAction: () -> Unit) {
        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { vibrate(); true }
                MotionEvent.ACTION_UP -> {
                    pointerUpAction()
                    true
                }
                else -> false
            }
        }
    }

    private fun ImageButton.setActive(newActive: Boolean) {
        imageAlpha = if (newActive) 255 else 128
        isEnabled = newActive
    }

    private fun switchToTyping(bringUpKeyboard: Boolean) {
        val binding = _binding ?: return
        (binding.buttonRecord.layoutParams as LinearLayout.LayoutParams).apply {
            width = (50 * pixelDensity).toInt()
            weight = 0f
        }
        binding.buttonKeyboard.visibility = GONE
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
        val binding = _binding ?: return
        binding.edittextPrompt.editableText.clear()
        (binding.buttonRecord.layoutParams as LinearLayout.LayoutParams).apply {
            width = 0
            weight = 1f
        }
        binding.buttonKeyboard.visibility = VISIBLE
        binding.edittextPrompt.apply {
            visibility = GONE
            clearFocus()
        }
    }

    private fun syncButtonsWithEditText() {
        val binding = _binding ?: return
        binding.buttonSend.apply {
            visibility = if (isEnabled && binding.edittextPrompt.text.isNotEmpty()) VISIBLE else GONE
        }
        binding.buttonRecord.apply {
            visibility = if (isEnabled && binding.edittextPrompt.text.isEmpty()) VISIBLE else GONE
        }
    }

    private fun animateRecordingGlow() {
        val binding = _binding ?: return
        _recordingGlowJob = viewLifecycleOwner.lifecycleScope.launch {
            binding.recordingGlow.apply {
                alignWithView(binding.buttonRecord)
                visibility = VISIBLE
            }
            try {
                var visualVolume = 0f
                while (true) {
                    val mediaRecorder = _mediaRecorder ?: break
                    val soundVolume = (log2(mediaRecorder.maxAmplitude.toDouble()) / 15)
                        .coerceAtLeast(0.0).coerceAtMost(1.0).toFloat()
                    // Limit the rate of shrinking the glow, but allow sudden growth
                    visualVolume = (visualVolume - 0.025f).coerceAtLeast(0f)
                    visualVolume = if (soundVolume > visualVolume) soundVolume else visualVolume
                    binding.recordingGlow.setVolume(visualVolume)
                    delay(20)
                }
                var timeStep = 0f
                while (true) {
                    binding.recordingGlow.setVolume((2.5f + sin(timeStep)) / 20)
                    timeStep += 0.1f
                    delay(20)
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

    private fun addMessage(message: MessageModel): TextView {
        messages.add(message)
        val context = requireContext()
        val chatView = _binding!!.viewChat
        val messageView = LayoutInflater.from(requireContext())
            .inflate(R.layout.chat_message_item, chatView, false) as TextView
        messageView.text = message.text
        messageView.setTextColor(
            when(message.author) {
                Role.USER -> context.getColorCompat(R.color.user_text_foreground)
                Role.GPT -> context.getColorCompat(R.color.gpt_text_foreground)
            }
        )
        messageView.setBackgroundColor(
            when(message.author) {
                Role.USER -> context.getColorCompat(R.color.user_text_background)
                Role.GPT -> context.getColorCompat(R.color.gpt_text_background)
            }
        )
        chatView.addView(messageView)
        return messageView
    }

    private fun clearChat() {
        val binding = _binding ?: return
        _receiveResponseJob?.cancel()
        binding.viewChat.removeAllViews()
        messages.clear()
        binding.edittextPrompt.editableText.clear()
    }

    private fun scrollToBottom() {
        val binding = _binding ?: return
        binding.scrollviewChat.post {
            if (!_autoscrollEnabled || !binding.scrollviewChat.canScrollVertically(1)) {
                return@post
            }
            binding.appbarLayout.setExpanded(false, true)
            binding.scrollviewChat.smoothScrollTo(0, binding.scrollviewChat.getChildAt(0).bottom)
        }
    }

    private suspend fun identifyLanguage(text: String): String {
        val languages = languageIdentifier.identifyPossibleLanguages(text).await().map { it.languageTag }
        return languages
            .firstOrNull { systemLanguages.contains(it) }
            ?: languages.first()
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

    private fun isGpt4Selected(): Boolean {
        return (_binding ?: return false).toolbar.menu.findItem(R.id.menuitem_gpt_switch).actionView!!
            .findViewById<SwitchCompat>(R.id.view_gpt_switch).isChecked
    }
}

data class MessageModel(
    val author: Role,
    val text: CharSequence
)

enum class Role {
    USER, GPT
}

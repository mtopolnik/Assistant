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
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.content.res.Configuration
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.View.*
import android.view.ViewGroup
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.view.animation.LinearInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat.checkSelfPermission
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.core.view.GravityCompat
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.postDelayed
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.mlkit.nl.languageid.IdentifiedLanguage
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.languageid.LanguageIdentifier.UNDETERMINED_LANGUAGE_TAG
import io.ktor.client.plugins.ClientRequestException
import io.ktor.utils.io.*
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import io.noties.markwon.core.CorePlugin
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.tasks.await
import kotlinx.parcelize.Parcelize
import org.mtopol.assistant.MessageType.PROMPT
import org.mtopol.assistant.MessageType.RESPONSE
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

private const val KEY_CHAT_ID = "chat-id"

private const val MAX_RECORDING_TIME_MILLIS = 120_000L
private const val STOP_RECORDING_DELAY_MILLIS = 300L
private const val MIN_HOLD_RECORD_BUTTON_MILLIS = 400L
private const val RECORD_HINT_DURATION_MILLIS = 3_000L
private const val DALLE_IMAGE_DIMENSION = 512
private const val REPLY_VIEW_UPDATE_PERIOD_MILLIS = 100L
private const val CHATS_MENUITEM_ID_BASE = 1000

private const val BUFFER_MS = 50_000
private const val START_PLAYBACK_WHEN_BUFFERED_MS = 250
private const val AFTER_UNDERRUN_REBUFFER_MS = 500

private val sentenceDelimiterRegex = """(?<=\D[.!]['"]?)\s+|(?<=\d[.!]'?)\s+(?=\p{Lu})|(?<=.[;?]'?)\s+|\n+""".toRegex()
private val speechImprovementRegex = """ ?[()] ?""".toRegex()
private val whitespaceRegex = """\s+""".toRegex()

class ChatFragmentModel(
    savedState: SavedStateHandle
) : ViewModel() {
    private val _chatIdLiveData = savedState.getLiveData(KEY_CHAT_ID, lastChatId())

    var chatId: Int
        get() = _chatIdLiveData.value!!
        set(value) { _chatIdLiveData.value = value }

    val withFragmentLiveData = MutableLiveData<(ChatFragment) -> Unit>()

    fun withFragment(task: (ChatFragment) -> Unit) {
        withFragmentLiveData.value = task
    }

    val chatContent = loadChatContent(chatId)

    var recordingGlowJob: Job? = null
    var transcriptionJob: Job? = null
    var handleResponseJob: Job? = null
    var isResponding: Boolean = false
    var autoscrollEnabled: Boolean = true

    @SuppressLint("StaticFieldLeak")
    var replyTextView: TextView? = null
    var muteToggledCallback: (() -> Unit)? = null

    override fun onCleared() {
        Log.i("lifecycle", "Destroy ViewModel")
    }
}

enum class MessageType {
    PROMPT, RESPONSE
}

enum class Voice(val itemId: Int) {
    BUILT_IN(R.id.voice_builtin),
    ALLOY(R.id.voice_alloy), ECHO(R.id.voice_echo), FABLE(R.id.voice_fable),
    NOVA(R.id.voice_nova), ONYX(R.id.voice_onyx), SHIMMER(R.id.voice_shimmer)
}

@SuppressLint("ClickableViewAccessibility")
class ChatFragment : Fragment(), MenuProvider {

    private val vmodel: ChatFragmentModel by viewModels()
    private val sharedImageViewModel: SharedImageViewModel by activityViewModels()
    private lateinit var userLanguages: List<String>
    private lateinit var binding: FragmentChatBinding
    private lateinit var audioPathname: String
    private lateinit var languageIdentifier: LanguageIdentifier
    private lateinit var markwon: Markwon
    private lateinit var drawerToggle: ActionBarDrawerToggle
    private lateinit var voiceMenuItem: MenuItem
    private lateinit var chatsMenuItem: MenuItem

    private var recordButtonPressTime = 0L
    private var _mediaRecorder: MediaRecorder? = null

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (it.isNotEmpty()) Log.i("", "User granted us the requested permissions")
            else Log.w("", "User did not grant us the requested permissions")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("lifecycle", "onCreate ChatFragment")
        requireActivity().onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        binding.drawerLayout.closeDrawer(GravityCompat.START)
                    } else if (binding.imgZoomed.isVisible) {
                        exitFullScreen()
                    } else {
                        startActivity(
                            Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_HOME)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                    }
                }
            })
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

        // Trigger lazy loading of the API clients
        GlobalScope.launch(IO) { openAi; anthropic }

        binding = FragmentChatBinding.inflate(inflater, container, false)
        vmodel.withFragmentLiveData.observe(viewLifecycleOwner) { it.invoke(this) }
        sharedImageViewModel.imgUriLiveData.observe(viewLifecycleOwner) { imgUris ->
            // We need this hack to prevent duplicate event observation:
            if (imgUris.isEmpty()) {
                return@observe
            }
            sharedImageViewModel.imgUriLiveData.value = listOf()

            lifecycleScope.launch {
                val lastExchange = vmodel.chatContent.lastOrNull()
                val isStartOfExchange = lastExchange == null || lastExchange.promptParts.isNotEmpty()
                val promptContainer = if (isStartOfExchange) {
                    addMessageContainerToView(PROMPT)
                } else {
                    lastMessageContainer()
                }
                val savedImgUris = withContext(IO) {
                    imgUris
                        .mapNotNull {
                            scaleAndSave(
                                it,
                                DALLE_IMAGE_DIMENSION,
                                DALLE_IMAGE_DIMENSION
                            )
                        }
                        .map { Uri.fromFile(it) }
                }
                addImagesToView(promptContainer, savedImgUris)
                val typedUris = savedImgUris.map { PromptPart.Image(it) }
                if (isStartOfExchange) {
                    vmodel.chatContent.add(Exchange(typedUris))
                } else {
                    lastExchange!!.promptParts.addAll(typedUris)
                }
            }
        }
        val context: Context = (requireActivity() as MainActivity).also { activity ->
            activity.addMenuProvider(this, viewLifecycleOwner)
            activity.setSupportActionBar(binding.toolbar)
            activity.supportActionBar?.apply {
                setDisplayShowTitleEnabled(false)
                drawerToggle = ActionBarDrawerToggle(
                    activity, binding.drawerLayout, binding.toolbar, R.string.menu_open, R.string.menu_close)
                binding.drawerLayout.addDrawerListener(drawerToggle)
            }
            configureNavigationDrawerBehavior(activity)
        }
        markwon = Markwon.builder(context)
            .usePlugin(CorePlugin.create())
            .usePlugin(SoftBreakAddsNewLinePlugin.create())
            .build();
        syncChatView()
        syncChatsMenu()
        // Reduce the size of the scrollview when soft keyboard shown
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, insets.bottom)
            windowInsets
        }

        languageIdentifier = LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.2f).build()
        )
        audioPathname = File(context.externalCacheDir, "prompt.mp4").absolutePath
        binding.root.doOnLayout {
            if (vmodel.recordingGlowJob != null) {
                binding.showRecordingGlow()
            }
        }
        binding.imgZoomed.apply {
            coroScope = vmodel.viewModelScope
            GestureDetector(activity, ExitFullScreenListener()).also { gd ->
                setOnTouchListener { _, e -> gd.onTouchEvent(e); true }
            }
        }
        binding.scrollviewChat.apply {
            setOnScrollChangeListener { view, _, newPos, _, oldPos ->
                if (newPos < oldPos) {
                    vmodel.autoscrollEnabled = false
                }
            }
        }
        val recordButton = binding.buttonRecord
        recordButton.setOnTouchListener(object : OnTouchListener {
            private val hintView =
                inflater.inflate(R.layout.record_button_hint, null).also { hintView ->
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED).also {
                        hintView.measure(it, it)
                    }
                }
            private val hintWindow = PopupWindow(hintView, WRAP_CONTENT, WRAP_CONTENT).apply {
                animationStyle = android.R.style.Animation_Toast
            }

            override fun onTouch(view: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        recordButtonPressTime = System.currentTimeMillis()
                        startRecordingPrompt()
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (System.currentTimeMillis() - recordButtonPressTime < MIN_HOLD_RECORD_BUTTON_MILLIS) {
                            showRecordingHint()
                            return true
                        }
                        hintWindow.dismiss()
                        if (_mediaRecorder == null) {
                            return true
                        }
                        binding.buttonRecord.isEnabled = false
                        lifecycleScope.launch {
                            try {
                                delay(STOP_RECORDING_DELAY_MILLIS)
                                vibrate()
                            } finally {
                                binding.buttonRecord.isEnabled = true
                            }
                            sendRecordedPrompt()
                        }
                        return true
                    }
                }
                return false
            }

            private fun showRecordingHint() {
                vmodel.recordingGlowJob?.cancel()
                vmodel.viewModelScope.launch {
                    stopRecording()
                }
                if (hintWindow.isShowing) {
                    return
                }
                hintWindow.showAsDropDown(
                    recordButton,
                    ((recordButton.width - hintView.measuredWidth) / 2).coerceAtLeast(0),
                    -(recordButton.height + hintView.measuredHeight)
                )
                recordButton.postDelayed(RECORD_HINT_DURATION_MILLIS) {
                    hintWindow.dismiss()
                }
            }
        })
        binding.buttonLanguage.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    showLanguageMenu(); true
                }

                else -> false
            }
        }
        updateLanguageButton()
        binding.buttonKeyboard.onClickWithVibrate {
            switchToTyping()
            showKeyboard()
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
                        if (!appContext.mainPrefs.openaiApiKey.isBlank()) {
                            hideKeyboard()
                            switchToVoice()
                        }
                    } else if (editable.isNotEmpty() && !hadTextLastTime) {
                        switchToTyping()
                    }
                    hadTextLastTime = editable.isNotEmpty()
                }

                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        if (appContext.mainPrefs.openaiApiKey.isBlank()) {
            switchToTyping()
        }
        return binding.root
    }

    private inner class ExitFullScreenListener() : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            exitFullScreen()
            return true
        }
    }

    private fun exitFullScreen() {
        lifecycleScope.launch {
            binding.imgZoomed.apply {
                animateZoomExit()
                setImageURI(null)
                visibility = INVISIBLE
            }
            binding.chatLayout.visibility = VISIBLE
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        drawerToggle.syncState()
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
        applyAccessRules(menu)
        updateMuteItem(menu.findItem(R.id.action_toggle_sound))
    }

    private fun applyAccessRules(mainMenu: Menu) {
        val mainPrefs = appContext.mainPrefs

        fun TextView.updateText() {
            text = mainPrefs.selectedModel.uiId
        }

        ApiKeyWallet(mainPrefs).also { wallet ->
            if (!wallet.allowsTts()) {
                voiceMenuItem.setVisible(false)
                mainPrefs.applyUpdate {
                    setSelectedVoice(Voice.BUILT_IN)
                }
            }
            wallet.supportedModels.also { models ->
                if (models.isNotEmpty() && mainPrefs.selectedModel !in models) {
                    mainPrefs.applyUpdate {
                        setSelectedModel(models[0])
                    }
                }
            }
            binding.viewDrawer.menu.apply {
                findItem(R.id.action_delete_anthropic_key).setVisible(wallet.hasAnthropicKey())
                findItem(R.id.action_delete_openai_key).setVisible(wallet.hasOpenaiKey())
            }

            mainMenu.findItem(R.id.action_gpt_toggle)
                .actionView!!
                .findViewById<TextView>(R.id.textview_model_selector).apply {
                    updateText()
                    setOnClickListener {
                        val currIndex = wallet.supportedModels.indexOf(mainPrefs.selectedModel)
                        val nextIndex = (currIndex + 1) % wallet.supportedModels.size
                        mainPrefs.applyUpdate {
                            setSelectedModel(wallet.supportedModels[nextIndex])
                        }
                        updateText()
                    }
                }
        }
        mainPrefs.selectedVoice.itemId.also { selectedVoiceItemId ->
            for (item in voiceMenuItem.subMenu!!.children) {
                item.setChecked(item.itemId == selectedVoiceItemId)
            }
        }
    }

    override fun onPrepareMenu(menu: Menu) {
        Log.i("lifecycle", "onPrepareMenu")
        val responding = vmodel.isResponding
        val hasContent = vmodel.chatContent.isNotEmpty()
        menu.findItem(R.id.action_cancel).isVisible = responding
        menu.findItem(R.id.action_undo).isVisible = !responding
        menu.findItem(R.id.action_speak_again).isEnabled =
            !appContext.mainPrefs.isMuted && !responding && hasContent
    }

    private fun updateMuteItem(item: MenuItem) {
        item.isChecked = appContext.mainPrefs.isMuted
        item.setIcon(if (item.isChecked) R.drawable.baseline_volume_off_24 else R.drawable.baseline_volume_up_24)
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
                        vmodel.withFragment { it.activity?.invalidateOptionsMenu() }
                    }
                }
                activity?.invalidateOptionsMenu()
                true
            }
            R.id.action_toggle_sound -> {
                appContext.mainPrefs.applyUpdate { setIsMuted(!appContext.mainPrefs.isMuted) }
                updateMuteItem(item)
                activity?.invalidateOptionsMenu()
                vmodel.muteToggledCallback?.invoke()
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
            R.id.action_archive_chat -> {
                saveOrDeleteCurrentChat()
                newChat()
                true
            }
            R.id.action_delete_chat -> {
                deleteChat(vmodel.chatId)
                newChat()
                true
            }
            else -> false
        }
    }

    private fun configureNavigationDrawerBehavior(activity: MainActivity) {
        val mainPrefs = appContext.mainPrefs

        fun onApiKeyDeleted() {
            resetClients()
            activity.invalidateOptionsMenu()
            if (ApiKeyWallet(mainPrefs).isEmpty()) {
                activity.navigateToApiKeyFragment()
            }
        }

        fun selectVoice(itemId: Int) {
            for (item in voiceMenuItem.subMenu!!.children) {
                item.setChecked(item.itemId == itemId)
            }
            mainPrefs.applyUpdate {
                setSelectedVoice(Voice.entries.first { it.itemId == itemId })
            }
        }

        binding.drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            private var previousOpenedState = 0
            override fun onDrawerOpened(drawerView: View) {
                Log.i("chats", "drawerOpened")
                previousOpenedState = 1
            }
            override fun onDrawerClosed(drawerView: View) {
                Log.i("chats", "drawerClosed")
                previousOpenedState = 0
            }
            override fun onDrawerStateChanged(newState: Int) {
                Log.i("chats", "drawerStateChanged: newState $newState previousOpenedState $previousOpenedState")
                if (newState == DrawerLayout.STATE_SETTLING) {
                    if (previousOpenedState == 0) {
                        Log.i("chats", "syncChatsMenu()")
                        syncChatsMenu()
                    }
                }
            }
        })
        binding.viewDrawer.apply {
            setNavigationItemSelectedListener { item ->
                when (item.itemId) {
                    R.id.action_about -> {
                        showAboutDialogFragment(requireActivity())
                    }
                    R.id.action_edit_system_prompt -> {
                        findNavController().navigate(R.id.fragment_system_prompt)
                    }
                    R.id.action_play_store -> {
                        requireContext().visitOnPlayStore()
                    }
                    R.id.action_delete_anthropic_key -> {
                        mainPrefs.applyUpdate {
                            setAnthropicApiKey("")
                        }
                        onApiKeyDeleted()
                    }
                    R.id.action_delete_openai_key -> {
                        mainPrefs.applyUpdate {
                            setOpenaiApiKey("")
                        }
                        switchToTyping()
                        onApiKeyDeleted()
                    }
                    R.id.action_enter_api_key -> {
                        activity.navigateToApiKeyFragment()
                    }
                    R.id.action_toggle_send_audio_prompt -> {
                        appContext.mainPrefs.applyUpdate {
                            setIsSendAudioPrompt(!appContext.mainPrefs.isSendAudioPrompt)
                        }
                        item.isChecked = appContext.mainPrefs.isSendAudioPrompt
                    }
                    in voiceMenuItem.subMenu!!.children.map { it.itemId } -> {
                        selectVoice(item.itemId)
                    }
                    in chatsMenuItem.subMenu!!.children.map { it.itemId } -> {
                        val chatId = item.itemId - CHATS_MENUITEM_ID_BASE
                        if (chatId != vmodel.chatId) {
                            saveOrDeleteCurrentChat()
                            val chat = loadChatContent(chatId)
                            Log.i("chats", "Loaded history of chat #$chatId")
                            vmodel.chatContent.apply {
                                clear()
                                addAll(chat)
                            }
                            vmodel.replyTextView = null
                            vmodel.chatId = chatId
                            syncChatView()
                        } else {
                            Log.i("chats", "Selected current chat $chatId")
                        }
                    }
                    else -> {
                        Log.i("chats", "Item ID not in menu: ${item.itemId}")
                        binding.viewDrawer.invalidate()
                    }
                }
                binding.drawerLayout.closeDrawer(GravityCompat.START)
                true
            }
            menu.findItem(R.id.submenu_voice)?.also { voiceMenuItem = it }
            menu.findItem(R.id.submenu_chats)?.also { chatsMenuItem = it }
            menu.findItem(R.id.action_toggle_send_audio_prompt)?.also {
                it.isChecked = appContext.mainPrefs.isSendAudioPrompt
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.i("lifecycle", "onResume ChatFragment")
        userLanguages = appContext.mainPrefs.configuredLanguages()
    }

    override fun onPause() {
        super.onPause()
        Log.i("lifecycle", "onPause ChatFragment")
        vmodel.recordingGlowJob?.cancel()
        runBlocking { stopRecording() }
        saveOrDeleteCurrentChat()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        drawerToggle.onConfigurationChanged(newConfig)
    }

    private fun syncChatsMenu() {
        val chatsMenu = chatsMenuItem.subMenu!!
        chatsMenu.clear()
        val chatHandles = chatHandles()
        for (i in chatHandles.size - 1 downTo 0) {
            val handle = chatHandles[i]
            val chatId = handle.chatId
            val label =
                if (i == chatHandles.size - 1) appContext.getString(R.string.new_chat)
                else handle.title.takeIf { it.isNotEmpty() } ?: "${appContext.getString(R.string.chat)} #$chatId"
            chatsMenu.add(Menu.NONE, CHATS_MENUITEM_ID_BASE + chatId, Menu.NONE, label).also { menuItem ->
                menuItem.setChecked(chatId == vmodel.chatId)
                if (handle.title.isNotEmpty() || i == chatHandles.size - 1) {
                    return@also
                }
                lifecycleScope.launch {
                    var title = loadChatTitle(chatId)
                    if (title.isEmpty()) {
                        title = openAi.summarizing(loadChatContent(chatId))
                    }
                    if (title.isEmpty() || handle.title.isNotEmpty()) {
                        return@launch
                    }
                    handle.title = title
                    menuItem.title = title
                    binding.viewDrawer.invalidate()
                }
            }
            Log.i("chats", "chatsMenu.add($handle)")
        }
        binding.viewDrawer.invalidate()
    }

    private fun syncChatView() {
        binding.viewChat.removeAllViews()
        var newestHistoryTextView: TextView? = null
        var newestHistoryExchange: Exchange? = null
        for (exchange in vmodel.chatContent) {
            newestHistoryExchange = exchange
            addPromptToView(exchange)
            if (exchange.replyMarkdown.isNotBlank()) {
                newestHistoryTextView = addResponseToView(exchange)
            }
        }
        if (vmodel.replyTextView != null) {
            newestHistoryTextView!!.also {
                vmodel.replyTextView = it
                newestHistoryExchange!!.replyText = it.text
            }
        }
    }

    private suspend fun undoLastPrompt() {
        val promptBox = binding.edittextPrompt
        if (promptBox.text.isNotEmpty()) {
            promptBox.editableText.clear()
            return
        }
        vmodel.handleResponseJob?.apply {
            cancel()
            join()
        }
        if (vmodel.chatContent.isEmpty()) {
            return
        }

        // Remove the last view. This may be the prompt or the response view, depending
        // on previous undo actions.
        binding.viewChat.apply {
            removeViewAt(childCount - 1)
        }

        // Determine where the prompt text is and remove it
        val lastEntry = vmodel.chatContent.last()
        if (lastEntry.promptParts.size > 1 && lastEntry.promptParts.last() is PromptPart.Text) {
            // The prompt contains both text and image. Remove just the text and leave the
            // image. This means the prompt view stays.
            val promptContainer = lastMessageContainer()
            promptContainer.apply { removeViewAt(childCount - 1) }
        } else {
            if (lastEntry.promptText() != null) {
                // The prompt contains just text and no image. Remove the prompt view.
                binding.viewChat.apply { removeViewAt(childCount - 1) }
            }
            vmodel.chatContent.removeLast()
        }

        // Put the prompt text back into the edit box
        promptBox.editableText.apply {
            replace(0, length, lastEntry.promptText() ?: "")
        }
        // Clear the prompt and response text from the chat history entry
        lastEntry.apply {
            removePromptText()
            replyMarkdown = ""
        }
    }

    private fun sendPromptAndReceiveImageResponse(prompt: CharSequence) {
        val previousResponseJob = vmodel.handleResponseJob?.apply { cancel() }
        vmodel.handleResponseJob = vmodel.viewModelScope.launch {
            try {
                previousResponseJob?.join()
                val exchange = prepareNewExchange(PromptPart.Text(prompt))
                val responseContainer = addMessageContainerToView(RESPONSE)
                val responseText = addTextToView(responseContainer, "", RESPONSE)
                val editable = responseText.editableText
                val imageUris = openAi.imageGeneration(prompt, appContext.mainPrefs.selectedModel, editable)
                if (editable.isBlank()) {
                    responseContainer.removeView(responseText)
                } else {
                    exchange.replyMarkdown = editable.toString()
                }
                exchange.replyImageUris = imageUris
                addImagesToView(responseContainer, exchange.replyImageUris)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("lifecycle", "Error in receiveResponseJob", e)
            } finally {
                vmodel.isResponding = false
                vmodel.withFragment { it.activity?.invalidateOptionsMenu() }
            }
        }
    }

    private fun prepareNewExchange(finalPart: PromptPart): Exchange {
        vmodel.isResponding = true
        vmodel.withFragment { it.activity?.invalidateOptionsMenu() }
        vmodel.autoscrollEnabled = true
        scrollToBottom()
        val promptText = if (finalPart is PromptPart.Text) finalPart.text else "<recorded audio>"
        val chatHistory = vmodel.chatContent
        return if (chatHistory.isEmpty() || chatHistory.last().hasFinalPromptPart()) {
            addMessageContainerToView(PROMPT).also { layout -> addTextToView(layout, promptText, PROMPT) }
            Exchange(finalPart).also { chatHistory.add(it) }
        } else {
            addTextToView(lastMessageContainer(), promptText, PROMPT)
            chatHistory.last().also { it.promptParts.add(finalPart) }
        }
    }

    private fun sendPromptAndReceiveTextResponse(finalPart: PromptPart) {
        var lastSpokenPos = 0
        val previousResponseJob = vmodel.handleResponseJob?.apply { cancel() }
        vmodel.handleResponseJob = vmodel.viewModelScope.launch {
            try {
                previousResponseJob?.join()
                val exchange = prepareNewExchange(finalPart)
                val replyMarkdown = StringBuilder()
                exchange.replyMarkdown = replyMarkdown
                vmodel.replyTextView = addTextResponseToView(exchange)

                suspend fun updateReplyTextView(replyMarkdown: StringBuilder) {
                    Log.i("scroll", "updateReplyTextView")
                    withContext(Default) {
                        replyMarkdown.toString()
                            .let { markwon.parse(it) }
                            .let { markwon.render(it) }
                    }.let { markwon.setParsedMarkdown(vmodel.replyTextView!!, it) }
                    binding.scrollviewChat.viewTreeObserver.also { observer ->
                        observer.addOnGlobalLayoutListener(object : OnGlobalLayoutListener {
                            override fun onGlobalLayout() {
                                observer.removeOnGlobalLayoutListener(this)
                                scrollToBottom()
                            }
                        })
                    }
                }

                val sentenceFlow: Flow<String> = channelFlow {
                    val selectedModel = appContext.mainPrefs.selectedModel
                    val responseFlow = if (selectedModel == AiModel.CLAUDE_3_5_SONNET)
                        anthropic.messages(vmodel.chatContent, selectedModel)
                    else
                        openAi.chatCompletions(vmodel.chatContent, selectedModel)

                    var replyTextUpdateTime = 0L
                    responseFlow
                        .onEach { token ->
                            replyMarkdown.append(token)
                            if (System.currentTimeMillis() - replyTextUpdateTime < REPLY_VIEW_UPDATE_PERIOD_MILLIS) {
                                return@onEach
                            }
                            updateReplyTextView(replyMarkdown)
                            replyTextUpdateTime = System.currentTimeMillis()
                            val replyText = vmodel.replyTextView!!.text
                            exchange.replyText = replyText
                            val fullSentences = replyText
                                .substring(lastSpokenPos, replyText.length)
                                .dropLastIncompleteSentence()
                            fullSentences.takeIf { it.isNotBlank() }?.also {
                                Log.i("speech", "full sentences: $it")
                            }
                            if (wordCount(fullSentences) >= 3) {
                                channel.send(fullSentences)
                                lastSpokenPos += fullSentences.length
                            }
                        }
                        .onCompletion { exception ->
                            exception?.also { e ->
                                when (e) {
                                    is CancellationException -> {}
                                    is ClientRequestException -> {
                                        Log.e(
                                            "lifecycle",
                                            "API error in chatCompletions flow",
                                            e
                                        )
                                        val message = e.message
                                        when {
                                            message.contains("` does not exist\"") ->
                                                Toast.makeText(
                                                    appContext,
                                                    getString(R.string.gpt4_unavailable),
                                                    Toast.LENGTH_SHORT
                                                ).show()

                                            message.contains("image_url is only supported by certain models.") ->
                                                Toast.makeText(
                                                    appContext,
                                                    getString(R.string.gpt4_required),
                                                    Toast.LENGTH_SHORT
                                                ).show()

                                            else ->
                                                showApiErrorToast(e)
                                        }
                                    }
                                    else -> {
                                        Log.e("lifecycle", "Error in chatCompletions flow", e)
                                        Toast.makeText(
                                            appContext,
                                            getString(R.string.error_while_gpt_talking),
                                            Toast.LENGTH_SHORT
                                        )
                                            .show()
                                    }
                                }
                            }
                            updateReplyTextView(replyMarkdown)
                            val replyText = vmodel.replyTextView!!.text
                            exchange.replyText = replyText
                            if (lastSpokenPos < replyText.length) {
                                channel.send(replyText.substring(lastSpokenPos, replyText.length))
                            }
                            if (appContext.mainPrefs.isMuted) {
                                vmodel.handleResponseJob?.cancel()
                            }
                        }
                        .launchIn(this)
                }
                    .onCompletion { exception ->
                        exception?.also {
                            Log.e("speech", it.message ?: it.toString())
                        }
                    }
                speak(sentenceFlow)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("lifecycle", "Error in receiveResponseJob", e)
            } finally {
                vmodel.isResponding = false
                vmodel.withFragment { it.activity?.invalidateOptionsMenu() }
            }
        }
    }

    private suspend fun speak(sentenceFlow: Flow<String>) {
        if (appContext.mainPrefs.selectedVoice == Voice.BUILT_IN) {
            speakWithAndroidSpeech(sentenceFlow)
        } else {
            speakWithOpenAi(sentenceFlow)
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private suspend fun speakWithOpenAi(sentenceFlow: Flow<String>) {
        var lastValidVoice = appContext.mainPrefs.selectedVoice
        val exoPlayer = ExoPlayer.Builder(appContext)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(C.USAGE_ASSISTANT)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(), false)
            .setLoadControl(DefaultLoadControl.Builder()
                .setBufferDurationsMs(BUFFER_MS, BUFFER_MS, START_PLAYBACK_WHEN_BUFFERED_MS, AFTER_UNDERRUN_REBUFFER_MS)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build())
            .build()
        val speakLatch = CompletableDeferred<Unit>()
        if (!appContext.mainPrefs.isMuted) {
            speakLatch.complete(Unit)
        }
        vmodel.muteToggledCallback = {
            if (appContext.mainPrefs.isMuted) {
                exoPlayer.pause()
            } else {
                speakLatch.complete(Unit)
                exoPlayer.play()
            }
        }
        try {
            coroutineScope {
                val sentenceChannel = Channel<String>(Channel.UNLIMITED)
                sentenceFlow
                    .onEach { sentence -> sentenceChannel.send(sentence) }
                    .onCompletion {
                        sentenceChannel.close()
                        if (appContext.mainPrefs.isMuted) {
                            this@coroutineScope.cancel()
                        }
                    }
                    .launchIn(this)
                val sentenceBuf = StringBuilder()
                while (true) {
                    (sentenceChannel.receiveCatching().getOrNull() ?: break).also {
                        sentenceBuf.append(it)
                    }
                    while (true) {
                        (sentenceChannel.tryReceive().getOrNull() ?: break).also {
                            sentenceBuf.append(it)
                        }
                    }
                    speakLatch.await()
                    if (exoPlayer.playbackState == Player.STATE_IDLE) {
                        Log.i("speech", "exoPlayer.play()")
                        exoPlayer.prepare()
                        exoPlayer.play()
                    }
                    appContext.mainPrefs.selectedVoice.also { selectedVoice ->
                        if (selectedVoice != Voice.BUILT_IN) {
                            lastValidVoice = selectedVoice
                        }
                    }
                    openAi.speak(sentenceBuf, lastValidVoice.name.lowercase(), exoPlayer)
                    sentenceBuf.clear()
                }
                val doneLatch = CompletableDeferred<Unit>()
                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) {
                            doneLatch.complete(Unit)
                        }
                    }
                })
                doneLatch.await()
            }
        } finally {
            vmodel.muteToggledCallback = null
            exoPlayer.stop()
            exoPlayer.release()
        }
    }

    private suspend fun speakWithAndroidSpeech(sentenceFlow: Flow<String>) {
        coroutineScope {
            val voiceFileFlow: Flow<File> = channelFlow {
                val tts = newTextToSpeech()
                var nextUtteranceId = 0L
                sentenceFlow
                    .onCompletion {
                        if (appContext.mainPrefs.isMuted) {
                            this@coroutineScope.cancel()
                        }
                    }
                    .buffer(Channel.UNLIMITED)
                    .map { it.replace(speechImprovementRegex, ", ") }
                    .onEach { sentence ->
                        Log.i("speech", "Speak: $sentence")
                        tts.identifyAndSetLanguage(sentence)
                        channel.send(tts.speakToFile(sentence, nextUtteranceId++))
                    }
                    .onCompletion {
                        tts.apply { stop(); shutdown() }
                    }
                    .launchIn(this)
            }.buffer(3)
            val mediaPlayer = MediaPlayer()
            vmodel.muteToggledCallback = {
                if (appContext.mainPrefs.isMuted) {
                    mediaPlayer.pause()
                } else {
                    mediaPlayer.start()
                }
            }
            var cancelled = false
            voiceFileFlow
                .onCompletion {
                    vmodel.muteToggledCallback = null
                    mediaPlayer.apply {
                        Log.i("speech", "stop media player")
                        stop()
                        release()
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
        }
    }

    private fun sendPromptAndReceiveResponse(prompt: String) {
        if (appContext.mainPrefs.selectedModel.isChatModel()) {
            sendPromptAndReceiveTextResponse(PromptPart.Text(prompt))
        } else {
            sendPromptAndReceiveImageResponse(prompt)
        }
    }

    private fun lastMessageContainer() =
        binding.viewChat.run { getChildAt(childCount - 1) } as LinearLayout

    private suspend fun MediaPlayer.play(file: File) {
        reset()
        setDataSource(file.absolutePath)
        prepare()
        suspendCancellableCoroutine { continuation ->
            setOnCompletionListener {
                Log.i("speech", "complete playing ${file.name}")
                continuation.resumeWith(success(Unit))
            }
            if (appContext.mainPrefs.isMuted) {
                Log.i("speech", "play ${file.name}, paused at start")
            } else {
                Log.i("speech", "start playing ${file.name}")
                start()
            }
        }
    }

    private suspend fun newTextToSpeech(): TextToSpeech =
        suspendCancellableCoroutine { continuation ->
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
            val utteranceFile = File(appContext.externalCacheDir, "utterance-$utteranceId.wav")
            setOnUtteranceProgressListener(
                UtteranceContinuationListener(
                    utteranceId,
                    continuation,
                    utteranceFile
                )
            )
            if (synthesizeToFile(
                    sentence,
                    Bundle(),
                    utteranceFile,
                    utteranceId
                ) == TextToSpeech.ERROR
            ) {
                continuation.resumeWith(failure(Exception("synthesizeToFile() failed to enqueue its request")))
            }
        }
    }

    private suspend fun speakLastResponse() {
        val response = vmodel.chatContent.lastOrNull()?.replyText?.toString() ?: return
        vmodel.isResponding = true
        vmodel.withFragment { it.activity?.invalidateOptionsMenu() }
        try {
            speak(flowOf(response))
        } finally {
            vmodel.isResponding = false
            vmodel.withFragment { it.activity?.invalidateOptionsMenu() }
        }
    }

    private fun startRecordingPrompt() {
        val activity = requireActivity() as MainActivity

        // don't extract to fun, IDE inspection for permission checks will complain
        if (checkSelfPermission(activity, permission.RECORD_AUDIO) != PERMISSION_GRANTED) {
            permissionRequest.launch(
                arrayOf(
                    permission.RECORD_AUDIO,
                    permission.WRITE_EXTERNAL_STORAGE
                )
            )
            return
        }
        vmodel.transcriptionJob?.cancel()
        vmodel.handleResponseJob?.cancel()
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
                setAudioChannels(1)
                setAudioSamplingRate(22050)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(audioPathname)
                prepare()
                start()
            }
            vibrate()
            animateRecordingGlow()
        } catch (e: Exception) {
            Log.e("speech", "Voice recording error", e)
            Toast.makeText(
                requireContext(),
                "Something went wrong while we were recording your voice",
                Toast.LENGTH_SHORT
            ).show()
            vmodel.recordingGlowJob?.cancel()
            lifecycleScope.launch {
                stopRecording()
                vmodel.withFragment { it.binding.buttonRecord.isEnabled = true }
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
        val previousRecordingGlowJob = vmodel.recordingGlowJob?.apply { cancel() }
        vmodel.recordingGlowJob = vmodel.viewModelScope.launch {
            previousRecordingGlowJob?.join()
            vmodel.withFragment { it.binding.showRecordingGlow() }
            launch {
                delay(MAX_RECORDING_TIME_MILLIS)
                sendRecordedPrompt()
            }
            try {
                var lastPeak = 0f
                var lastPeakTime = 0L
                var lastRecordingVolume = 0f
                while (true) {
                    val frameTime = awaitFrame()
                    val mediaRecorder = _mediaRecorder ?: break
                    val soundVolume = (log2(mediaRecorder.maxAmplitude.toDouble()) / 15).toFloat()
                        .coerceAtLeast(0f)
                    val decayingPeak =
                        lastPeak * (1f - 2 * nanosToSeconds(frameTime - lastPeakTime))
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
                if (vmodel.recordingGlowJob == coroutineContext[Job]!!) {
                    vmodel.recordingGlowJob = null
                }
            }
        }
    }

    private fun sendRecordedPrompt() {
        val mainPrefs = appContext.mainPrefs
        if (mainPrefs.isSendAudioPrompt && mainPrefs.selectedModel == AiModel.GPT_4) {
            sendAudioPrompt()
        } else {
            transcribeAndSendPrompt()
        }
    }

    private fun transcribeAndSendPrompt() {
        val recordingGlowJob = vmodel.recordingGlowJob
        val previousTranscriptionJob = vmodel.transcriptionJob?.apply { cancel() }
        vmodel.transcriptionJob = vmodel.viewModelScope.launch {
            try {
                previousTranscriptionJob?.join()
                val recordingSuccess = stopRecording()
                if (!recordingSuccess) {
                    return@launch
                }
                val prefs = appContext.mainPrefs
                val promptContext =
                    vmodel.chatContent.joinToString("\n\n") { it.promptText().toString() }
                val transcription =
                    openAi.transcription(prefs.speechRecogLanguage, promptContext, audioPathname)
                if (transcription.isEmpty()) {
                    return@launch
                }
                vmodel.withFragment {
                    sendPromptAndReceiveResponse(transcription)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("speech", "Text-to-speech error", e)
                vmodel.withFragment { fragment ->
                    if (e is ClientRequestException) {
                        showApiErrorToast(e)
                    } else {
                        Toast.makeText(
                            fragment.activity,
                            "Something went wrong while OpenAI was listening to you",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } finally {
                recordingGlowJob?.cancel()
            }
        }
    }

    private fun sendAudioPrompt() {
        val recordingGlowJob = vmodel.recordingGlowJob
        vmodel.viewModelScope.launch {
            val recordingSuccess = stopRecording()
            recordingGlowJob?.cancel()
            if (!recordingSuccess) {
                return@launch
            }
            val pcmPathname = audioPathname.replaceAfterLast('.', "pcm")
            Log.i("audio", "Converting recorded AAC to PCM")
            try {
                decodeAudioToFile(audioPathname, pcmPathname)
                Log.i("audio", "PCM saved: ${File(pcmPathname).length()} bytes")
                vmodel.withFragment {
                    sendPromptAndReceiveTextResponse(PromptPart.Audio(Uri.fromFile(File(pcmPathname))))
                }
            } catch (e: Exception) {
                Log.e("audio", "Conversion failed", e)
            }
        }
    }

    private fun Button.onClickWithVibrate(pointerUpAction: () -> Unit) {
        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    vibrate(); true
                }

                MotionEvent.ACTION_UP -> {
                    pointerUpAction(); true
                }

                else -> false
            }
        }
    }

    private fun switchToTyping() {
        binding.apply {
            buttonKeyboard.visibility = GONE
            buttonRecord.visibility = GONE
            buttonLanguage.visibility = GONE
            buttonSend.visibility = VISIBLE
            edittextPrompt.apply {
                visibility = VISIBLE
                requestFocus()
            }
        }
    }

    private fun switchToVoice() {
        binding.edittextPrompt.editableText.clear()
        viewScope.launch {
            delay(100)
            binding.apply {
                buttonKeyboard.visibility = VISIBLE
                buttonRecord.visibility = VISIBLE
                buttonLanguage.visibility = VISIBLE
                buttonSend.visibility = GONE
                edittextPrompt.apply {
                    visibility = GONE
                    clearFocus()
                }
            }
        }
    }

    private fun showKeyboard() {
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(binding.edittextPrompt, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    private fun FragmentChatBinding.showRecordingGlow() {
        recordingGlow.apply {
            alignWithView(buttonRecord)
            visibility = VISIBLE
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

    private fun styleMessageContainer(box: View, backgroundFill: Int, backgroundBorder: Int) {
        val context = requireContext()
        (box.background as LayerDrawable).apply {
            findDrawableByLayerId(R.id.background_fill).setTint(
                context.getColorCompat(
                    backgroundFill
                )
            )
            (findDrawableByLayerId(R.id.background_border) as GradientDrawable)
                .setStroke(1.dp, context.getColorCompat(backgroundBorder))
        }
    }

    private fun addMessageContainerToView(messageType: MessageType): LinearLayout {
        val chatView = binding.viewChat
        val container = LayoutInflater.from(requireContext())
            .inflate(R.layout.message_container, chatView, false) as LinearLayout
        val (bg, border) =
            if (messageType == PROMPT) Pair(R.color.prompt_background, R.color.prompt_border)
            else Pair(R.color.response_background, R.color.response_border)
        styleMessageContainer(container, bg, border)
        chatView.addView(container)
        return container
    }

    private fun addImagesToView(container: LinearLayout, imageUris: List<Uri>) {
        for (imageUri in imageUris) {
            val imageView = LayoutInflater.from(requireContext())
                .inflate(R.layout.message_image, container, false) as ImageView
            imageView.setImageURI(imageUri)
            container.addView(imageView)
            GestureDetector(activity, ImageViewListener(imageView, imageUri)).also { gd ->
                imageView.setOnTouchListener { _, e -> gd.onTouchEvent(e); true }
            }
        }
    }

    private fun addTextToView(
        container: LinearLayout,
        text: CharSequence,
        messageType: MessageType
    ): EditText {
        val editText = LayoutInflater.from(requireContext())
            .inflate(R.layout.message_text, container, false) as EditText
        val color =
            if (messageType == PROMPT) R.color.prompt_foreground else R.color.response_foreground
        editText.setTextColor(requireContext().getColorCompat(color))
        markwon.setMarkdown(editText, text.toString())
        container.addView(editText)
        return editText
    }

    private fun addPromptToView(exchange: Exchange) {
        val promptContainer = addMessageContainerToView(PROMPT)
        addImagesToView(promptContainer, exchange.promptParts.mapNotNull { (it as? PromptPart.Image)?.uri })
        exchange.promptText()?.also { addTextToView(promptContainer, it, PROMPT) }
    }

    private fun addTextResponseToView(exchange: Exchange): TextView {
        val responseContainer = addMessageContainerToView(RESPONSE)
        return addTextToView(responseContainer, exchange.replyMarkdown, RESPONSE)
    }

    private fun addResponseToView(exchange: Exchange): TextView {
        val responseContainer = addMessageContainerToView(RESPONSE)
        val textView = addTextToView(responseContainer, exchange.replyMarkdown, RESPONSE)
        addImagesToView(responseContainer, exchange.replyImageUris)
        return textView
    }

    private inner class ImageViewListener(
        private val imageView: ImageView,
        private val imageUri: Uri
    ) : GestureDetector.SimpleOnGestureListener() {

        override fun onLongPress(e: MotionEvent) {
            vibrate()
            val imageFile = imageUri.toFile()
            Intent().apply {
                action = Intent.ACTION_SEND
                type = "image/${imageFile.extension}"
                putExtra(
                    Intent.EXTRA_STREAM,
                    FileProvider.getUriForFile(requireContext(), FILE_PROVIDER_AUTHORITY, imageFile)
                )
            }.also {
                startActivity(Intent.createChooser(it, null))
            }
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val (bitmapW, bitmapH) = imageView.bitmapSize(PointF()) ?: return true
            val viewWidth = imageView.width
            val focusInBitmapX = (e.x / viewWidth) * bitmapW
            val focusInBitmapY = (e.y / imageView.height) * bitmapH
            val (imgOnScreenX, imgOnScreenY) = IntArray(2).also { imageView.getLocationInWindow(it) }
            binding.chatLayout.visibility = INVISIBLE
            binding.imgZoomed.apply {
                setImageURI(imageUri)
                reset()
                visibility = VISIBLE
                lifecycleScope.launch {
                    awaitBitmapMeasured()
                    animateZoomEnter(
                        imgOnScreenX,
                        imgOnScreenY,
                        viewWidth,
                        focusInBitmapX,
                        focusInBitmapY
                    )
                }
            }
            return true
        }
    }

    private fun saveOrDeleteCurrentChat() {
        if (vmodel.chatContent.isNotEmpty()) {
            saveChatContent(vmodel.chatId, vmodel.chatContent)
        } else {
            deleteChat(vmodel.chatId)
        }
    }

    private fun newChat() {
        Log.i("chats", "newChat() chatIds ${chatHandles()} chatId ${vmodel.chatId}")
        vmodel.handleResponseJob?.cancel()
        binding.edittextPrompt.editableText.clear()
        activity?.invalidateOptionsMenu()
        vmodel.replyTextView = null
        vmodel.chatId = lastChatId()
        binding.viewChat.removeAllViews()
        vmodel.chatContent.clear()
        Log.i("chats", "newChat done, chatIds ${chatHandles()} chatId ${vmodel.chatId}")
    }

    private fun scrollToBottom() {
        binding.scrollviewChat.post {
            if (!vmodel.autoscrollEnabled || !binding.scrollviewChat.canScrollVertically(1)) {
                return@post
            }
            binding.appbarLayout.setExpanded(false, true)
            binding.scrollviewChat.apply {
                val messageTop = lastMessageContainer().top
                if (scrollY < messageTop) {
                    smoothScrollTo(0, messageTop)
                }
            }
        }
    }

    private suspend fun TextToSpeech.identifyAndSetLanguage(text: String) {

        fun isUserLanguage(language: String) = userLanguages.firstOrNull { it == language } != null

        withContext(Default) {
            val languagesWithConfidence = languageIdentifier.identifyPossibleLanguages(text).await()
            val languagesWithAdjustedConfidence = languagesWithConfidence
                .map { lang ->
                    if (isUserLanguage(lang.languageTag)) {
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
            val identifiedLanguage = when {
                topLang.languageTag != UNDETERMINED_LANGUAGE_TAG && topLang.confidence >= 0.8 -> topLang.languageTag
                else -> langTags.firstOrNull { isUserLanguage(it) }
                    ?: appContext.mainPrefs.speechRecogLanguage
                    ?: userLanguages.first()
            }
            Log.i("speech", "Chosen language: $identifiedLanguage")
            setSpokenLanguage(identifiedLanguage)
        }
    }

    private fun showLanguageMenu() {
        val addItemId = Menu.FIRST
        val autoItemId = Menu.FIRST + 1
        val itemIdOffset = Menu.FIRST + 2
        val pop = PopupMenu(requireContext(), binding.buttonLanguage, Gravity.END)
        pop.menu.add(Menu.NONE, addItemId, Menu.NONE, getString(R.string.item_add_remove))
        pop.menu.add(Menu.NONE, autoItemId, Menu.NONE, "Auto")
        for ((i, language) in userLanguages.withIndex()) {
            pop.menu.add(
                Menu.NONE, i + itemIdOffset, Menu.NONE,
                "${language.uppercase()} - ${language.toDisplayLanguage()}"
            )
        }
        pop.setOnMenuItemClickListener { item ->
            if (item.itemId == addItemId) {
                findNavController().navigate(R.id.fragment_add_remove_languages)
                return@setOnMenuItemClickListener true
            }
            appContext.mainPrefs.applyUpdate {
                setSpeechRecogLanguage(
                    if (item.itemId == autoItemId) null
                    else userLanguages[item.itemId - itemIdOffset]
                )
            }
            updateLanguageButton()
            true
        }
        pop.show()
    }

    private fun updateLanguageButton() {
        val language = appContext.mainPrefs.speechRecogLanguage
        val languageButton = binding.buttonLanguage as MaterialButton
        if (language == null) {
            languageButton.apply {
                setIconResource(R.drawable.baseline_record_voice_over_28)
                text = ""
            }
        } else {
            languageButton.apply {
                icon = null
                text = language.uppercase()
            }
        }
    }

    private fun TextToSpeech.setSpokenLanguage(tag: String) {
        when (setLanguage(Locale.forLanguageTag(tag))) {
            TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> {
                Log.i("", "Language not supported for text-to-speech: $tag")
                language = Locale.forLanguageTag("hr")
            }
        }
    }

    private fun showApiErrorToast(e: ClientRequestException) {
        Toast.makeText(
            requireActivity(),
            if (e.response.status.value == 401) getString(R.string.message_incorrect_api_key)
            else "API error: ${e.message}",
            Toast.LENGTH_LONG
        ).show()
    }

    private val viewScope get() = viewLifecycleOwner.lifecycleScope

    private fun wordCount(text: String) = text.trim().split(whitespaceRegex).size

    private fun String.dropLastIncompleteSentence(): String {
        val lastMatch = sentenceDelimiterRegex.findAll(this).lastOrNull() ?: return ""
        return substring(0, lastMatch.range.last + 1)
    }
}

class UtteranceContinuationListener<T>(
    private val utteranceId: String,
    private val continuation: Continuation<T>,
    private val successValue: T
) : UtteranceProgressListener() {
    override fun onStart(utteranceId: String) {}

    override fun onDone(doneUtteranceId: String) {
        if (doneUtteranceId != utteranceId) {
            Log.e("speech", "unexpected utteranceId in onDone: $doneUtteranceId != $utteranceId")
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
sealed class PromptPart : Parcelable {
    @Parcelize
    class Text(var text: CharSequence) : PromptPart()
    @Parcelize
    class Image(val uri: Uri) : PromptPart()
    @Parcelize
    class Audio(val uri: Uri) : PromptPart()
}

@Parcelize
data class Exchange(
    val promptParts: MutableList<PromptPart> = mutableListOf(),
    var replyMarkdown: CharSequence = "",
    var replyImageUris: List<Uri> = listOf(),
    var replyText: CharSequence = "",
) : Parcelable {
    fun hasFinalPromptPart() = when (promptParts.lastOrNull()) {
        null, is PromptPart.Image -> false
        is PromptPart.Audio, is PromptPart.Text -> true
    }
    fun promptText(): CharSequence? = (promptParts.lastOrNull() as? PromptPart.Text)?.text
    fun removePromptText() {
        if (promptParts.lastOrNull() is PromptPart.Text) {
            promptParts.removeLast()
        }
    }
}

fun Exchange(part: PromptPart) = Exchange().apply { promptParts.add(part) }
fun Exchange(parts: List<PromptPart>) = Exchange().apply { promptParts.addAll(parts) }

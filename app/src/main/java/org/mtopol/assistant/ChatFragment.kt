package org.mtopol.assistant

import android.Manifest.permission
import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.VIBRATOR_SERVICE
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat.checkSelfPermission
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aallam.openai.api.BetaOpenAI
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mtopol.assistant.databinding.FragmentMainBinding
import java.io.File
import kotlin.math.log2

@OptIn(BetaOpenAI::class)
class ChatFragment : Fragment(), MenuProvider {

    private var _binding: FragmentMainBinding? = null
    private var _mediaRecorder: MediaRecorder? = null
    private var _recordingGlowJob: Job? = null

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it.isNotEmpty()) Log.i("", "User granted us the requested permissions")
        else Log.w("", "User did not grant us the requested permissions")
    }

    private val binding get() = _binding!!

    private lateinit var audioPathname: String

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val binding = FragmentMainBinding.inflate(inflater, container, false).also {
            _binding = it
        }
        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.addMenuProvider(this, viewLifecycleOwner)
        val chatAdapter = ChatAdapter(requireContext())
        binding.recyclerviewChat.apply {
            adapter = chatAdapter
            layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        }
        audioPathname = File(requireContext().cacheDir, "prompt.mp4").absolutePath
        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val chatView = binding.recyclerviewChat
        val promptView = binding.edittextPrompt
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
                        sendRecordedPrompt()
                    }
                    true
                }
                else -> false
            }
        }
        binding.buttonSend.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    vibrate()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    binding.buttonSend.performClick()
                    true
                }
                else -> false
            }
        }
        binding.buttonSend.setOnClickListener {
            val prompt = promptView.text.toString()
            if (prompt.isEmpty()) {
                return@setOnClickListener
            }
            promptView.editableText.clear()
            binding.buttonSend.isEnabled = false
            val adapter = chatView.adapter as ChatAdapter
            adapter.messages.add(MessageModel(Role.USER, StringBuilder(prompt)))
            adapter.notifyItemInserted(adapter.messages.size - 1)
            val gptReply = StringBuilder()
            viewLifecycleOwner.lifecycleScope.launch {
                openAi.value.getResponseFlow(adapter.messages, isGpt4Selected())
                    .onStart {
                        adapter.messages.add(MessageModel(Role.GPT, gptReply))
                        adapter.notifyItemInserted(adapter.messages.size - 1)
                    }
                    .onCompletion { exception ->
                        if ((exception?.message ?: "").endsWith("does not exist")) {
                            gptReply.append("GPT-4 is not yet avaiable. Sorry.")
                            adapter.notifyItemChanged(adapter.messages.size - 1)
                            scrollToBottom()
                        } else {
                            Toast.makeText(requireContext(),
                                "Something went wrong while GPT was talking",
                                Toast.LENGTH_SHORT).show()
                        }
                        binding.buttonSend.isEnabled = true
                    }
                    .collect { chunk ->
                        chunk.choices[0].delta?.content?.also {
                            gptReply.append(it)
                        }
                        adapter.notifyItemChanged(adapter.messages.size - 1)
                        scrollToBottom()
                    }
            }
        }
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
    }

    private fun startRecordingPrompt() {
        val context = requireActivity()
        // don't extract to fun, IDE inspection of permission checks will complain
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
            animateRecordingGlow(mediaRecorder)
        } catch (e: Exception) {
            Log.e("", "MediaRecorder.start() failed", e)
            removeRecordingGlow()
            stopRecording()
            setRecordingEnabled(true)
        }
    }

    private fun sendRecordedPrompt() {
        removeRecordingGlow()
        setRecordingEnabled(false)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val recordingSuccess = withContext(IO) {
                    stopRecording()
                }
                if (!recordingSuccess) {
                    return@launch
                }
                openAi.value.getTranscription(audioPathname).also {
                    binding.edittextPrompt.editableText.append(it)
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(),
                    "Something went wrong while OpenAI was listening to you",
                    Toast.LENGTH_SHORT).show()
            } finally {
                setRecordingEnabled(true)
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

    private fun setRecordingEnabled(enabled: Boolean) {
        binding.buttonRecord.apply {
            alpha = if (enabled) 1.0f else 0.5f
            isEnabled = enabled
        }
    }

    private fun animateRecordingGlow(mediaRecorder: MediaRecorder) {
        _recordingGlowJob = viewLifecycleOwner.lifecycleScope.launch {
            binding.recordingGlow.apply {
                alignWithView(binding.buttonRecord)
                visibility = View.VISIBLE
            }
            try {
                while (true) {
                    binding.recordingGlow.volume =
                        (log2(mediaRecorder.maxAmplitude.toDouble()) / 15)
                            .coerceAtLeast(0.0).coerceAtMost(1.0).toFloat()
                    delay(20)
                }
            } finally {
                binding.recordingGlow.visibility = View.INVISIBLE
            }
        }
    }

    private fun removeRecordingGlow() {
        _recordingGlowJob?.cancel()
        _recordingGlowJob = null
    }

    private fun clearChat() {
        val chatView = binding.recyclerviewChat
        val adapter = chatView.adapter as ChatAdapter
        val itemCount = adapter.itemCount
        adapter.messages.clear()
        adapter.notifyItemRangeRemoved(0, itemCount)
        binding.edittextPrompt.editableText.clear()
    }

    private fun scrollToBottom() {
        binding.scrollviewChat.post {
            if (!binding.scrollviewChat.canScrollVertically(1)) {
                return@post
            }
            binding.appbarLayout.setExpanded(false, true)
            binding.scrollviewChat.smoothScrollTo(0, binding.scrollviewChat.getChildAt(0).bottom)
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

    private fun isGpt4Selected() =
        binding.toolbar.menu.findItem(R.id.menuitem_gpt_switch).actionView!!
            .findViewById<SwitchCompat>(R.id.view_gpt_switch).isChecked
}

data class MessageModel(
    val author: Role,
    val text: StringBuilder
)

enum class Role {
    USER, GPT
}

class ChatAdapter(private val context: Context) :
    RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    val messages = mutableListOf<MessageModel>()

    override fun getItemCount() = messages.size

    class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val messageTextView: TextView = itemView.findViewById(R.id.view_message_text)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        return ChatViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.chat_message_item, parent, false)
        )
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val message = messages[position]
        holder.messageTextView.text = message.text
        holder.messageTextView.setBackgroundColor(
            when(message.author) {
                Role.USER -> context.getColorCompat(R.color.black)
                Role.GPT -> context.getColorCompat(R.color.primary)
            }
        )
    }
}

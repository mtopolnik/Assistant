package org.mtopol.assistant

import android.Manifest.permission
import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.VIBRATOR_SERVICE
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.media.AudioFormat.CHANNEL_IN_MONO
import android.media.AudioFormat.ENCODING_PCM_16BIT
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat.requestPermissions
import androidx.core.content.ContextCompat.checkSelfPermission
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aallam.openai.api.BetaOpenAI
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mtopol.assistant.databinding.FragmentMainBinding
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(BetaOpenAI::class)
class ChatFragment : Fragment(), MenuProvider {

    private var _binding: FragmentMainBinding? = null

    private val binding get() = _binding!!

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
//        binding.edittextPrompt.text.append("Are you GPT-3?")
        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val chatView = binding.recyclerviewChat
        val promptView = binding.edittextPrompt
        val recordingFlag = AtomicBoolean()
        binding.buttonRecord.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    vibrate()
                    startRecordingPrompt(recordingFlag)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    vibrate()
                    recordingFlag.set(false)
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
                openAi.getResponseFlow(adapter.messages, isGpt4Selected())
                    .onStart {
                        adapter.messages.add(MessageModel(Role.GPT, gptReply))
                        adapter.notifyItemInserted(adapter.messages.size - 1)
                    }
                    .onCompletion {
                        binding.buttonSend.isEnabled = true
                    }
                    .catch { cause ->
                        if ((cause.message ?: "").endsWith("The model: `gpt-4` does not exist")) {
                            gptReply.append("GPT-4 is not yet avaiable. Sorry.")
                        }
                        adapter.notifyItemChanged(adapter.messages.size - 1)
                        scrollToBottom()
                    }
                    .collect { completion ->
                        completion.choices[0].delta?.content?.also {
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
                clearChatHistory()
                true
            }
            else -> false
        }
    }

    private fun startRecordingPrompt(recordingFlag: AtomicBoolean) {
        requestPermissions()
        if (checkSelfPermission(requireContext(), permission.RECORD_AUDIO) == PERMISSION_GRANTED) {
            Toast.makeText(requireContext(), "Missing permission to record audio", Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            recordingFlag.set(true)
            launch {
                delay(DateUtils.MINUTE_IN_MILLIS)
                recordingFlag.set(false)
            }
            val recording = withContext(IO) {
                try {
                    val bufferSize = AudioRecord.getMinBufferSize(44100, CHANNEL_IN_MONO, ENCODING_PCM_16BIT)
                    val output = ByteArrayOutputStream()
                    val buffer = ByteBuffer.allocate(2 * bufferSize)
                    val audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        44100, CHANNEL_IN_MONO, ENCODING_PCM_16BIT,
                        bufferSize
                    )
                    audioRecord.startRecording()
                    while (isActive && recordingFlag.get()) {
                        audioRecord.read(buffer, 2 * bufferSize)
                        buffer.flip()
                        output.write(buffer.array(), buffer.position(), buffer.limit())
                        buffer.clear()
                    }
                    output.toByteArray()
                } finally {
                    recordingFlag.set(false)
                }
            }
        }
    }

    private fun requestPermissions() {
        val context = requireActivity()
        if (checkSelfPermission(context, permission.RECORD_AUDIO) != PERMISSION_GRANTED) {
            requestPermissions(context, arrayOf(permission.RECORD_AUDIO), 1)
        }
    }

    private fun clearChatHistory() {
        vibrate()
        val chatView = binding.recyclerviewChat
        val adapter = chatView.adapter as ChatAdapter
        val itemCount = adapter.itemCount
        adapter.messages.clear()
        adapter.notifyItemRangeRemoved(0, itemCount)
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

package org.mtopol.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.VIBRATOR_SERVICE
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aallam.openai.api.BetaOpenAI
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.mtopol.assistant.databinding.FragmentMainBinding

@OptIn(BetaOpenAI::class)
class ChatFragment : Fragment() {

    private var _binding: FragmentMainBinding? = null

    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val binding = FragmentMainBinding.inflate(inflater, container, false).also {
            _binding = it
        }
        (requireActivity() as AppCompatActivity).setSupportActionBar(binding.toolbar)
        binding.recyclerviewChat.apply {
            adapter = ChatAdapter(requireContext())
            layoutManager = LinearLayoutManager(requireContext())
        }

        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val chatView = binding.recyclerviewChat
        val promptView = binding.edittextPrompt
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
            val adapter = chatView.adapter as ChatAdapter
            adapter.messages.add(ChatMessage(UserOrGpt.USER, StringBuilder(prompt)))
            adapter.notifyItemInserted(adapter.messages.size - 1)
            val gptReply = StringBuilder()
            viewLifecycleOwner.lifecycleScope.launch {
                openAi.getResponseFlow(prompt)
                    .onStart {
                        adapter.messages.add(ChatMessage(UserOrGpt.GPT, gptReply))
                        adapter.notifyItemInserted(adapter.messages.size - 1)
                        binding.recyclerviewChat.smoothScrollToPosition(adapter.itemCount - 1)
                    }
                    .collect { completion ->
                        completion.choices[0].delta?.content?.also {
                            gptReply.append(it)
                            adapter.notifyItemChanged(adapter.messages.size - 1)
                        }
                    }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (requireContext().getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            requireContext().getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(20)
        }

    }
}

data class ChatMessage(
    val author: UserOrGpt,
    val text: StringBuilder
)

enum class UserOrGpt {
    USER, GPT
}

class ChatAdapter(private val context: Context) :
    RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    val messages = mutableListOf<ChatMessage>()

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
                UserOrGpt.USER -> context.getColorCompat(R.color.primary)
                UserOrGpt.GPT -> context.getColorCompat(R.color.black)
            }
        )
    }

    override fun getItemCount() = messages.size
}

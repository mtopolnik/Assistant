package org.mtopol.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.VIBRATOR_SERVICE
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat.getSystemService
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
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
//        binding.textviewChat.text = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Proin aliquet metus eget" +
//                " sapien euismod, vel laoreet eros tempor. Suspendisse malesuada lectus vel turpis vestibulum, quis" +
//                " gravida metus commodo. Aliquam consectetur nisl in magna feugiat, non finibus lectus venenatis.\n\n" +
//                (1..50).joinToString("\n")
        (requireActivity() as AppCompatActivity).setSupportActionBar(binding.toolbar)
        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val chatView = binding.textviewChat
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
            viewLifecycleOwner.lifecycleScope.launch {
                openAi.getResponseFlow(prompt)
                    .onStart {
                        chatView.text = ""
                        promptView.editableText.clear()
                    }
                    .collect { completion ->
                        completion.choices[0].delta?.content?.also {
                            chatView.append(it)
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

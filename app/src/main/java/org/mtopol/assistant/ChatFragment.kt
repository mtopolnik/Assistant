package org.mtopol.assistant

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.aallam.openai.api.BetaOpenAI
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
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
//        val chatView = binding.textviewChat
//        val promptView = binding.edittextPrompt
//        binding.buttonSend.setOnClickListener {
//            viewLifecycleOwner.lifecycleScope.launch {
//                openAi.getResponseFlow(promptView.text.toString())
//                    .onStart {
//                        chatView.text = ""
//                        promptView.editableText.clear()
//                    }
//                    .collect { completion ->
//                        completion.choices[0].delta?.content?.also {
//                            Log.i("", "Append $it")
//                            chatView.append(it)
//                        } ?: Log.i("", "Chunk is empty")
//                    }
//            }
//        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

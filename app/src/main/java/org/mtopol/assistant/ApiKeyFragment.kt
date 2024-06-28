/*
 * Copyright (c) 2023 Marko Topolnik.
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

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import org.mtopol.assistant.databinding.FragmentApiKeyBinding

class ApiKeyFragment : Fragment() {

    private var _binding: FragmentApiKeyBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        Log.i("lifecycle", "onCreateView ApiKeyFragment")
        val binding = FragmentApiKeyBinding.inflate(inflater, container, false).also {
            _binding = it
        }
        binding.buttonApikeyOk.setOnClickListener {
            val apiKey = binding.edittextApiKey.text.toString().trim()
            if (apiKey.looksLikeApiKey()) {
                requireContext().mainPrefs.applyUpdate {
                    if (apiKey.looksLikeAnthropicKey()) {
                        setAnthropicApiKey(apiKey)
                    } else {
                        setOpenaiApiKey(apiKey)
                    }
                }
                resetClients()
            } else {
                Toast.makeText(appContext, "This doesn't look like an API key", Toast.LENGTH_LONG).show()
            }
            if (ApiKeyWallet(requireContext().mainPrefs).isNotEmpty()) {
                findNavController().navigate(R.id.fragment_chat, null,
                    NavOptions.Builder().setPopUpTo(R.id.fragment_api_key, true).build())
            }
        }
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.i("lifecycle", "onDestroyView ApiKeyFragment")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i("lifecycle", "onDestroy ApiKeyFragment")
    }
}

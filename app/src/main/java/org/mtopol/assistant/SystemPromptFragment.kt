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
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.MenuProvider
import androidx.navigation.fragment.findNavController
import org.mtopol.assistant.databinding.FragmentChatBinding
import org.mtopol.assistant.databinding.FragmentSystemPromptBinding

class SystemPromptFragment : Fragment(), MenuProvider {

    private lateinit var binding: FragmentSystemPromptBinding

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentSystemPromptBinding.inflate(inflater, container, false)
        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(R.string.title_edit_system_prompt)
        }
        activity.addMenuProvider(this, viewLifecycleOwner)

        val edittextPrompt = binding.edittextSystemPrompt
        edittextPrompt.setText(requireContext().mainPrefs.systemPrompt)
        binding.buttonSaveSystemPrompt.setOnClickListener {
            requireContext().mainPrefs.applyUpdate {
                setSystemPrompt(edittextPrompt.text.toString())
            }
            findNavController().popBackStack()
        }
        return binding.root
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.menu_systemprompt, menu)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_reset -> {
                binding.edittextSystemPrompt.setText(appContext.getString(R.string.system_prompt_default))
            }
            android.R.id.home -> {
                findNavController().popBackStack()
                return true
            }
        }
        return false
    }
}

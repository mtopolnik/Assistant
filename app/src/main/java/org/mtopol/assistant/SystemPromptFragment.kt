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
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.mtopol.assistant.databinding.FragmentSystemPromptBinding
import java.util.*

class SystemPromptFragment : Fragment(), MenuProvider {

    private val defaultSystemPrompt = appContext.getString(R.string.system_prompt_default)

    private lateinit var binding: FragmentSystemPromptBinding

    private var _translationJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentSystemPromptBinding.inflate(inflater, container, false)
        (requireActivity() as AppCompatActivity).apply {
            setSupportActionBar(binding.toolbar)
            supportActionBar?.apply {
                setDisplayHomeAsUpEnabled(true)
                setTitle(R.string.title_edit_system_prompt)
            }
            addMenuProvider(this@SystemPromptFragment, viewLifecycleOwner)
        }

        val edittextPrompt = binding.edittextSystemPrompt
        edittextPrompt.apply {
            setText(requireContext().mainPrefs.systemPrompt)
        }
        binding.buttonTranslate.setOnClickListener {
            createTranslationMenu().apply {
                setOnMenuItemClickListener { item ->
                    binding.buttonSaveSystemPrompt.isEnabled = false
                    binding.buttonTranslate.isEnabled = false
                    _translationJob = viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            openAi.translation(
                                item.title.toString(), edittextPrompt.text.toString(), appContext.mainPrefs.isGpt4
                            )
                                .onStart { edittextPrompt.editableText.clear() }
                                .collect { edittextPrompt.editableText.append(it) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e("translation", "Error in translation", e)
                            Toast.makeText(appContext,
                                getString(R.string.translation_error), Toast.LENGTH_SHORT)
                                .show()
                        } finally {
                            binding.buttonSaveSystemPrompt.isEnabled = true
                            binding.buttonTranslate.isEnabled = true
                            _translationJob = null
                        }
                    }
                    true
                }
                show()
            }
        }
        binding.buttonSaveSystemPrompt.setOnClickListener {
            requireContext().mainPrefs.applyUpdate {
                setSystemPrompt(edittextPrompt.text.toString().takeIf { it != defaultSystemPrompt })
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
            R.id.action_undo -> {
                viewLifecycleOwner.lifecycleScope.launch {
                    _translationJob?.apply { cancel(); join() }
                    binding.edittextSystemPrompt.setText(appContext.mainPrefs.systemPrompt)
                }
            }
            R.id.action_reset -> {
                viewLifecycleOwner.lifecycleScope.launch {
                    _translationJob?.apply { cancel(); join() }
                    binding.edittextSystemPrompt.setText(defaultSystemPrompt)
                }
            }
            android.R.id.home -> {
                findNavController().popBackStack()
                return true
            }
        }
        return false
    }

    private fun createTranslationMenu(): PopupMenu {
        val autoItemId = Menu.FIRST
        val itemIdOffset = autoItemId + 1
        val pop = PopupMenu(requireContext(), binding.buttonTranslate, Gravity.START)
        val systemLocales = systemLocales()
        for ((i, locale) in systemLocales.withIndex()) {
            pop.menu.add(Menu.NONE, i + itemIdOffset, Menu.NONE,
                locale.getDisplayLanguage(Locale.ENGLISH).capitalizeFirstLetter()
            )
        }
        return pop
    }
}

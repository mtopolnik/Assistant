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
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText

class SystemPromptFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_system_prompt, container, false)
        val edittextPrompt = view.findViewById<EditText>(R.id.editTextPrompt)
        val buttonSave = view.findViewById<Button>(R.id.btnSave)
        edittextPrompt.setText(requireContext().mainPrefs.systemPrompt)
        buttonSave.setOnClickListener {
            requireContext().mainPrefs.applyUpdate {
                setSystemPrompt(edittextPrompt.text.toString())
            }
        }
        return view
    }
}

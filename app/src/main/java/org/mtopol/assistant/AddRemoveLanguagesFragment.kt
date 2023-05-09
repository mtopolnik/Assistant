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

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.SearchView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ItemTouchHelper.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.mtopol.assistant.databinding.FragmentEditLanguagesBinding
import org.mtopol.assistant.databinding.LanguageDialogBinding
import java.util.*

class AddRemoveLanguagesFragment : Fragment(), MenuProvider {

    private val configuredLanguages = appContext.mainPrefs.configuredLanguages()

    private lateinit var recyclerViewAdapter: LangRecyclerViewAdapter
    private lateinit var binding: FragmentEditLanguagesBinding

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentEditLanguagesBinding.inflate(inflater, container, false)
        (requireActivity() as AppCompatActivity).also {activity ->
            activity.setSupportActionBar(binding.toolbar)
            activity.supportActionBar?.apply {
                setDisplayHomeAsUpEnabled(true)
                setTitle(R.string.title_edit_languages)
            }
            activity.addMenuProvider(this, viewLifecycleOwner)
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerViewAdapter = LangRecyclerViewAdapter().apply { setLanguages(configuredLanguages) }
        binding.recyclerviewLanguages.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = recyclerViewAdapter
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
            val callback = SimpleItemTouchHelperCallback(recyclerViewAdapter)
            val touchHelper = ItemTouchHelper(callback)
            touchHelper.attachToRecyclerView(this)
            recyclerViewAdapter.itemTouchHelper = touchHelper
        }
        binding.fabAddLanguage.setOnClickListener { showLanguageSelectionDialog() }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.menu_edit_languages, menu)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_reset -> {
                recyclerViewAdapter.setLanguages(systemLanguages())
                true
            }
            android.R.id.home -> {
                findNavController().popBackStack()
                true
            }
            else -> false
        }
    }

    override fun onPause() {
        super.onPause()
        val prefs = appContext.mainPrefs
        prefs.applyUpdate {
            setConfiguredLanguages(recyclerViewAdapter.languages().takeIf { it != systemLanguages() })
        }
        val configuredLanguages = prefs.configuredLanguages()
        val chosenLanguage = prefs.speechRecogLanguage
        if (!configuredLanguages.contains(chosenLanguage)) {
            prefs.applyUpdate { setSpeechRecogLanguage(configuredLanguages.first()) }
        }
    }

    private fun showLanguageSelectionDialog() {
        val inflater = layoutInflater
        val binding = LanguageDialogBinding.inflate(inflater)
        val adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_list_item_1,
            availableLanguages().toTypedArray()
        )
        binding.listviewLanguages.adapter = adapter
        binding.viewSearch.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(newText: String?): Boolean {
                adapter.filter.filter(newText)
                return false
            }
        })
        binding.viewSearch.requestFocus()
        val addLanguageDialog = AlertDialog.Builder(requireContext()).setView(binding.root).create()
        binding.listviewLanguages.setOnItemClickListener { _, _, position, _ ->
            adapter.getItem(position)?.also {
                recyclerViewAdapter.addLanguageItem(it)
            }
            addLanguageDialog.dismiss()
        }
        addLanguageDialog.show()
    }

    private fun availableLanguages(): List<LanguageItem> {
        val languages = mutableSetOf<LanguageItem>()
        val languagesInUse = recyclerViewAdapter.languages().toSet()
        for (locale in Locale.getAvailableLocales()) {
            if (locale.language.isNotEmpty() && !languagesInUse.contains(locale.language)) {
                languages.add(LanguageItem(locale.language))
            }
        }
        return languages.sortedBy { it.label }
    }
}

class LanguageItem(val language: String) {
    val label = language.toDisplayLanguage()

    override fun equals(other: Any?) = this === other ||
            this.javaClass == other?.javaClass &&
            this.language == (other as LanguageItem).language
    override fun hashCode(): Int = language.hashCode()
    override fun toString() = label
}

class LangRecyclerViewAdapter : RecyclerView.Adapter<LangRecyclerViewAdapter.LanguageViewHolder>() {
    private val languages = mutableListOf<LanguageItem>()

    inner class LanguageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dragHandle: ImageView = view.findViewById(R.id.drag_handle)
        val languageText: TextView = view.findViewById(R.id.textview_language)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setLanguages(newLanguages: List<String>) {
        if (newLanguages.isEmpty()) {
            throw IllegalArgumentException("Can't set to empty locale list")
        }
        languages.clear()
        languages.addAll(newLanguages.map { LanguageItem(it) })
        notifyDataSetChanged()
    }

    fun languages(): List<String> = languages.map { it.language }

    fun addLanguageItem(language: LanguageItem) {
        languages.add(language)
        notifyItemInserted(languages.size - 1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LanguageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.menuitem_language, parent, false)
        return LanguageViewHolder(view)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: LanguageViewHolder, position: Int) {
        holder.languageText.text = languages[position].label

        holder.dragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                itemTouchHelper.startDrag(holder)
            }
            false
        }
    }

    override fun getItemCount(): Int {
        return languages.size
    }

    fun onItemMove(fromPosition: Int, toPosition: Int) {
        Collections.swap(languages, fromPosition, toPosition)
        notifyItemMoved(fromPosition, toPosition)
    }

    fun onItemDismiss(position: Int) {
        languages.removeAt(position)
        notifyItemRemoved(position)
    }

    lateinit var itemTouchHelper: ItemTouchHelper
}

class SimpleItemTouchHelperCallback(private val adapter: LangRecyclerViewAdapter) : ItemTouchHelper.Callback() {

    override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) =
        makeMovementFlags(UP or DOWN, LEFT or RIGHT)

    override fun onMove(
        recyclerView: RecyclerView, source: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
    ): Boolean {
        adapter.onItemMove(source.adapterPosition, target.adapterPosition)
        return true
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        adapter.onItemDismiss(viewHolder.adapterPosition)
    }

    override fun isItemViewSwipeEnabled() = adapter.itemCount > 1

    override fun isLongPressDragEnabled() = true
}

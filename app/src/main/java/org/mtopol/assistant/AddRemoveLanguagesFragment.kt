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
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ItemTouchHelper.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.mtopol.assistant.databinding.FragmentEditLanguagesBinding
import org.mtopol.assistant.databinding.LanguageDialogBinding
import java.util.*

class AddRemoveLanguagesFragment : Fragment(), MenuProvider {

    private lateinit var recyclerViewAdapter: LangRecyclerViewAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = FragmentEditLanguagesBinding.inflate(inflater, container, false)
        (requireActivity() as AppCompatActivity).apply {
            setSupportActionBar(binding.toolbar)
            supportActionBar?.apply {
                setDisplayHomeAsUpEnabled(true)
                setTitle(R.string.title_edit_languages)
            }
            addMenuProvider(this@AddRemoveLanguagesFragment, viewLifecycleOwner)
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerViewAdapter = LangRecyclerViewAdapter(mutableListOf())

        view.findViewById<RecyclerView>(R.id.languages_recycler_view).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = recyclerViewAdapter
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
            val callback = SimpleItemTouchHelperCallback(recyclerViewAdapter)
            val touchHelper = ItemTouchHelper(callback)
            touchHelper.attachToRecyclerView(this)
            recyclerViewAdapter.itemTouchHelper = touchHelper
        }

        view.findViewById<FloatingActionButton>(R.id.fab_add_language).setOnClickListener {
            showLanguageSelectionDialog()
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
        val addLanguageDialog = AlertDialog.Builder(requireContext()).setView(binding.root).create()
        binding.listviewLanguages.setOnItemClickListener { _, _, position, _ ->
            adapter.getItem(position)?.also {
                recyclerViewAdapter.addLanguage(it)

            }
            addLanguageDialog.dismiss()
        }
        addLanguageDialog.show()
    }

    private fun availableLanguages(): List<LanguageItem> {
        val languages = mutableSetOf<LanguageItem>()
        val defaultLocale = systemLocales().first()
        val languagesInUse = recyclerViewAdapter.languages().map { it.languageId }.toSet()
        for (locale in Locale.getAvailableLocales()) {
            val languageId = locale.language
            if (languageId.isNotEmpty() && !languagesInUse.contains(languageId)) {
                languages.add(LanguageItem(languageId, locale.getDisplayLanguage(defaultLocale)))
            }
        }
        return languages.sortedBy { it.label }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        return true
    }
}

class LanguageItem(val languageId: String, displayLanguage: String) {
    val label = displayLanguage.capitalizeFirstLetter()

    override fun equals(other: Any?) = this === other ||
            this.javaClass == other?.javaClass &&
            this.languageId == (other as LanguageItem).languageId
    override fun hashCode(): Int = languageId.hashCode()
    override fun toString() = label
}

class LangRecyclerViewAdapter(private val languages: MutableList<LanguageItem>) :
    RecyclerView.Adapter<LangRecyclerViewAdapter.LanguageViewHolder>() {

    inner class LanguageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dragHandle: ImageView = view.findViewById(R.id.drag_handle)
        val languageText: TextView = view.findViewById(R.id.textview_language)
    }

    fun languages(): List<LanguageItem> = languages

    fun addLanguage(language: LanguageItem) {
        languages.add(language)
        notifyItemInserted(languages.size - 1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LanguageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.language_item, parent, false)
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

    override fun isItemViewSwipeEnabled() = true

    override fun isLongPressDragEnabled() = true
}

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
import android.app.AlertDialog
import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity

const val TAG_ABOUT = "dialog_about"

fun showAboutDialogFragment(activity: FragmentActivity) {
    AboutDialogFragment().show(activity.supportFragmentManager, TAG_ABOUT)
}

class AboutDialogFragment : DialogFragment() {

    @SuppressLint("InflateParams")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val activity = requireActivity()
        val rootView = layoutInflater.inflate(R.layout.fragment_about, null)
        val version =
            try { activity.packageManager?.getPackageInfo(activity.packageName, 0)?.versionName }
            catch (e: Exception) { null } ?: "??"
        rootView.findViewById<TextView>(R.id.textview_about)?.apply {
            text = getString(R.string.about_text, version)
        }
        return AlertDialog.Builder(activity)
            .setTitle(R.string.app_name)
            .setIcon(R.mipmap.ic_launcher)
            .setView(rootView)
            .setPositiveButton(android.R.string.ok) { _, _ -> }
            .create()
    }
}

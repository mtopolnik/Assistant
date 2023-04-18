/*
 * Copyright (C) 2023 Marko Topolnik
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

import android.util.Log
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView

class ChatScrollListener() : NestedScrollView.OnScrollChangeListener {
    var isAutoScrollEnabled = true
        private set

    override fun onScrollChange(v: NestedScrollView, scrollX: Int, scrollY: Int, oldScrollX: Int, oldScrollY: Int) {
        val recyclerView = v.findViewById<RecyclerView>(R.id.view_chat)
        val recyclerViewBottom = recyclerView.bottom
        val nestedScrollViewBottom = v.height + v.scrollY
        Log.i("", "recyclerViewBottom $recyclerViewBottom nestedScrollViewBottom $nestedScrollViewBottom")
        isAutoScrollEnabled = recyclerViewBottom <= nestedScrollViewBottom
    }
}

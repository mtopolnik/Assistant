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

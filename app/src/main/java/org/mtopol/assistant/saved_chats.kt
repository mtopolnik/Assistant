/*
 * Copyright (c) 2024 Marko Topolnik.
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

import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

private const val MAX_SAVED_CHATS = 20
private val chatFileRegex = """^chat-(\d+)\.parcel$""".toRegex()

private fun chatFilename(chatId: Int): String = "chat-%d.parcel".format(chatId)
private fun chatTitleFilename(chatId: Int): String = "chat-title-%d.txt".format(chatId)

class ChatHandle(
    val chatId: Int,
    var title: String = ""
) {
    override fun equals(other: Any?): Boolean =
        this === other || javaClass == other?.javaClass && chatId == (other as ChatHandle).chatId

    override fun hashCode(): Int = chatId
}

private val chatHandles: MutableList<ChatHandle> = appContext
    .fileList()
    .mapNotNull { fname -> chatFileRegex.find(fname)?.groups?.get(1)?.value?.let { idStr ->
        val chatId = idStr.toInt()
        ChatHandle(chatId, loadChatTitle(chatId))
    } }
    .toMutableList()
    .apply {
        sortByDescending { it.chatId }
        while (size > MAX_SAVED_CHATS) {
            val oldestId = removeLast().chatId
            deleteChatFiles(oldestId)
        }
        reverse()
        val lastChat = lastOrNull()
        if (lastChat == null) {
            add(ChatHandle(1))
        } else if (chatFileExists(lastChat.chatId)) {
            add(ChatHandle(lastChat.chatId + 1))
        }
    }

fun chatHandles(): List<ChatHandle> = chatHandles

fun lastChatId(): Int = lastChatHandle().chatId

fun lastChatHandle(): ChatHandle = chatHandles.last()

fun deleteChat(chatId: Int) {
    val handle = ChatHandle(chatId)
    if (handle !in chatHandles) {
        Log.i("chats", "Request to delete #$chatId not in chatIds $chatHandles")
        return
    }
    deleteChatFiles(chatId)
    if (handle != lastChatHandle()) {
        chatHandles.remove(handle)
    } else if (chatHandles.size == 1) {
        chatHandles.clear()
        chatHandles.add(ChatHandle(1))
    }
    Log.i("chats", "Deleted chat #$chatId")
}

private fun deleteChatFiles(chatId: Int) {
    appContext.deleteFile(chatFilename(chatId))
    appContext.deleteFile(chatTitleFilename(chatId))
}

fun saveChatTitle(chatId: Int, title: String) {
    appContext.openFileOutput(chatTitleFilename(chatId), Context.MODE_PRIVATE).use { outputStream ->
        outputStream.write(title.toByteArray(StandardCharsets.UTF_8))
    }
}

fun saveChatContent(chatId: Int, history: List<Exchange>) {
    val chatFileExisted = chatFileExists(chatId)
    appContext.openFileOutput(chatFilename(chatId), Context.MODE_PRIVATE).use { outputStream ->
        history.forEach {
            val parcel = Parcel.obtain()
            try {
                it.writeToParcel(parcel, 0)
                outputStream.writeInt(parcel.dataSize())
                outputStream.write(parcel.marshall())
            } catch (e: Exception) {
                Log.e("chats", "Failed to save chat history", e)
            } finally {
                parcel.recycle()
            }
        }
    }
    if (chatId == lastChatId() && !chatFileExisted) {
        chatHandles.add(ChatHandle(chatId + 1))
    }
    Log.i("chats", "Saved chat #$chatId")
    while (chatHandles.size > MAX_SAVED_CHATS) {
        val oldestId = chatHandles.removeFirst().chatId
        deleteChatFiles(oldestId)
    }
}

fun chatFileExists(chatId: Int) =
    try {
        appContext.openFileInput(chatFilename(chatId)).use { true }
    } catch (e: FileNotFoundException) {
        false
    }

fun loadChatTitle(chatId: Int): String {
    val filename = chatTitleFilename(chatId)
    return try {
        appContext.openFileInput(filename).use { inputStream ->
            String(inputStream.readBytes(), StandardCharsets.UTF_8)
        }
    } catch (e: Exception) {
        Log.i("chats", "Couldn't load chat title from $filename, ${e.message}")
        ""
    }
}

fun loadChatContent(chatId: Int?): MutableList<Exchange> {
    val list = mutableListOf<Exchange>()
    if (chatId == null) {
        return list
    }
    val filename = chatFilename(chatId)
    try {
        appContext.openFileInput(filename).use { inputStream ->
            while (true) {
                val item: Exchange = inputStream.readExchange() ?: break
                Log.i("chats", "Loaded exchange: ${item.promptText()}")
                list += item
            }
        }
    } catch (e: Exception) {
        Log.i("chats", "Couldn't load chat content from $filename, ${e.message}")
    }
    return list
}

@Suppress("UNCHECKED_CAST")
private val exchangeCreator = Exchange::class.java.getField("CREATOR").get(null) as Parcelable.Creator<Exchange>

private fun InputStream.readExchange(): Exchange? {
    try {
        val size = readInt()
        if (size < 0) {
            return null
        }
        val bytes = ByteArray(size)
        if (read(bytes) != size) {
            return null
        }
        val parcel = Parcel.obtain()
        try {
            parcel.unmarshall(bytes, 0, size)
            parcel.setDataPosition(0)
            return exchangeCreator.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    } catch (e: Exception) {
        Log.e("chats", "Error while loading Exchange", e)
        return null
    }
}

private fun OutputStream.writeInt(value: Int) = write(
    ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
)

private fun InputStream.readInt(): Int {
    val intBuf = ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    if (read(intBuf.array()) < intBuf.capacity()) {
        return -1
    }
    return intBuf.getInt()
}

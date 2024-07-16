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

private const val MAX_SAVED_CHATS = 5
private const val CHAT_FILE_FORMAT = "chat-%d.parcel"
private val chatFileRegex = """^chat-(\d+)\.parcel$""".toRegex()

private val chatIds: MutableList<Int> = appContext
    .fileList()
    .mapNotNull { fname -> chatFileRegex.find(fname)?.groups?.get(1)?.value?.toInt() }
    .toMutableList()
    .apply {
        sortDescending()
        while (size > MAX_SAVED_CHATS) {
            val oldestId = removeLast()
            appContext.deleteFile(CHAT_FILE_FORMAT.format(oldestId))
        }
        reverse()
        add((lastOrNull() ?: 0) + 1)
    }

fun chatIds(): List<Int> = chatIds

fun lastChatId() = chatIds.last()

fun deleteChat(chatId: Int) {
    if (chatId !in chatIds) {
        Log.i("chats", "Request to delete #$chatId not in chatIds $chatIds")
        return
    }
    appContext.deleteFile(CHAT_FILE_FORMAT.format(chatId))
    if (chatId != lastChatId()) {
        chatIds.remove(chatId)
    } else if (chatIds.size == 1) {
        chatIds.clear()
        chatIds.add(1)
    }
    Log.i("chats", "Deleted chat #$chatId")
}

fun saveChat(chatId: Int, history: List<Exchange>) {
    val chatFileExisted = chatFileExists(chatId)
    appContext.openFileOutput(CHAT_FILE_FORMAT.format(chatId), Context.MODE_PRIVATE).use { outputStream ->
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
        chatIds.add(chatId + 1)
    }
    Log.i("chats", "Saved chat #$chatId")
    while (chatIds.size > MAX_SAVED_CHATS) {
        val oldestId = chatIds.removeFirst()
        appContext.deleteFile(CHAT_FILE_FORMAT.format(oldestId))
    }
}

fun chatFileExists(chatId: Int) =
    try {
        appContext.openFileInput(CHAT_FILE_FORMAT.format(chatId)).use { true }
    } catch (e: FileNotFoundException) {
        false
    }

fun loadChatHistory(chatId: Int?): MutableList<Exchange> {
    val list = mutableListOf<Exchange>()
    if (chatId == null) {
        return list
    }
    val filename = CHAT_FILE_FORMAT.format(chatId)
    try {
        appContext.openFileInput(filename).use { inputStream ->
            while (true) {
                val item: Exchange = inputStream.readExchange() ?: break
                Log.i("chats", "Loaded exchange: ${item.promptText()}")
                list += item
            }
        }
    } catch (e: Exception) {
        Log.i("chats", "Couldn't load chat history from $filename, ${e.message}")
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

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
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val CHAT_FILE_FORMAT = "chat-%d.parcel"
private val chatFileRegex = """^chat-(\d+)\.parcel$""".toRegex()

private val chatIds: MutableList<Int> = appContext
    .fileList()
    .mapNotNull { fname -> chatFileRegex.find(fname)?.groups?.get(1)?.value?.toInt() }
    .toMutableList()
    .apply { sort() }

fun lastChatId(): Int? = chatIds.lastOrNull()

fun saveChatHistory(history: List<Exchange>, chatId: Int) {
    appContext.openFileOutput(CHAT_FILE_FORMAT.format(chatId), Context.MODE_PRIVATE).use { outputStream ->
        history.forEach {
            val parcel = Parcel.obtain()
            try {
                it.writeToParcel(parcel, 0)
                outputStream.writeInt(parcel.dataSize())
                outputStream.write(parcel.marshall())
            } finally {
                parcel.recycle()
            }
        }
    }
}

fun restoreChatHistory(chatId: Int?): MutableList<Exchange> {
    val list = mutableListOf<Exchange>()
    if (chatId == null) {
        return list
    }
    val filename = CHAT_FILE_FORMAT.format(chatId)
    try {
        appContext.openFileInput(filename).use { inputStream ->
            while (true) {
                val item: Exchange = inputStream.readExchange() ?: break
                list += item
            }
        }
    } catch (e: Exception) {
        Log.e("lifecycle", "Failed to restore chat history from $filename", e)
    }
    return list
}

@Suppress("UNCHECKED_CAST")
private val exchangeCreator = Exchange::class.java.getField("CREATOR").get(null) as Parcelable.Creator<Exchange>

private  fun InputStream.readExchange(): Exchange? {
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
        Log.e("lifecycle", "Error while reading parcelable list", e)
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

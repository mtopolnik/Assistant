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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.deleteIfExists
import kotlin.io.path.moveTo
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.collections.removeFirst as removeFirstKt
import kotlin.collections.removeLast as removeLastKt

private const val MAX_SAVED_CHATS = 50
private val chatFileRegex = """^chat-(\d+)\.parcel$""".toRegex()
private val promptAudioFileRegex = """^chat-(\d+)_prompt-(\d+)\.aopus$""".toRegex()
private val emptyTitle: Deferred<String> = CompletableDeferred("")

private fun chatFilename(chatId: Int): String = "chat-$chatId.parcel"
private fun chatTitleFilename(chatId: Int): String = "chat-title-$chatId.txt"
fun promptAudioFilename(chatId: Int, promptId: Int): String = "chat-${chatId}_prompt-$promptId.aopus"

class ChatHandle(
    var chatId: Int,
    var title: Deferred<String> = emptyTitle,
    var isDirty: Boolean = false
) {
    override fun equals(other: Any?): Boolean =
        this === other || javaClass == other?.javaClass && chatId == (other as ChatHandle).chatId

    override fun hashCode(): Int = chatId

    override fun toString() = "$chatId"

    fun onTitleAvailable(scope: CoroutineScope, onSuccess: (String) -> Unit) {
        if (title !== emptyTitle) {
            scope.launch {
                title.await().takeIf { it.isNotBlank() } ?.also { onSuccess(it) }
            }
        }
        title = scope.async {
            var newTitle = loadChatTitle(chatId)
            if (newTitle.isBlank()) {
                newTitle = openAi.summarizing(loadChatContent(chatId))
            }
            if (newTitle.isBlank()) {
                title = emptyTitle
            } else {
                saveChatTitle(chatId, newTitle)
                onSuccess(newTitle)
            }
            newTitle
        }
    }

    companion object {
        fun withTitle(chatId: Int, title: String) = ChatHandle(chatId, CompletableDeferred(title))
    }
}

private val chatHandles: MutableList<ChatHandle> = appContext
    .fileList()
    .mapNotNull { fname -> chatFileRegex.find(fname)?.groups?.get(1)?.value?.let { idStr ->
        val id = idStr.toInt()
        ChatHandle.withTitle(id, loadChatTitle(id))
    } }
    .toMutableList()
    .apply {
        sortByDescending { it.chatId }
        while (size > MAX_SAVED_CHATS) {
            val oldestId = removeLastKt().chatId
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

fun getChatHandle(chatId: Int): ChatHandle? = chatHandles.find { it.chatId == chatId }

fun deleteChat(chatId: Int) {
    val handle = ChatHandle(chatId)
    if (handle !in chatHandles) {
        Log.i("chats", "Request to delete #$chatId not in chatIds $chatHandles")
        return
    }
    deleteChatFiles(chatId)
    if (handle != lastChatHandle()) {
        chatHandles.remove(handle)
    }
    if (chatHandles.size == 1) {
        chatHandles.clear()
        chatHandles.add(ChatHandle(1))
    }
    Log.i("chats", "Deleted chat #$chatId")
}

private fun deleteChatFiles(chatId: Int) {
    appContext.deleteFile(chatFilename(chatId))
    appContext.deleteFile(chatTitleFilename(chatId))
    Files.newDirectoryStream(appContext.filesDir!!.toPath(), "chat-${chatId}_prompt-*.aopus")
        .use { dirStream -> dirStream.forEach { path -> path.deleteIfExists() }
    }
}

fun saveChatTitle(chatId: Int, title: String) {
    appContext.openFileOutput(chatTitleFilename(chatId), Context.MODE_PRIVATE).use { outputStream ->
        outputStream.write(title.toByteArray(StandardCharsets.UTF_8))
    }
}

fun saveChatContent(chatId: Int, history: List<Exchange>) {
    val chatFileExisted = chatFileExists(chatId)
    appContext.openFileOutput(chatFilename(chatId), Context.MODE_PRIVATE).use { outputStream ->
        history.forEach { exchange ->
            val parcel = Parcel.obtain()
            try {
                exchange.writeToParcel(parcel, 0)
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
        val oldestId = chatHandles.removeFirstKt().chatId
        deleteChatFiles(oldestId)
    }
}

fun moveChatToTop(chatId: Int): Int {
    val chatHandleToMove = getChatHandle(chatId) ?: return chatId
    val lastHandle = lastChatHandle()
    val lastNonEmpty: ChatHandle? = chatHandles.takeIf { it.size > 1 } ?.run { get(size - 2) }
    if (chatHandleToMove == lastHandle || lastNonEmpty == null || chatHandleToMove == lastNonEmpty) {
        return chatId
    }
    chatHandles.remove(chatHandleToMove)
    val lastChatHandle = chatHandles.removeLastKt()
    chatHandleToMove.chatId = lastChatHandle.chatId
    lastChatHandle.chatId++
    chatHandles.add(chatHandleToMove)
    chatHandles.add(lastChatHandle)
    val chatIdAfterMove = chatHandleToMove.chatId

    fun renameFile(fname: (Int) -> String) {
        appContext.getFileStreamPath(fname(chatId))
            .renameTo(appContext.getFileStreamPath(fname(chatIdAfterMove))).also { didRename ->
                if (!didRename) {
                    Log.e("chats", "Failed to rename chat #$chatId to #$chatIdAfterMove")
                }
            }
    }

    renameFile(::chatFilename)
    renameFile(::chatTitleFilename)
    Files.newDirectoryStream(appContext.filesDir!!.toPath(), "chat-${chatId}_prompt-*.aopus")
        .use { dirStream -> dirStream.forEach { path ->
            val promptId = promptAudioFileRegex.find(path.name)?.groups?.get(2)?.value
            path.moveTo(path.resolveSibling("chat-${chatIdAfterMove}_prompt-$promptId.aopus"))
        } }

    Log.i("chats", "Renamed chat #$chatId to #$chatIdAfterMove")
    return chatIdAfterMove
}

fun chatFileExists(chatId: Int) = appContext.getFileStreamPath(chatFilename(chatId)).let { it.isFile && it.exists() }

fun loadChatTitle(chatId: Int): String {
    val filename = chatTitleFilename(chatId)
    return try {
        appContext.openFileInput(filename).use { inputStream ->
            String(inputStream.readBytes(), StandardCharsets.UTF_8)
        }
    } catch (e: Exception) {
        Log.i("chats", "Couldn't load title of chat #$chatId, $filename not found")
        ""
    }
}

fun loadChatContent(chatId: Int?): MutableList<Exchange> {
    val exchanges = mutableListOf<Exchange>()
    if (chatId == null) {
        return exchanges
    }
    val filename = chatFilename(chatId)
    try {
        appContext.openFileInput(filename).use { inputStream ->
            while (true) {
                val exchange = inputStream.readExchange() ?: break
                exchanges += exchange
            }
        }
        Log.i("chats", "Loaded content of chatId $chatId")
    } catch (e: FileNotFoundException) {
        Log.i("chats", "Couldn't load chat #$chatId, $filename not found")
    }
    return exchanges
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

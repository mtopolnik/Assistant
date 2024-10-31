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

import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN

private val goodField = "Good".toByteArray()
private val peteField = "Pete".toByteArray()
private val headerBuf = ByteBuffer.allocate(6).order(LITTLE_ENDIAN)

class PacketWriter(
    private val outputStream: FileOutputStream
) : AutoCloseable {

    fun writePacket(buf: ByteBuffer, isLast: Boolean) {
        headerBuf.apply {
            clear()
            put(if (isLast) goodField else peteField)
            putShort(buf.remaining().toShort())
            flip()
        }
        outputStream.channel.apply {
            write(headerBuf)
            write(buf)
        }
    }

    override fun close() {
        outputStream.close()
    }
}

class PacketReader(
    private val inputStream: FileInputStream
) : AutoCloseable {
    private val headerBuf = ByteArray(6)
    private val headerByteBuf = ByteBuffer.wrap(headerBuf).order(LITTLE_ENDIAN)

    /**
     * Returns true if it read the final packet.
     */
    fun readPacket(buf: ByteBuffer): Boolean {
        val bytesRead = inputStream.read(headerBuf)
        if (bytesRead < headerBuf.size) {
            buf.clear()
            return true
        }
        val size = headerByteBuf.getShort(4).toInt()
        buf.limit(size)
        inputStream.channel.read(buf)
        return (headerBuf[0] == 'G'.code.toByte())
    }

    override fun close() {
        inputStream.close()
    }
}


// CRC lookup table for Ogg checksum calculation
private val CRC_TABLE = IntArray(256).apply {
    for (i in 0..255) {
        var r = i shl 24
        for (j in 0..7) {
            r = if ((r and 0x80000000.toInt()) != 0) {
                (r shl 1) xor 0x04c11db7
            } else {
                r shl 1
            }
        }
        this[i] = r
    }
}

private fun ByteBuffer.crc(): Int {
    val data = array()
    var crc = 0
    for (b in data) {
        crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) and 0xff) xor (b.toInt() and 0xff)]
    }
    return crc
}

class OpusWriter(
    private val outputStream: OutputStream,
) : AutoCloseable {
    private val serialNumber = 0x65746550 // spells out Pete in little-endian
    private var packetCount = 0
    private var pageSequenceNumber = 0

    fun writeHeadersFromMediaCodecFirstPacket(buf: ByteBuffer) {
        buf.position(buf.position() + 16)
        buf.limit(buf.position() + 19)
        writeOggPage(buf, 0, 0, true)
        writeOpusCommentHeader()
    }

    fun writeAudioData(audioBuf: ByteBuffer, granulePosition: Long) {
        writeOggPage(audioBuf, 0, granulePosition)
        packetCount++
    }

    override fun close() {
        outputStream.close()
    }

    private fun writeOpusCommentHeader() {
        val opusTagsBytes = "OpusTags".toByteArray()
        val vendorBytes = "Good Guy Pete".toByteArray()
        val size = opusTagsBytes.size + 4 + vendorBytes.size + 4
        val commentData = ByteBuffer.allocate(size).order(LITTLE_ENDIAN).apply {
            put(opusTagsBytes)
            putInt(vendorBytes.size)
            put(vendorBytes)
            putInt(0)  // No user comments
            flip()
        }
        writeOggPage(commentData, 0, 0, true)
    }

    private fun writeOggPage(
        dataBuf: ByteBuffer,
        headerType: Int,
        granulePos: Long,
        isHeader: Boolean = false
    ) {
        val dataSize = dataBuf.remaining()
        val numSegments = (dataSize + 255) / 255
        
        // Calculate page header size:
        // 4 bytes - "OggS"
        // 1 byte  - Stream structure version
        // 1 byte  - Header type flag
        // 8 bytes - Granule position
        // 4 bytes - Serial number
        // 4 bytes - Page sequence number
        // 4 bytes - Checksum
        // 1 byte  - Number of segments
        // N bytes - Segment table
        val headerSize = 27 + numSegments
        val page = ByteBuffer.allocate(headerSize + dataSize).order(LITTLE_ENDIAN).apply {
            put("OggS".toByteArray())
            put(0)  // Stream structure version
            put((headerType or (if (isHeader) 2 else 0)).toByte())
            putLong(granulePos)
            putInt(serialNumber)
            putInt(pageSequenceNumber++)
            val crcPos = position()
            putInt(0)  // Checksum placeholder
            put(numSegments.toByte())

            // Write segment table
            var remaining = dataSize
            for (i in 0 until numSegments) {
                put(minOf(remaining, 255).toByte())
                remaining -= 255
            }
            put(dataBuf)
            putInt(crcPos, crc())
        }
        outputStream.write(page.array())
    }
}

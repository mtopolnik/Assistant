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

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN

private const val MAX_FRAG_SIZE = 0xFF
private val magic = "GoodPete".toByteArray()

class PacketWriter {
    private val outputStream = ByteArrayOutputStream()
    private var magicWritten = false

    fun writePacket(packetBuf: ByteBuffer) {
        if (packetBuf.remaining() == 0) {
            throw IllegalArgumentException("No bytes in packet")
        }
        if (!magicWritten) {
            outputStream.write(magic)
            magicWritten = true
        }
        outputStream.apply {
            var remainingSize = packetBuf.remaining()
            do {
                val fragSize = remainingSize.coerceAtMost(MAX_FRAG_SIZE)
                remainingSize -= fragSize
                write(fragSize)
            } while (fragSize == MAX_FRAG_SIZE)
            while (packetBuf.remaining() > 0) {
                write(packetBuf.get().toInt())
            }
        }
    }

    fun consumeContent(): ByteArray = outputStream.toByteArray().also {
        outputStream.reset()
        magicWritten = false
    }
}

class PacketReader(inputBytes: ByteArray) {
    private val inputByteBuf = ByteBuffer.wrap(inputBytes)
    private var magicRead = false

    /**
     * Returns true if the input buffer is exhausted.
     */
    fun readPacket(packetBuf: ByteBuffer) {
        if (!magicRead) {
            if (inputByteBuf.remaining() < magic.size) {
                throw IOException("Input byte stream is shorter than the magic header")
            }
            magic.indices.forEach { i ->
                if (inputByteBuf.get() != magic[i]) {
                    throw IOException("Corrupt input byte stream: magic header not present")
                }
            }
            magicRead = true
        }
        if (inputByteBuf.remaining() == 0) {
            return
        }
        var packetSize = 0
        do {
            val fragSize = inputByteBuf.get().toUByte().toInt()
            packetSize += fragSize
        } while (fragSize == MAX_FRAG_SIZE)
        if (packetSize == 0) {
            throw IOException("Corrupt input byte stream: read packet size == 0")
        }
        if (packetSize > inputByteBuf.remaining()) {
            throw IOException("Corrupt input byte stream: read packet size == $packetSize, " +
                    "but only ${inputByteBuf.remaining()} bytes left to read")
        }
        if (packetSize > packetBuf.remaining()) {
            throw IOException("Not enough room in packetBuf. size == $packetSize, " +
                    "packetBuf.remaining() == ${packetBuf.remaining()}")
        }
        inputByteBuf.limit().also { limitBackup ->
            inputByteBuf.limit(inputByteBuf.position() + packetSize)
            packetBuf.put(inputByteBuf)
            inputByteBuf.limit(limitBackup)
        }
    }
}

private fun ByteArray.startsWith(prefix: ByteArray) =
    this.size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

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

class OpusOggWriter(
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

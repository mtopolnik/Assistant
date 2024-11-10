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

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import kotlin.experimental.and

private const val MAX_FRAG_SIZE = 0xFF
private val magic = "GoodPete".toByteArray()

fun presentationTimeUsToGranulePos(presentationTimeUs: Long) = presentationTimeUs * 48 / 1_000

class PacketWriter {
    private val outputStream = ByteArrayOutputStream()
    private val granulePosBuf = ByteBuffer.allocate(4).order(LITTLE_ENDIAN)
    private var magicWritten = false

    fun writePacket(packetBuf: ByteBuffer, granulePosition: Long) {
        if (packetBuf.remaining() == 0) {
            throw IllegalArgumentException("No bytes in packet")
        }
        if (!magicWritten) {
            outputStream.write(magic)
            magicWritten = true
        }
        granulePosBuf.putInt(0, granulePosition.toInt())
        outputStream.apply {
            write(granulePosBuf.array())
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
    private val inputByteBuf = ByteBuffer.wrap(inputBytes).order(LITTLE_ENDIAN)
    private var gotMagic = false
    var granulePosition = 0L

    fun readPacket(packetBuf: ByteBuffer) {
        if (!gotMagic) {
            if (inputByteBuf.remaining() < magic.size) {
                throw IOException("Input byte stream is shorter than the magic header")
            }
            magic.indices.forEach { i ->
                if (inputByteBuf.get() != magic[i]) {
                    throw IOException("Corrupt input byte stream: magic header not present")
                }
            }
            gotMagic = true
        }
        if (inputByteBuf.remaining() == 0) {
            return
        }
        granulePosition = inputByteBuf.getInt().toLong()
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

private const val FLAG_BOS: Byte = 2
private val oggCapturePattern = "OggS".toByteArray()
private val opusHead = "OpusHead".toByteArray()
private val opusTags = "OpusTags".toByteArray()

class OggOpusWriter {
    private val outputStream = ByteArrayOutputStream()
    private val serialNumber = 0x65746550 // spells out Pete in little-endian
    private var pageSequenceNumber = 0
    private var firstPacketDone = false

    fun writePacket(audioBuf: ByteBuffer, granulePosition: Long) {
        if (!firstPacketDone) {
            firstPacketDone = true
            writeHeadersFromMediaCodecFirstPacket(audioBuf)
        } else {
            writeOggPage(audioBuf, false, granulePosition)
        }
    }

    fun consumeContent(): ByteArray = outputStream.toByteArray().also {
        outputStream.reset()
        firstPacketDone = false
    }

    private fun writeHeadersFromMediaCodecFirstPacket(buf: ByteBuffer) {
        buf.position(buf.position() + 16)
        buf.limit(buf.position() + 19)
        writeOggPage(buf, true, 0)
        writeOpusCommentHeader()
    }

    private fun writeOpusCommentHeader() {
        val vendorBytes = "Good Guy Pete".toByteArray()
        val size = opusTags.size + 4 + vendorBytes.size + 4
        val commentData = ByteBuffer.allocate(size).order(LITTLE_ENDIAN).apply {
            put(opusTags)
            putInt(vendorBytes.size)
            put(vendorBytes)
            putInt(0)  // No user comments
            flip()
        }
        writeOggPage(commentData, false, 0)
    }

    private fun writeOggPage(
        dataBuf: ByteBuffer,
        isBos: Boolean,
        granulePos: Long
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
            put(oggCapturePattern)
            put(0)  // Stream structure version
            put(if (isBos) FLAG_BOS else 0)
            putLong(granulePos)
            putInt(serialNumber)
            putInt(pageSequenceNumber++)
            val crcPos = position()
            putInt(0)  // Checksum placeholder
            put(numSegments.toByte())

            // Write segment table
            var remaining = dataSize
            repeat(numSegments) {
                put(minOf(remaining, 255).toByte())
                remaining -= 255
            }
            put(dataBuf)
            putInt(crcPos, crc())
        }
        outputStream.write(page.array())
    }
}

fun analyzeOgg(input: InputStream) {
    var opusIdentificationHeaderDone = false
    var opusCommentHeaderDone = false
    while (true) {
        val numSegments: Int
        val headBuf = ByteBuffer.allocate(27).order(LITTLE_ENDIAN)
        val bytesRead = input.read(headBuf.array())
        if (bytesRead < headBuf.array().size) {
            break
        }
        if (!headBuf.array().startsWith(oggCapturePattern)) {
            Log.e("ogg", "OggS magic not present at page start: ${headBuf.array().contentToString()}")
            break
        }
        headBuf.position(oggCapturePattern.size)
        val streamStructureVersion = headBuf.get()
        val headerType = headBuf.get()
        val granulePos = headBuf.getLong()
        val streamSerialNumber = headBuf.getInt()
        val pageSequenceNumber = headBuf.getInt()
        val crc = headBuf.getInt()
        numSegments = headBuf.get().toInt()
        var dataSize = 0
        repeat(numSegments) {
            dataSize += input.read()
        }
        Log.i("ogg", "Ogg Packet version $streamStructureVersion headerType $headerType granulePos $granulePos" +
                " streamSerialNumber $streamSerialNumber pageSequenceNumber $pageSequenceNumber crc $crc dataSize $dataSize")
        val isBos = headerType and FLAG_BOS != 0.toByte()
        if (isBos == opusIdentificationHeaderDone) {
            Log.e("ogg", "Invalid BOS flag in header. BOS flag = $isBos, is first packet? ${!opusIdentificationHeaderDone}")
        }
        if (!opusIdentificationHeaderDone) {
            opusIdentificationHeaderDone = true
            val dataBuf = ByteBuffer.allocate(dataSize).order(LITTLE_ENDIAN)
            input.read(dataBuf.array())
            if (!dataBuf.array().startsWith(opusHead)) {
                Log.e("ogg", "OpusHead magic not present in Opus Identification header")
                break
            }
            dataBuf.position(opusHead.size)
            val version = dataBuf.get()
            val channelCount = dataBuf.get()
            val preSkip = dataBuf.getShort()
            val inputSampleRate = dataBuf.getInt()
            val outputGain = dataBuf.getShort()
            val channelMappingFamily = dataBuf.get()
            Log.i(
                "ogg", "OpusHead version $version channelCount $channelCount preSkip $preSkip" +
                        " inputSampleRate $inputSampleRate outputGain $outputGain channelMappingFamily $channelMappingFamily"
            )
            if (channelMappingFamily.toInt() == 0 && dataBuf.remaining() != 0) {
                Log.e("ogg", "Channel mapping family 0, but data still left in Opus Identification header")
                break
            }
        } else if (!opusCommentHeaderDone) {
            opusCommentHeaderDone = true
            val dataBuf = ByteBuffer.allocate(dataSize).order(LITTLE_ENDIAN)
            input.read(dataBuf.array())
            if (!dataBuf.array().startsWith(opusTags)) {
                Log.e("ogg", "OpusTags magic not present in Opus Comments header")
                break
            }
            dataBuf.position(opusTags.size)
            val vendorStringLen = dataBuf.getInt()
            if (vendorStringLen > 100) {
                Log.e("ogg", "Vendor string length > 100: $vendorStringLen")
                break
            }
            val vendorString = String(dataBuf.array(), dataBuf.position(), vendorStringLen)
            dataBuf.position(dataBuf.position() + vendorStringLen)
            val userCommentListLen = dataBuf.getInt()
            if (userCommentListLen > 100) {
                Log.e("ogg", "User comment list length > 100: $userCommentListLen")
                break
            }
            Log.i("ogg", "Vendor string $vendorString userCommentListLen $userCommentListLen")
        } else {
            repeat(dataSize) {
                input.read()
            }
        }
    }
}

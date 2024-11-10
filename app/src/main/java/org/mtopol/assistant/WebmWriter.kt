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

import java.io.FileOutputStream

class WebmWriter(private val outputStream: FileOutputStream) {
    private var timestampScale = 1000000 // nanoseconds

    fun writeHeader() {
        writeEBMLHeader()

        // Segment
        writeID(0x18538067)
        // Segment size unknown, write placeholder
        writeSize(-1L)
        writeSeekHead()
        writeSegmentInfo()
        writeTracks()
    }

    fun writeOpusPacket(data: ByteArray, timestamp: Long) {
        writeID(0xA3) // SimpleBlock
        val contentSize = data.size + 4 // data size + header size
        writeSize(contentSize.toLong())
        // Track number (1) with required leading bit
        outputStream.write(0x81)
        // Timestamp (relative to cluster)
        writeInt16BE((timestamp / timestampScale).toInt())
        // Flags
        outputStream.write(0x00)
        // Frame data
        outputStream.write(data)
    }

    private fun writeEBMLHeader() {
        writeID(0x1A45DFA3) // EBML
        writeSize(31)
        writeID(0x4286) // EBMLVersion
        writeSize(1)
        outputStream.write(1)
        writeID(0x42F7) // EBMLReadVersion
        writeSize(1)
        outputStream.write(1)
        writeID(0x42F2) // EBMLMaxIDLength
        writeSize(1)
        outputStream.write(4)
        writeID(0x42F3) // EBMLMaxSizeLength
        writeSize(1)
        outputStream.write(8)
        writeID(0x4282) // DocType
        writeSize(4)
        outputStream.write("webm".toByteArray())
        writeID(0x4287) // DocTypeVersion
        writeSize(1)
        outputStream.write(2)
        writeID(0x4285) // DocTypeReadVersion
        writeSize(1)
        outputStream.write(2)
    }

    private fun writeTracks() {
        writeID(0x1654AE6B) // Tracks
        val tracksContentSize = 44L
        writeSize(tracksContentSize)

        // Track Entry
        writeID(0xAE) // TrackEntry
        writeSize(38)
        writeID(0xD7) // TrackNumber
        writeSize(1)
        outputStream.write(1)
        writeID(0x73C5) // TrackUID
        writeSize(1)
        outputStream.write(1)
        writeID(0x83) // TrackType
        writeSize(1)
        outputStream.write(2) // 2 = audio
        writeID(0x86) // CodecID
        writeSize(9)
        outputStream.write("A_OPUS".toByteArray())

        // Audio specific settings
        writeID(0xE1) // Audio
        writeSize(12)
        writeID(0xB5) // SamplingFrequency
        writeSize(4)
        writeFloat(48000f) // Opus default
        writeID(0x9F) // Channels
        writeSize(1)
        outputStream.write(2) // stereo
    }

    private fun writeSeekHead() {
        writeID(0x114D9B74) // SeekHead
        writeSize(47)

        // Seek Entry for Info
        writeID(0x4DBB) // Seek
        writeSize(15)
        writeID(0x53AB) // SeekID
        writeSize(4)
        writeID(0x1549A966) // Info ID
        writeID(0x53AC) // SeekPosition
        writeSize(3)
        writeInt24BE(0) // Position from start of Segment

        // Seek Entry for Tracks
        writeID(0x4DBB) // Seek
        writeSize(15)
        writeID(0x53AB) // SeekID
        writeSize(4)
        writeID(0x1654AE6B) // Tracks ID
        writeID(0x53AC) // SeekPosition
        writeSize(3)
        writeInt24BE(0) // Position from start of Segment
    }

    private fun writeSegmentInfo() {
        writeID(0x1549A966) // Info
        writeSize(32)

        // Timestamp scale in nanoseconds
        writeID(0x2AD7B1) // TimestampScale
        writeSize(4)
        writeInt32BE(1000000) // 1ms

        // Muxing app
        writeID(0x4D80) // MuxingApp
        writeSize(12)
        outputStream.write("WebMWriter".toByteArray())

        // Writing app
        writeID(0x5741) // WritingApp
        writeSize(12)
        outputStream.write("WebMWriter".toByteArray())
    }

    private fun writeID(id: Int) {
        // Write big-endian EBML ID
        when {
            id and 0xFF000000.toInt() != 0 -> writeInt32BE(id)
            id and 0x00FF0000 != 0 -> writeInt24BE(id)
            id and 0x0000FF00 != 0 -> writeInt16BE(id)
            else -> outputStream.write(id)
        }
    }

    private fun writeSize(size: Long) {
        // EBML variable size encoding
        if (size < 126) {
            outputStream.write(size.toInt() or 0x80)
        } else {
            // Implement larger sizes as needed
            // This is a simplified version
        }
    }

    private fun writeInt16BE(value: Int) {
        outputStream.write(value shr 8)
        outputStream.write(value)
    }

    private fun writeInt24BE(value: Int) {
        outputStream.write(value shr 16)
        outputStream.write(value shr 8)
        outputStream.write(value)
    }

    private fun writeInt32BE(value: Int) {
        outputStream.write(value shr 24)
        outputStream.write(value shr 16)
        outputStream.write(value shr 8)
        outputStream.write(value)
    }

    private fun writeFloat(value: Float) {
        val bits = value.toBits()
        writeInt32BE(bits)
    }
}

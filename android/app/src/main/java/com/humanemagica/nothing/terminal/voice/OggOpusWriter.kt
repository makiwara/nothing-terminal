// Vendored from nothing-to-say@8488823 (voice/OggOpusWriter.kt). Faithful copy — re-sync, don't
// diverge. Cockpit-specific behaviour lives in the sink and the native surface, not here.
package com.humanemagica.nothing.terminal.voice

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Minimal OGG container writer for an Opus stream: an OpusHead id page, an OpusTags comment
 * page, then one audio packet per page. Enough to produce a Telegram-acceptable voice note.
 * Granule positions are in 48 kHz samples per the Opus-in-Ogg mapping.
 */
class OggOpusWriter(
    private val out: OutputStream,
    private val channels: Int = 1,
    private val sampleRate: Int = 48000,
    private val preSkip: Int = 312,
) {
    private val serial = (System.nanoTime() and 0x7fffffffL).toInt()
    private var pageSeq = 0
    private var granule = 0L

    init {
        writePage(buildIdHeader(), headerType = 0x02, granuleForPage = 0) // BOS
        writePage(buildCommentHeader(), headerType = 0x00, granuleForPage = 0)
    }

    /** One audio packet per page. [samples48k] is the 48 kHz sample count the packet adds. */
    fun writeAudioPacket(packet: ByteArray, samples48k: Int, last: Boolean) {
        granule += samples48k
        writePage(packet, headerType = if (last) 0x04 else 0x00, granuleForPage = granule)
    }

    fun finish() = out.flush()

    private fun buildIdHeader(): ByteArray {
        val b = ByteArrayOutputStream()
        b.write("OpusHead".toByteArray(Charsets.US_ASCII))
        b.write(1)
        b.write(channels)
        writeLe16(b, preSkip)
        writeLe32(b, sampleRate)
        writeLe16(b, 0)
        b.write(0)
        return b.toByteArray()
    }

    private fun buildCommentHeader(): ByteArray {
        val b = ByteArrayOutputStream()
        b.write("OpusTags".toByteArray(Charsets.US_ASCII))
        val vendor = "nts".toByteArray(Charsets.US_ASCII)
        writeLe32(b, vendor.size)
        b.write(vendor)
        writeLe32(b, 0)
        return b.toByteArray()
    }

    private fun writePage(data: ByteArray, headerType: Int, granuleForPage: Long) {
        val segs = lacing(data.size)
        val header = ByteArray(27 + segs.size)
        header[0] = 'O'.code.toByte(); header[1] = 'g'.code.toByte()
        header[2] = 'g'.code.toByte(); header[3] = 'S'.code.toByte()
        header[4] = 0
        header[5] = headerType.toByte()
        putLe64(header, 6, granuleForPage)
        putLe32(header, 14, serial)
        putLe32(header, 18, pageSeq++)
        // CRC at 22..25 left zero while computing
        header[26] = segs.size.toByte()
        for (i in segs.indices) header[27 + i] = segs[i].toByte()
        val page = header + data
        putLe32(page, 22, oggCrc(page))
        out.write(page)
    }

    /** OGG lacing: full 255-byte segments then the remainder (a trailing 0 when the length
     *  is an exact multiple of 255). */
    private fun lacing(len: Int): IntArray {
        val full = len / 255
        return IntArray(full + 1) { if (it < full) 255 else len % 255 }
    }

    private companion object {
        val CRC = IntArray(256) { i ->
            var r = i shl 24
            repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04c11db7 else r shl 1 }
            r
        }

        fun oggCrc(data: ByteArray): Int {
            var crc = 0
            for (byte in data) {
                crc = (crc shl 8) xor CRC[((crc ushr 24) xor (byte.toInt() and 0xff)) and 0xff]
            }
            return crc
        }

        fun writeLe16(b: ByteArrayOutputStream, v: Int) {
            b.write(v and 0xff); b.write((v ushr 8) and 0xff)
        }

        fun writeLe32(b: ByteArrayOutputStream, v: Int) {
            b.write(v and 0xff); b.write((v ushr 8) and 0xff)
            b.write((v ushr 16) and 0xff); b.write((v ushr 24) and 0xff)
        }

        fun putLe32(a: ByteArray, off: Int, v: Int) {
            a[off] = (v and 0xff).toByte(); a[off + 1] = ((v ushr 8) and 0xff).toByte()
            a[off + 2] = ((v ushr 16) and 0xff).toByte(); a[off + 3] = ((v ushr 24) and 0xff).toByte()
        }

        fun putLe64(a: ByteArray, off: Int, v: Long) {
            for (i in 0..7) a[off + i] = ((v ushr (8 * i)) and 0xff).toByte()
        }
    }
}

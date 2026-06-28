// Vendored from nothing-to-say@8488823 (voice/OpusRecorder.kt). Faithful copy — re-sync, don't
// diverge. Cockpit-specific behaviour lives in the sink and the native surface, not here.
package com.humanemagica.nothing.terminal.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusSignal
import java.io.File
import java.util.UUID

private const val SAMPLE_RATE = 48000
private const val FRAME = 960 // 20 ms at 48 kHz

/**
 * Captures microphone PCM, encodes Opus with Concentus, and muxes OGG into a file under the
 * recording directory. Works on API 28 (no MediaRecorder OGG/Opus dependency, no NDK).
 */
class OpusRecorder(private val dir: File) {

    data class Result(val path: String, val durationSec: Int)

    @Volatile private var recording = false
    @Volatile private var stopRequested = false

    fun isRecording(): Boolean = recording

    /** Sticky stop: honoured even if it arrives before record()'s loop starts (near-instant
     *  release), so the capture loop can never run unbounded. */
    fun stop() {
        stopRequested = true
        recording = false
    }

    /** Blocking — call on a background thread. Records until [stop]; returns the file.
     *  RECORD_AUDIO is requested up front by the activity before recording can start. */
    @SuppressLint("MissingPermission")
    fun record(): Result {
        dir.mkdirs()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val audio = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, FRAME * 2 * 4),
        )
        val encoder = OpusEncoder(SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP).apply {
            setBitrate(24000)
            setComplexity(5)
            setSignalType(OpusSignal.OPUS_SIGNAL_VOICE)
        }
        val file = File(dir, "${UUID.randomUUID()}.ogg")
        val out = file.outputStream().buffered()
        val writer = OggOpusWriter(out, channels = 1, sampleRate = SAMPLE_RATE, preSkip = encoder.lookahead)

        val pcm = ShortArray(FRAME)
        val enc = ByteArray(4000)
        var totalSamples = 0L
        var pending: ByteArray? = null

        recording = true
        audio.startRecording()
        try {
            while (!stopRequested) {
                var read = 0
                while (read < FRAME) {
                    val r = audio.read(pcm, read, FRAME - read)
                    if (r <= 0) break
                    read += r
                }
                if (read < FRAME) break
                val len = encoder.encode(pcm, 0, FRAME, enc, 0, enc.size)
                pending?.let { writer.writeAudioPacket(it, FRAME, last = false) }
                pending = enc.copyOf(len)
                totalSamples += FRAME
            }
            pending?.let { writer.writeAudioPacket(it, FRAME, last = true) } // EOS on the last page
        } finally {
            recording = false
            audio.stop()
            audio.release()
            writer.finish()
            out.close()
        }
        return Result(file.path, (totalSamples / SAMPLE_RATE).toInt())
    }
}

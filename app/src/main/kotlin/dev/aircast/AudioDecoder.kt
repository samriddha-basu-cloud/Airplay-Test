package dev.aircast

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

/**
 * Decodes any audio file Android can play (mp3, aac/m4a, flac, ogg, wav, ...) into a
 * 44.1 kHz / stereo / 16-bit PCM WAV file, which is what the AirPlay (RAOP) sender needs.
 */
object AudioDecoder {

    private const val OUT_RATE = 44100
    private const val OUT_CHANNELS = 2

    fun decodeToWav(
        context: Context,
        uri: Uri,
        out: File,
        isCancelled: () -> Boolean,
        onProgress: (Int) -> Unit,
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)

            var trackIndex = -1
            var inFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    inFormat = f
                    break
                }
            }
            if (trackIndex < 0 || inFormat == null) throw IllegalArgumentException("No audio track found in this file")

            extractor.selectTrack(trackIndex)
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inFormat, null, null, 0)
            codec.start()

            var srcRate = inFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, OUT_RATE)
            var srcChannels = inFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2)
            var pcmFloat = false
            var resampler = LinearResampler(srcRate, OUT_RATE)

            var dataBytes = 0L
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var lastPercent = -1

            BufferedOutputStream(FileOutputStream(out), 256 * 1024).use { os ->
                os.write(ByteArray(44)) // WAV header placeholder, patched at the end

                while (!outputDone) {
                    if (isCancelled()) throw CancellationException("cancelled")

                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            val newRate = f.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, srcRate)
                            val newCh = f.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, srcChannels)
                            pcmFloat = f.getIntegerOrDefault(MediaFormat.KEY_PCM_ENCODING, 2) == 4
                            if (newRate != srcRate) {
                                srcRate = newRate
                                resampler = LinearResampler(srcRate, OUT_RATE)
                            }
                            srcChannels = newCh
                        }

                        outIdx >= 0 -> {
                            val ob = codec.getOutputBuffer(outIdx)!!
                            if (info.size > 0) {
                                ob.position(info.offset)
                                ob.limit(info.offset + info.size)
                                ob.order(ByteOrder.LITTLE_ENDIAN)
                                val stereo = toStereoShorts(ob, srcChannels, pcmFloat)
                                val resampled = resampler.process(stereo)
                                val bytes = ByteBuffer.allocate(resampled.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                                bytes.asShortBuffer().put(resampled)
                                os.write(bytes.array())
                                dataBytes += bytes.capacity()
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true

                            if (durationUs > 0) {
                                val pct = ((info.presentationTimeUs * 100) / durationUs).toInt().coerceIn(0, 100)
                                if (pct != lastPercent) {
                                    lastPercent = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }
            }

            writeWavHeader(out, dataBytes)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }

    /** Converts one decoder output buffer into interleaved stereo 16-bit samples. */
    private fun toStereoShorts(buf: ByteBuffer, channels: Int, isFloat: Boolean): ShortArray {
        val ch = channels.coerceAtLeast(1)
        val samples: ShortArray
        if (isFloat) {
            val fb = buf.asFloatBuffer()
            samples = ShortArray(fb.remaining())
            for (i in samples.indices) {
                val v = (fb.get() * 32767f).toInt().coerceIn(-32768, 32767)
                samples[i] = v.toShort()
            }
        } else {
            val sb = buf.asShortBuffer()
            samples = ShortArray(sb.remaining())
            sb.get(samples)
        }
        val frames = samples.size / ch
        val out = ShortArray(frames * 2)
        for (f in 0 until frames) {
            val l = samples[f * ch]
            val r = if (ch >= 2) samples[f * ch + 1] else l
            out[f * 2] = l
            out[f * 2 + 1] = r
        }
        return out
    }

    private fun writeWavHeader(file: File, dataBytes: Long) {
        val byteRate = OUT_RATE * OUT_CHANNELS * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((36 + dataBytes).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(OUT_CHANNELS.toShort())
        header.putInt(OUT_RATE)
        header.putInt(byteRate)
        header.putShort((OUT_CHANNELS * 2).toShort())
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes.toInt())
        RandomAccessFile(file, "rw").use { it.write(header.array()) }
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default

    /** Streaming linear-interpolation resampler for interleaved stereo 16-bit audio. */
    private class LinearResampler(inRate: Int, outRate: Int) {
        private val passthrough = inRate == outRate
        private val step = inRate.toDouble() / outRate
        private var t = 0.0
        private var prevL = 0
        private var prevR = 0

        fun process(input: ShortArray): ShortArray {
            if (passthrough) return input
            val n = input.size / 2
            if (n == 0) return input

            val out = ArrayList<Short>((n / step).toInt() * 2 + 4)
            while (t < n - 1) {
                val i = Math.floor(t).toInt()
                val frac = t - i
                val aL: Int
                val aR: Int
                if (i < 0) {
                    aL = prevL
                    aR = prevR
                } else {
                    aL = input[i * 2].toInt()
                    aR = input[i * 2 + 1].toInt()
                }
                val bL = input[(i + 1) * 2].toInt()
                val bR = input[(i + 1) * 2 + 1].toInt()
                out.add((aL + (bL - aL) * frac).toInt().toShort())
                out.add((aR + (bR - aR) * frac).toInt().toShort())
                t += step
            }
            t -= n
            prevL = input[(n - 1) * 2].toInt()
            prevR = input[(n - 1) * 2 + 1].toInt()
            return out.toShortArray()
        }
    }
}

"""Minimal stand-in for the `miniaudio` package so pyatv can run on Android.

The real `miniaudio` needs native compilation and is not available for Android.
pyatv only needs it to decode audio files into PCM. This app decodes audio to a
44.1 kHz / stereo / 16-bit WAV file on the Kotlin side (MediaCodec), so this shim
only has to read such WAV files using the standard library.

Only the names that pyatv 0.16.x touches are provided.
"""

import array
import enum
import io
import wave


class SampleFormat(enum.Enum):
    UNKNOWN = 0
    UNSIGNED8 = 1
    SIGNED16 = 2
    SIGNED24 = 3
    SIGNED32 = 4
    FLOAT32 = 5


class SeekOrigin(enum.Enum):
    START = 0
    CURRENT = 1


class StreamableSource:
    """Base class kept only so pyatv's wrapper classes can subclass it."""

    def read(self, num_bytes):  # pragma: no cover
        raise NotImplementedError

    def seek(self, offset, origin):  # pragma: no cover
        raise NotImplementedError

    def close(self):  # pragma: no cover
        pass


class WavFileReadStream:  # pragma: no cover - only used for non-file sources
    def __init__(self, *args, **kwargs):
        raise NotImplementedError("Streaming sources are not supported in this build")


def stream_any(*args, **kwargs):  # pragma: no cover
    raise NotImplementedError("Streaming sources are not supported in this build")


class DecodedSoundFile:
    def __init__(self, name, nchannels, sample_rate, sample_width, samples):
        self.name = name
        self.nchannels = nchannels
        self.sample_rate = sample_rate
        self.sample_width = sample_width
        self.samples = samples
        frames = len(samples) // max(nchannels, 1)
        self.num_frames = frames
        self.duration = frames / float(sample_rate) if sample_rate else 0.0


def _open_wav(filename):
    try:
        return wave.open(filename, "rb")
    except wave.Error as ex:
        raise RuntimeError("Unsupported audio file (expected PCM WAV): %s" % ex)


def get_file_info(filename):
    with _open_wav(filename) as wav:
        return DecodedSoundFile(
            filename,
            wav.getnchannels(),
            wav.getframerate(),
            wav.getsampwidth(),
            array.array("h"),
        )


def decode_file(filename, output_format=None, nchannels=2, sample_rate=44100, **kwargs):
    with _open_wav(filename) as wav:
        channels = wav.getnchannels()
        rate = wav.getframerate()
        width = wav.getsampwidth()
        if width != 2:
            raise RuntimeError("Expected 16-bit WAV, got %d-bit" % (width * 8))
        if channels != nchannels or rate != sample_rate:
            raise RuntimeError(
                "WAV must be %d Hz / %d ch, got %d Hz / %d ch"
                % (sample_rate, nchannels, rate, channels)
            )
        raw = wav.readframes(wav.getnframes())

    samples = array.array("h")
    # WAV data is little endian; `array` uses native order which is little on Android.
    samples.frombytes(raw[: len(raw) - (len(raw) % 2)])
    return DecodedSoundFile(filename, channels, rate, width, samples)

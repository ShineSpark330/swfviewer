package com.swfmanager.core;

/** A DefineSound tag decoded into something a platform audio player can handle. */
public final class SoundData {
    public static final int FMT_RAW = 0, FMT_ADPCM = 1, FMT_MP3 = 2, FMT_PCM_LE = 3,
            FMT_NELLY_16 = 4, FMT_NELLY_8 = 5, FMT_NELLY = 6, FMT_SPEEX = 11;

    public int format;
    public int sampleRate;
    public int channels;
    public int bitsPerSample;
    public long sampleCount;
    /** Raw payload from the tag (after the header). */
    public byte[] payload;
    /** Playable bytes (MP3 stream or WAV file), or null if the format is unsupported. */
    public byte[] playable;
    /** "mp3" or "wav" when {@link #playable} is set. */
    public String extension = "";

    public double durationSeconds() {
        return sampleRate > 0 ? sampleCount / (double) sampleRate : 0;
    }

    public String formatName() {
        switch (format) {
            case FMT_RAW: return "PCM";
            case FMT_ADPCM: return "ADPCM";
            case FMT_MP3: return "MP3";
            case FMT_PCM_LE: return "PCM";
            case FMT_NELLY_16:
            case FMT_NELLY_8:
            case FMT_NELLY: return "Nellymoser";
            case FMT_SPEEX: return "Speex";
            default: return "format " + format;
        }
    }
}

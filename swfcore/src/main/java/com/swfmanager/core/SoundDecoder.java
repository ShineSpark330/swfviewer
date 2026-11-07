package com.swfmanager.core;

import java.io.ByteArrayOutputStream;

/** Reads DefineSound tags and converts their audio to MP3 bytes or a WAV file. */
public final class SoundDecoder {
    private SoundDecoder() {}

    private static final int[] RATES = {5512, 11025, 22050, 44100};

    private static final int[] STEP = {
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80,
        88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598,
        658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327,
        3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289,
        16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    };

    private static final int[][] INDEX_TABLES = {
        {-1, 2},
        {-1, -1, 2, 4},
        {-1, -1, -1, -1, 2, 4, 6, 8},
        {-1, -1, -1, -1, -1, -1, -1, -1, 1, 2, 4, 6, 8, 10, 13, 16}
    };

    /** Header only (no audio conversion): cheap enough for list views. */
    public static SoundData peek(SwfFile f, Tag t) {
        return read(f, t, false);
    }

    private static SoundData read(SwfFile f, Tag t, boolean withPayload) {
        BitReader r = f.reader(t);
        r.u16(); // id
        int flags = r.u8();
        SoundData s = new SoundData();
        s.format = flags >> 4;
        s.sampleRate = RATES[(flags >> 2) & 3];
        s.bitsPerSample = (flags & 2) != 0 ? 16 : 8;
        s.channels = (flags & 1) != 0 ? 2 : 1;
        s.sampleCount = r.u32();
        if (withPayload) s.payload = r.bytes(r.remaining());
        return s;
    }

    public static SoundData decode(SwfFile f, Tag t) {
        SoundData s = read(f, t, true);
        switch (s.format) {
            case SoundData.FMT_MP3:
                if (s.payload.length > 2) {
                    byte[] mp3 = new byte[s.payload.length - 2]; // skip SeekSamples
                    System.arraycopy(s.payload, 2, mp3, 0, mp3.length);
                    s.playable = mp3;
                    s.extension = "mp3";
                }
                break;
            case SoundData.FMT_RAW:
            case SoundData.FMT_PCM_LE: {
                // "Native endian" raw PCM is little-endian in practice on every real-world file.
                byte[] pcm = s.payload;
                if (s.bitsPerSample == 8) {
                    // SWF 8-bit PCM is unsigned, same as WAV.
                    s.playable = wav(pcm, s.sampleRate, s.channels, 8);
                } else {
                    s.playable = wav(pcm, s.sampleRate, s.channels, 16);
                }
                s.extension = "wav";
                break;
            }
            case SoundData.FMT_ADPCM: {
                byte[] pcm = adpcm(s.payload, s.channels, s.sampleCount);
                s.playable = wav(pcm, s.sampleRate, s.channels, 16);
                s.extension = "wav";
                break;
            }
            default:
                break; // Nellymoser / Speex: not supported
        }
        return s;
    }

    /** Decodes SWF ADPCM into little-endian 16-bit PCM. */
    static byte[] adpcm(byte[] data, int channels, long sampleCount) {
        BitReader r = new BitReader(data);
        int codeBits = r.ubi(2) + 2;
        int[] indexTable = INDEX_TABLES[codeBits - 2];
        int signBit = 1 << (codeBits - 1);
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(sampleCount * channels * 2, 1 << 26));
        int[] sample = new int[channels];
        int[] index = new int[channels];
        long produced = 0;
        try {
            while (produced < sampleCount) {
                for (int c = 0; c < channels; c++) {
                    sample[c] = (short) r.ubi(16);
                    index[c] = r.ubi(6);
                    if (index[c] > 88) index[c] = 88;
                }
                writeSamples(out, sample, channels);
                produced++;
                for (int i = 1; i < 4096 && produced < sampleCount; i++, produced++) {
                    for (int c = 0; c < channels; c++) {
                        int code = r.ubi(codeBits);
                        int mag = code & (signBit - 1);
                        int step = STEP[index[c]];
                        int delta = step >> (codeBits - 2);
                        for (int k = 0; k < codeBits - 1; k++) {
                            if ((mag & (1 << k)) != 0) delta += step >> (codeBits - 2 - k);
                        }
                        int v = sample[c] + ((code & signBit) != 0 ? -delta : delta);
                        sample[c] = v > 32767 ? 32767 : (v < -32768 ? -32768 : v);
                        int ix = index[c] + indexTable[mag];
                        index[c] = ix < 0 ? 0 : (ix > 88 ? 88 : ix);
                    }
                    writeSamples(out, sample, channels);
                }
            }
        } catch (SwfException e) {
            // Ran out of data: return what we decoded.
        }
        return out.toByteArray();
    }

    private static void writeSamples(ByteArrayOutputStream out, int[] sample, int channels) {
        for (int c = 0; c < channels; c++) {
            out.write(sample[c] & 0xFF);
            out.write((sample[c] >> 8) & 0xFF);
        }
    }

    /** Wraps PCM bytes in a canonical 44-byte-header WAV file. */
    public static byte[] wav(byte[] pcm, int rate, int channels, int bits) {
        int byteRate = rate * channels * bits / 8;
        ByteArrayOutputStream o = new ByteArrayOutputStream(pcm.length + 44);
        writeAscii(o, "RIFF");
        le32(o, 36 + pcm.length);
        writeAscii(o, "WAVE");
        writeAscii(o, "fmt ");
        le32(o, 16);
        le16(o, 1);
        le16(o, channels);
        le32(o, rate);
        le32(o, byteRate);
        le16(o, channels * bits / 8);
        le16(o, bits);
        writeAscii(o, "data");
        le32(o, pcm.length);
        o.write(pcm, 0, pcm.length);
        return o.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream o, String s) {
        for (int i = 0; i < s.length(); i++) o.write(s.charAt(i));
    }

    private static void le16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
    }

    private static void le32(ByteArrayOutputStream o, int v) {
        le16(o, v & 0xFFFF);
        le16(o, (v >>> 16) & 0xFFFF);
    }
}

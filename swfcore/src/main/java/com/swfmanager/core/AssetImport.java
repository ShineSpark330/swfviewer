package com.swfmanager.core;

import java.util.Locale;

/**
 * Turns ordinary files (PNG/JPEG pixels, MP3, WAV, anything) into SWF definition tags that
 * {@link SwfEditor} can add or swap in. Pure Java: decoding PNG/GIF/etc. into pixels is left to
 * the platform, which then calls {@link #lossless2}.
 */
public final class AssetImport {
    private AssetImport() {}

    public enum Kind { PNG, JPEG, GIF, BMP, WEBP, MP3, WAV, OTHER }

    public static boolean isImage(Kind k) {
        return k == Kind.PNG || k == Kind.JPEG || k == Kind.GIF || k == Kind.BMP || k == Kind.WEBP;
    }

    /** Guesses the file type from its first bytes. */
    public static Kind detect(byte[] b) {
        int n = b.length;
        if (n >= 4 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return Kind.PNG;
        if (n >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return Kind.JPEG;
        if (n >= 4 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return Kind.GIF;
        if (n >= 2 && b[0] == 'B' && b[1] == 'M') return Kind.BMP;
        if (n >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F') {
            if (b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return Kind.WEBP;
            if (b[8] == 'W' && b[9] == 'A' && b[10] == 'V' && b[11] == 'E') return Kind.WAV;
        }
        if (n >= 3 && b[0] == 'I' && b[1] == 'D' && b[2] == '3') return Kind.MP3;
        if (n >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xE0) == 0xE0) return Kind.MP3;
        return Kind.OTHER;
    }

    // ---- images --------------------------------------------------------------------------

    /** 32-bit lossless image with alpha (DefineBitsLossless2) from non-premultiplied ARGB pixels. */
    public static SwfEditor.NewTag lossless2(int id, int w, int h, int[] argb) {
        if (w <= 0 || h <= 0 || w > 65535 || h > 65535) {
            throw new IllegalArgumentException("Image size " + w + "×" + h + " is not supported");
        }
        if (argb.length < w * h) throw new IllegalArgumentException("Not enough pixels");
        byte[] raw = new byte[w * h * 4];
        int p = 0;
        for (int i = 0; i < w * h; i++) {
            int px = argb[i];
            int a = px >>> 24, r = (px >> 16) & 0xFF, g = (px >> 8) & 0xFF, b = px & 0xFF;
            if (a != 255) { // SWF stores colour premultiplied by alpha
                r = (r * a + 127) / 255;
                g = (g * a + 127) / 255;
                b = (b * a + 127) / 255;
            }
            raw[p++] = (byte) a;
            raw[p++] = (byte) r;
            raw[p++] = (byte) g;
            raw[p++] = (byte) b;
        }
        byte[] z = Zip.deflate(raw, 0, raw.length, 6);
        byte[] body = new byte[2 + 1 + 2 + 2 + z.length];
        put16(body, 0, id);
        body[2] = 5; // 32-bit
        put16(body, 3, w);
        put16(body, 5, h);
        System.arraycopy(z, 0, body, 7, z.length);
        return new SwfEditor.NewTag(Tags.DEFINE_BITS_LOSSLESS2, id, body, w + "×" + h + " image", w, h);
    }

    /**
     * True for plain 8-bit baseline/extended-sequential JPEGs with 1 or 3 colour components, which
     * every Flash player can embed untouched (progressive and CMYK files need re-encoding).
     */
    public static boolean isBaselineJpeg(byte[] b) {
        return jpegInfo(b) != null;
    }

    /** {width, height} of a baseline JPEG, or null if it is anything else. */
    public static int[] jpegInfo(byte[] b) {
        int i = 2;
        while (i + 9 < b.length) {
            if ((b[i] & 0xFF) != 0xFF) {
                i++;
                continue;
            }
            int m = b[i + 1] & 0xFF;
            if (m == 0xFF) {
                i++;
                continue;
            }
            if (m == 0xD8 || m == 0x01 || (m >= 0xD0 && m <= 0xD7)) {
                i += 2;
                continue;
            }
            int len = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            if (m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) {
                if (m != 0xC0 && m != 0xC1) return null; // progressive / lossless / arithmetic
                int precision = b[i + 4] & 0xFF;
                int h = ((b[i + 5] & 0xFF) << 8) | (b[i + 6] & 0xFF);
                int w = ((b[i + 7] & 0xFF) << 8) | (b[i + 8] & 0xFF);
                int comps = b[i + 9] & 0xFF;
                if (precision != 8 || (comps != 1 && comps != 3) || w <= 0 || h <= 0) return null;
                return new int[] {w, h};
            }
            if (len < 2) return null;
            i += 2 + len;
        }
        return null;
    }

    /** Embeds a baseline JPEG as-is (DefineBitsJPEG2); no re-encoding, so no quality loss. */
    public static SwfEditor.NewTag jpeg(int id, byte[] jpeg) {
        int[] wh = jpegInfo(jpeg);
        if (wh == null) throw new IllegalArgumentException("Only baseline JPEG files can be embedded directly");
        byte[] body = new byte[2 + jpeg.length];
        put16(body, 0, id);
        System.arraycopy(jpeg, 0, body, 2, jpeg.length);
        return new SwfEditor.NewTag(Tags.DEFINE_BITS_JPEG2, id, body, wh[0] + "×" + wh[1] + " JPEG", wh[0], wh[1]);
    }

    // ---- sounds --------------------------------------------------------------------------

    private static final int[] BR_V1 = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};
    private static final int[] BR_V2 = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};
    private static final int[] SR_V1 = {44100, 48000, 32000};
    private static final int[] SR_V2 = {22050, 24000, 16000};
    private static final int[] SR_V25 = {11025, 12000, 8000};

    /** {frameLength, sampleRate, channels, samplesPerFrame, versionBits} or null if not a valid layer-3 header. */
    private static int[] mp3Header(byte[] b, int p) {
        if (p + 4 > b.length) return null;
        int h = ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
        if ((h & 0xFFE00000) != 0xFFE00000) return null;
        int ver = (h >> 19) & 3;
        int layer = (h >> 17) & 3;
        int brIdx = (h >> 12) & 15;
        int srIdx = (h >> 10) & 3;
        int pad = (h >> 9) & 1;
        int mode = (h >> 6) & 3;
        if (ver == 1 || layer != 1 || brIdx == 0 || brIdx == 15 || srIdx == 3) return null;
        int sr = ver == 3 ? SR_V1[srIdx] : ver == 2 ? SR_V2[srIdx] : SR_V25[srIdx];
        int br = (ver == 3 ? BR_V1[brIdx] : BR_V2[brIdx]) * 1000;
        int len = (ver == 3 ? 144 : 72) * br / sr + pad;
        return new int[] {len, sr, mode == 3 ? 1 : 2, ver == 3 ? 1152 : 576, ver};
    }

    /** MP3 file (any ID3 tags are stripped) as a DefineSound. */
    public static SwfEditor.NewTag mp3(int id, byte[] f) {
        int p = 0;
        if (f.length >= 10 && f[0] == 'I' && f[1] == 'D' && f[2] == '3') {
            int size = ((f[6] & 0x7F) << 21) | ((f[7] & 0x7F) << 14) | ((f[8] & 0x7F) << 7) | (f[9] & 0x7F);
            p = 10 + size + ((f[5] & 0x10) != 0 ? 10 : 0);
        }
        // Find the first frame header that is followed by another valid one.
        int start = -1;
        int[] first = null;
        for (int i = p; i + 4 <= f.length; i++) {
            int[] h = mp3Header(f, i);
            if (h == null) continue;
            int[] nxt = mp3Header(f, i + h[0]);
            if (nxt != null || i + h[0] >= f.length - 128) {
                start = i;
                first = h;
                break;
            }
        }
        if (first == null) throw new IllegalArgumentException("This doesn't look like an MP3 file");
        int rate = first[1];
        int code;
        if (rate == 44100) code = 3;
        else if (rate == 22050) code = 2;
        else if (rate == 11025) code = 1;
        else {
            throw new IllegalArgumentException("Flash only supports MP3 sounds at 44.1, 22.05 or 11.025 kHz; this one is "
                    + rate + " Hz. Re-export it at 44.1 kHz.");
        }
        int pos = start, frames = 0;
        while (true) {
            int[] h = mp3Header(f, pos);
            if (h == null || h[1] != rate || h[2] != first[2] || pos + h[0] > f.length) break;
            frames++;
            pos += h[0];
        }
        if (frames == 0) throw new IllegalArgumentException("This doesn't look like an MP3 file");
        long samples = (long) frames * first[3];
        int audioLen = pos - start;
        int stereo = first[2] == 2 ? 1 : 0;
        byte[] body = new byte[2 + 1 + 4 + 2 + audioLen];
        put16(body, 0, id);
        body[2] = (byte) ((2 << 4) | (code << 2) | 2 | stereo);
        put32(body, 3, samples);
        put16(body, 7, 0); // SeekSamples
        System.arraycopy(f, start, body, 9, audioLen);
        String info = "MP3 " + rate / 1000.0 + " kHz " + (stereo == 1 ? "stereo" : "mono") + " "
                + String.format(Locale.US, "%.1f", samples / (double) rate) + " s";
        return new SwfEditor.NewTag(Tags.DEFINE_SOUND, id, body, info, 0, 0);
    }

    /** Uncompressed 8/16-bit PCM WAV as a DefineSound. */
    public static SwfEditor.NewTag wav(int id, byte[] f) {
        if (detect(f) != Kind.WAV) throw new IllegalArgumentException("This doesn't look like a WAV file");
        int p = 12;
        int fmtTag = -1, channels = 0, rate = 0, bits = 0;
        int dataOff = -1, dataLen = 0;
        while (p + 8 <= f.length) {
            long size = le32(f, p + 4);
            String tag = new String(f, p, 4, java.nio.charset.Charset.forName("US-ASCII"));
            int body = p + 8;
            if (tag.equals("fmt ") && size >= 16 && body + 16 <= f.length) {
                fmtTag = le16(f, body);
                channels = le16(f, body + 2);
                rate = (int) le32(f, body + 4);
                bits = le16(f, body + 14);
                if (fmtTag == 0xFFFE && size >= 26 && body + 26 <= f.length) fmtTag = le16(f, body + 24);
            } else if (tag.equals("data")) {
                dataOff = body;
                dataLen = (int) Math.min(size, f.length - body);
                break;
            }
            long next = body + size + (size & 1);
            if (next > f.length) break;
            p = (int) next;
        }
        if (fmtTag != 1 || dataOff < 0) {
            throw new IllegalArgumentException("Only uncompressed PCM WAV files are supported (convert it, or use an MP3)");
        }
        if ((bits != 8 && bits != 16) || (channels != 1 && channels != 2)) {
            throw new IllegalArgumentException("WAV must be 8 or 16-bit, mono or stereo");
        }
        int code;
        if (rate == 44100) code = 3;
        else if (rate == 22050) code = 2;
        else if (rate == 11025) code = 1;
        else if (rate == 5512 || rate == 5500) code = 0;
        else {
            throw new IllegalArgumentException("Flash sounds must be 44100, 22050, 11025 or 5512 Hz; this WAV is "
                    + rate + " Hz. Re-export it at 44100 Hz, or use an MP3.");
        }
        int frameBytes = channels * bits / 8;
        dataLen -= dataLen % frameBytes;
        long samples = dataLen / frameBytes;
        byte[] body = new byte[2 + 1 + 4 + dataLen];
        put16(body, 0, id);
        body[2] = (byte) ((3 << 4) | (code << 2) | (bits == 16 ? 2 : 0) | (channels == 2 ? 1 : 0));
        put32(body, 3, samples);
        System.arraycopy(f, dataOff, body, 7, dataLen);
        String info = "WAV " + rate / 1000.0 + " kHz " + bits + "-bit " + (channels == 2 ? "stereo" : "mono") + " "
                + String.format(Locale.US, "%.1f", samples / (double) rate) + " s";
        return new SwfEditor.NewTag(Tags.DEFINE_SOUND, id, body, info, 0, 0);
    }

    // ---- raw data ------------------------------------------------------------------------

    /** Any file as DefineBinaryData (an embedded ByteArray for ActionScript 3). */
    public static SwfEditor.NewTag binary(int id, byte[] data) {
        byte[] body = new byte[6 + data.length];
        put16(body, 0, id);
        System.arraycopy(data, 0, body, 6, data.length);
        return new SwfEditor.NewTag(Tags.DEFINE_BINARY_DATA, id, body, Asset.formatSize(data.length) + " of data", 0, 0);
    }

    // ---- bytes ---------------------------------------------------------------------------

    private static void put16(byte[] b, int p, int v) {
        b[p] = (byte) v;
        b[p + 1] = (byte) (v >> 8);
    }

    private static void put32(byte[] b, int p, long v) {
        b[p] = (byte) v;
        b[p + 1] = (byte) (v >> 8);
        b[p + 2] = (byte) (v >> 16);
        b[p + 3] = (byte) (v >> 24);
    }

    private static int le16(byte[] b, int p) {
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8);
    }

    private static long le32(byte[] b, int p) {
        return (b[p] & 0xFFL) | ((b[p + 1] & 0xFFL) << 8) | ((b[p + 2] & 0xFFL) << 16) | ((b[p + 3] & 0xFFL) << 24);
    }
}

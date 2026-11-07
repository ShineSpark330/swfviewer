package com.swfmanager.core;

import java.util.Arrays;

/** Decodes the bitmap tags: DefineBits, JPEG2/3/4, Lossless and Lossless2. */
public final class ImageDecoder {
    private ImageDecoder() {}

    public static boolean isImageTag(int type) {
        switch (type) {
            case Tags.DEFINE_BITS:
            case Tags.DEFINE_BITS_JPEG2:
            case Tags.DEFINE_BITS_JPEG3:
            case Tags.DEFINE_BITS_JPEG4:
            case Tags.DEFINE_BITS_LOSSLESS:
            case Tags.DEFINE_BITS_LOSSLESS2:
                return true;
            default:
                return false;
        }
    }

    /** Cheap one-line description (no pixel decoding) for list views. */
    public static String describe(SwfFile f, Tag t) {
        try {
            BitReader r = f.reader(t);
            r.u16();
            switch (t.type) {
                case Tags.DEFINE_BITS_LOSSLESS:
                case Tags.DEFINE_BITS_LOSSLESS2: {
                    int fmt = r.u8();
                    int w = r.u16(), h = r.u16();
                    String kind = fmt == 3 ? "8-bit palette" : fmt == 4 ? "15-bit" : "32-bit";
                    return w + "×" + h + " lossless " + kind + (t.type == Tags.DEFINE_BITS_LOSSLESS2 ? " +alpha" : "");
                }
                case Tags.DEFINE_BITS_JPEG3:
                case Tags.DEFINE_BITS_JPEG4: {
                    long alphaOff = r.u32();
                    if (t.type == Tags.DEFINE_BITS_JPEG4) r.u16();
                    byte[] img = r.bytes((int) Math.min(alphaOff, r.remaining()));
                    return sizeText(img) + " +alpha";
                }
                case Tags.DEFINE_BITS_JPEG2:
                    return sizeText(r.bytes(r.remaining()));
                default:
                    return "JPEG (shared tables)";
            }
        } catch (SwfException e) {
            return "damaged image";
        }
    }

    private static String sizeText(byte[] img) {
        img = stripJunk(img);
        String k = sniff(img);
        int[] wh = k.equals("JPEG") ? jpegSize(img) : null;
        return k + (wh != null ? " " + wh[0] + "×" + wh[1] : "");
    }

    public static ImageData decode(SwfFile f, Tag t, byte[] jpegTables) {
        BitReader r = f.reader(t);
        r.u16(); // character id
        switch (t.type) {
            case Tags.DEFINE_BITS_LOSSLESS:
                return lossless(r, false);
            case Tags.DEFINE_BITS_LOSSLESS2:
                return lossless(r, true);
            case Tags.DEFINE_BITS: {
                byte[] img = r.bytes(r.remaining());
                if (jpegTables != null && jpegTables.length > 4) {
                    img = stripJunk(img);
                    byte[] tbl = stripJunk(jpegTables);
                    int tEnd = tbl.length;
                    if (tEnd >= 2 && (tbl[tEnd - 2] & 0xFF) == 0xFF && (tbl[tEnd - 1] & 0xFF) == 0xD9) tEnd -= 2;
                    int iStart = (img.length >= 2 && (img[0] & 0xFF) == 0xFF && (img[1] & 0xFF) == 0xD8) ? 2 : 0;
                    byte[] merged = new byte[tEnd + img.length - iStart];
                    System.arraycopy(tbl, 0, merged, 0, tEnd);
                    System.arraycopy(img, iStart, merged, tEnd, img.length - iStart);
                    img = merged;
                }
                return encoded(img, null);
            }
            case Tags.DEFINE_BITS_JPEG2:
                return encoded(r.bytes(r.remaining()), null);
            case Tags.DEFINE_BITS_JPEG3:
            case Tags.DEFINE_BITS_JPEG4: {
                long alphaOff = r.u32();
                if (t.type == Tags.DEFINE_BITS_JPEG4) r.u16(); // deblock strength
                int n = (int) Math.min(alphaOff, r.remaining());
                byte[] img = r.bytes(n);
                byte[] zAlpha = r.bytes(r.remaining());
                return encoded(img, zAlpha);
            }
            default:
                throw new SwfException("Not an image tag");
        }
    }

    private static ImageData encoded(byte[] img, byte[] zAlpha) {
        img = stripJunk(img);
        ImageData d = new ImageData();
        d.encoded = img;
        d.kind = sniff(img);
        int[] wh = d.kind.equals("JPEG") ? jpegSize(img) : null;
        if (wh != null) {
            d.width = wh[0];
            d.height = wh[1];
        }
        if (zAlpha != null && zAlpha.length > 0 && d.width > 0 && d.height > 0) {
            byte[] a = Zip.inflate(zAlpha, 0, zAlpha.length);
            int need = d.width * d.height;
            d.alpha = a.length >= need ? (a.length == need ? a : Arrays.copyOf(a, need)) : Arrays.copyOf(a, need);
            d.hasAlpha = true;
        }
        return d;
    }

    private static ImageData lossless(BitReader r, boolean v2) {
        int fmt = r.u8();
        int w = r.u16(), h = r.u16();
        if (w <= 0 || h <= 0 || (long) w * h > 50_000_000L) throw new SwfException("Unreasonable bitmap size " + w + "x" + h);
        int tableSize = 0;
        if (fmt == 3) tableSize = r.u8() + 1;
        byte[] z = r.bytes(r.remaining());
        byte[] raw = Zip.inflate(z, 0, z.length);
        ImageData d = new ImageData();
        d.width = w;
        d.height = h;
        d.hasAlpha = v2;
        int[] px = new int[w * h];
        int p = 0;
        if (fmt == 3) {
            int entry = v2 ? 4 : 3;
            int[] pal = new int[tableSize];
            for (int i = 0; i < tableSize; i++) {
                int o = i * entry;
                if (o + entry > raw.length) break;
                int rr = raw[o] & 0xFF, g = raw[o + 1] & 0xFF, b = raw[o + 2] & 0xFF;
                int a = v2 ? raw[o + 3] & 0xFF : 255;
                pal[i] = argbFromStraight(a, rr, g, b);
            }
            p = tableSize * entry;
            int stride = (w + 3) & ~3;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int o = p + y * stride + x;
                    if (o >= raw.length) break;
                    int idx = raw[o] & 0xFF;
                    px[y * w + x] = idx < tableSize ? pal[idx] : 0;
                }
            }
            d.kind = "Lossless 8-bit";
        } else if (fmt == 4) {
            int stride = ((w * 2) + 3) & ~3;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int o = y * stride + x * 2;
                    if (o + 1 >= raw.length) break;
                    int v = ((raw[o] & 0xFF) << 8) | (raw[o + 1] & 0xFF);
                    int rr = (v >> 10) & 0x1F, g = (v >> 5) & 0x1F, b = v & 0x1F;
                    rr = (rr << 3) | (rr >> 2);
                    g = (g << 3) | (g >> 2);
                    b = (b << 3) | (b >> 2);
                    px[y * w + x] = 0xFF000000 | (rr << 16) | (g << 8) | b;
                }
            }
            d.kind = "Lossless 15-bit";
        } else if (fmt == 5) {
            for (int i = 0; i < w * h; i++) {
                int o = i * 4;
                if (o + 3 >= raw.length) break;
                if (v2) {
                    int a = raw[o] & 0xFF;
                    int rr = raw[o + 1] & 0xFF, g = raw[o + 2] & 0xFF, b = raw[o + 3] & 0xFF;
                    // Stored premultiplied; convert back to straight alpha.
                    if (a > 0 && a < 255) {
                        rr = Math.min(255, rr * 255 / a);
                        g = Math.min(255, g * 255 / a);
                        b = Math.min(255, b * 255 / a);
                    }
                    px[i] = (a << 24) | (rr << 16) | (g << 8) | b;
                } else {
                    px[i] = 0xFF000000 | ((raw[o + 1] & 0xFF) << 16) | ((raw[o + 2] & 0xFF) << 8) | (raw[o + 3] & 0xFF);
                }
            }
            d.kind = "Lossless 32-bit";
        } else {
            throw new SwfException("Unknown bitmap format " + fmt);
        }
        d.argb = px;
        return d;
    }

    private static int argbFromStraight(int a, int r, int g, int b) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // ---- JPEG helpers --------------------------------------------------------------------

    /** Some old SWFs prefix JPEG data with FF D9 FF D8; drop it. */
    static byte[] stripJunk(byte[] b) {
        if (b.length > 4 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD9
                && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0xD8) {
            return Arrays.copyOfRange(b, 2, b.length);
        }
        return b;
    }

    public static String sniff(byte[] b) {
        if (b.length >= 4 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "PNG";
        if (b.length >= 3 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F') return "GIF";
        return "JPEG";
    }

    /** Scans JPEG markers for the frame header and returns {width, height}, or null. */
    public static int[] jpegSize(byte[] b) {
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
            boolean sof = m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC;
            if (sof) {
                int h = ((b[i + 5] & 0xFF) << 8) | (b[i + 6] & 0xFF);
                int w = ((b[i + 7] & 0xFF) << 8) | (b[i + 8] & 0xFF);
                return new int[] {w, h};
            }
            i += 2 + len;
        }
        return null;
    }
}

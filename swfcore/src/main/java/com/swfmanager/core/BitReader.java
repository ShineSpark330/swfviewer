package com.swfmanager.core;

import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * Reads SWF primitive types. Byte reads (u8/u16/...) automatically align to the next
 * byte boundary; bit reads (ub/sb/fb) are MSB-first as the SWF spec requires.
 */
public final class BitReader {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public final byte[] buf;
    public final int end;
    /** Position of the next unread whole byte. */
    public int pos;
    private int bitBuf;
    private int bitCount;

    public BitReader(byte[] buf, int start, int end) {
        if (start < 0 || end > buf.length || start > end) {
            throw new SwfException("Bad reader range " + start + ".." + end);
        }
        this.buf = buf;
        this.pos = start;
        this.end = end;
    }

    public BitReader(byte[] buf) {
        this(buf, 0, buf.length);
    }

    public int remaining() {
        return end - pos;
    }

    public boolean eof() {
        return bitCount == 0 && pos >= end;
    }

    public void align() {
        bitCount = 0;
    }

    public int u8() {
        align();
        if (pos >= end) throw new SwfException("Unexpected end of data");
        return buf[pos++] & 0xFF;
    }

    public int u16() {
        int lo = u8();
        int hi = u8();
        return lo | (hi << 8);
    }

    public int s16() {
        return (short) u16();
    }

    public long u32() {
        long b0 = u8(), b1 = u8(), b2 = u8(), b3 = u8();
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    public int s32() {
        return (int) u32();
    }

    public int readBit() {
        if (bitCount == 0) {
            if (pos >= end) throw new SwfException("Unexpected end of data");
            bitBuf = buf[pos++] & 0xFF;
            bitCount = 8;
        }
        bitCount--;
        return (bitBuf >> bitCount) & 1;
    }

    public long ub(int n) {
        long v = 0;
        for (int i = 0; i < n; i++) v = (v << 1) | readBit();
        return v;
    }

    public int ubi(int n) {
        return (int) ub(n);
    }

    public int sb(int n) {
        if (n == 0) return 0;
        long v = ub(n);
        if ((v & (1L << (n - 1))) != 0) v -= (1L << n);
        return (int) v;
    }

    /** Signed fixed-point 16.16 stored in n bits. */
    public float fb(int n) {
        return sb(n) / 65536f;
    }

    public String cstring() {
        align();
        int s = pos;
        while (pos < end && buf[pos] != 0) pos++;
        String str = new String(buf, s, pos - s, UTF8);
        if (pos < end) pos++;
        return str;
    }

    public byte[] bytes(int n) {
        align();
        if (n < 0 || pos + n > end) throw new SwfException("Unexpected end of data");
        byte[] out = Arrays.copyOfRange(buf, pos, pos + n);
        pos += n;
        return out;
    }

    public void skip(int n) {
        align();
        if (n < 0 || pos + n > end) throw new SwfException("Unexpected end of data");
        pos += n;
    }

    /** Reads an SWF RECT and returns {xMin, xMax, yMin, yMax} in twips. */
    public int[] rect() {
        int n = ubi(5);
        int[] r = new int[4];
        for (int i = 0; i < 4; i++) r[i] = sb(n);
        align();
        return r;
    }
}

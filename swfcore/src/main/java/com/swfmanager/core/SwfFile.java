package com.swfmanager.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** An SWF header plus its top-level tag list. {@link #data} is the fully decompressed file. */
public final class SwfFile {
    /** Original signature: "FWS" (plain), "CWS" (zlib) or "ZWS" (LZMA). */
    public String signature;
    public int version;
    public long declaredLength;
    /** Stage rectangle in twips. */
    public int xMin, xMax, yMin, yMax;
    public float frameRate;
    public int frameCount;
    /** Decompressed file bytes, including the 8-byte header (signature rewritten to FWS). */
    public byte[] data;
    /** Offset of the first top-level tag. */
    public int tagsStart;
    public final List<Tag> tags = new ArrayList<>();

    public static SwfFile parse(byte[] raw) {
        if (raw == null || raw.length < 8) throw new SwfException("File too small to be an SWF");
        char c0 = (char) (raw[0] & 0xFF), c1 = (char) (raw[1] & 0xFF), c2 = (char) (raw[2] & 0xFF);
        if (c1 != 'W' || c2 != 'S' || (c0 != 'F' && c0 != 'C' && c0 != 'Z')) {
            throw new SwfException("Not an SWF file (bad signature)");
        }
        SwfFile f = new SwfFile();
        f.signature = "" + c0 + c1 + c2;
        f.version = raw[3] & 0xFF;
        f.declaredLength = (raw[4] & 0xFFL) | ((raw[5] & 0xFFL) << 8) | ((raw[6] & 0xFFL) << 16) | ((raw[7] & 0xFFL) << 24);

        if (c0 == 'F') {
            f.data = raw.clone();
        } else if (c0 == 'C') {
            byte[] body = Zip.inflate(raw, 8, raw.length - 8);
            f.data = new byte[8 + body.length];
            System.arraycopy(raw, 0, f.data, 0, 8);
            System.arraycopy(body, 0, f.data, 8, body.length);
            f.data[0] = 'F';
        } else {
            throw new SwfException("This SWF uses LZMA compression (ZWS), which is not supported yet");
        }

        BitReader r = new BitReader(f.data, 8, f.data.length);
        int[] rect = r.rect();
        f.xMin = rect[0];
        f.xMax = rect[1];
        f.yMin = rect[2];
        f.yMax = rect[3];
        f.frameRate = r.u16() / 256f;
        f.frameCount = r.u16();
        f.tagsStart = r.pos;
        f.tags.addAll(readTags(f.data, f.tagsStart, f.data.length));
        return f;
    }

    /** Reads consecutive tags from [start, end), stopping after an End tag. */
    public static List<Tag> readTags(byte[] data, int start, int end) {
        List<Tag> out = new ArrayList<>();
        int pos = start;
        while (pos + 2 <= end) {
            int code = (data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8);
            int type = code >> 6;
            int len = code & 0x3F;
            int dataOff = pos + 2;
            boolean lng = false;
            if (len == 0x3F) {
                if (dataOff + 4 > end) break;
                long l = (data[dataOff] & 0xFFL) | ((data[dataOff + 1] & 0xFFL) << 8)
                        | ((data[dataOff + 2] & 0xFFL) << 16) | ((data[dataOff + 3] & 0xFFL) << 24);
                if (l > Integer.MAX_VALUE) break;
                len = (int) l;
                dataOff += 4;
                lng = true;
            }
            if (dataOff + len > end) {
                // Truncated final tag: clamp rather than fail so partial files still open.
                len = Math.max(0, end - dataOff);
            }
            out.add(new Tag(type, pos, dataOff, len, lng));
            pos = dataOff + len;
            if (type == Tags.END) break;
        }
        return out;
    }

    public byte[] tagBytes(Tag t) {
        return Arrays.copyOfRange(data, t.dataOffset, t.endOffset());
    }

    public BitReader reader(Tag t) {
        return new BitReader(data, t.dataOffset, t.endOffset());
    }
}

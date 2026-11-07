package com.swfmanager.core;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** zlib helpers. Inflate is forgiving: truncated streams return whatever decoded. */
public final class Zip {
    private Zip() {}

    public static byte[] inflate(byte[] src, int off, int len) {
        Inflater inf = new Inflater();
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, len * 3));
        try {
            inf.setInput(src, off, len);
            byte[] chunk = new byte[16384];
            while (!inf.finished()) {
                int n;
                try {
                    n = inf.inflate(chunk);
                } catch (DataFormatException e) {
                    if (out.size() == 0) throw new SwfException("Corrupt zlib data", e);
                    break; // keep partial output
                }
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                out.write(chunk, 0, n);
            }
        } finally {
            inf.end();
        }
        return out.toByteArray();
    }

    public static byte[] deflate(byte[] src, int off, int len) {
        return deflate(src, off, len, Deflater.BEST_COMPRESSION);
    }

    public static byte[] deflate(byte[] src, int off, int len, int level) {
        Deflater def = new Deflater(level);
        def.setInput(src, off, len);
        def.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, len / 2));
        byte[] chunk = new byte[16384];
        while (!def.finished()) {
            int n = def.deflate(chunk);
            out.write(chunk, 0, n);
        }
        def.end();
        return out.toByteArray();
    }
}

package com.swfmanager.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import com.swfmanager.core.ImageData;
import com.swfmanager.core.SwfDocument;

import java.util.HashSet;
import java.util.Set;

/** Decodes SWF images into Android Bitmaps and keeps recently used ones in memory. */
final class BitmapStore {
    private final SwfDocument doc;
    private final LruCache<Integer, Bitmap> cache;
    private final Set<Integer> failed = new HashSet<>();

    BitmapStore(SwfDocument doc) {
        this.doc = doc;
        int maxBytes = (int) Math.min(Integer.MAX_VALUE, Runtime.getRuntime().maxMemory() / 6);
        cache = new LruCache<Integer, Bitmap>(maxBytes) {
            @Override
            protected int sizeOf(Integer key, Bitmap value) {
                return value.getByteCount();
            }
        };
    }

    /** Returns the decoded bitmap for an image character, or null if it can't be decoded. */
    Bitmap get(int id) {
        Bitmap b = cache.get(id);
        if (b != null) return b;
        synchronized (this) {
            b = cache.get(id);
            if (b != null) return b;
            if (failed.contains(id)) return null;
            b = decode(id);
            if (b == null) failed.add(id);
            else cache.put(id, b);
            return b;
        }
    }

    private Bitmap decode(int id) {
        try {
            ImageData d = doc.image(id);
            if (d == null) return null;
            if (d.argb != null) {
                if (d.width <= 0 || d.height <= 0) return null;
                return Bitmap.createBitmap(d.argb, d.width, d.height, Bitmap.Config.ARGB_8888);
            }
            if (d.encoded == null) return null;
            Bitmap src = BitmapFactory.decodeByteArray(d.encoded, 0, d.encoded.length);
            if (src == null) return null;
            if (d.alpha == null) return src;
            int w = src.getWidth(), h = src.getHeight();
            if (d.alpha.length < w * h) return src;
            Bitmap out = src.copy(Bitmap.Config.ARGB_8888, true);
            int[] px = new int[w * h];
            out.getPixels(px, 0, w, 0, 0, w, h);
            for (int i = 0; i < px.length; i++) {
                px[i] = ((d.alpha[i] & 0xFF) << 24) | (px[i] & 0x00FFFFFF);
            }
            out.setPixels(px, 0, w, 0, 0, w, h);
            return out;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }
}

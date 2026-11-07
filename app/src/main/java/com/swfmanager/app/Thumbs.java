package com.swfmanager.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import com.swfmanager.core.Asset;
import com.swfmanager.core.AssetType;
import com.swfmanager.core.FrameRenderer;
import com.swfmanager.core.Mat;
import com.swfmanager.core.SwfDocument;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Renders list thumbnails on a background thread: real previews for images/shapes/sprites, badges otherwise. */
final class Thumbs {
    private Thumbs() {}

    interface Callback {
        void onThumb(Bitmap bitmap);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final LruCache<Integer, Bitmap> CACHE = new LruCache<>(400);
    private static volatile int generation;

    static void reset() {
        generation++;
        CACHE.evictAll();
    }

    static Bitmap cached(Asset a) {
        return CACHE.get(a.tagIndex);
    }

    static void load(final Asset a, final int size, final Callback cb) {
        final int gen = generation;
        EXEC.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap b;
                try {
                    b = render(a, size);
                } catch (RuntimeException | OutOfMemoryError e) {
                    b = badge(a.type, size);
                }
                if (gen != generation) return;
                CACHE.put(a.tagIndex, b);
                final Bitmap result = b;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gen == generation) cb.onThumb(result);
                    }
                });
            }
        });
    }

    private static Bitmap render(Asset a, int size) {
        SwfDocument doc = AppState.doc;
        BitmapStore store = AppState.bitmaps;
        if (doc == null || store == null) return badge(a.type, size);
        switch (a.type) {
            case IMAGE: {
                Bitmap src = store.get(a.id);
                if (src == null) return badge(a.type, size);
                Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(out);
                c.drawColor(0xFFEEEEEE);
                float s = Math.min((size - 4f) / src.getWidth(), (size - 4f) / src.getHeight());
                float w = src.getWidth() * s, h = src.getHeight() * s;
                Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
                c.drawBitmap(src, null, new RectF((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2), p);
                return out;
            }
            case SHAPE:
            case SPRITE: {
                float[] b = doc.characterBounds(a.id);
                if (b == null) return badge(a.type, size);
                float w = b[2] - b[0], h = b[3] - b[1];
                if (w <= 0 || h <= 0) return badge(a.type, size);
                Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(out);
                c.drawColor(0xFFEEEEEE);
                float s = (size - 8f) / Math.max(w, h);
                c.translate(size / 2f - s * (b[0] + b[2]) / 2f, size / 2f - s * (b[1] + b[3]) / 2f);
                c.scale(s, s);
                AndroidSurface surface = new AndroidSurface(store);
                surface.setCanvas(c);
                new FrameRenderer(doc, surface).renderCharacter(a.id, 0, Mat.IDENTITY);
                return out;
            }
            default:
                return badge(a.type, size);
        }
    }

    static Bitmap badge(AssetType type, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(colorFor(type));
        float r = size * 0.18f;
        c.drawRoundRect(new RectF(size * 0.08f, size * 0.08f, size * 0.92f, size * 0.92f), r, r, p);
        p.setColor(0xFFFFFFFF);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(size * 0.28f);
        float y = size / 2f - (p.ascent() + p.descent()) / 2f;
        c.drawText(labelFor(type), size / 2f, y, p);
        return out;
    }

    private static String labelFor(AssetType t) {
        switch (t) {
            case SHAPE: return "SHP";
            case MORPH_SHAPE: return "MRP";
            case IMAGE: return "IMG";
            case SOUND: return "SND";
            case SPRITE: return "MC";
            case FONT: return "Aa";
            case TEXT: return "T";
            case BUTTON: return "BTN";
            case BINARY: return "BIN";
            case SCRIPT: return "AS";
            case VIDEO: return "VID";
            default: return "?";
        }
    }

    private static int colorFor(AssetType t) {
        switch (t) {
            case SHAPE: return 0xFF5B7DB1;
            case MORPH_SHAPE: return 0xFF7E57C2;
            case IMAGE: return 0xFF3F9E6B;
            case SOUND: return 0xFFE0833A;
            case SPRITE: return 0xFFB5527A;
            case FONT: return 0xFF607D8B;
            case TEXT: return 0xFF8D6E63;
            case BUTTON: return 0xFF26A69A;
            case BINARY: return 0xFF757575;
            case SCRIPT: return 0xFFC0504D;
            case VIDEO: return 0xFF5C6BC0;
            default: return 0xFF9E9E9E;
        }
    }
}

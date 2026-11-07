package com.swfmanager.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import com.swfmanager.core.FrameRenderer;
import com.swfmanager.core.Mat;
import com.swfmanager.core.SwfDocument;

/** {@link PreviewView.Content} implementations for bitmaps and for SWF vector content. */
final class Contents {
    private Contents() {}

    /** A plain bitmap, drawn crisp (no smoothing) once zoomed in. */
    static final class BitmapContent implements PreviewView.Content {
        private final Bitmap bitmap;
        private final Paint paint = new Paint();

        BitmapContent(Bitmap bitmap) {
            this.bitmap = bitmap;
        }

        @Override
        public RectF bounds() {
            return new RectF(0, 0, bitmap.getWidth(), bitmap.getHeight());
        }

        @Override
        public void draw(Canvas canvas, int tick, float scale) {
            paint.setFilterBitmap(scale < 2.5f);
            canvas.drawBitmap(bitmap, 0, 0, paint);
        }
    }

    /** A shape or sprite, or the whole movie's main timeline. Units are twips. */
    static final class SwfContent implements PreviewView.Content {
        private final SwfDocument doc;
        private final int charId; // -1 = main timeline
        private final AndroidSurface surface;
        private final FrameRenderer renderer;
        private final RectF bounds;
        private final Paint stagePaint = new Paint();

        SwfContent(SwfDocument doc, BitmapStore bitmaps, int charId) {
            this.doc = doc;
            this.charId = charId;
            this.surface = new AndroidSurface(bitmaps);
            this.renderer = new FrameRenderer(doc, surface);
            if (charId < 0) {
                bounds = new RectF(doc.file.xMin, doc.file.yMin, doc.file.xMax, doc.file.yMax);
            } else {
                float[] b = doc.characterBounds(charId);
                bounds = b == null ? new RectF(0, 0, 0, 0) : new RectF(b[0], b[1], b[2], b[3]);
            }
            stagePaint.setColor(doc.backgroundColor);
        }

        @Override
        public RectF bounds() {
            return bounds;
        }

        @Override
        public void draw(Canvas canvas, int tick, float scale) {
            surface.setCanvas(canvas);
            if (charId < 0) {
                canvas.save();
                canvas.clipRect(bounds);
                canvas.drawRect(bounds, stagePaint);
                renderer.renderMain(tick, Mat.IDENTITY);
                canvas.restore();
            } else {
                renderer.renderCharacter(charId, tick, Mat.IDENTITY);
            }
        }
    }
}

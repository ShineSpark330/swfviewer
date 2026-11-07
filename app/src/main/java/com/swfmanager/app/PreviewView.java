package com.swfmanager.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * Shows a {@link Content} on a checkerboard with pinch-zoom, drag-to-pan and double-tap reset.
 * For animated content it also keeps the frame clock.
 */
public class PreviewView extends View {

    /** Something drawable with natural bounds (in its own units). */
    public interface Content {
        RectF bounds();

        /** Draws the content for the given tick; {@code scale} is device pixels per content unit. */
        void draw(Canvas canvas, int tick, float scale);
    }

    public interface FrameListener {
        void onFrame(int frame);
    }

    private Content content;
    private int frameCount = 1;
    private float fps = 12f;
    private boolean playing;
    private long playStart;
    private int baseTick;
    private int lastReported = -1;
    private FrameListener frameListener;

    private final Matrix fit = new Matrix();
    private final Matrix user = new Matrix();
    private final Matrix total = new Matrix();
    private final Paint checker = new Paint();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    public PreviewView(Context context) {
        this(context, null);
    }

    public PreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float d = getResources().getDisplayMetrics().density;
        int cell = Math.max(4, (int) (8 * d));
        Bitmap tile = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(tile);
        Paint p = new Paint();
        p.setColor(0xFFDDDDDD);
        c.drawRect(0, 0, cell * 2, cell * 2, p);
        p.setColor(0xFFF2F2F2);
        c.drawRect(0, 0, cell, cell, p);
        c.drawRect(cell, cell, cell * 2, cell * 2, p);
        checker.setShader(new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float f = detector.getScaleFactor();
                float cur = userScale();
                float target = Math.max(0.2f, Math.min(60f, cur * f));
                f = target / cur;
                user.postScale(f, f, detector.getFocusX(), detector.getFocusY());
                invalidate();
                return true;
            }
        });
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (scaleDetector.isInProgress()) return false;
                user.postTranslate(-dx, -dy);
                invalidate();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                user.reset();
                invalidate();
                return true;
            }
        });
    }

    private float userScale() {
        float[] v = new float[9];
        user.getValues(v);
        return v[Matrix.MSCALE_X];
    }

    public void setContent(Content c) {
        content = c;
        user.reset();
        computeFit();
        invalidate();
    }

    /** Declares that the content animates over {@code frames} frames at {@code fps}. */
    public void setAnimation(int frames, float fps) {
        this.frameCount = Math.max(1, frames);
        this.fps = fps > 0 ? fps : 12f;
        this.lastReported = -1;
    }

    public void setFrameListener(FrameListener l) {
        frameListener = l;
    }

    public boolean isPlaying() {
        return playing;
    }

    public void setPlaying(boolean play) {
        if (play == playing) return;
        if (play) {
            playStart = SystemClock.uptimeMillis();
            playing = true;
            postInvalidateOnAnimation();
        } else {
            baseTick = currentTick();
            playing = false;
        }
    }

    public void setFrame(int frame) {
        baseTick = frame;
        playStart = SystemClock.uptimeMillis();
        invalidate();
    }

    private int currentTick() {
        if (!playing) return baseTick;
        return baseTick + (int) ((SystemClock.uptimeMillis() - playStart) * fps / 1000f);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        computeFit();
    }

    private void computeFit() {
        fit.reset();
        if (content == null || getWidth() == 0 || getHeight() == 0) return;
        RectF b = content.bounds();
        if (b.width() <= 0 || b.height() <= 0) return;
        float margin = 16f * getResources().getDisplayMetrics().density;
        float s = Math.min((getWidth() - 2 * margin) / b.width(), (getHeight() - 2 * margin) / b.height());
        if (s <= 0) s = 1f;
        fit.setTranslate(getWidth() / 2f - s * b.centerX(), getHeight() / 2f - s * b.centerY());
        fit.preScale(s, s);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawRect(0, 0, getWidth(), getHeight(), checker);
        if (content == null) return;
        total.set(fit);
        total.postConcat(user);
        int tick = currentTick();
        canvas.save();
        canvas.concat(total);
        content.draw(canvas, tick, total.mapRadius(1f));
        canvas.restore();

        if (frameListener != null) {
            final int frame = Math.floorMod(tick, frameCount);
            if (frame != lastReported) {
                lastReported = frame;
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (frameListener != null) frameListener.onFrame(frame);
                    }
                });
            }
        }
        if (playing) postInvalidateOnAnimation();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        gestureDetector.onTouchEvent(e);
        return true;
    }
}

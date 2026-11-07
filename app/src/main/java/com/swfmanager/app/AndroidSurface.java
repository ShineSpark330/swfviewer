package com.swfmanager.app;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import com.swfmanager.core.CXForm;
import com.swfmanager.core.Mat;
import com.swfmanager.core.ShapeData;
import com.swfmanager.core.Surface;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Draws SWF shapes onto an Android Canvas. The canvas is expected to be in twip units. */
final class AndroidSurface implements Surface {
    private static final float GRADIENT_EXTENT = 16384f;

    private final BitmapStore bitmaps;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<ShapeData.PathData, Path> paths = new IdentityHashMap<>();
    private final Matrix tmp = new Matrix();
    private Canvas canvas;

    AndroidSurface(BitmapStore bitmaps) {
        this.bitmaps = bitmaps;
    }

    void setCanvas(Canvas c) {
        this.canvas = c;
    }

    static Matrix matrix(Mat m, Matrix out) {
        out.setValues(new float[] {m.a, m.c, m.tx, m.b, m.d, m.ty, 0f, 0f, 1f});
        return out;
    }

    private Path path(ShapeData.PathData p) {
        Path cached = paths.get(p);
        if (cached != null) return cached;
        Path out = new Path();
        out.setFillType(Path.FillType.EVEN_ODD);
        int pi = 0;
        for (int i = 0; i < p.nCmds; i++) {
            switch (p.cmds[i]) {
                case ShapeData.PathData.MOVE:
                    out.moveTo(p.pts[pi], p.pts[pi + 1]);
                    pi += 2;
                    break;
                case ShapeData.PathData.LINE:
                    out.lineTo(p.pts[pi], p.pts[pi + 1]);
                    pi += 2;
                    break;
                case ShapeData.PathData.QUAD:
                    out.quadTo(p.pts[pi], p.pts[pi + 1], p.pts[pi + 2], p.pts[pi + 3]);
                    pi += 4;
                    break;
                default:
                    out.close();
                    break;
            }
        }
        paths.put(p, out);
        return out;
    }

    private static int tint(int argb, CXForm cx) {
        return cx == null ? argb : cx.apply(argb);
    }

    private static ColorMatrixColorFilter filter(CXForm cx) {
        return new ColorMatrixColorFilter(new ColorMatrix(new float[] {
                cx.rm, 0, 0, 0, cx.ra,
                0, cx.gm, 0, 0, cx.ga,
                0, 0, cx.bm, 0, cx.ba,
                0, 0, 0, cx.am, cx.aa}));
    }

    @Override
    public void drawShape(ShapeData s, Mat m, CXForm cx) {
        canvas.save();
        canvas.concat(matrix(m, tmp));
        for (ShapeData.Fill f : s.fills) {
            paint.reset();
            paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.FILL);
            if (applyFill(f, cx)) canvas.drawPath(path(f.path), paint);
        }
        for (ShapeData.Line l : s.lines) {
            paint.reset();
            paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(tint(l.argb, cx));
            paint.setStrokeWidth(Math.max(l.width, 20f)); // hairlines get one pixel at 100%
            paint.setStrokeCap(l.capStyle == 0 ? Paint.Cap.ROUND : l.capStyle == 1 ? Paint.Cap.BUTT : Paint.Cap.SQUARE);
            paint.setStrokeJoin(l.joinStyle == 0 ? Paint.Join.ROUND : l.joinStyle == 1 ? Paint.Join.BEVEL : Paint.Join.MITER);
            paint.setStrokeMiter(Math.max(1f, l.miterLimit));
            canvas.drawPath(path(l.path), paint);
        }
        canvas.restore();
    }

    private boolean applyFill(ShapeData.Fill f, CXForm cx) {
        switch (f.kind) {
            case ShapeData.Fill.SOLID:
                paint.setColor(tint(f.argb, cx));
                return true;
            case ShapeData.Fill.LINEAR:
            case ShapeData.Fill.RADIAL:
            case ShapeData.Fill.FOCAL: {
                int n = f.colors == null ? 0 : f.colors.length;
                if (n == 0) return false;
                int[] cols = new int[n];
                float[] pos = new float[n];
                for (int i = 0; i < n; i++) {
                    cols[i] = tint(f.colors[i], cx);
                    pos[i] = f.ratios[i];
                    if (i > 0 && pos[i] < pos[i - 1]) pos[i] = pos[i - 1];
                }
                if (n == 1) {
                    paint.setColor(cols[0]);
                    return true;
                }
                Shader sh = f.kind == ShapeData.Fill.LINEAR
                        ? new LinearGradient(-GRADIENT_EXTENT, 0, GRADIENT_EXTENT, 0, cols, pos, Shader.TileMode.CLAMP)
                        : new RadialGradient(0, 0, GRADIENT_EXTENT, cols, pos, Shader.TileMode.CLAMP);
                sh.setLocalMatrix(matrix(f.matrix, new Matrix()));
                paint.setShader(sh);
                return true;
            }
            case ShapeData.Fill.BITMAP: {
                Bitmap b = bitmaps.get(f.bitmapId);
                if (b == null) {
                    paint.setColor(0xFFFF00FF); // missing bitmap: magenta so it's obvious
                    return true;
                }
                Shader.TileMode mode = f.repeat ? Shader.TileMode.REPEAT : Shader.TileMode.CLAMP;
                BitmapShader sh = new BitmapShader(b, mode, mode);
                sh.setLocalMatrix(matrix(f.matrix, new Matrix()));
                paint.setShader(sh);
                paint.setFilterBitmap(f.smooth);
                if (cx != null) paint.setColorFilter(filter(cx));
                return true;
            }
            default:
                return false;
        }
    }

    @Override
    public void pushClip(List<ShapeData> shapes, List<Mat> matrices) {
        canvas.save();
        Path union = new Path();
        boolean any = false;
        for (int i = 0; i < shapes.size(); i++) {
            Matrix mm = matrix(matrices.get(i), new Matrix());
            for (ShapeData.Fill f : shapes.get(i).fills) {
                Path p = new Path(path(f.path));
                p.transform(mm);
                if (!any) {
                    union.set(p);
                    any = true;
                } else {
                    union.op(p, Path.Op.UNION);
                }
            }
        }
        if (any) canvas.clipPath(union);
        else canvas.clipRect(0f, 0f, 0f, 0f); // empty mask hides everything it covers
    }

    @Override
    public void popClip() {
        canvas.restore();
    }
}

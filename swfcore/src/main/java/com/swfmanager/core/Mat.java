package com.swfmanager.core;

/**
 * 2D affine matrix in SWF convention:
 * x' = a*x + c*y + tx, y' = b*x + d*y + ty. Translation is in twips.
 */
public final class Mat {
    public final float a, b, c, d, tx, ty;

    public static final Mat IDENTITY = new Mat(1, 0, 0, 1, 0, 0);

    public Mat(float a, float b, float c, float d, float tx, float ty) {
        this.a = a;
        this.b = b;
        this.c = c;
        this.d = d;
        this.tx = tx;
        this.ty = ty;
    }

    /** Returns the matrix that applies {@code o} first, then {@code this}. */
    public Mat mul(Mat o) {
        return new Mat(
                a * o.a + c * o.b,
                b * o.a + d * o.b,
                a * o.c + c * o.d,
                b * o.c + d * o.d,
                a * o.tx + c * o.ty + tx,
                b * o.tx + d * o.ty + ty);
    }

    public float applyX(float x, float y) {
        return a * x + c * y + tx;
    }

    public float applyY(float x, float y) {
        return b * x + d * y + ty;
    }

    /** Reads an SWF MATRIX record. */
    public static Mat read(BitReader r) {
        float sx = 1, sy = 1, r0 = 0, r1 = 0;
        if (r.readBit() == 1) {
            int n = r.ubi(5);
            sx = r.fb(n);
            sy = r.fb(n);
        }
        if (r.readBit() == 1) {
            int n = r.ubi(5);
            r0 = r.fb(n);
            r1 = r.fb(n);
        }
        int n = r.ubi(5);
        int tx = r.sb(n);
        int ty = r.sb(n);
        r.align();
        return new Mat(sx, r0, r1, sy, tx, ty);
    }

    public float[] toArray() {
        return new float[] {a, b, c, d, tx, ty};
    }
}

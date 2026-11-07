package com.swfmanager.core;

/** Colour transform: out = in * mult + add (add in 0..255 units). */
public final class CXForm {
    public final float rm, gm, bm, am;
    public final float ra, ga, ba, aa;

    public CXForm(float rm, float gm, float bm, float am, float ra, float ga, float ba, float aa) {
        this.rm = rm;
        this.gm = gm;
        this.bm = bm;
        this.am = am;
        this.ra = ra;
        this.ga = ga;
        this.ba = ba;
        this.aa = aa;
    }

    /** Combines two transforms: {@code inner} is applied first, then {@code outer}. Either may be null. */
    public static CXForm combine(CXForm outer, CXForm inner) {
        if (outer == null) return inner;
        if (inner == null) return outer;
        return new CXForm(
                inner.rm * outer.rm, inner.gm * outer.gm, inner.bm * outer.bm, inner.am * outer.am,
                inner.ra * outer.rm + outer.ra, inner.ga * outer.gm + outer.ga,
                inner.ba * outer.bm + outer.ba, inner.aa * outer.am + outer.aa);
    }

    /** Applies this transform to an ARGB colour. */
    public int apply(int argb) {
        int a = clamp(((argb >>> 24) & 0xFF) * am + aa);
        int r = clamp(((argb >>> 16) & 0xFF) * rm + ra);
        int g = clamp(((argb >>> 8) & 0xFF) * gm + ga);
        int b = clamp((argb & 0xFF) * bm + ba);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int clamp(float v) {
        return v < 0 ? 0 : (v > 255 ? 255 : (int) v);
    }

    /** Reads CXFORM (hasAlpha=false) or CXFORMWITHALPHA (hasAlpha=true). */
    public static CXForm read(BitReader r, boolean hasAlpha) {
        boolean hasAdd = r.readBit() == 1;
        boolean hasMult = r.readBit() == 1;
        int n = r.ubi(4);
        float rm = 1, gm = 1, bm = 1, am = 1, ra = 0, ga = 0, ba = 0, aa = 0;
        if (hasMult) {
            rm = r.sb(n) / 256f;
            gm = r.sb(n) / 256f;
            bm = r.sb(n) / 256f;
            if (hasAlpha) am = r.sb(n) / 256f;
        }
        if (hasAdd) {
            ra = r.sb(n);
            ga = r.sb(n);
            ba = r.sb(n);
            if (hasAlpha) aa = r.sb(n);
        }
        r.align();
        return new CXForm(rm, gm, bm, am, ra, ga, ba, aa);
    }
}

package com.swfmanager.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A parsed vector shape in platform-neutral form. Coordinates are twips (1/20 px).
 * Fills are drawn first in list order, then lines on top.
 */
public final class ShapeData {
    public int xMin, xMax, yMin, yMax;
    public final List<Fill> fills = new ArrayList<>();
    public final List<Line> lines = new ArrayList<>();

    /** Growable path: MOVE (1 point), LINE (1 point), QUAD (control + anchor), CLOSE (0 points). */
    public static final class PathData {
        public static final byte MOVE = 0, LINE = 1, QUAD = 2, CLOSE = 3;
        public byte[] cmds = new byte[16];
        public float[] pts = new float[32];
        public int nCmds, nPts;

        private void cmd(byte c) {
            if (nCmds == cmds.length) cmds = Arrays.copyOf(cmds, nCmds * 2);
            cmds[nCmds++] = c;
        }

        private void pt(float x, float y) {
            if (nPts + 2 > pts.length) pts = Arrays.copyOf(pts, pts.length * 2);
            pts[nPts++] = x;
            pts[nPts++] = y;
        }

        public void moveTo(float x, float y) {
            cmd(MOVE);
            pt(x, y);
        }

        public void lineTo(float x, float y) {
            cmd(LINE);
            pt(x, y);
        }

        public void quadTo(float cx, float cy, float x, float y) {
            cmd(QUAD);
            pt(cx, cy);
            pt(x, y);
        }

        public void close() {
            cmd(CLOSE);
        }

        public boolean isEmpty() {
            return nCmds == 0;
        }
    }

    public static final class Fill {
        public static final int SOLID = 0, LINEAR = 1, RADIAL = 2, FOCAL = 3, BITMAP = 4;
        public int kind;
        /** Solid colour (ARGB). */
        public int argb;
        /** Gradient / bitmap matrix (maps style space to shape space, translation in twips). */
        public Mat matrix = Mat.IDENTITY;
        /** Gradient stops. Ratios are 0..1. */
        public int[] colors;
        public float[] ratios;
        public float focal;
        public int bitmapId;
        public boolean repeat;
        public boolean smooth;
        public final PathData path = new PathData();

        /** A representative flat colour, used where a style can't be drawn fully. */
        public int representativeColor() {
            if (kind == SOLID) return argb;
            if (colors != null && colors.length > 0) return colors[0];
            return 0xFF808080;
        }
    }

    public static final class Line {
        /** Width in twips. */
        public float width;
        public int argb;
        public int capStyle; // 0 round, 1 none(butt), 2 square
        public int joinStyle; // 0 round, 1 bevel, 2 miter
        public float miterLimit = 3f;
        public final PathData path = new PathData();
    }

    public int width() {
        return xMax - xMin;
    }

    public int height() {
        return yMax - yMin;
    }
}

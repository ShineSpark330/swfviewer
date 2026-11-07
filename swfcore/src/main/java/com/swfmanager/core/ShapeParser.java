package com.swfmanager.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Parses DefineShape / 2 / 3 / 4 tags into {@link ShapeData}. */
public final class ShapeParser {
    private ShapeParser() {}

    /** One edge: straight (curve=false) or quadratic with control point (cx, cy). */
    private static final class Seg {
        final int x0, y0, cx, cy, x1, y1;
        final boolean curve;

        Seg(int x0, int y0, int cx, int cy, int x1, int y1, boolean curve) {
            this.x0 = x0;
            this.y0 = y0;
            this.cx = cx;
            this.cy = cy;
            this.x1 = x1;
            this.y1 = y1;
            this.curve = curve;
        }

        Seg reversed() {
            return new Seg(x1, y1, cx, cy, x0, y0, curve);
        }
    }

    public static int shapeVersion(int tagType) {
        switch (tagType) {
            case Tags.DEFINE_SHAPE: return 1;
            case Tags.DEFINE_SHAPE2: return 2;
            case Tags.DEFINE_SHAPE3: return 3;
            case Tags.DEFINE_SHAPE4: return 4;
            default: return 0;
        }
    }

    public static ShapeData parse(SwfFile f, Tag t) {
        int ver = shapeVersion(t.type);
        if (ver == 0) throw new SwfException("Not a shape tag");
        BitReader r = f.reader(t);
        r.u16(); // character id
        ShapeData out = new ShapeData();
        int[] b = r.rect();
        out.xMin = b[0];
        out.xMax = b[1];
        out.yMin = b[2];
        out.yMax = b[3];
        if (ver == 4) {
            r.rect(); // edge bounds
            r.u8(); // flags
        }

        List<ShapeData.Fill> fillStyles = new ArrayList<>();
        List<ShapeData.Line> lineStyles = new ArrayList<>();
        List<List<Seg>> fillEdges = new ArrayList<>();
        List<List<Seg>> lineEdges = new ArrayList<>();

        int fillBase = 0, lineBase = 0;
        readFillStyles(r, ver, fillStyles);
        readLineStyles(r, ver, lineStyles);
        grow(fillEdges, fillStyles.size());
        grow(lineEdges, lineStyles.size());

        int nFillBits = r.ubi(4);
        int nLineBits = r.ubi(4);
        int curX = 0, curY = 0;
        int fill0 = -1, fill1 = -1, line = -1; // resolved global indices, -1 = none

        try {
            while (true) {
                if (r.readBit() == 0) {
                    int flags = r.ubi(5);
                    if (flags == 0) break; // end of shape
                    if ((flags & 1) != 0) { // move to
                        int n = r.ubi(5);
                        curX = r.sb(n);
                        curY = r.sb(n);
                    }
                    if ((flags & 2) != 0) {
                        int i = r.ubi(nFillBits);
                        fill0 = i == 0 ? -1 : fillBase + i - 1;
                    }
                    if ((flags & 4) != 0) {
                        int i = r.ubi(nFillBits);
                        fill1 = i == 0 ? -1 : fillBase + i - 1;
                    }
                    if ((flags & 8) != 0) {
                        int i = r.ubi(nLineBits);
                        line = i == 0 ? -1 : lineBase + i - 1;
                    }
                    if ((flags & 16) != 0) { // new styles
                        r.align();
                        fillBase = fillStyles.size();
                        lineBase = lineStyles.size();
                        readFillStyles(r, ver, fillStyles);
                        readLineStyles(r, ver, lineStyles);
                        grow(fillEdges, fillStyles.size());
                        grow(lineEdges, lineStyles.size());
                        nFillBits = r.ubi(4);
                        nLineBits = r.ubi(4);
                    }
                } else {
                    boolean straight = r.readBit() == 1;
                    int n = r.ubi(4) + 2;
                    Seg s;
                    if (straight) {
                        int dx = 0, dy = 0;
                        if (r.readBit() == 1) {
                            dx = r.sb(n);
                            dy = r.sb(n);
                        } else if (r.readBit() == 1) {
                            dy = r.sb(n);
                        } else {
                            dx = r.sb(n);
                        }
                        s = new Seg(curX, curY, 0, 0, curX + dx, curY + dy, false);
                    } else {
                        int cdx = r.sb(n), cdy = r.sb(n), adx = r.sb(n), ady = r.sb(n);
                        int cx = curX + cdx, cy = curY + cdy;
                        s = new Seg(curX, curY, cx, cy, cx + adx, cy + ady, true);
                    }
                    curX = s.x1;
                    curY = s.y1;
                    if (fill0 >= 0) fillEdges.get(fill0).add(s.reversed());
                    if (fill1 >= 0) fillEdges.get(fill1).add(s);
                    if (line >= 0) lineEdges.get(line).add(s);
                }
            }
        } catch (SwfException e) {
            // Truncated shape: keep what was read so far.
        }

        for (int i = 0; i < fillStyles.size(); i++) {
            List<Seg> segs = fillEdges.get(i);
            if (segs.isEmpty()) continue;
            ShapeData.Fill fl = fillStyles.get(i);
            stitch(segs, fl.path, true);
            out.fills.add(fl);
        }
        for (int i = 0; i < lineStyles.size(); i++) {
            List<Seg> segs = lineEdges.get(i);
            if (segs.isEmpty()) continue;
            ShapeData.Line ln = lineStyles.get(i);
            stitch(segs, ln.path, false);
            out.lines.add(ln);
        }
        return out;
    }

    private static void grow(List<List<Seg>> lists, int size) {
        while (lists.size() < size) lists.add(new ArrayList<Seg>());
    }

    /** Joins edges end-to-start into contours. Fill contours are closed; lines only if they loop. */
    private static void stitch(List<Seg> segs, ShapeData.PathData out, boolean closeAll) {
        int n = segs.size();
        HashMap<Long, ArrayDeque<Integer>> byStart = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Seg s = segs.get(i);
            long k = key(s.x0, s.y0);
            ArrayDeque<Integer> q = byStart.get(k);
            if (q == null) {
                q = new ArrayDeque<>();
                byStart.put(k, q);
            }
            q.add(i);
        }
        boolean[] used = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (used[i]) continue;
            used[i] = true;
            Seg s = segs.get(i);
            int sx = s.x0, sy = s.y0;
            out.moveTo(sx, sy);
            emit(out, s);
            int ex = s.x1, ey = s.y1;
            boolean looped = ex == sx && ey == sy;
            while (!looped) {
                ArrayDeque<Integer> q = byStart.get(key(ex, ey));
                Seg next = null;
                while (q != null && !q.isEmpty()) {
                    int j = q.poll();
                    if (!used[j]) {
                        used[j] = true;
                        next = segs.get(j);
                        break;
                    }
                }
                if (next == null) break;
                emit(out, next);
                ex = next.x1;
                ey = next.y1;
                looped = ex == sx && ey == sy;
            }
            if (closeAll || looped) out.close();
        }
    }

    private static long key(int x, int y) {
        return ((long) x << 32) | (y & 0xFFFFFFFFL);
    }

    private static void emit(ShapeData.PathData p, Seg s) {
        if (s.curve) p.quadTo(s.cx, s.cy, s.x1, s.y1);
        else p.lineTo(s.x1, s.y1);
    }

    // ---- style records -------------------------------------------------------------------

    private static int readColor(BitReader r, int ver) {
        int red = r.u8(), g = r.u8(), b = r.u8();
        int a = ver >= 3 ? r.u8() : 255;
        return (a << 24) | (red << 16) | (g << 8) | b;
    }

    private static void readFillStyles(BitReader r, int ver, List<ShapeData.Fill> out) {
        int count = r.u8();
        if (count == 0xFF) count = r.u16();
        for (int i = 0; i < count; i++) out.add(readFillStyle(r, ver));
    }

    private static ShapeData.Fill readFillStyle(BitReader r, int ver) {
        ShapeData.Fill f = new ShapeData.Fill();
        int type = r.u8();
        switch (type) {
            case 0x00:
                f.kind = ShapeData.Fill.SOLID;
                f.argb = readColor(r, ver);
                break;
            case 0x10:
            case 0x12:
            case 0x13: {
                f.kind = type == 0x10 ? ShapeData.Fill.LINEAR
                        : type == 0x12 ? ShapeData.Fill.RADIAL : ShapeData.Fill.FOCAL;
                f.matrix = Mat.read(r);
                int count = r.u8() & 0x0F;
                f.colors = new int[count];
                f.ratios = new float[count];
                for (int i = 0; i < count; i++) {
                    f.ratios[i] = r.u8() / 255f;
                    f.colors[i] = readColor(r, ver);
                }
                if (type == 0x13) f.focal = r.s16() / 256f;
                break;
            }
            case 0x40:
            case 0x41:
            case 0x42:
            case 0x43:
                f.kind = ShapeData.Fill.BITMAP;
                f.bitmapId = r.u16();
                f.matrix = Mat.read(r);
                f.repeat = type == 0x40 || type == 0x42;
                f.smooth = type == 0x40 || type == 0x41;
                break;
            default:
                throw new SwfException("Unknown fill style 0x" + Integer.toHexString(type));
        }
        return f;
    }

    private static void readLineStyles(BitReader r, int ver, List<ShapeData.Line> out) {
        int count = r.u8();
        if (count == 0xFF) count = r.u16();
        for (int i = 0; i < count; i++) {
            ShapeData.Line l = new ShapeData.Line();
            l.width = r.u16();
            if (ver == 4) {
                int startCap = r.ubi(2);
                int join = r.ubi(2);
                boolean hasFill = r.readBit() == 1;
                r.ubi(3); // noHScale, noVScale, pixelHinting
                r.ubi(5); // reserved
                r.readBit(); // noClose
                r.ubi(2); // endCap
                l.capStyle = startCap;
                l.joinStyle = join;
                if (join == 2) l.miterLimit = r.u16() / 256f;
                if (hasFill) l.argb = readFillStyle(r, ver).representativeColor();
                else l.argb = readColor(r, ver);
            } else {
                l.argb = readColor(r, ver);
            }
            out.add(l);
        }
    }
}

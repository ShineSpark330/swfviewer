package com.swfmanager.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Walks display lists and sends shapes to a {@link Surface}. Nested sprites animate by
 * looping on their own frame count (frame = tick % frames), which is a good match for
 * simple movie clips.
 */
public final class FrameRenderer {
    private static final int MAX_DEPTH = 24;

    private final SwfDocument doc;
    private final Surface surface;

    public FrameRenderer(SwfDocument doc, Surface surface) {
        this.doc = doc;
        this.surface = surface;
    }

    /** Draws frame {@code frame} of the main timeline. */
    public void renderMain(int frame, Mat base) {
        Timeline tl = doc.mainTimeline();
        int n = tl.frameCount();
        drawItems(tl.frames.get(Math.floorMod(frame, n)), base, null, frame, 0);
    }

    /** Draws one character (shape or sprite) at the given tick. */
    public void renderCharacter(int charId, int tick, Mat base) {
        drawCharacter(charId, base, null, tick, 0);
    }

    private void drawItems(List<Timeline.Item> items, Mat parent, CXForm pcx, int tick, int depth) {
        ArrayDeque<Integer> clipEnds = new ArrayDeque<>();
        for (Timeline.Item it : items) {
            while (!clipEnds.isEmpty() && it.depth > clipEnds.peek()) {
                surface.popClip();
                clipEnds.pop();
            }
            Mat m = parent.mul(it.matrix);
            if (it.clipDepth > 0) {
                List<ShapeData> shapes = new ArrayList<>();
                List<Mat> mats = new ArrayList<>();
                collectShapes(it.charId, m, tick, 0, shapes, mats);
                surface.pushClip(shapes, mats);
                clipEnds.push(it.clipDepth);
                continue;
            }
            drawCharacter(it.charId, m, CXForm.combine(pcx, it.cx), tick, depth);
        }
        while (!clipEnds.isEmpty()) {
            surface.popClip();
            clipEnds.pop();
        }
    }

    private void drawCharacter(int id, Mat m, CXForm cx, int tick, int depth) {
        if (depth > MAX_DEPTH) return;
        Asset a = doc.character(id);
        if (a == null) return;
        if (a.type == AssetType.SHAPE) {
            ShapeData s = doc.shape(id);
            if (s != null) surface.drawShape(s, m, cx);
        } else if (a.type == AssetType.SPRITE) {
            Timeline tl = doc.spriteTimeline(id);
            if (tl != null && tl.frameCount() > 0) {
                drawItems(tl.frames.get(Math.floorMod(tick, tl.frameCount())), m, cx, tick, depth + 1);
            }
        }
    }

    private void collectShapes(int id, Mat m, int tick, int depth, List<ShapeData> shapes, List<Mat> mats) {
        if (depth > MAX_DEPTH) return;
        Asset a = doc.character(id);
        if (a == null) return;
        if (a.type == AssetType.SHAPE) {
            ShapeData s = doc.shape(id);
            if (s != null) {
                shapes.add(s);
                mats.add(m);
            }
        } else if (a.type == AssetType.SPRITE) {
            Timeline tl = doc.spriteTimeline(id);
            if (tl == null || tl.frameCount() == 0) return;
            for (Timeline.Item it : tl.frames.get(Math.floorMod(tick, tl.frameCount()))) {
                if (it.clipDepth == 0) collectShapes(it.charId, m.mul(it.matrix), tick, depth + 1, shapes, mats);
            }
        }
    }

    /** A Surface that only measures where things land, in twips. */
    public static final class BoundsSurface implements Surface {
        public float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        public float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;

        public boolean isEmpty() {
            return minX > maxX;
        }

        @Override
        public void drawShape(ShapeData s, Mat m, CXForm cx) {
            float[] xs = {s.xMin, s.xMax, s.xMin, s.xMax};
            float[] ys = {s.yMin, s.yMin, s.yMax, s.yMax};
            for (int i = 0; i < 4; i++) {
                float x = m.applyX(xs[i], ys[i]), y = m.applyY(xs[i], ys[i]);
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }

        @Override
        public void pushClip(List<ShapeData> shapes, List<Mat> matrices) {}

        @Override
        public void popClip() {}
    }
}

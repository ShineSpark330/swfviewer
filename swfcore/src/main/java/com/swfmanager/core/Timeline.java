package com.swfmanager.core;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Display-list snapshots per frame for the main movie or a sprite. Each frame is a
 * depth-sorted list of immutable {@link Item}s (shared between frames when unchanged).
 */
public final class Timeline {

    public static final class Item {
        public final int depth;
        public final int charId;
        public final Mat matrix;
        public final CXForm cx; // may be null
        public final int clipDepth; // 0 if this item is not a mask
        public final String name;

        Item(int depth, int charId, Mat matrix, CXForm cx, int clipDepth, String name) {
            this.depth = depth;
            this.charId = charId;
            this.matrix = matrix;
            this.cx = cx;
            this.clipDepth = clipDepth;
            this.name = name;
        }
    }

    public final List<List<Item>> frames = new ArrayList<>();

    public int frameCount() {
        return frames.size();
    }

    /** Builds a timeline from the tags in [start, end) of {@code data}. */
    public static Timeline build(byte[] data, List<Tag> tags) {
        Timeline tl = new Timeline();
        TreeMap<Integer, Item> display = new TreeMap<>();
        for (Tag t : tags) {
            try {
                switch (t.type) {
                    case Tags.PLACE_OBJECT:
                    case Tags.PLACE_OBJECT2:
                    case Tags.PLACE_OBJECT3:
                        place(data, t, display);
                        break;
                    case Tags.REMOVE_OBJECT: {
                        BitReader r = new BitReader(data, t.dataOffset, t.endOffset());
                        r.u16();
                        display.remove(r.u16());
                        break;
                    }
                    case Tags.REMOVE_OBJECT2: {
                        BitReader r = new BitReader(data, t.dataOffset, t.endOffset());
                        display.remove(r.u16());
                        break;
                    }
                    case Tags.SHOW_FRAME:
                        tl.frames.add(new ArrayList<>(display.values()));
                        break;
                    default:
                        break;
                }
            } catch (SwfException e) {
                // Skip malformed placement; keep building so the rest still previews.
            }
        }
        // A sprite/movie with no ShowFrame still has one (possibly empty) frame.
        if (tl.frames.isEmpty()) tl.frames.add(new ArrayList<>(display.values()));
        return tl;
    }

    private static void place(byte[] data, Tag t, TreeMap<Integer, Item> display) {
        BitReader r = new BitReader(data, t.dataOffset, t.endOffset());
        int depth;
        int charId = -1;
        Mat matrix = null;
        CXForm cx = null;
        int clipDepth = -1;
        String name = null;
        boolean move = false;
        boolean hasChar;

        if (t.type == Tags.PLACE_OBJECT) {
            charId = r.u16();
            depth = r.u16();
            matrix = Mat.read(r);
            if (r.remaining() > 0) cx = CXForm.read(r, false);
            hasChar = true;
        } else {
            int f1 = r.u8();
            int f2 = 0;
            if (t.type == Tags.PLACE_OBJECT3) f2 = r.u8();
            depth = r.u16();
            move = (f1 & 0x01) != 0;
            hasChar = (f1 & 0x02) != 0;
            boolean hasMatrix = (f1 & 0x04) != 0;
            boolean hasCx = (f1 & 0x08) != 0;
            boolean hasRatio = (f1 & 0x10) != 0;
            boolean hasName = (f1 & 0x20) != 0;
            boolean hasClip = (f1 & 0x40) != 0;
            if (t.type == Tags.PLACE_OBJECT3 && ((f2 & 0x08) != 0 || ((f2 & 0x10) != 0 && hasChar))) {
                r.cstring(); // class name
            }
            if (hasChar) charId = r.u16();
            if (hasMatrix) matrix = Mat.read(r);
            if (hasCx) cx = CXForm.read(r, true);
            if (hasRatio) r.u16();
            if (hasName) name = r.cstring();
            if (hasClip) clipDepth = r.u16();
        }

        Item old = display.get(depth);
        if (hasChar && !move) {
            display.put(depth, new Item(depth, charId, matrix != null ? matrix : Mat.IDENTITY, cx,
                    Math.max(clipDepth, 0), name));
        } else if (old != null) {
            display.put(depth, new Item(depth,
                    hasChar ? charId : old.charId,
                    matrix != null ? matrix : old.matrix,
                    cx != null ? cx : old.cx,
                    clipDepth >= 0 ? clipDepth : old.clipDepth,
                    name != null ? name : old.name));
        }
    }
}

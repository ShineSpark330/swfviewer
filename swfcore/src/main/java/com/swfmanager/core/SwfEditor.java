package com.swfmanager.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Edits an SWF at the tag level: delete, replace, add and rename assets. Every operation takes the
 * loaded {@link SwfDocument} and returns the complete new file as plain (uncompressed, "FWS")
 * bytes; load them with {@link SwfDocument#load} to continue working. Call {@link #finish} when
 * writing to disk to re-apply zlib compression.
 *
 * Character IDs are never renumbered, so every reference to an untouched asset stays valid.
 */
public final class SwfEditor {
    private SwfEditor() {}

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** A definition tag that is about to be written. */
    public static final class NewTag {
        public final int type;
        public final int id;
        /** Complete tag body, starting with the 2-byte character ID. */
        public final byte[] body;
        /** Short description for messages, e.g. "128×64 image". */
        public final String info;
        /** Pixel size for images, else 0. */
        public final int width, height;

        public NewTag(int type, int id, byte[] body, String info, int width, int height) {
            this.type = type;
            this.id = id;
            this.body = body;
            this.info = info;
            this.width = width;
            this.height = height;
        }
    }

    /** One tag of the file being rewritten: a slice of some byte array. */
    private static final class Raw {
        final int type;
        final byte[] buf;
        final int off, len;
        final boolean longHeader;

        Raw(int type, byte[] buf, int off, int len, boolean longHeader) {
            this.type = type;
            this.buf = buf;
            this.off = off;
            this.len = len;
            this.longHeader = longHeader;
        }

        Raw(int type, byte[] body) {
            this(type, body, 0, body.length, false);
        }

        int size() {
            return ((longHeader || len >= 0x3F) ? 6 : 2) + len;
        }
    }

    // ---- public operations ---------------------------------------------------------------

    /** First character ID that no asset in the file uses. */
    public static int nextId(SwfDocument doc) {
        int max = 0;
        for (Asset a : doc.assets) if (a.id > max) max = a.id;
        // Imported characters have IDs too, without a definition tag in this file.
        SwfFile f = doc.file;
        for (Tag t : f.tags) {
            if (t.type != 57 && t.type != 71) continue; // ImportAssets, ImportAssets2
            try {
                BitReader r = f.reader(t);
                r.cstring();
                if (t.type == 71) r.skip(2);
                int n = r.u16();
                for (int i = 0; i < n && !r.eof(); i++) {
                    int id = r.u16();
                    r.cstring();
                    if (id > max) max = id;
                }
            } catch (RuntimeException e) {
                // ignore a damaged import table
            }
        }
        if (max >= 65535) throw new IllegalStateException("This file has no free character IDs left");
        return max + 1;
    }

    /** How many times the character is placed on a timeline (main or inside sprites). */
    public static int placementCount(SwfDocument doc, int id) {
        if (id < 0) return 0;
        SwfFile f = doc.file;
        int n = 0;
        for (Tag t : f.tags) {
            if (t.type == Tags.DEFINE_SPRITE) {
                try {
                    for (Tag in : SwfFile.readTags(f.data, t.dataOffset + 4, t.endOffset())) {
                        if (places(f.data, in, id)) n++;
                    }
                } catch (RuntimeException e) {
                    // skip
                }
            } else if (places(f.data, t, id)) {
                n++;
            }
        }
        return n;
    }

    /** Removes the asset, every placement of it, and its export/class name. */
    public static byte[] delete(SwfDocument doc, Asset a) {
        List<Raw> tags = rawTags(doc);
        checkIndex(tags, a);
        List<Raw> out = new ArrayList<>(tags.size());
        for (int i = 0; i < tags.size(); i++) {
            Raw r = tags.get(i);
            if (i == a.tagIndex) continue;
            if (a.id >= 0) {
                if (r.type == Tags.DEFINE_SPRITE) {
                    out.add(stripSprite(r, a.id));
                    continue;
                }
                if (isPlace(r.type) && placesRaw(r, a.id)) continue;
            }
            out.add(r);
        }
        if (a.id > 0) setName(out, doc.file.version, a.id, "");
        return write(doc.file, out, 0);
    }

    /** Swaps the asset's definition for a new one with the same ID. */
    public static byte[] replace(SwfDocument doc, Asset a, NewTag nt) {
        if (a.id < 0) throw new IllegalArgumentException("This item can't be replaced");
        if (nt.id != a.id) throw new IllegalArgumentException("New tag has the wrong character ID");
        if (SwfDocument.classify(nt.type) != a.type) {
            throw new IllegalArgumentException("The new file is a different kind of asset than "
                    + a.type.label.toLowerCase(java.util.Locale.ROOT));
        }
        List<Raw> tags = rawTags(doc);
        checkIndex(tags, a);
        tags.set(a.tagIndex, new Raw(nt.type, nt.body));
        return write(doc.file, tags, requiredVersion(nt.type));
    }

    /** Adds a new definition (before the first frame is shown) and optionally names it. */
    public static byte[] add(SwfDocument doc, NewTag nt, String name) {
        if (nt.id != nextId(doc)) throw new IllegalArgumentException("New tag has the wrong character ID");
        if (name != null && !name.isEmpty()) checkNameFree(doc, name, -1);
        List<Raw> tags = rawTags(doc);
        int at = insertionPoint(tags);
        tags.add(at, new Raw(nt.type, nt.body));
        if (name != null && !name.isEmpty()) setName(tags, doc.file.version, nt.id, name);
        return write(doc.file, tags, requiredVersion(nt.type));
    }

    /** Sets (or, with an empty name, clears) the class / export name of an asset. */
    public static byte[] rename(SwfDocument doc, Asset a, String name) {
        if (a.id <= 0) throw new IllegalArgumentException("This item has no name to edit");
        name = name == null ? "" : name.trim();
        if (!name.isEmpty()) checkNameFree(doc, name, a.id);
        List<Raw> tags = rawTags(doc);
        setName(tags, doc.file.version, a.id, name);
        return write(doc.file, tags, 0);
    }

    /**
     * Prepares plain file bytes for writing to disk. With {@code compress} the body is
     * zlib-compressed ("CWS"), like most files that were compressed to begin with.
     */
    public static byte[] finish(byte[] fws, boolean compress) {
        if (!compress || fws.length < 9 || fws[3] < 6) return fws;
        byte[] body = Zip.deflate(fws, 8, fws.length - 8, 6);
        byte[] out = new byte[8 + body.length];
        System.arraycopy(fws, 0, out, 0, 8);
        out[0] = 'C';
        System.arraycopy(body, 0, out, 8, body.length);
        return out;
    }

    // ---- helpers -------------------------------------------------------------------------

    private static void checkIndex(List<Raw> tags, Asset a) {
        if (a.tagIndex < 0 || a.tagIndex >= tags.size()) throw new IllegalArgumentException("Asset is out of date");
    }

    private static void checkNameFree(SwfDocument doc, String name, int exceptId) {
        for (Asset x : doc.assets) {
            if (x.id != exceptId && x.name.equals(name)) {
                throw new IllegalArgumentException("The name \"" + name + "\" is already used by #" + x.id);
            }
        }
        if (name.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid name");
    }

    /** Minimum SWF version that understands the tag type. */
    private static int requiredVersion(int type) {
        switch (type) {
            case Tags.DEFINE_BITS_JPEG2: return 2;
            case Tags.DEFINE_BITS_LOSSLESS2: return 3;
            case Tags.DEFINE_SOUND: return 4;
            case Tags.DEFINE_BINARY_DATA: return 9;
            default: return 0;
        }
    }

    private static List<Raw> rawTags(SwfDocument doc) {
        SwfFile f = doc.file;
        List<Raw> out = new ArrayList<>(f.tags.size() + 4);
        for (Tag t : f.tags) out.add(new Raw(t.type, f.data, t.dataOffset, t.length, t.longHeader));
        return out;
    }

    private static int insertionPoint(List<Raw> tags) {
        for (int i = 0; i < tags.size(); i++) if (tags.get(i).type == Tags.SHOW_FRAME) return i;
        for (int i = 0; i < tags.size(); i++) if (tags.get(i).type == Tags.END) return i;
        return tags.size();
    }

    private static byte[] write(SwfFile f, List<Raw> tags, int minVersion) {
        boolean hasEnd = !tags.isEmpty() && tags.get(tags.size() - 1).type == Tags.END;
        int headerLen = f.tagsStart - 8; // stage rect, frame rate, frame count
        long total = 8L + headerLen + (hasEnd ? 0 : 2);
        for (Raw r : tags) total += r.size();
        if (total > Integer.MAX_VALUE - 16) throw new IllegalStateException("The edited file would be too large");
        byte[] out = new byte[(int) total];
        out[0] = 'F';
        out[1] = 'W';
        out[2] = 'S';
        out[3] = (byte) Math.max(f.version, minVersion);
        out[4] = (byte) total;
        out[5] = (byte) (total >> 8);
        out[6] = (byte) (total >> 16);
        out[7] = (byte) (total >> 24);
        System.arraycopy(f.data, 8, out, 8, headerLen);
        int p = 8 + headerLen;
        for (Raw r : tags) p = putTag(out, p, r);
        if (!hasEnd) { // out[] is zero-filled, which is exactly an End tag
            p += 2;
        }
        return out;
    }

    private static int putTag(byte[] out, int p, Raw r) {
        if (r.longHeader || r.len >= 0x3F) {
            int code = (r.type << 6) | 0x3F;
            out[p++] = (byte) code;
            out[p++] = (byte) (code >> 8);
            out[p++] = (byte) r.len;
            out[p++] = (byte) (r.len >> 8);
            out[p++] = (byte) (r.len >> 16);
            out[p++] = (byte) (r.len >> 24);
        } else {
            int code = (r.type << 6) | r.len;
            out[p++] = (byte) code;
            out[p++] = (byte) (code >> 8);
        }
        System.arraycopy(r.buf, r.off, out, p, r.len);
        return p + r.len;
    }

    // ---- placements ----------------------------------------------------------------------

    private static boolean places(byte[] d, Tag t, int id) {
        try {
            BitReader r = new BitReader(d, t.dataOffset, t.endOffset());
            switch (t.type) {
                case Tags.PLACE_OBJECT:
                    return r.u16() == id;
                case Tags.PLACE_OBJECT2: {
                    int f1 = r.u8();
                    if ((f1 & 0x02) == 0) return false;
                    r.u16();
                    return r.u16() == id;
                }
                case Tags.PLACE_OBJECT3: {
                    int f1 = r.u8(), f2 = r.u8();
                    boolean hasChar = (f1 & 0x02) != 0;
                    if (!hasChar) return false;
                    r.u16();
                    if ((f2 & 0x08) != 0 || (f2 & 0x10) != 0) r.cstring();
                    return r.u16() == id;
                }
                default:
                    return false;
            }
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isPlace(int type) {
        return type == Tags.PLACE_OBJECT || type == Tags.PLACE_OBJECT2 || type == Tags.PLACE_OBJECT3;
    }

    private static boolean placesRaw(Raw r, int id) {
        Tag t = new Tag(r.type, 0, r.off, r.len, r.longHeader);
        return places(r.buf, t, id);
    }

    private static Raw stripSprite(Raw sprite, int id) {
        List<Tag> inner;
        try {
            inner = SwfFile.readTags(sprite.buf, sprite.off + 4, sprite.off + sprite.len);
        } catch (RuntimeException e) {
            return sprite;
        }
        boolean any = false;
        for (Tag t : inner) {
            if (places(sprite.buf, t, id)) {
                any = true;
                break;
            }
        }
        if (!any) return sprite;
        ByteArrayOutputStream bo = new ByteArrayOutputStream(sprite.len);
        bo.write(sprite.buf, sprite.off, 4); // sprite id + frame count
        for (Tag t : inner) {
            if (places(sprite.buf, t, id)) continue;
            Raw ir = new Raw(t.type, sprite.buf, t.dataOffset, t.length, t.longHeader);
            byte[] tmp = new byte[ir.size()];
            putTag(tmp, 0, ir);
            bo.write(tmp, 0, tmp.length);
        }
        byte[] body = bo.toByteArray();
        return new Raw(Tags.DEFINE_SPRITE, body, 0, body.length, sprite.longHeader);
    }

    // ---- names (SymbolClass / ExportAssets) ----------------------------------------------

    private static final class NameEntry {
        final int id;
        final byte[] name;

        NameEntry(int id, byte[] name) {
            this.id = id;
            this.name = name;
        }
    }

    private static List<NameEntry> readNames(Raw r) {
        List<NameEntry> out = new ArrayList<>();
        byte[] b = r.buf;
        int p = r.off, end = r.off + r.len;
        if (end - p < 2) return out;
        int n = (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8);
        p += 2;
        for (int i = 0; i < n && p + 2 <= end; i++) {
            int id = (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8);
            p += 2;
            int s = p;
            while (p < end && b[p] != 0) p++;
            byte[] nm = new byte[p - s];
            System.arraycopy(b, s, nm, 0, nm.length);
            p = Math.min(end, p + 1);
            out.add(new NameEntry(id, nm));
        }
        return out;
    }

    private static Raw writeNames(int type, List<NameEntry> entries) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(entries.size() & 0xFF);
        o.write((entries.size() >> 8) & 0xFF);
        for (NameEntry e : entries) {
            o.write(e.id & 0xFF);
            o.write((e.id >> 8) & 0xFF);
            o.write(e.name, 0, e.name.length);
            o.write(0);
        }
        return new Raw(type, o.toByteArray());
    }

    /** Removes every name the character has, then (if non-empty) gives it {@code name}. */
    private static void setName(List<Raw> tags, int version, int id, String name) {
        int target = -1;
        for (int i = 0; i < tags.size(); i++) {
            Raw r = tags.get(i);
            if (r.type != Tags.SYMBOL_CLASS && r.type != Tags.EXPORT_ASSETS) continue;
            List<NameEntry> es = readNames(r);
            List<NameEntry> kept = new ArrayList<>(es.size());
            for (NameEntry e : es) if (e.id != id) kept.add(e);
            if (kept.size() != es.size()) {
                tags.set(i, writeNames(r.type, kept));
            }
        }
        if (name.isEmpty()) return;
        for (int i = 0; i < tags.size() && target < 0; i++) if (tags.get(i).type == Tags.SYMBOL_CLASS) target = i;
        for (int i = 0; i < tags.size() && target < 0; i++) if (tags.get(i).type == Tags.EXPORT_ASSETS) target = i;
        byte[] nm = name.getBytes(UTF8);
        if (target >= 0) {
            Raw r = tags.get(target);
            List<NameEntry> es = readNames(r);
            es.add(new NameEntry(id, nm));
            tags.set(target, writeNames(r.type, es));
            return;
        }
        boolean as3 = version >= 9;
        for (Raw r : tags) if (r.type == Tags.DO_ABC) as3 = true;
        List<NameEntry> es = new ArrayList<>();
        es.add(new NameEntry(id, nm));
        tags.add(insertionPoint(tags), writeNames(as3 ? Tags.SYMBOL_CLASS : Tags.EXPORT_ASSETS, es));
    }
}

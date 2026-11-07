package com.swfmanager.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A loaded SWF plus an index of its assets and lazily parsed, cached shapes / images /
 * sounds / timelines. This is the main entry point for the app.
 */
public final class SwfDocument {
    public final SwfFile file;
    public final List<Asset> assets = new ArrayList<>();
    /** Document class from SymbolClass (character 0), or "". */
    public String documentClass = "";
    /** Stage background as ARGB (defaults to white). */
    public int backgroundColor = 0xFFFFFFFF;

    private final Map<Integer, Asset> byId = new HashMap<>();
    private final Map<Integer, ShapeData> shapes = new HashMap<>();
    private final Map<Integer, ImageData> images = new HashMap<>();
    private final Map<Integer, Timeline> sprites = new HashMap<>();
    private final Map<Integer, float[]> bounds = new HashMap<>();
    private Timeline main;
    private byte[] jpegTables;

    private SwfDocument(SwfFile f) {
        this.file = f;
    }

    public static SwfDocument load(byte[] raw) {
        SwfDocument d = new SwfDocument(SwfFile.parse(raw));
        d.index();
        return d;
    }

    // ---- indexing ------------------------------------------------------------------------

    /** Which kind of asset a definition tag type is, or null if it isn't one. */
    public static AssetType classify(int type) {
        switch (type) {
            case Tags.DEFINE_SHAPE:
            case Tags.DEFINE_SHAPE2:
            case Tags.DEFINE_SHAPE3:
            case Tags.DEFINE_SHAPE4:
                return AssetType.SHAPE;
            case Tags.DEFINE_MORPH_SHAPE:
            case Tags.DEFINE_MORPH_SHAPE2:
                return AssetType.MORPH_SHAPE;
            case Tags.DEFINE_BITS:
            case Tags.DEFINE_BITS_JPEG2:
            case Tags.DEFINE_BITS_JPEG3:
            case Tags.DEFINE_BITS_JPEG4:
            case Tags.DEFINE_BITS_LOSSLESS:
            case Tags.DEFINE_BITS_LOSSLESS2:
                return AssetType.IMAGE;
            case Tags.DEFINE_SOUND:
                return AssetType.SOUND;
            case Tags.DEFINE_SPRITE:
                return AssetType.SPRITE;
            case Tags.DEFINE_FONT:
            case Tags.DEFINE_FONT2:
            case Tags.DEFINE_FONT3:
            case 91: // DefineFont4
                return AssetType.FONT;
            case Tags.DEFINE_TEXT:
            case Tags.DEFINE_TEXT2:
            case Tags.DEFINE_EDIT_TEXT:
                return AssetType.TEXT;
            case Tags.DEFINE_BUTTON:
            case Tags.DEFINE_BUTTON2:
                return AssetType.BUTTON;
            case Tags.DEFINE_BINARY_DATA:
                return AssetType.BINARY;
            case Tags.DO_ABC:
            case Tags.DO_ACTION:
            case Tags.DO_INIT_ACTION:
                return AssetType.SCRIPT;
            case Tags.DEFINE_VIDEO_STREAM:
                return AssetType.VIDEO;
            default:
                return null;
        }
    }

    private void index() {
        Map<Integer, String> names = new HashMap<>();
        List<Tag> tags = file.tags;
        for (int i = 0; i < tags.size(); i++) {
            Tag t = tags.get(i);
            try {
                if (t.type == Tags.JPEG_TABLES) {
                    jpegTables = file.tagBytes(t);
                } else if (t.type == Tags.SET_BACKGROUND_COLOR && t.length >= 3) {
                    BitReader r = file.reader(t);
                    int rr = r.u8(), g = r.u8(), b = r.u8();
                    backgroundColor = 0xFF000000 | (rr << 16) | (g << 8) | b;
                } else if (t.type == Tags.SYMBOL_CLASS || t.type == Tags.EXPORT_ASSETS) {
                    BitReader r = file.reader(t);
                    int n = r.u16();
                    for (int k = 0; k < n && !r.eof(); k++) {
                        int id = r.u16();
                        String nm = r.cstring();
                        if (id == 0 && t.type == Tags.SYMBOL_CLASS) documentClass = nm;
                        else if (!names.containsKey(id)) names.put(id, nm);
                    }
                } else {
                    AssetType type = classify(t.type);
                    if (type != null) {
                        int id = -1;
                        if (type != AssetType.SCRIPT && t.length >= 2) {
                            id = file.reader(t).u16();
                        }
                        Asset a = new Asset(id, type, t, i);
                        a.info = info(a);
                        assets.add(a);
                        if (id >= 0) byId.put(id, a);
                    }
                }
            } catch (RuntimeException e) {
                // A damaged tag shouldn't stop the whole file from opening.
            }
        }
        for (Asset a : assets) {
            String nm = names.get(a.id);
            if (nm != null && a.id >= 0) a.name = nm;
        }
    }

    private String info(Asset a) {
        Tag t = a.tag;
        try {
            switch (a.type) {
                case SHAPE:
                case MORPH_SHAPE: {
                    BitReader r = file.reader(t);
                    r.u16();
                    int[] b = r.rect();
                    return fmt((b[1] - b[0]) / 20.0) + "×" + fmt((b[3] - b[2]) / 20.0) + " px";
                }
                case IMAGE:
                    return ImageDecoder.describe(file, t);
                case SOUND: {
                    SoundData s = SoundDecoder.peek(file, t);
                    return s.formatName() + " " + (s.sampleRate / 1000.0) + " kHz "
                            + (s.channels == 2 ? "stereo" : "mono") + " " + fmt(s.durationSeconds()) + " s";
                }
                case SPRITE: {
                    BitReader r = file.reader(t);
                    r.u16();
                    return r.u16() + " frames";
                }
                case FONT:
                    return fontInfo(t);
                case TEXT:
                    return textInfo(t);
                case BINARY: {
                    int n = Math.max(0, t.length - 6);
                    BitReader r = file.reader(t);
                    r.skip(6);
                    String magic = "";
                    if (n >= 3) {
                        byte[] h = r.bytes(Math.min(n, 4));
                        magic = magicName(h);
                    }
                    return Asset.formatSize(n) + (magic.isEmpty() ? "" : " " + magic);
                }
                case SCRIPT: {
                    if (t.type == Tags.DO_ABC) {
                        BitReader r = file.reader(t);
                        r.u32();
                        String nm = r.cstring();
                        return "ActionScript 3 bytecode" + (nm.isEmpty() ? "" : " \"" + nm + "\"");
                    }
                    return "ActionScript 1/2";
                }
                default:
                    return "";
            }
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String magicName(byte[] h) {
        if (h.length >= 3 && h[1] == 'W' && h[2] == 'S' && (h[0] == 'F' || h[0] == 'C' || h[0] == 'Z')) return "(SWF)";
        if (h.length >= 4 && (h[0] & 0xFF) == 0x89 && h[1] == 'P') return "(PNG)";
        if (h.length >= 2 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8) return "(JPEG)";
        if (h.length >= 3 && h[0] == 'G' && h[1] == 'I' && h[2] == 'F') return "(GIF)";
        if (h.length >= 4 && h[0] == 'P' && h[1] == 'K') return "(ZIP)";
        if (h.length >= 3 && h[0] == 'I' && h[1] == 'D' && h[2] == '3') return "(MP3)";
        return "";
    }

    private String fontInfo(Tag t) {
        BitReader r = file.reader(t);
        r.u16();
        if (t.type == Tags.DEFINE_FONT) {
            int first = r.u16();
            return (first / 2) + " glyphs";
        }
        if (t.type == 91) return "font (DefineFont4)";
        int flags = r.u8();
        r.u8(); // language
        int nameLen = r.u8();
        String name = new String(r.bytes(nameLen), java.nio.charset.Charset.forName("UTF-8"));
        int glyphs = r.u16();
        boolean bold = (flags & 1) != 0, italic = (flags & 2) != 0;
        return name + (bold ? " bold" : "") + (italic ? " italic" : "") + ", " + glyphs + " glyphs";
    }

    private String textInfo(Tag t) {
        if (t.type != Tags.DEFINE_EDIT_TEXT) return "static text";
        BitReader r = file.reader(t);
        r.u16();
        r.rect();
        int f1 = r.u8(), f2 = r.u8();
        boolean hasText = (f1 & 0x80) != 0, hasColor = (f1 & 0x04) != 0, hasMax = (f1 & 0x02) != 0;
        boolean hasFont = (f1 & 0x01) != 0, hasClass = (f2 & 0x80) != 0, hasLayout = (f2 & 0x20) != 0;
        if (hasFont) r.u16();
        if (hasClass) r.cstring();
        if (hasFont) r.u16();
        if (hasColor) r.skip(4);
        if (hasMax) r.u16();
        if (hasLayout) r.skip(9);
        String var = r.cstring();
        String text = hasText ? r.cstring() : "";
        if (text.length() > 40) text = text.substring(0, 40) + "…";
        StringBuilder sb = new StringBuilder("editable text");
        if (!var.isEmpty()) sb.append(" var=").append(var);
        if (!text.isEmpty()) sb.append(" \"").append(text.replace('\n', ' ').replace('\r', ' ')).append('"');
        return sb.toString();
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.1f", v).replace(".0", "");
    }

    // ---- lookups -------------------------------------------------------------------------

    public Asset character(int id) {
        return byId.get(id);
    }

    public Map<AssetType, List<Asset>> grouped() {
        Map<AssetType, List<Asset>> m = new EnumMap<>(AssetType.class);
        for (Asset a : assets) {
            List<Asset> l = m.get(a.type);
            if (l == null) {
                l = new ArrayList<>();
                m.put(a.type, l);
            }
            l.add(a);
        }
        return m;
    }

    public int count(AssetType type) {
        int n = 0;
        for (Asset a : assets) if (a.type == type) n++;
        return n;
    }

    public synchronized ShapeData shape(int id) {
        if (shapes.containsKey(id)) return shapes.get(id);
        ShapeData s = null;
        Asset a = byId.get(id);
        if (a != null && a.type == AssetType.SHAPE) {
            try {
                s = ShapeParser.parse(file, a.tag);
            } catch (RuntimeException e) {
                s = null;
            }
        }
        shapes.put(id, s);
        return s;
    }

    public synchronized ImageData image(int id) {
        if (images.containsKey(id)) return images.get(id);
        ImageData d = null;
        Asset a = byId.get(id);
        if (a != null && a.type == AssetType.IMAGE) {
            try {
                d = ImageDecoder.decode(file, a.tag, jpegTables);
            } catch (RuntimeException e) {
                d = null;
            }
        }
        images.put(id, d);
        return d;
    }

    /** Decodes a sound (not cached: payloads can be large). */
    public SoundData sound(int id) {
        Asset a = byId.get(id);
        if (a == null || a.type != AssetType.SOUND) return null;
        try {
            return SoundDecoder.decode(file, a.tag);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public synchronized Timeline mainTimeline() {
        if (main == null) main = Timeline.build(file.data, file.tags);
        return main;
    }

    public synchronized Timeline spriteTimeline(int id) {
        if (sprites.containsKey(id)) return sprites.get(id);
        Timeline tl = null;
        Asset a = byId.get(id);
        if (a != null && a.type == AssetType.SPRITE) {
            try {
                Tag t = a.tag;
                // Sprite body: id (2), frame count (2), then tags.
                List<Tag> inner = SwfFile.readTags(file.data, t.dataOffset + 4, t.endOffset());
                tl = Timeline.build(file.data, inner);
            } catch (RuntimeException e) {
                tl = null;
            }
        }
        sprites.put(id, tl);
        return tl;
    }

    /**
     * Bounding box {minX, minY, maxX, maxY} in twips of a shape or sprite across its frames, or
     * null if nothing visible.
     */
    public synchronized float[] characterBounds(int id) {
        if (bounds.containsKey(id)) return bounds.get(id);
        FrameRenderer.BoundsSurface bs = new FrameRenderer.BoundsSurface();
        FrameRenderer fr = new FrameRenderer(this, bs);
        Asset a = byId.get(id);
        int frames = 1;
        if (a != null && a.type == AssetType.SPRITE) {
            Timeline tl = spriteTimeline(id);
            if (tl != null) frames = Math.min(tl.frameCount(), 120);
        }
        for (int f = 0; f < frames; f++) fr.renderCharacter(id, f, Mat.IDENTITY);
        float[] b = bs.isEmpty() ? null : new float[] {bs.minX, bs.minY, bs.maxX, bs.maxY};
        bounds.put(id, b);
        return b;
    }

    public int stageWidthTwips() {
        return file.xMax - file.xMin;
    }

    public int stageHeightTwips() {
        return file.yMax - file.yMin;
    }

    // ---- text views ----------------------------------------------------------------------

    /** Multi-line technical details for the info panel. */
    public String details(Asset a) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tag: ").append(Tags.name(a.tag.type)).append(" (").append(a.tag.type).append(")\n");
        if (a.id >= 0) sb.append("Character ID: ").append(a.id).append('\n');
        if (!a.name.isEmpty()) sb.append("Name: ").append(a.name).append('\n');
        if (!a.info.isEmpty()) sb.append("Info: ").append(a.info).append('\n');
        sb.append("Tag index: ").append(a.tagIndex).append('\n');
        sb.append("Data size: ").append(Asset.formatSize(a.tag.length)).append(" (").append(a.tag.length).append(" bytes)\n");
        sb.append("File offset: 0x").append(Integer.toHexString(a.tag.headerOffset)).append('\n');
        if (a.type == AssetType.BINARY || a.type == AssetType.SCRIPT) {
            sb.append("\nFirst bytes:\n").append(hexDump(file.data, a.tag.dataOffset, Math.min(a.tag.length, 128)));
        }
        return sb.toString();
    }

    public static String hexDump(byte[] d, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i += 16) {
            sb.append(String.format(Locale.US, "%04x  ", i));
            StringBuilder ascii = new StringBuilder();
            for (int j = 0; j < 16; j++) {
                if (i + j < len) {
                    int b = d[off + i + j] & 0xFF;
                    sb.append(String.format(Locale.US, "%02x ", b));
                    ascii.append(b >= 32 && b < 127 ? (char) b : '.');
                } else {
                    sb.append("   ");
                }
            }
            sb.append(' ').append(ascii).append('\n');
        }
        return sb.toString();
    }

    /** One line per top-level tag, for the raw tag view. */
    public List<String> rawTagLines() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < file.tags.size(); i++) {
            Tag t = file.tags.get(i);
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.US, "%4d  %-20s %8d B", i, Tags.name(t.type), t.length));
            Asset a = null;
            for (Asset x : assets) {
                if (x.tagIndex == i) {
                    a = x;
                    break;
                }
            }
            if (a != null && a.id >= 0) sb.append("  #").append(a.id);
            if (a != null && !a.name.isEmpty()) sb.append("  ").append(a.name);
            out.add(sb.toString());
        }
        return Collections.unmodifiableList(out);
    }

    public String headerSummary() {
        SwfFile f = file;
        return "SWF v" + f.version + " (" + f.signature + ")  "
                + fmt((f.xMax - f.xMin) / 20.0) + "×" + fmt((f.yMax - f.yMin) / 20.0) + " px  "
                + fmt(f.frameRate) + " fps  " + f.frameCount + " frames  " + assets.size() + " assets";
    }
}

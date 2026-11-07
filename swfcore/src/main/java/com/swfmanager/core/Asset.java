package com.swfmanager.core;

/** One browsable item in an SWF: a character definition, script block, etc. */
public final class Asset {
    /** Character ID, or -1 for items without one (scripts). */
    public final int id;
    public final AssetType type;
    public final Tag tag;
    /** Class / export name from SymbolClass or ExportAssets, or "". */
    public String name = "";
    /** Short human-readable details, e.g. "128x64 lossless". */
    public String info = "";
    /** Index of this tag in the top-level tag list. */
    public final int tagIndex;

    public Asset(int id, AssetType type, Tag tag, int tagIndex) {
        this.id = id;
        this.type = type;
        this.tag = tag;
        this.tagIndex = tagIndex;
    }

    public String title() {
        StringBuilder sb = new StringBuilder();
        sb.append(Tags.name(tag.type));
        if (id >= 0) sb.append(" #").append(id);
        if (!name.isEmpty()) sb.append("  ").append(name);
        return sb.toString();
    }

    public String subtitle() {
        StringBuilder sb = new StringBuilder();
        if (!info.isEmpty()) sb.append(info).append("  ·  ");
        sb.append(formatSize(tag.length));
        return sb.toString();
    }

    public static String formatSize(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format("%.1f KB", n / 1024.0);
        return String.format("%.2f MB", n / (1024.0 * 1024.0));
    }
}

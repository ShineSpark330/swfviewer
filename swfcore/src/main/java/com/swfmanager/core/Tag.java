package com.swfmanager.core;

/** One SWF tag located inside the decompressed file bytes. */
public final class Tag {
    public final int type;
    /** Offset of the tag header. */
    public final int headerOffset;
    /** Offset of the first byte of tag data. */
    public final int dataOffset;
    public final int length;
    /** True if the header used the 32-bit long form. */
    public final boolean longHeader;

    public Tag(int type, int headerOffset, int dataOffset, int length, boolean longHeader) {
        this.type = type;
        this.headerOffset = headerOffset;
        this.dataOffset = dataOffset;
        this.length = length;
        this.longHeader = longHeader;
    }

    public int endOffset() {
        return dataOffset + length;
    }

    @Override
    public String toString() {
        return Tags.name(type) + "(" + type + ") len=" + length;
    }
}

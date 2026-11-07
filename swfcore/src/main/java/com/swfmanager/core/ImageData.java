package com.swfmanager.core;

/**
 * A decoded-enough image. Lossless bitmaps arrive as ARGB pixels; JPEG/PNG/GIF bitmaps
 * arrive as their encoded bytes (the platform decodes them) plus an optional alpha plane.
 */
public final class ImageData {
    public int width, height;
    /** Non-premultiplied ARGB pixels, row-major. Null for encoded images. */
    public int[] argb;
    /** JPEG / PNG / GIF file bytes. Null for lossless images. */
    public byte[] encoded;
    /** Optional 8-bit alpha plane (width*height) to apply on top of {@link #encoded}. */
    public byte[] alpha;
    /** Short description such as "JPEG", "Lossless 32-bit". */
    public String kind = "";

    public boolean hasAlpha;
}

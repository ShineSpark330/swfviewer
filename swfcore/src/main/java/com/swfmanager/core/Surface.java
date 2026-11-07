package com.swfmanager.core;

import java.util.List;

/**
 * Drawing target used by {@link FrameRenderer}. The Android app implements it on top of
 * Canvas; the desktop test tool implements it on Java2D.
 */
public interface Surface {
    /** Draws a shape; {@code m} maps shape space (twips) to surface space, {@code cx} may be null. */
    void drawShape(ShapeData shape, Mat m, CXForm cx);

    /** Starts clipping to the union of the given shapes (mask layer). Must be matched by popClip. */
    void pushClip(List<ShapeData> shapes, List<Mat> matrices);

    void popClip();
}

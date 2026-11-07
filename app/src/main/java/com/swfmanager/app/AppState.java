package com.swfmanager.app;

import com.swfmanager.core.SwfDocument;

/** Process-wide holder for the currently open SWF (shared by the list and preview screens). */
final class AppState {
    private AppState() {}

    static SwfDocument doc;
    static String fileName = "";
    static BitmapStore bitmaps;

    static void set(SwfDocument d, String name) {
        doc = d;
        fileName = name;
        bitmaps = new BitmapStore(d);
        Thumbs.reset();
    }
}

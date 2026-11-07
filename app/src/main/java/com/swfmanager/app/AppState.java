package com.swfmanager.app;

import android.net.Uri;

import com.swfmanager.core.SwfDocument;

import java.util.ArrayList;
import java.util.List;

/** Process-wide holder for the currently open SWF (shared by the list and preview screens). */
final class AppState {
    private AppState() {}

    /** Undo history is skipped for files bigger than this (each step keeps a full copy in memory). */
    private static final int UNDO_MAX_FILE = 40 * 1024 * 1024;
    private static final int UNDO_MAX_TOTAL = 96 * 1024 * 1024;

    static SwfDocument doc;
    static String fileName = "";
    static BitmapStore bitmaps;

    /** Where the file was opened from (null if unknown). */
    static Uri sourceUri;
    /** Whether to zlib-compress when saving (true if the file was compressed when opened). */
    static boolean compress;
    /** True when there are edits that haven't been saved. */
    static boolean dirty;
    /** Bumped on every change, so screens know when to refresh. */
    static int revision;

    private static boolean savedSinceOpen;
    /** Previous versions of the file (plain bytes), newest last. */
    private static final List<byte[]> undo = new ArrayList<>();

    /** A file was opened. */
    static void set(SwfDocument d, String name, Uri source) {
        doc = d;
        fileName = name;
        sourceUri = source;
        compress = "CWS".equals(d.file.signature);
        dirty = false;
        savedSinceOpen = false;
        undo.clear();
        reload(d);
    }

    /** An edit produced a new document. */
    static void edited(SwfDocument newDoc) {
        byte[] old = doc != null ? doc.file.data : null;
        if (old != null && old.length <= UNDO_MAX_FILE) {
            undo.add(old);
            long total = 0;
            for (byte[] b : undo) total += b.length;
            while (undo.size() > 1 && total > UNDO_MAX_TOTAL) {
                total -= undo.remove(0).length;
            }
        } else {
            undo.clear();
        }
        dirty = true;
        reload(newDoc);
    }

    /** Whether an edit to this document can be undone afterwards (big files skip the history). */
    static boolean keepsUndoFor(SwfDocument d) {
        return d.file.data.length <= UNDO_MAX_FILE;
    }

    static boolean canUndo() {
        return !undo.isEmpty();
    }

    /** Takes the previous version off the history, or null if there is none. */
    static byte[] popUndo() {
        return undo.isEmpty() ? null : undo.remove(undo.size() - 1);
    }

    /** The document was swapped for an earlier version of itself. */
    static void restored(SwfDocument older) {
        dirty = !(undo.isEmpty() && !savedSinceOpen);
        reload(older);
    }

    static void markSaved(Uri target, String name) {
        dirty = false;
        savedSinceOpen = true;
        if (target != null) sourceUri = target;
        if (name != null && !name.isEmpty()) fileName = name;
        revision++;
    }

    /** Forgets the open file (after the user chose to discard it). */
    static void clear() {
        doc = null;
        bitmaps = null;
        sourceUri = null;
        dirty = false;
        undo.clear();
        Thumbs.reset();
        revision++;
    }

    private static void reload(SwfDocument d) {
        doc = d;
        bitmaps = new BitmapStore(d);
        Thumbs.reset();
        revision++;
    }
}

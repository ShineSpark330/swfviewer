package com.swfmanager.app;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Small file helpers shared by the activities. */
final class Io {
    private Io() {}

    static byte[] readAll(ContentResolver cr, Uri uri) throws IOException {
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) throw new IOException("Cannot open file");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /** Replaces the contents of the file the Uri points at. */
    static void writeAll(ContentResolver cr, Uri uri, byte[] data) throws IOException {
        try (OutputStream out = cr.openOutputStream(uri, "wt")) {
            if (out == null) throw new IOException("Cannot open file for writing");
            out.write(data);
            out.flush();
        }
    }

    static String displayName(ContentResolver cr, Uri uri, String fallback) {
        try (Cursor c = cr.query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) return n;
            }
        } catch (RuntimeException ignored) {
            // fall through to the path
        }
        String p = uri.getLastPathSegment();
        return p != null ? p : fallback;
    }
}

package com.swfmanager.app;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.swfmanager.core.Asset;
import com.swfmanager.core.AssetImport;
import com.swfmanager.core.AssetType;
import com.swfmanager.core.FrameRenderer;
import com.swfmanager.core.ImageData;
import com.swfmanager.core.Mat;
import com.swfmanager.core.SoundData;
import com.swfmanager.core.SoundDecoder;
import com.swfmanager.core.Tags;
import com.swfmanager.core.SwfDocument;
import com.swfmanager.core.SwfEditor;

import java.io.ByteArrayOutputStream;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Everything that changes the open file: rename, replace, delete, add, undo and save. One instance
 * per activity; it must be created in onCreate (it registers activity-result launchers).
 */
final class Editing {

    interface Listener {
        /** The open document changed (or was saved); called on the UI thread. */
        void onChanged();
    }

    private final AppCompatActivity act;
    private final Listener listener;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<String[]> replacePicker;
    private final ActivityResultLauncher<String[]> addPicker;
    private final ActivityResultLauncher<String> savePicker;
    private final ActivityResultLauncher<String> extractPicker;
    private Asset pendingReplace;
    private Asset pendingExtract;
    private AlertDialog busy;
    /** Detail about the last import, set on the worker thread and read on the UI thread afterwards. */
    private volatile String lastInfo = "";

    Editing(AppCompatActivity activity, Listener l) {
        this.act = activity;
        this.listener = l;
        replacePicker = act.registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                new ActivityResultCallback<Uri>() {
                    @Override
                    public void onActivityResult(Uri uri) {
                        Asset a = pendingReplace;
                        pendingReplace = null;
                        if (uri != null && a != null) replaceWith(a, uri);
                    }
                });
        addPicker = act.registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                new ActivityResultCallback<Uri>() {
                    @Override
                    public void onActivityResult(Uri uri) {
                        if (uri != null) prepareAdd(uri);
                    }
                });
        savePicker = act.registerForActivityResult(new ActivityResultContracts.CreateDocument("application/x-shockwave-flash"),
                new ActivityResultCallback<Uri>() {
                    @Override
                    public void onActivityResult(Uri uri) {
                        if (uri != null) writeTo(uri, false);
                    }
                });
        extractPicker = act.registerForActivityResult(new ActivityResultContracts.CreateDocument("application/octet-stream"),
                new ActivityResultCallback<Uri>() {
                    @Override
                    public void onActivityResult(Uri uri) {
                        Asset a = pendingExtract;
                        pendingExtract = null;
                        if (uri != null && a != null) extractTo(a, uri);
                    }
                });
    }

    void close() {
        exec.shutdown();
        hideBusy();
    }

    // ---- what can be done to an asset ----------------------------------------------------

    static boolean canRename(Asset a) {
        return a.id > 0;
    }

    static boolean canReplace(Asset a) {
        return a.type == AssetType.IMAGE || a.type == AssetType.SOUND || a.type == AssetType.BINARY;
    }

    /** Editable text fields and binary data can be edited as text. */
    static boolean canEditContent(Asset a) {
        return (a.type == AssetType.TEXT && a.tag.type == Tags.DEFINE_EDIT_TEXT) || a.type == AssetType.BINARY;
    }

    static String editLabel(Asset a) {
        return a.type == AssetType.BINARY ? "Edit as text…" : "Edit text…";
    }

    // ---- rename --------------------------------------------------------------------------

    void rename(final Asset a) {
        if (!canRename(a)) return;
        final EditText et = new EditText(act);
        et.setSingleLine(true);
        et.setHint("Name (empty = no name)");
        et.setText(a.name);
        et.setSelection(et.getText().length());
        new AlertDialog.Builder(act)
                .setTitle("Rename #" + a.id)
                .setMessage("This is the class / export name that ActionScript uses to find the asset. "
                        + "If the game's code looks it up by this name, renaming it will break that.")
                .setView(pad(et))
                .setPositiveButton("Rename", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        final String name = et.getText().toString().trim();
                        final SwfDocument doc = AppState.doc;
                        runEdit("Renaming…", new Callable<byte[]>() {
                            @Override
                            public byte[] call() {
                                return SwfEditor.rename(doc, a, name);
                            }
                        }, name.isEmpty() ? "Name removed" : "Renamed to " + name);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- edit content --------------------------------------------------------------------

    void editContent(final Asset a) {
        final SwfDocument doc = AppState.doc;
        String initial;
        boolean mono = false;
        if (a.type == AssetType.TEXT) {
            SwfEditor.EditTextInfo e = SwfEditor.readEditText(doc, a);
            if (e == null) {
                toast("This text field can't be read.");
                return;
            }
            initial = e.text;
        } else if (a.type == AssetType.BINARY) {
            byte[] data = SwfEditor.binaryData(doc, a);
            String t = SwfEditor.asText(data);
            if (t == null) {
                toast("This data isn't plain text, so it can't be edited here. Use Extract, edit the file elsewhere, "
                        + "then Replace with file.");
                return;
            }
            initial = t;
            mono = true;
        } else {
            return;
        }
        final boolean asBinary = a.type == AssetType.BINARY;
        final EditText et = new EditText(act);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        et.setMinLines(5);
        et.setMaxLines(14);
        et.setGravity(Gravity.TOP | Gravity.START);
        if (mono) et.setTypeface(android.graphics.Typeface.MONOSPACE);
        et.setText(initial);
        new AlertDialog.Builder(act)
                .setTitle((asBinary ? "Edit data #" : "Edit text #") + a.id)
                .setView(pad(et))
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        final String text = et.getText().toString();
                        runEdit("Saving…", new Callable<byte[]>() {
                            @Override
                            public byte[] call() throws Exception {
                                if (asBinary) {
                                    return SwfEditor.replace(doc, a, AssetImport.binary(a.id, text.getBytes("UTF-8")));
                                }
                                return SwfEditor.setEditText(doc, a, text);
                            }
                        }, "Changed #" + a.id);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- extract -------------------------------------------------------------------------

    void extract(Asset a) {
        SwfDocument doc = AppState.doc;
        if (doc == null) return;
        String base = !a.name.isEmpty() ? a.name : a.type.label.toLowerCase(Locale.ROOT).replace(' ', '_') + "_" + a.id;
        base = base.replaceAll("[^A-Za-z0-9._-]", "_");
        pendingExtract = a;
        extractPicker.launch(base + "." + extensionFor(doc, a));
    }

    private static String extensionFor(SwfDocument doc, Asset a) {
        switch (a.type) {
            case IMAGE: {
                ImageData d = doc.image(a.id);
                return d != null && d.encoded != null && d.alpha == null && "JPEG".equals(d.kind) ? "jpg" : "png";
            }
            case SOUND: {
                int f = SoundDecoder.peek(doc.file, a.tag).format;
                if (f == SoundData.FMT_MP3) return "mp3";
                return f == SoundData.FMT_RAW || f == SoundData.FMT_PCM_LE || f == SoundData.FMT_ADPCM ? "wav" : "bin";
            }
            case TEXT:
                return a.tag.type == Tags.DEFINE_EDIT_TEXT ? "txt" : "bin";
            case BINARY: {
                byte[] d = SwfEditor.binaryData(doc, a);
                byte[] head = java.util.Arrays.copyOf(d, Math.min(d.length, 16));
                if (head.length >= 3 && head[1] == 'W' && head[2] == 'S' && (head[0] == 'F' || head[0] == 'C' || head[0] == 'Z')) return "swf";
                if (head.length >= 2 && head[0] == 'P' && head[1] == 'K') return "zip";
                switch (AssetImport.detect(head)) {
                    case PNG: return "png";
                    case JPEG: return "jpg";
                    case GIF: return "gif";
                    case MP3: return "mp3";
                    case WAV: return "wav";
                    default: return SwfEditor.asText(d) != null ? "txt" : "bin";
                }
            }
            case SHAPE:
            case SPRITE:
                return "png";
            default:
                return "bin";
        }
    }

    private void extractTo(final Asset a, final Uri uri) {
        final SwfDocument doc = AppState.doc;
        final Context ctx = act.getApplicationContext();
        showBusy("Extracting…");
        exec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] out = extractBytes(doc, a);
                    Io.writeAll(ctx.getContentResolver(), uri, out);
                    final String name = Io.displayName(ctx.getContentResolver(), uri, "file");
                    final int size = out.length;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            hideBusy();
                            toast("Extracted " + name + " (" + Asset.formatSize(size) + ")");
                        }
                    });
                } catch (final Throwable e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            hideBusy();
                            toast("Could not extract: " + message(e));
                        }
                    });
                }
            }
        });
    }

    private static byte[] extractBytes(SwfDocument doc, Asset a) throws Exception {
        switch (a.type) {
            case IMAGE: {
                ImageData d = doc.image(a.id);
                if (d == null) throw new IllegalStateException("this image can't be decoded");
                if (d.encoded != null && d.alpha == null && "JPEG".equals(d.kind)) return d.encoded;
                Bitmap bm = AppState.bitmaps.get(a.id);
                if (bm == null) throw new IllegalStateException("this image can't be decoded");
                return png(bm);
            }
            case SOUND: {
                SoundData s = doc.sound(a.id);
                if (s == null || s.playable == null) {
                    throw new IllegalStateException("this sound format (" + (s == null ? "unknown" : s.formatName())
                            + ") can't be converted yet");
                }
                return s.playable;
            }
            case TEXT: {
                SwfEditor.EditTextInfo e = SwfEditor.readEditText(doc, a);
                if (e != null) return e.text.getBytes("UTF-8");
                return SwfEditor.rawBody(doc, a);
            }
            case BINARY:
                return SwfEditor.binaryData(doc, a);
            case SHAPE:
            case SPRITE:
                return renderPng(doc, a);
            default:
                return SwfEditor.rawBody(doc, a);
        }
    }

    private static byte[] png(Bitmap bm) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.PNG, 100, o);
        return o.toByteArray();
    }

    /** First frame of a shape or sprite as a transparent PNG. */
    private static byte[] renderPng(SwfDocument doc, Asset a) {
        float[] b = doc.characterBounds(a.id);
        if (b == null) throw new IllegalStateException("there is nothing visible to draw");
        float wTw = b[2] - b[0], hTw = b[3] - b[1];
        if (wTw <= 0 || hTw <= 0) throw new IllegalStateException("there is nothing visible to draw");
        float longestPx = Math.max(wTw, hTw) / 20f;
        float target = Math.max(512f, Math.min(2048f, longestPx * 2f));
        float s = target / Math.max(wTw, hTw); // pixels per twip
        int w = Math.max(1, Math.round(wTw * s)), h = Math.max(1, Math.round(hTw * s));
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        try {
            Canvas c = new Canvas(bm);
            c.translate(-s * b[0], -s * b[1]);
            c.scale(s, s);
            AndroidSurface surface = new AndroidSurface(AppState.bitmaps);
            surface.setCanvas(c);
            new FrameRenderer(doc, surface).renderCharacter(a.id, 0, Mat.IDENTITY);
            return png(bm);
        } finally {
            bm.recycle();
        }
    }

    // ---- delete --------------------------------------------------------------------------

    void delete(final Asset a) {
        final SwfDocument doc = AppState.doc;
        int uses = SwfEditor.placementCount(doc, a.id);
        StringBuilder sb = new StringBuilder();
        if (uses > 0) {
            sb.append("It is placed ").append(uses).append(uses == 1 ? " time" : " times")
                    .append(" on a timeline; those placements are removed too.\n\n");
        }
        sb.append("Anything else that points at it (for example a shape that uses this image, or code that "
                + "loads it by name) may stop working.");
        if (AppState.keepsUndoFor(doc)) sb.append(" You can undo this from the menu.");
        new AlertDialog.Builder(act)
                .setTitle("Delete " + a.title() + "?")
                .setMessage(sb.toString())
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        runEdit("Deleting…", new Callable<byte[]>() {
                            @Override
                            public byte[] call() {
                                return SwfEditor.delete(doc, a);
                            }
                        }, "Deleted " + a.title());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- replace -------------------------------------------------------------------------

    void replace(Asset a) {
        if (!canReplace(a)) return;
        pendingReplace = a;
        replacePicker.launch(new String[] {"*/*"});
    }

    private void replaceWith(final Asset a, final Uri uri) {
        final SwfDocument doc = AppState.doc;
        final Context ctx = act.getApplicationContext();
        runEdit("Replacing…", new Callable<byte[]>() {
            @Override
            public byte[] call() throws Exception {
                byte[] data = Io.readAll(ctx.getContentResolver(), uri);
                SwfEditor.NewTag nt;
                switch (a.type) {
                    case IMAGE:
                        nt = encodeImage(a.id, data);
                        break;
                    case SOUND:
                        nt = encodeSound(a.id, data);
                        break;
                    default:
                        nt = AssetImport.binary(a.id, data);
                        break;
                }
                lastInfo = nt.info;
                if (a.type == AssetType.IMAGE && nt.width > 0) {
                    com.swfmanager.core.ImageData old = doc.image(a.id);
                    if (old != null && old.width > 0 && (old.width != nt.width || old.height != nt.height)) {
                        lastInfo += " (was " + old.width + "×" + old.height
                                + "; things that use it may look stretched)";
                    }
                }
                return SwfEditor.replace(doc, a, nt);
            }
        }, "Replaced #" + a.id);
    }

    // ---- add -----------------------------------------------------------------------------

    void add() {
        if (AppState.doc == null) return;
        addPicker.launch(new String[] {"*/*"});
    }

    private void prepareAdd(final Uri uri) {
        final SwfDocument doc = AppState.doc;
        final Context ctx = act.getApplicationContext();
        showBusy("Reading file…");
        exec.execute(new Runnable() {
            @Override
            public void run() {
                String fileName = Io.displayName(ctx.getContentResolver(), uri, "file");
                byte[] data = null;
                AssetImport.Kind kind = AssetImport.Kind.OTHER;
                SwfEditor.NewTag nt = null;
                String problem = null;
                int id = 0;
                try {
                    data = Io.readAll(ctx.getContentResolver(), uri);
                    kind = AssetImport.detect(data);
                    id = SwfEditor.nextId(doc);
                    try {
                        if (AssetImport.isImage(kind)) nt = encodeImage(id, data);
                        else if (kind == AssetImport.Kind.MP3 || kind == AssetImport.Kind.WAV) nt = encodeSound(id, data);
                        else nt = AssetImport.binary(id, data);
                    } catch (IllegalArgumentException e) {
                        problem = e.getMessage();
                    } catch (OutOfMemoryError e) {
                        problem = "Not enough memory to convert this file.";
                    }
                } catch (final Throwable e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            hideBusy();
                            toast("Could not add file: " + message(e));
                        }
                    });
                    return;
                }
                final byte[] fData = data;
                final AssetImport.Kind fKind = kind;
                final SwfEditor.NewTag fNt = nt;
                final String fProblem = problem;
                final int fId = id;
                final String fName = fileName;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        hideBusy();
                        if (!act.isFinishing() && !act.isDestroyed()) {
                            showAddDialog(doc, fName, fData, fKind, fId, fNt, fProblem);
                        }
                    }
                });
            }
        });
    }

    private void showAddDialog(final SwfDocument doc, String fileName, final byte[] data, AssetImport.Kind kind,
                               final int id, final SwfEditor.NewTag nt, String problem) {
        final EditText et = new EditText(act);
        et.setSingleLine(true);
        et.setHint("Name (optional)");
        String msg;
        AlertDialog.Builder b = new AlertDialog.Builder(act).setTitle("Add to file").setView(pad(et));
        if (nt != null) {
            String what = nt.type == com.swfmanager.core.Tags.DEFINE_BINARY_DATA ? "raw data (" + nt.info + ")" : nt.info;
            msg = fileName + "\n\nWill be added as: " + what
                    + "\n\nThe name is how ActionScript can find it; leave it empty if you don't need that.";
            b.setPositiveButton("Add", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    applyAdd(doc, nt, et.getText().toString().trim());
                }
            });
            if (nt.type != com.swfmanager.core.Tags.DEFINE_BINARY_DATA && kind != AssetImport.Kind.OTHER) {
                b.setNeutralButton("As raw data", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        applyAdd(doc, AssetImport.binary(id, data), et.getText().toString().trim());
                    }
                });
            }
        } else {
            msg = fileName + "\n\n" + problem + "\n\nYou can still add the file untouched as raw data.";
            b.setPositiveButton("Add as raw data", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    applyAdd(doc, AssetImport.binary(id, data), et.getText().toString().trim());
                }
            });
        }
        b.setMessage(msg).setNegativeButton("Cancel", null).show();
    }

    private void applyAdd(final SwfDocument doc, final SwfEditor.NewTag nt, final String name) {
        runEdit("Adding…", new Callable<byte[]>() {
            @Override
            public byte[] call() {
                lastInfo = nt.info;
                return SwfEditor.add(doc, nt, name);
            }
        }, "Added as #" + nt.id);
    }

    // ---- converting picked files ---------------------------------------------------------

    private static SwfEditor.NewTag encodeImage(int id, byte[] data) {
        AssetImport.Kind kind = AssetImport.detect(data);
        if (!AssetImport.isImage(kind)) {
            throw new IllegalArgumentException("That file isn't a picture. Pick a PNG or JPEG.");
        }
        if (kind == AssetImport.Kind.JPEG && AssetImport.isBaselineJpeg(data)) {
            return AssetImport.jpeg(id, data);
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) throw new IllegalArgumentException("Couldn't read that image.");
        if ((long) o.outWidth * o.outHeight > 40_000_000L || o.outWidth > 65535 || o.outHeight > 65535) {
            throw new IllegalArgumentException("That image is too large (" + o.outWidth + "×" + o.outHeight + ").");
        }
        Bitmap bm = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (bm == null) throw new IllegalArgumentException("Couldn't read that image.");
        try {
            int w = bm.getWidth(), h = bm.getHeight();
            int[] px = new int[w * h];
            bm.getPixels(px, 0, w, 0, 0, w, h); // straight (not premultiplied) ARGB
            return AssetImport.lossless2(id, w, h, px);
        } finally {
            bm.recycle();
        }
    }

    private static SwfEditor.NewTag encodeSound(int id, byte[] data) {
        AssetImport.Kind kind = AssetImport.detect(data);
        if (kind == AssetImport.Kind.MP3) return AssetImport.mp3(id, data);
        if (kind == AssetImport.Kind.WAV) return AssetImport.wav(id, data);
        throw new IllegalArgumentException("That file isn't a sound. Pick an MP3 or a WAV file.");
    }

    // ---- undo ----------------------------------------------------------------------------

    void undo() {
        final byte[] prev = AppState.popUndo();
        if (prev == null) {
            toast("Nothing to undo");
            return;
        }
        showBusy("Undoing…");
        exec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final SwfDocument d = SwfDocument.load(prev);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            hideBusy();
                            AppState.restored(d);
                            toast("Undone");
                            listener.onChanged();
                        }
                    });
                } catch (final Throwable e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            hideBusy();
                            toast("Could not undo: " + message(e));
                        }
                    });
                }
            }
        });
    }

    // ---- save ----------------------------------------------------------------------------

    void saveAs() {
        if (AppState.doc == null) return;
        String base = AppState.fileName == null || AppState.fileName.isEmpty() ? "movie.swf" : AppState.fileName;
        if (base.toLowerCase(Locale.ROOT).endsWith(".swf")) base = base.substring(0, base.length() - 4);
        savePicker.launch(base + "_edited.swf");
    }

    /** Writes over the file that was opened (after confirmation); falls back to Save as. */
    void saveOverOriginal() {
        if (AppState.doc == null) return;
        final Uri src = AppState.sourceUri;
        if (src == null) {
            toast("The original location isn't known; choose where to save.");
            saveAs();
            return;
        }
        new AlertDialog.Builder(act)
                .setTitle("Replace the original file?")
                .setMessage("This overwrites " + AppState.fileName + " with your edited version. "
                        + "Use Save as… instead if you want to keep the original.")
                .setPositiveButton("Overwrite", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        writeTo(src, true);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void writeTo(final Uri uri, final boolean overwrite) {
        final SwfDocument doc = AppState.doc;
        final boolean compress = AppState.compress;
        final Context ctx = act.getApplicationContext();
        showBusy("Saving…");
        exec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] out = SwfEditor.finish(doc.file.data, compress);
                    Io.writeAll(ctx.getContentResolver(), uri, out);
                    final String name = Io.displayName(ctx.getContentResolver(), uri, AppState.fileName);
                    final int size = out.length;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            hideBusy();
                            AppState.markSaved(uri, name);
                            toast("Saved " + name + " (" + Asset.formatSize(size) + ")");
                            listener.onChanged();
                        }
                    });
                } catch (final Throwable e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            hideBusy();
                            if (overwrite) {
                                toast("Couldn't write to the original file (" + message(e) + "). Choose where to save instead.");
                                saveAs();
                            } else {
                                toast("Could not save: " + message(e));
                            }
                        }
                    });
                }
            }
        });
    }

    // ---- plumbing ------------------------------------------------------------------------

    /**
     * Runs an edit off the UI thread, re-parses the result (so a bad edit can never replace the
     * open file), then swaps it in. {@code doneMsg} is shown afterwards, followed by lastInfo if set.
     */
    private void runEdit(String busyText, final Callable<byte[]> op, final String doneMsg) {
        showBusy(busyText);
        lastInfo = "";
        exec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] out = op.call();
                    final SwfDocument nd = SwfDocument.load(out);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            hideBusy();
                            AppState.edited(nd);
                            toast(lastInfo.isEmpty() ? doneMsg : doneMsg + ": " + lastInfo);
                            listener.onChanged();
                        }
                    });
                } catch (final Throwable e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            hideBusy();
                            toast("Not changed: " + message(e));
                        }
                    });
                }
            }
        });
    }

    private static String message(Throwable e) {
        if (e instanceof OutOfMemoryError) return "not enough memory";
        String m = e.getMessage();
        return m != null && !m.isEmpty() ? m : e.getClass().getSimpleName();
    }

    private void toast(String s) {
        Toast.makeText(act, s, Toast.LENGTH_LONG).show();
    }

    private FrameLayout pad(EditText et) {
        FrameLayout f = new FrameLayout(act);
        int p = Math.round(20 * act.getResources().getDisplayMetrics().density);
        f.setPadding(p, p / 2, p, 0);
        f.addView(et, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return f;
    }

    private void showBusy(String text) {
        hideBusy();
        if (act.isFinishing() || act.isDestroyed()) return;
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = Math.round(24 * act.getResources().getDisplayMetrics().density);
        row.setPadding(p, p, p, p);
        row.addView(new ProgressBar(act));
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setPadding(p / 2, 0, 0, 0);
        row.addView(tv);
        busy = new AlertDialog.Builder(act).setView(row).setCancelable(false).create();
        busy.show();
    }

    private void hideBusy() {
        if (busy != null) {
            try {
                busy.dismiss();
            } catch (RuntimeException ignored) {
                // window already gone
            }
            busy = null;
        }
    }
}

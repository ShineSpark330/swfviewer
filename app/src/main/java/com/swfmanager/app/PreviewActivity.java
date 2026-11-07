package com.swfmanager.app;

import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.swfmanager.core.Asset;
import com.swfmanager.core.SoundData;
import com.swfmanager.core.SwfDocument;
import com.swfmanager.core.SwfEditor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Previews one asset (image, shape, sprite, sound, or details only) or the whole movie. */
public class PreviewActivity extends AppCompatActivity {
    public static final String EXTRA_ASSET = "asset";

    private PreviewView preview;
    private TextView message, details, frameLabel;
    private View controls;
    private Button playPause;
    private SeekBar seek;

    private Editing editing;
    private Asset current;

    private MediaPlayer player;
    private boolean playerReady;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            if (player != null && playerReady) {
                seek.setProgress(player.getCurrentPosition());
                updateTimeLabel();
                if (player.isPlaying()) handler.postDelayed(this, 100);
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_preview);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        preview = findViewById(R.id.preview);
        message = findViewById(R.id.message);
        details = findViewById(R.id.details);
        frameLabel = findViewById(R.id.frameLabel);
        controls = findViewById(R.id.controls);
        playPause = findViewById(R.id.playPause);
        seek = findViewById(R.id.seek);

        // Edits made here change the whole file, so the preview closes and the list refreshes.
        editing = new Editing(this, new Editing.Listener() {
            @Override
            public void onChanged() {
                finish();
            }
        });

        SwfDocument doc = AppState.doc;
        int idx = getIntent().getIntExtra(EXTRA_ASSET, -2);
        if (doc == null || idx < -1 || idx >= doc.assets.size()) {
            finish();
            return;
        }

        if (idx == -1) {
            setTitle(AppState.fileName);
            details.setText(doc.headerSummary());
            showAnimated(new Contents.SwfContent(doc, AppState.bitmaps, -1),
                    doc.mainTimeline().frameCount(), doc.file.frameRate);
            return;
        }

        Asset a = doc.assets.get(idx);
        current = a;
        setTitle(a.title());
        details.setText(doc.details(a));
        switch (a.type) {
            case IMAGE: {
                Bitmap bm = AppState.bitmaps.get(a.id);
                if (bm == null) showMessage("This image could not be decoded.");
                else preview.setContent(new Contents.BitmapContent(bm));
                break;
            }
            case SHAPE: {
                if (doc.shape(a.id) == null || doc.characterBounds(a.id) == null) {
                    showMessage("This shape has nothing to draw (or could not be parsed).");
                } else {
                    preview.setContent(new Contents.SwfContent(doc, AppState.bitmaps, a.id));
                }
                break;
            }
            case SPRITE: {
                if (doc.spriteTimeline(a.id) == null || doc.characterBounds(a.id) == null) {
                    showMessage("This sprite has nothing visible to draw.");
                } else {
                    showAnimated(new Contents.SwfContent(doc, AppState.bitmaps, a.id),
                            doc.spriteTimeline(a.id).frameCount(), doc.file.frameRate);
                }
                break;
            }
            case SOUND:
                setupSound(a);
                break;
            case TEXT: {
                SwfEditor.EditTextInfo e = SwfEditor.readEditText(doc, a);
                if (e == null) {
                    showMessage("Static text can't be shown yet. Details are shown below.");
                } else {
                    String body = e.hasText && !e.text.isEmpty() ? e.text : "(no initial text)";
                    showText(body + "\n\n— variable: " + (e.variable.isEmpty() ? "(none)" : e.variable)
                            + (e.html ? "\n— HTML text" : ""), false);
                }
                break;
            }
            case BINARY: {
                byte[] data = SwfEditor.binaryData(doc, a);
                String text = SwfEditor.asText(data);
                if (text != null) {
                    showText(text.length() > 100000 ? text.substring(0, 100000) + "\n… (shortened; Extract to get it all)" : text, true);
                } else {
                    int n = Math.min(data.length, 4096);
                    showText(SwfDocument.hexDump(data, 0, n) + (data.length > n
                            ? "\n… " + (data.length - n) + " more bytes (Extract to save the whole thing)" : ""), true);
                }
                break;
            }
            default:
                showMessage("No visual preview for " + a.type.label.toLowerCase(Locale.ROOT)
                        + " yet. Details are shown below.");
                break;
        }
    }

    /** Shows long, scrollable text (text fields, binary data) in place of the preview. */
    private void showText(String text, boolean mono) {
        preview.setVisibility(View.GONE);
        message.setVisibility(View.VISIBLE);
        message.setText(text);
        message.setTypeface(mono ? android.graphics.Typeface.MONOSPACE : android.graphics.Typeface.DEFAULT);
        message.setTextSize(mono ? 12 : 16);
        message.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        message.setMovementMethod(new android.text.method.ScrollingMovementMethod());
    }

    private void showMessage(String text) {
        preview.setVisibility(View.GONE);
        message.setVisibility(View.VISIBLE);
        message.setText(text);
    }

    // ---- animated vector content ---------------------------------------------------------

    private void showAnimated(PreviewView.Content content, final int frames, float fps) {
        preview.setContent(content);
        if (frames <= 1) return;
        preview.setAnimation(frames, fps);
        controls.setVisibility(View.VISIBLE);
        seek.setMax(frames - 1);
        frameLabel.setText("1 / " + frames);
        playPause.setText("Pause");
        preview.setFrameListener(new PreviewView.FrameListener() {
            @Override
            public void onFrame(int frame) {
                seek.setProgress(frame);
                frameLabel.setText((frame + 1) + " / " + frames);
            }
        });
        playPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean play = !preview.isPlaying();
                preview.setPlaying(play);
                playPause.setText(play ? "Pause" : "Play");
            }
        });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    preview.setPlaying(false);
                    playPause.setText("Play");
                    preview.setFrame(progress);
                    frameLabel.setText((progress + 1) + " / " + frames);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {}

            @Override
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        preview.setPlaying(true);
    }

    // ---- sound ---------------------------------------------------------------------------

    private void setupSound(final Asset a) {
        preview.setVisibility(View.GONE);
        message.setVisibility(View.VISIBLE);
        message.setText("♪  " + a.info + "\nPreparing…");
        exec.execute(new Runnable() {
            @Override
            public void run() {
                final SoundData s = AppState.doc.sound(a.id);
                if (s == null || s.playable == null) {
                    final String fmt = s == null ? "unknown" : s.formatName();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            message.setText("This sound format (" + fmt + ") can't be played yet.");
                        }
                    });
                    return;
                }
                try {
                    final File f = new File(getCacheDir(), "preview_" + a.id + "." + s.extension);
                    try (FileOutputStream out = new FileOutputStream(f)) {
                        out.write(s.playable);
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            startPlayer(f, a);
                        }
                    });
                } catch (final IOException e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            message.setText("Could not prepare audio: " + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private void startPlayer(File f, final Asset a) {
        if (isFinishing() || isDestroyed()) return;
        try {
            player = new MediaPlayer();
            player.setDataSource(f.getAbsolutePath());
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    playerReady = true;
                    message.setText("♪  " + a.info);
                    controls.setVisibility(View.VISIBLE);
                    seek.setMax(Math.max(1, mp.getDuration()));
                    playPause.setText("Play");
                    updateTimeLabel();
                }
            });
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    playPause.setText("Play");
                    seek.setProgress(0);
                    updateTimeLabel();
                }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    playerReady = false;
                    message.setText("Android could not play this audio (error " + what + ").");
                    controls.setVisibility(View.GONE);
                    return true;
                }
            });
            player.prepareAsync();
        } catch (IOException | RuntimeException e) {
            message.setText("Could not play audio: " + e.getMessage());
            return;
        }
        playPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!playerReady) return;
                if (player.isPlaying()) {
                    player.pause();
                    playPause.setText("Play");
                } else {
                    player.start();
                    playPause.setText("Pause");
                    handler.post(progressTick);
                }
            }
        });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && playerReady) {
                    player.seekTo(progress);
                    updateTimeLabel();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {}

            @Override
            public void onStopTrackingTouch(SeekBar bar) {}
        });
    }

    private void updateTimeLabel() {
        if (player == null || !playerReady) return;
        frameLabel.setText(formatMs(player.getCurrentPosition()) + " / " + formatMs(player.getDuration()));
    }

    private static String formatMs(int ms) {
        int s = ms / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    // ---- lifecycle -----------------------------------------------------------------------

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null && playerReady && player.isPlaying()) {
            player.pause();
            playPause.setText("Play");
        }
        preview.setPlaying(false);
        if (controls.getVisibility() == View.VISIBLE && player == null) playPause.setText("Play");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        exec.shutdown();
        if (editing != null) editing.close();
        if (player != null) {
            player.release();
            player = null;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.preview_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean have = current != null;
        menu.findItem(R.id.action_extract).setVisible(have);
        MenuItem edit = menu.findItem(R.id.action_edit);
        edit.setVisible(have && Editing.canEditContent(current));
        if (have) edit.setTitle(Editing.editLabel(current));
        menu.findItem(R.id.action_rename).setVisible(have && Editing.canRename(current));
        menu.findItem(R.id.action_replace).setVisible(have && Editing.canReplace(current));
        menu.findItem(R.id.action_delete).setVisible(have);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        } else if (current != null && id == R.id.action_extract) {
            editing.extract(current);
            return true;
        } else if (current != null && id == R.id.action_edit) {
            editing.editContent(current);
            return true;
        } else if (current != null && id == R.id.action_rename) {
            editing.rename(current);
            return true;
        } else if (current != null && id == R.id.action_replace) {
            editing.replace(current);
            return true;
        } else if (current != null && id == R.id.action_delete) {
            editing.delete(current);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}

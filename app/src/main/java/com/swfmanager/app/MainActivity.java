package com.swfmanager.app;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.swfmanager.core.Asset;
import com.swfmanager.core.AssetType;
import com.swfmanager.core.SwfDocument;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Opens an SWF, lists its assets with type filters and search, and edits / saves it. */
public class MainActivity extends AppCompatActivity {

    /** Chip order; types with no assets are skipped. */
    private static final AssetType[] ORDER = {
        AssetType.IMAGE, AssetType.SHAPE, AssetType.SPRITE, AssetType.SOUND, AssetType.FONT,
        AssetType.TEXT, AssetType.BUTTON, AssetType.MORPH_SHAPE, AssetType.BINARY,
        AssetType.SCRIPT, AssetType.VIDEO, AssetType.OTHER
    };

    /**
     * Like the stock "open document" picker, but asks for write access (and to keep it), so that
     * "Save over original" can work when the file's provider allows it.
     */
    private static final class OpenDoc extends ActivityResultContract<String[], Uri> {
        @NonNull
        @Override
        public Intent createIntent(@NonNull Context context, @NonNull String[] input) {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            return i;
        }

        @Nullable
        @Override
        public Uri parseResult(int resultCode, @Nullable Intent intent) {
            return resultCode == android.app.Activity.RESULT_OK && intent != null ? intent.getData() : null;
        }
    }

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private AssetAdapter adapter;
    private TextView summary, emptyText;
    private View chipScroll, empty, progress;
    private EditText search;
    private ChipGroup chips;
    private ActivityResultLauncher<String[]> openLauncher;
    private Editing editing;
    private OnBackPressedCallback backCallback;
    private AssetType currentFilter;
    private int shownRevision = -1;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        summary = findViewById(R.id.summary);
        emptyText = findViewById(R.id.emptyText);
        chipScroll = findViewById(R.id.chipScroll);
        empty = findViewById(R.id.empty);
        progress = findViewById(R.id.progress);
        search = findViewById(R.id.search);
        chips = findViewById(R.id.chips);

        editing = new Editing(this, new Editing.Listener() {
            @Override
            public void onChanged() {
                refreshAfterChange();
            }
        });

        adapter = new AssetAdapter(getResources().getDisplayMetrics().density, new AssetAdapter.OnClick() {
            @Override
            public void onAsset(Asset asset) {
                openPreview(asset);
            }

            @Override
            public void onAssetLong(Asset asset) {
                showActions(asset);
            }
        });
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        openLauncher = registerForActivityResult(new OpenDoc(), new androidx.activity.result.ActivityResultCallback<Uri>() {
            @Override
            public void onActivityResult(Uri uri) {
                if (uri != null) load(uri);
            }
        });
        findViewById(R.id.openButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFile();
            }
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                adapter.setQuery(s.toString());
                updateEmpty();
            }
        });

        // With unsaved edits, Back asks before throwing them away.
        backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                confirmDiscard(new Runnable() {
                    @Override
                    public void run() {
                        AppState.clear();
                        backCallback.setEnabled(false);
                        getOnBackPressedDispatcher().onBackPressed();
                    }
                });
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backCallback);

        if (AppState.doc != null) {
            show(AppState.doc, AppState.fileName);
        } else {
            Uri incoming = getIntent() != null ? getIntent().getData() : null;
            if (incoming != null) load(incoming);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getData() != null) load(intent.getData());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The preview screen can edit too; pick up its changes.
        if (AppState.doc != null && AppState.revision != shownRevision) refreshAfterChange();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        exec.shutdown();
        editing.close();
    }

    private void pickFile() {
        if (AppState.dirty) {
            confirmDiscard(new Runnable() {
                @Override
                public void run() {
                    openLauncher.launch(new String[] {"*/*"});
                }
            });
        } else {
            openLauncher.launch(new String[] {"*/*"});
        }
    }

    private void confirmDiscard(final Runnable discard) {
        new AlertDialog.Builder(this)
                .setTitle("Unsaved changes")
                .setMessage("You've edited " + AppState.fileName + " but haven't saved it.")
                .setPositiveButton("Save as…", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        editing.saveAs();
                    }
                })
                .setNegativeButton("Discard changes", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        discard.run();
                    }
                })
                .setNeutralButton("Cancel", null)
                .show();
    }

    // ---- loading -------------------------------------------------------------------------

    private void load(final Uri uri) {
        progress.setVisibility(View.VISIBLE);
        empty.setVisibility(View.GONE);
        exec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] raw = Io.readAll(getContentResolver(), uri);
                    final SwfDocument doc = SwfDocument.load(raw);
                    final String name = Io.displayName(getContentResolver(), uri, "movie.swf");
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            progress.setVisibility(View.GONE);
                            AppState.set(doc, name, uri);
                            show(doc, name);
                        }
                    });
                } catch (final Throwable e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            progress.setVisibility(View.GONE);
                            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                            Toast.makeText(MainActivity.this, "Could not open file: " + msg, Toast.LENGTH_LONG).show();
                            updateEmpty();
                        }
                    });
                }
            }
        });
    }

    // ---- UI ------------------------------------------------------------------------------

    private String summaryText(SwfDocument doc) {
        return doc.headerSummary() + (AppState.dirty ? "  ·  unsaved changes" : "");
    }

    private void show(SwfDocument doc, String name) {
        shownRevision = AppState.revision;
        setTitle(name);
        summary.setText(summaryText(doc));
        summary.setVisibility(View.VISIBLE);
        search.setText("");
        search.setVisibility(View.VISIBLE);
        currentFilter = null;
        buildChips(doc);
        invalidateOptionsMenu();
        adapter.setTypeFilter(null);
        adapter.setAssets(doc.assets);
        backCallback.setEnabled(AppState.dirty);
        updateEmpty();
    }

    /** The document was edited, undone or saved: update the list but keep the search and filter. */
    private void refreshAfterChange() {
        SwfDocument doc = AppState.doc;
        shownRevision = AppState.revision;
        if (doc == null) return;
        setTitle(AppState.fileName);
        summary.setText(summaryText(doc));
        summary.setVisibility(View.VISIBLE);
        search.setVisibility(View.VISIBLE);
        if (currentFilter != null && doc.count(currentFilter) == 0) {
            currentFilter = null;
            adapter.setTypeFilter(null);
        }
        buildChips(doc);
        adapter.setAssets(doc.assets);
        backCallback.setEnabled(AppState.dirty);
        invalidateOptionsMenu();
        updateEmpty();
    }

    private void buildChips(SwfDocument doc) {
        chips.removeAllViews();
        addChip("All (" + doc.assets.size() + ")", null, currentFilter == null);
        for (AssetType t : ORDER) {
            int n = doc.count(t);
            if (n > 0) addChip(t.label + " (" + n + ")", t, t == currentFilter);
        }
        chipScroll.setVisibility(View.VISIBLE);
    }

    private void addChip(String label, AssetType type, boolean checked) {
        Chip chip = (Chip) getLayoutInflater().inflate(R.layout.chip_filter, chips, false);
        chip.setId(View.generateViewId());
        chip.setText(label);
        chip.setTag(type);
        chips.addView(chip);
        chip.setChecked(checked);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                currentFilter = (AssetType) v.getTag();
                adapter.setTypeFilter(currentFilter);
                updateEmpty();
            }
        });
    }

    private void updateEmpty() {
        boolean haveDoc = AppState.doc != null;
        boolean nothing = adapter.shownCount() == 0;
        empty.setVisibility(nothing && progress.getVisibility() != View.VISIBLE ? View.VISIBLE : View.GONE);
        if (haveDoc && nothing) {
            emptyText.setText("No assets match.");
            findViewById(R.id.openButton).setVisibility(View.GONE);
        } else if (!haveDoc) {
            emptyText.setText("Open a Flash (.swf) file to browse its assets.");
            findViewById(R.id.openButton).setVisibility(View.VISIBLE);
        }
    }

    // ---- asset actions -------------------------------------------------------------------

    private void openPreview(Asset asset) {
        int idx = AppState.doc.assets.indexOf(asset);
        Intent i = new Intent(this, PreviewActivity.class);
        i.putExtra(PreviewActivity.EXTRA_ASSET, idx);
        startActivity(i);
    }

    /** Long-press menu: preview, rename, replace, delete. */
    private void showActions(final Asset asset) {
        final List<String> labels = new ArrayList<>();
        labels.add("Preview");
        labels.add("Extract…");
        if (Editing.canEditContent(asset)) labels.add(Editing.editLabel(asset));
        if (Editing.canRename(asset)) labels.add("Rename…");
        if (Editing.canReplace(asset)) labels.add("Replace with file…");
        labels.add("Delete…");
        new AlertDialog.Builder(this)
                .setTitle(asset.title())
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String l = labels.get(which);
                        if (l.equals("Preview")) openPreview(asset);
                        else if (l.startsWith("Extract")) editing.extract(asset);
                        else if (l.startsWith("Edit")) editing.editContent(asset);
                        else if (l.startsWith("Rename")) editing.rename(asset);
                        else if (l.startsWith("Replace")) editing.replace(asset);
                        else editing.delete(asset);
                    }
                })
                .show();
    }

    // ---- menu ----------------------------------------------------------------------------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean have = AppState.doc != null;
        menu.findItem(R.id.action_play).setVisible(have);
        menu.findItem(R.id.action_tags).setVisible(have);
        menu.findItem(R.id.action_add).setVisible(have);
        menu.findItem(R.id.action_save_over).setVisible(have);
        menu.findItem(R.id.action_undo).setVisible(have && AppState.canUndo());
        MenuItem saveAs = menu.findItem(R.id.action_save_as);
        saveAs.setVisible(have);
        // Make "Save as…" prominent only while there is something to save.
        saveAs.setShowAsAction(have && AppState.dirty
                ? MenuItem.SHOW_AS_ACTION_IF_ROOM : MenuItem.SHOW_AS_ACTION_NEVER);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_open) {
            pickFile();
            return true;
        } else if (id == R.id.action_add) {
            editing.add();
            return true;
        } else if (id == R.id.action_save_as) {
            editing.saveAs();
            return true;
        } else if (id == R.id.action_save_over) {
            editing.saveOverOriginal();
            return true;
        } else if (id == R.id.action_undo) {
            editing.undo();
            return true;
        } else if (id == R.id.action_play) {
            Intent i = new Intent(this, PreviewActivity.class);
            i.putExtra(PreviewActivity.EXTRA_ASSET, -1);
            startActivity(i);
            return true;
        } else if (id == R.id.action_tags) {
            showTagList();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showTagList() {
        List<String> lines = AppState.doc.rawTagLines();
        StringBuilder sb = new StringBuilder();
        for (String s : lines) sb.append(s).append('\n');
        TextView tv = new TextView(this);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextSize(11);
        tv.setPadding(32, 24, 32, 24);
        tv.setTextIsSelectable(true);
        tv.setText(sb.toString());
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this).setTitle("Raw tags (" + lines.size() + ")").setView(sv)
                .setPositiveButton("Close", null).show();
    }
}

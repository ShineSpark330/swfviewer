package com.swfmanager.app;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Opens an SWF and lists its assets with type filters and search. */
public class MainActivity extends AppCompatActivity {

    /** Chip order; types with no assets are skipped. */
    private static final AssetType[] ORDER = {
        AssetType.IMAGE, AssetType.SHAPE, AssetType.SPRITE, AssetType.SOUND, AssetType.FONT,
        AssetType.TEXT, AssetType.BUTTON, AssetType.MORPH_SHAPE, AssetType.BINARY,
        AssetType.SCRIPT, AssetType.VIDEO, AssetType.OTHER
    };

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private AssetAdapter adapter;
    private TextView summary, emptyText;
    private View chipScroll, empty, progress;
    private EditText search;
    private ChipGroup chips;
    private ActivityResultLauncher<String[]> openLauncher;

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

        adapter = new AssetAdapter(getResources().getDisplayMetrics().density, new AssetAdapter.OnClick() {
            @Override
            public void onAsset(Asset asset) {
                int idx = AppState.doc.assets.indexOf(asset);
                Intent i = new Intent(MainActivity.this, PreviewActivity.class);
                i.putExtra(PreviewActivity.EXTRA_ASSET, idx);
                startActivity(i);
            }
        });
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        openLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), new androidx.activity.result.ActivityResultCallback<Uri>() {
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
    protected void onDestroy() {
        super.onDestroy();
        exec.shutdown();
    }

    private void pickFile() {
        openLauncher.launch(new String[] {"*/*"});
    }

    // ---- loading -------------------------------------------------------------------------

    private void load(final Uri uri) {
        progress.setVisibility(View.VISIBLE);
        empty.setVisibility(View.GONE);
        exec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] raw = readAll(uri);
                    final SwfDocument doc = SwfDocument.load(raw);
                    final String name = displayName(uri);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            progress.setVisibility(View.GONE);
                            AppState.set(doc, name);
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

    private byte[] readAll(Uri uri) throws java.io.IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new java.io.IOException("Cannot open file");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) return n;
            }
        } catch (RuntimeException ignored) {
            // fall through to the path
        }
        String p = uri.getLastPathSegment();
        return p != null ? p : "movie.swf";
    }

    // ---- UI ------------------------------------------------------------------------------

    private void show(SwfDocument doc, String name) {
        setTitle(name);
        summary.setText(doc.headerSummary());
        summary.setVisibility(View.VISIBLE);
        search.setText("");
        search.setVisibility(View.VISIBLE);
        buildChips(doc);
        invalidateOptionsMenu();
        adapter.setTypeFilter(null);
        adapter.setAssets(doc.assets);
        updateEmpty();
    }

    private void buildChips(SwfDocument doc) {
        chips.removeAllViews();
        addChip("All (" + doc.assets.size() + ")", null, true);
        for (AssetType t : ORDER) {
            int n = doc.count(t);
            if (n > 0) addChip(t.label + " (" + n + ")", t, false);
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
                adapter.setTypeFilter((AssetType) v.getTag());
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
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_open) {
            pickFile();
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

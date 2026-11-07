package com.swfmanager.app;

import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.swfmanager.core.Asset;
import com.swfmanager.core.AssetType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Asset list with a type filter and a text filter. */
final class AssetAdapter extends RecyclerView.Adapter<AssetAdapter.Holder> {

    interface OnClick {
        void onAsset(Asset asset);

        void onAssetLong(Asset asset);
    }

    private final List<Asset> all = new ArrayList<>();
    private final List<Asset> shown = new ArrayList<>();
    private final OnClick listener;
    private final int thumbPx;
    private AssetType typeFilter;
    private String query = "";

    AssetAdapter(float density, OnClick onClick) {
        this.listener = onClick;
        this.thumbPx = Math.round(56 * density);
    }

    void setAssets(List<Asset> assets) {
        all.clear();
        all.addAll(assets);
        refilter();
    }

    void setTypeFilter(AssetType t) {
        typeFilter = t;
        refilter();
    }

    void setQuery(String q) {
        query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        refilter();
    }

    int shownCount() {
        return shown.size();
    }

    @SuppressWarnings("notifyDataSetChanged")
    private void refilter() {
        shown.clear();
        for (Asset a : all) {
            if (typeFilter != null && a.type != typeFilter) continue;
            if (!query.isEmpty()) {
                String hay = (a.title() + " " + a.info).toLowerCase(Locale.ROOT);
                String q = query.startsWith("#") ? query.substring(1) : query;
                boolean idMatch = a.id >= 0 && query.startsWith("#") && String.valueOf(a.id).equals(q);
                if (!idMatch && !hay.contains(query)) continue;
            }
            shown.add(a);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_asset, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull final Holder h, int position) {
        final Asset a = shown.get(position);
        h.title.setText(a.title());
        h.sub.setText(a.subtitle());
        h.thumb.setTag(a.tagIndex);
        Bitmap cached = Thumbs.cached(a);
        if (cached != null) {
            h.thumb.setImageBitmap(cached);
        } else {
            h.thumb.setImageBitmap(Thumbs.badge(a.type, thumbPx));
            Thumbs.load(a, thumbPx, new Thumbs.Callback() {
                @Override
                public void onThumb(Bitmap bitmap) {
                    Object tag = h.thumb.getTag();
                    if (tag instanceof Integer && (Integer) tag == a.tagIndex) h.thumb.setImageBitmap(bitmap);
                }
            });
        }
        h.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onAsset(a);
            }
        });
        h.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                listener.onAssetLong(a);
                return true;
            }
        });
    }

    @Override
    public int getItemCount() {
        return shown.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final TextView title, sub;

        Holder(View v) {
            super(v);
            thumb = v.findViewById(R.id.thumb);
            title = v.findViewById(R.id.title);
            sub = v.findViewById(R.id.sub);
        }
    }
}

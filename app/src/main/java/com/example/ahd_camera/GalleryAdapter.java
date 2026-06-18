package com.example.ahd_camera;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class GalleryAdapter extends RecyclerView.Adapter<GalleryAdapter.GalleryViewHolder> {

    interface OnItemClickListener {
        void onItemClick(GalleryItem item);
    }

    private static final String TAG = "GalleryAdapter";
    private static final Size THUMBNAIL_SIZE = new Size(240, 160);

    private final ContentResolver contentResolver;
    private final OnItemClickListener onItemClickListener;
    private final List<GalleryItem> items = new ArrayList<>();
    private RecyclerView attachedRecyclerView;
    private int selectedPosition = RecyclerView.NO_POSITION;

    GalleryAdapter(ContentResolver contentResolver, OnItemClickListener onItemClickListener) {
        this.contentResolver = contentResolver;
        this.onItemClickListener = onItemClickListener;
    }

    void submitItems(List<GalleryItem> galleryItems) {
        items.clear();
        items.addAll(galleryItems);
        if (items.isEmpty()) {
            selectedPosition = RecyclerView.NO_POSITION;
        } else if (selectedPosition == RecyclerView.NO_POSITION) {
            selectedPosition = 0;
        } else if (selectedPosition >= items.size()) {
            selectedPosition = items.size() - 1;
        }
        notifyDataSetChanged();
        Log.d(TAG, "submitItems: count=" + items.size() + ", selectedPosition=" + selectedPosition);
    }

    int getSelectedPosition() {
        return selectedPosition;
    }

    void setSelectedPosition(int position) {
        if (items.isEmpty()) {
            selectedPosition = RecyclerView.NO_POSITION;
            return;
        }
        int clampedPosition = Math.max(0, Math.min(position, items.size() - 1));
        if (selectedPosition == clampedPosition) {
            return;
        }
        int oldPosition = selectedPosition;
        selectedPosition = clampedPosition;
        Log.d(TAG, "setSelectedPosition: old=" + oldPosition + ", new=" + selectedPosition);
        updateVisibleSelectionState(oldPosition, false);
        updateVisibleSelectionState(selectedPosition, true);
    }

    @NonNull
    @Override
    public GalleryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_gallery_media, parent, false);
        return new GalleryViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull GalleryViewHolder holder, int position) {
        GalleryItem item = items.get(position);
        boolean selected = position == selectedPosition;
        holder.titleView.setText(item.displayName);
        holder.subtitleView.setText(item.subtitle);
        holder.typeView.setText(item.isVideo() ? "VIDEO" : "IMAGE");
        holder.typeView.setBackgroundColor(item.isVideo() ? 0xAA1F5FBF : 0xAA1E7A46);
        holder.bindSelectionState(selected);

        try {
            Bitmap bitmap = contentResolver.loadThumbnail(item.uri, THUMBNAIL_SIZE, null);
            holder.thumbnailView.setImageBitmap(bitmap);
            holder.thumbnailView.setBackgroundColor(Color.TRANSPARENT);
        } catch (IOException | SecurityException e) {
            Log.w(TAG, "onBindViewHolder: loadThumbnail failed for " + item.uri, e);
            holder.thumbnailView.setImageDrawable(null);
            holder.thumbnailView.setBackgroundColor(item.isVideo() ? 0xFF10325C : 0xFF1E3A24);
        }

        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                return;
            }
            int adapterPosition = holder.getBindingAdapterPosition();
            if (adapterPosition != RecyclerView.NO_POSITION) {
                Log.d(TAG, "onFocusChange: position=" + adapterPosition + ", name=" + item.displayName);
                setSelectedPosition(adapterPosition);
            }
        });
        holder.itemView.setOnClickListener(v -> onItemClickListener.onItemClick(item));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        attachedRecyclerView = recyclerView;
        Log.d(TAG, "onAttachedToRecyclerView");
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        if (attachedRecyclerView == recyclerView) {
            attachedRecyclerView = null;
        }
        Log.d(TAG, "onDetachedFromRecyclerView");
    }

    private void updateVisibleSelectionState(int position, boolean selected) {
        if (position == RecyclerView.NO_POSITION || attachedRecyclerView == null) {
            return;
        }
        RecyclerView.ViewHolder holder = attachedRecyclerView.findViewHolderForAdapterPosition(position);
        if (holder instanceof GalleryViewHolder) {
            ((GalleryViewHolder) holder).bindSelectionState(selected);
        }
    }

    static final class GalleryViewHolder extends RecyclerView.ViewHolder {
        private final ImageView thumbnailView;
        private final TextView titleView;
        private final TextView subtitleView;
        private final TextView typeView;

        GalleryViewHolder(@NonNull View itemView) {
            super(itemView);
            thumbnailView = itemView.findViewById(R.id.gallery_item_thumbnail);
            titleView = itemView.findViewById(R.id.gallery_item_title);
            subtitleView = itemView.findViewById(R.id.gallery_item_subtitle);
            typeView = itemView.findViewById(R.id.gallery_item_type);
        }

        void bindSelectionState(boolean selected) {
            itemView.setSelected(selected);
        }
    }
}

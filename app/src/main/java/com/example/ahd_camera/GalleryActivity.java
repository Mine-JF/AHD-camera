package com.example.ahd_camera;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class GalleryActivity extends AppCompatActivity {

    private static final String TAG = "GalleryActivity";
    private static final int GRID_SPAN_COUNT = 3;

    private RecyclerView recyclerView;
    private TextView emptyView;
    private GalleryAdapter adapter;

    private final ActivityResultLauncher<String[]> mediaPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                Log.d(TAG, "mediaPermissionLauncher: result=" + result);
                loadGalleryItems();
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        Log.d(TAG, "onCreate: enter");
        Utils.prepareFullscreenWindow(getWindow());
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gallery);

        recyclerView = findViewById(R.id.gallery_recycler_view);
        emptyView = findViewById(R.id.gallery_empty_text);
        adapter = new GalleryAdapter(getContentResolver(), this::openPreview);

        recyclerView.setLayoutManager(new GridLayoutManager(this, GRID_SPAN_COUNT));
        recyclerView.setAdapter(adapter);
        Log.d(TAG, "onCreate: recycler initialized");
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume: ensure permission and load");
        Utils.hideSystemUI(getWindow());
        ensureMediaPermissionAndLoad();
    }

    @Override
    protected void onDestroy() {
        if (adapter != null) {
            adapter.release();
        }
        super.onDestroy();
    }

    private void ensureMediaPermissionAndLoad() {
        if (hasMediaReadPermission()) {
            Log.d(TAG, "ensureMediaPermissionAndLoad: permission granted");
            loadGalleryItems();
            return;
        }

        Log.w(TAG, "ensureMediaPermissionAndLoad: request permission");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            mediaPermissionLauncher.launch(new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
            });
        } else {
            mediaPermissionLauncher.launch(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE});
        }
    }

    private boolean hasMediaReadPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                    == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void loadGalleryItems() {
        List<GalleryItem> items = new ArrayList<>();
        List<String> removableVolumes = StorageUtils.getMountedRemovableVolumeNames(this);
        if (removableVolumes.isEmpty()) {
            Log.w(TAG, "loadGalleryItems: no mounted removable external volumes");
            showItems(items);
            return;
        }

        for (String volumeName : removableVolumes) {
            loadGalleryItemsFromVolume(volumeName, items);
        }
        Collections.sort(items, Comparator.comparingLong((GalleryItem item) -> item.dateModifiedSec).reversed());
        Log.d(TAG, "loadGalleryItems: total count=" + items.size()
                + ", removableVolumes=" + removableVolumes);

        showItems(items);
    }

    private void loadGalleryItemsFromVolume(String volumeName, List<GalleryItem> items) {
        Uri collection = MediaStore.Files.getContentUri(volumeName);
        String[] projection = {
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATE_MODIFIED,
                MediaStore.Files.FileColumns.SIZE
        };
        String selection = MediaStore.Files.FileColumns.MEDIA_TYPE + "=? OR "
                + MediaStore.Files.FileColumns.MEDIA_TYPE + "=?";
        String[] selectionArgs = {
                String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE),
                String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
        };
        String sortOrder = MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC";

        try (Cursor cursor = getContentResolver().query(
                collection, projection, selection, selectionArgs, sortOrder)) {
            if (cursor == null) {
                Log.w(TAG, "loadGalleryItemsFromVolume: cursor is null, volume=" + volumeName);
                return;
            }

            int idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID);
            int typeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE);
            int nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME);
            int dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED);
            int sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE);

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idIndex);
                int mediaType = cursor.getInt(typeIndex);
                String displayName = cursor.getString(nameIndex);
                long dateModifiedSec = cursor.getLong(dateIndex);
                long sizeBytes = cursor.getLong(sizeIndex);
                Uri itemUri = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                        ? MediaStore.Video.Media.getContentUri(volumeName)
                        : MediaStore.Images.Media.getContentUri(volumeName);
                Uri contentUri = Uri.withAppendedPath(itemUri, String.valueOf(id));
                items.add(new GalleryItem(
                        contentUri,
                        displayName == null ? "Unnamed" : displayName,
                        buildSubtitle(dateModifiedSec, sizeBytes, volumeName),
                        mediaType,
                        dateModifiedSec
                ));
            }
            Log.d(TAG, "loadGalleryItemsFromVolume: volume=" + volumeName
                    + ", accumulatedCount=" + items.size());
        } catch (SecurityException e) {
            Log.e(TAG, "loadGalleryItemsFromVolume: permission denied, volume=" + volumeName, e);
        } catch (RuntimeException e) {
            Log.e(TAG, "loadGalleryItemsFromVolume: query failed, volume=" + volumeName, e);
        }
    }

    private String buildSubtitle(long dateModifiedSec, long sizeBytes, String volumeName) {
        String date = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(new Date(dateModifiedSec * 1000L));
        return date + "  " + formatSize(sizeBytes);
    }

    private String formatSize(long sizeBytes) {
        if (sizeBytes <= 0) {
            return "0B";
        }
        if (sizeBytes < 1024 * 1024) {
            return (sizeBytes / 1024) + "KB";
        }
        return String.format(Locale.US, "%.1fMB", sizeBytes / 1024f / 1024f);
    }

    private void showItems(List<GalleryItem> items) {
        adapter.submitItems(items);
        emptyView.setText(items.isEmpty() ? getString(R.string.gallery_empty) : "");
        if (!items.isEmpty()) {
            recyclerView.post(() -> focusGalleryItem(adapter.getSelectedPosition()));
        }
    }

    private void focusGalleryItem(int position) {
        if (position == RecyclerView.NO_POSITION) {
            Log.w(TAG, "focusGalleryItem: invalid position");
            return;
        }
        Log.d(TAG, "focusGalleryItem: position=" + position);
        recyclerView.scrollToPosition(position);
        RecyclerView.ViewHolder holder = recyclerView.findViewHolderForAdapterPosition(position);
        if (holder != null) {
            holder.itemView.requestFocus();
            return;
        }
        recyclerView.post(() -> {
            RecyclerView.ViewHolder delayedHolder = recyclerView.findViewHolderForAdapterPosition(position);
            if (delayedHolder != null) {
                delayedHolder.itemView.requestFocus();
            } else {
                Log.w(TAG, "focusGalleryItem: holder still not attached, position=" + position);
            }
        });
    }

    private void openPreview(GalleryItem item) {
        Log.d(TAG, "openPreview: uri=" + item.uri + ", isVideo=" + item.isVideo());
        Intent intent = new Intent(this, MediaPreviewActivity.class);
        intent.setData(item.uri);
        intent.putExtra(MediaPreviewActivity.EXTRA_IS_VIDEO, item.isVideo());
        intent.putExtra(MediaPreviewActivity.EXTRA_TITLE, item.displayName);
        startActivity(intent);
    }

    private void moveSelection(int delta) {
        int current = adapter.getSelectedPosition();
        if (current == RecyclerView.NO_POSITION) {
            Log.w(TAG, "moveSelection: no selected item");
            return;
        }
        int next = Math.max(0, Math.min(current + delta, adapter.getItemCount() - 1));
        if (next == current) {
            Log.d(TAG, "moveSelection: keep current=" + current);
            return;
        }
        adapter.setSelectedPosition(next);
        focusGalleryItem(next);
    }

    private void openSelectedItem() {
        int position = adapter.getSelectedPosition();
        if (position == RecyclerView.NO_POSITION || position >= adapter.getItemCount()) {
            Log.w(TAG, "openSelectedItem: invalid position=" + position);
            return;
        }
        GalleryItem item = adapter.getItem(position);
        openPreview(item);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!HardwareKeySourceFilter.isBoardKey(event)) {
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_ESCAPE:
                case KeyEvent.KEYCODE_BUTTON_Z:
                case KeyEvent.KEYCODE_BUTTON_3:
                    Log.d(TAG, "dispatchKeyEvent: exit gallery");
                    finish();
                    return true;
                case KeyEvent.KEYCODE_BUTTON_2:
                case KeyEvent.KEYCODE_BUTTON_7:
                    Log.d(TAG, "dispatchKeyEvent: open selected item");
                    openSelectedItem();
                    return true;
                case KeyEvent.KEYCODE_BUTTON_12:
                    moveSelection(-1);
                    return true;
                case KeyEvent.KEYCODE_BUTTON_13:
                    moveSelection(1);
                    return true;
                case KeyEvent.KEYCODE_BUTTON_10:
                    moveSelection(-GRID_SPAN_COUNT);
                    return true;
                case KeyEvent.KEYCODE_BUTTON_11:
                    moveSelection(GRID_SPAN_COUNT);
                    return true;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
    }
}

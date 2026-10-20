package com.example.ahd_camera;

import android.content.Context;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 外部可移除存储工具类。
 * 统一管理 U 盘 / SD 卡检测，避免图库、拍照、录像各写一套判断逻辑。
 */
final class StorageUtils {

    private static final String TAG = "StorageUtils";

    private StorageUtils() {
    }

    static boolean hasMountedRemovableStorage(Context context) {
        return !getMountedRemovableVolumeNames(context).isEmpty();
    }

    static boolean hasWritableRemovableStorage(Context context) {
        return getPreferredRemovableVolumeName(context) != null;
    }

    static String getPreferredRemovableVolumeName(Context context) {
        List<String> volumeNames = getMountedSdCardVolumeNames(context);
        if (volumeNames.isEmpty()) {
            Log.w(TAG, "getPreferredRemovableVolumeName: no removable volume");
            return null;
        }
        StorageManager manager = context.getSystemService(StorageManager.class);
        if (manager != null) {
            for (String volumeName : volumeNames) {
                for (StorageVolume volume : manager.getStorageVolumes()) {
                    if (volumeName.equals(volume.getMediaStoreVolumeName())
                            && Environment.MEDIA_MOUNTED.equals(volume.getState())) {
                        Log.d(TAG, "getPreferredRemovableVolumeName: volumeName=" + volumeName);
                        return volumeName;
                    }
                }
            }
        }
        Log.w(TAG, "getPreferredRemovableVolumeName: removable storage is read-only");
        return null;
    }

    /** 优先使用 MMC/SD 卡；无法识别底层设备时保留原有可移除存储行为。 */
    static List<String> getMountedSdCardVolumeNames(Context context) {
        List<String> volumes = getMountedRemovableVolumeNames(context);
        if (volumes.size() <= 1) {
            return volumes;
        }
        List<String> sdCards = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/mounts"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split(" ");
                if (fields.length < 2 || !(fields[0].contains("public:179,")
                        || fields[0].contains("mmcblk"))) {
                    continue;
                }
                for (String volume : volumes) {
                    if (fields[1].toLowerCase(java.util.Locale.US)
                            .contains(volume.toLowerCase(java.util.Locale.US))
                            && !sdCards.contains(volume)) {
                        sdCards.add(volume);
                    }
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Unable to identify MMC mount; using removable volumes", e);
        }
        if (!sdCards.isEmpty()) {
            Log.i(TAG, "Mounted SD card MediaStore volumes=" + sdCards);
            return sdCards;
        }
        Log.w(TAG, "MMC mount not identified; using mounted removable volumes=" + volumes);
        return volumes;
    }

    static List<String> getMountedRemovableVolumeNames(Context context) {
        StorageManager storageManager = context.getSystemService(StorageManager.class);
        if (storageManager == null) {
            Log.w(TAG, "getMountedRemovableVolumeNames: storageManager is null");
            return Collections.emptyList();
        }

        Set<String> mediaStoreVolumeNames = new HashSet<>(MediaStore.getExternalVolumeNames(context));
        List<String> removableVolumeNames = new ArrayList<>();
        for (StorageVolume volume : storageManager.getStorageVolumes()) {
            if (!volume.isRemovable()) {
                continue;
            }
            String state = volume.getState();
            if (!Environment.MEDIA_MOUNTED.equals(state)
                    && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
                Log.d(TAG, "getMountedRemovableVolumeNames: skip state=" + state);
                continue;
            }
            String volumeName = volume.getMediaStoreVolumeName();
            if (volumeName == null || volumeName.isEmpty()) {
                Log.w(TAG, "getMountedRemovableVolumeNames: skip volume without MediaStore name");
                continue;
            }
            if (!mediaStoreVolumeNames.contains(volumeName)) {
                Log.w(TAG, "getMountedRemovableVolumeNames: volume not indexed, volumeName=" + volumeName);
                continue;
            }
            removableVolumeNames.add(volumeName);
            Log.d(TAG, "getMountedRemovableVolumeNames: add volumeName=" + volumeName
                    + ", uuid=" + volume.getUuid()
                    + ", directory=" + volume.getDirectory()
                    + ", description=" + volume.getDescription(context));
        }
        return removableVolumeNames;
    }
}

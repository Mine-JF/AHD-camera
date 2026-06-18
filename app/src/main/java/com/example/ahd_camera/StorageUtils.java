package com.example.ahd_camera;

import android.content.Context;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;
import android.util.Log;

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

    static String getPreferredRemovableVolumeName(Context context) {
        List<String> volumeNames = getMountedRemovableVolumeNames(context);
        if (volumeNames.isEmpty()) {
            Log.w(TAG, "getPreferredRemovableVolumeName: no removable volume");
            return null;
        }
        String volumeName = volumeNames.get(0);
        Log.d(TAG, "getPreferredRemovableVolumeName: volumeName=" + volumeName);
        return volumeName;
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
                    + ", description=" + volume.getDescription(context));
        }
        return removableVolumeNames;
    }
}

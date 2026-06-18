package com.example.ahd_camera;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class V4l2CameraController implements CameraPreviewController {

    private static final String TAG = "V4l2CameraController";
    private static final int PIXEL_FORMAT_MJPEG = 0;
    private static final int MAX_PREVIEW_WIDTH = 1280;
    private static final int MAX_PREVIEW_HEIGHT = 720;
    private static final long TOPOLOGY_POLL_MS = 1000L;
    private static final String SYSFS_VIDEO4LINUX_DIR = "/sys/class/video4linux";
    private static final String[] PREFERRED_USB_NAME_KEYWORDS = {
            "rlzg",
            "rsict",
            "uvc",
            "camera"
    };

    private final CameraStatusCallback statusCallback;
    private final Handler mainHandler = new Handler(android.os.Looper.getMainLooper());
    private final Runnable topologyPollRunnable = new Runnable() {
        @Override
        public void run() {
            pollTopology();
            if (availabilityMonitorRegistered && cameraHandler != null) {
                cameraHandler.postDelayed(this, TOPOLOGY_POLL_MS);
            }
        }
    };

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private boolean availabilityMonitorRegistered;
    private String[] lastTopology = new String[0];
    private boolean nativeUnavailableLogged;
    private final PreviewSlot leftSlot = new PreviewSlot("left");
    private final PreviewSlot rightSlot = new PreviewSlot("right");

    public V4l2CameraController(CameraStatusCallback statusCallback) {
        this.statusCallback = statusCallback;
        Log.d(TAG, "controller created");
    }

    @Override
    public void bindPreviews(TextureView leftPreview, TextureView rightPreview) {
        Log.d(TAG, "bindPreviews: left=" + leftPreview + ", right=" + rightPreview);
        leftSlot.previewView = leftPreview;
        rightSlot.previewView = rightPreview;

        leftPreview.setSurfaceTextureListener(buildSurfaceListener(leftSlot));
        rightPreview.setSurfaceTextureListener(buildSurfaceListener(rightSlot));
    }

    @Override
    public void startBackgroundThread() {
        if (cameraThread != null) {
            Log.d(TAG, "startBackgroundThread skipped: thread already started");
            return;
        }
        cameraThread = new HandlerThread("v4l2-camera-thread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        lastTopology = new String[0];
        Log.d(TAG, "startBackgroundThread: thread started");
    }

    @Override
    public void stopBackgroundThread() {
        if (cameraThread == null) {
            Log.d(TAG, "stopBackgroundThread skipped: thread already null");
            return;
        }
        Log.d(TAG, "stopBackgroundThread: begin");
        stopAvailabilityMonitor();
        closeAllCameras();
        cameraThread.quitSafely();
        try {
            cameraThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cameraThread = null;
        cameraHandler = null;
        Log.d(TAG, "stopBackgroundThread: finished");
    }

    @Override
    public void startAvailabilityMonitor() {
        if (availabilityMonitorRegistered || cameraHandler == null) {
            Log.d(TAG, "startAvailabilityMonitor skipped: registered=" + availabilityMonitorRegistered
                    + ", handlerNull=" + (cameraHandler == null));
            return;
        }
        availabilityMonitorRegistered = true;
        lastTopology = getSelectedCameraIds();
        Log.d(TAG, "startAvailabilityMonitor: initial topology=" + Arrays.toString(lastTopology));
        cameraHandler.post(topologyPollRunnable);
    }

    @Override
    public void stopAvailabilityMonitor() {
        Log.d(TAG, "stopAvailabilityMonitor: registered=" + availabilityMonitorRegistered);
        availabilityMonitorRegistered = false;
        if (cameraHandler != null) {
            cameraHandler.removeCallbacks(topologyPollRunnable);
        }
    }

    @Override
    public String[] getSelectedCameraIds() {
        List<String> selected = selectUsbCameraNodes();
        int count = Math.min(selected.size(), 2);
        String[] result = selected.subList(0, count).toArray(new String[0]);
        Log.d(TAG, "getSelectedCameraIds: selected=" + Arrays.toString(result)
                + ", candidates=" + selected);
        return result;
    }

    private List<String> selectUsbCameraNodes() {
        File sysfsDir = new File(SYSFS_VIDEO4LINUX_DIR);
        File[] videoEntries = sysfsDir.listFiles(file -> file.isDirectory() && file.getName().startsWith("video"));
        if (videoEntries == null || videoEntries.length == 0) {
            Log.w(TAG, "selectUsbCameraNodes: no sysfs video entries under " + SYSFS_VIDEO4LINUX_DIR);
            return Collections.emptyList();
        }

        Arrays.sort(videoEntries, Comparator.comparing(File::getName));

        Map<String, List<VideoNodeInfo>> groupedUsbNodes = new LinkedHashMap<>();
        for (File entry : videoEntries) {
            VideoNodeInfo info = readVideoNodeInfo(entry);
            if (info == null) {
                continue;
            }
            Log.d(TAG, "selectUsbCameraNodes inspect: " + info);
            if (!info.isUsb) {
                continue;
            }
            if (!looksLikeUsbCamera(info.displayName)) {
                Log.d(TAG, "selectUsbCameraNodes skip non-camera usb node: " + info.devPath
                        + ", name=" + info.displayName);
                continue;
            }
            groupedUsbNodes.computeIfAbsent(info.groupKey, ignored -> new ArrayList<>()).add(info);
        }

        if (groupedUsbNodes.isEmpty()) {
            Log.w(TAG, "selectUsbCameraNodes: no matching usb camera groups");
            return Collections.emptyList();
        }

        List<String> selected = new ArrayList<>();
        for (Map.Entry<String, List<VideoNodeInfo>> groupEntry : groupedUsbNodes.entrySet()) {
            List<VideoNodeInfo> groupNodes = groupEntry.getValue();
            groupNodes.sort(Comparator
                    .comparingInt((VideoNodeInfo info) -> info.index == Integer.MAX_VALUE ? Integer.MAX_VALUE : info.index)
                    .thenComparingInt(info -> info.videoNumber));
            VideoNodeInfo chosen = groupNodes.get(0);
            selected.add(chosen.devPath);
            Log.i(TAG, "selectUsbCameraNodes choose: group=" + groupEntry.getKey()
                    + ", chosen=" + chosen.devPath
                    + ", name=" + chosen.displayName
                    + ", index=" + chosen.index
                    + ", all=" + groupNodes);
        }

        return selected;
    }

    private List<String> fallbackSelectVideoNodes() {
        List<String> selected = new ArrayList<>();
        File devDir = new File("/dev");
        File[] entries = devDir.listFiles();
        if (entries == null) {
            Log.w(TAG, "fallbackSelectVideoNodes: /dev listFiles returned null");
            return selected;
        }

        Arrays.sort(entries, Comparator.comparing(File::getName));
        for (File entry : entries) {
            String name = entry.getName();
            if (!name.startsWith("video")) {
                continue;
            }
            if (entry.exists()) {
                selected.add(entry.getAbsolutePath());
            }
        }
        Log.w(TAG, "fallbackSelectVideoNodes: selected all video nodes=" + selected);
        return selected;
    }

    private VideoNodeInfo readVideoNodeInfo(File sysfsVideoDir) {
        String videoName = sysfsVideoDir.getName();
        int videoNumber = parseVideoNumber(videoName);
        if (videoNumber < 0) {
            return null;
        }

        File devNode = new File("/dev/" + videoName);
        if (!devNode.exists()) {
            Log.d(TAG, "readVideoNodeInfo skip missing dev node: " + devNode.getAbsolutePath());
            return null;
        }

        String displayName = readFirstLine(new File(sysfsVideoDir, "name"));
        String indexText = readFirstLine(new File(sysfsVideoDir, "index"));
        int index = parseIntegerOrDefault(indexText, Integer.MAX_VALUE);
        String deviceRealPath = resolveRealPath(new File(sysfsVideoDir, "device"));
        String groupKey = buildGroupKey(deviceRealPath, videoName);
        boolean isUsb = deviceRealPath.toLowerCase(Locale.US).contains("usb");

        return new VideoNodeInfo(
                devNode.getAbsolutePath(),
                videoName,
                videoNumber,
                displayName,
                index,
                deviceRealPath,
                groupKey,
                isUsb
        );
    }

    private boolean looksLikeUsbCamera(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) {
            return true;
        }
        String normalized = displayName.toLowerCase(Locale.US);
        for (String keyword : PREFERRED_USB_NAME_KEYWORDS) {
            if (normalized.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String buildGroupKey(String deviceRealPath, String fallbackName) {
        if (deviceRealPath == null || deviceRealPath.isEmpty()) {
            return fallbackName;
        }

        int videoSegment = deviceRealPath.lastIndexOf("/video4linux/");
        if (videoSegment >= 0) {
            return deviceRealPath.substring(0, videoSegment);
        }

        return deviceRealPath;
    }

    private String resolveRealPath(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            Log.w(TAG, "resolveRealPath failed: " + file.getAbsolutePath(), e);
            return file.getAbsolutePath();
        }
    }

    private String readFirstLine(File file) {
        if (!file.exists()) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (IOException e) {
            Log.w(TAG, "readFirstLine failed: " + file.getAbsolutePath(), e);
            return "";
        }
    }

    private int parseVideoNumber(String videoName) {
        if (videoName == null || !videoName.startsWith("video")) {
            return -1;
        }
        return parseIntegerOrDefault(videoName.substring("video".length()), -1);
    }

    private int parseIntegerOrDefault(String value, int defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    public void openSelectedCameras(String[] cameraIds) {
        Log.d(TAG, "openSelectedCameras: " + Arrays.toString(cameraIds));
        closeAllCameras();

        if (cameraIds.length > 0) {
            leftSlot.devicePath = cameraIds[0];
            leftSlot.disconnectReported = false;
            maybeStartPreview(leftSlot);
        }

        if (cameraIds.length > 1) {
            rightSlot.devicePath = cameraIds[1];
            rightSlot.disconnectReported = false;
            maybeStartPreview(rightSlot);
        }
    }

    @Override
    public void closeAllCameras() {
        Log.d(TAG, "closeAllCameras");
        closeSlot(leftSlot);
        closeSlot(rightSlot);
    }

    @Override
    public int getOpenCameraCount() {
        int count = 0;
        if (leftSlot.nativeHandle != 0L) {
            count++;
        }
        if (rightSlot.nativeHandle != 0L) {
            count++;
        }
        Log.d(TAG, "getOpenCameraCount: " + count);
        return count;
    }

    private TextureView.SurfaceTextureListener buildSurfaceListener(PreviewSlot slot) {
        return new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull android.graphics.SurfaceTexture surface, int width, int height) {
                Log.d(TAG, "onSurfaceTextureAvailable: slot=" + slot.label + ", size=" + width + "x" + height);
                maybeStartPreview(slot);
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull android.graphics.SurfaceTexture surface, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull android.graphics.SurfaceTexture surface) {
                Log.d(TAG, "onSurfaceTextureDestroyed: slot=" + slot.label + ", devicePath=" + slot.devicePath);
                closeSlot(slot);
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull android.graphics.SurfaceTexture surface) {
            }
        };
    }

    private void maybeStartPreview(PreviewSlot slot) {
        if (!V4l2CameraNative.isAvailable()) {
            if (!nativeUnavailableLogged) {
                nativeUnavailableLogged = true;
                Log.w(TAG, "maybeStartPreview: native unavailable, skip preview startup. summary="
                        + V4l2CameraNative.getAvailabilitySummary());
            } else {
                Log.d(TAG, "maybeStartPreview skipped: native unavailable, slot=" + slot.label);
            }
            return;
        }
        if (cameraHandler == null || slot.previewView == null || !slot.previewView.isAvailable()) {
            Log.d(TAG, "maybeStartPreview skipped: slot=" + slot.label
                    + ", handlerNull=" + (cameraHandler == null)
                    + ", previewNull=" + (slot.previewView == null)
                    + ", previewAvailable=" + (slot.previewView != null && slot.previewView.isAvailable()));
            return;
        }
        if (slot.devicePath == null || slot.starting || slot.nativeHandle != 0L) {
            Log.d(TAG, "maybeStartPreview skipped: slot=" + slot.label
                    + ", devicePath=" + slot.devicePath
                    + ", starting=" + slot.starting
                    + ", nativeHandle=" + slot.nativeHandle);
            return;
        }

        slot.starting = true;
        final int requestGeneration = slot.generation;
        final String requestedDevicePath = slot.devicePath;
        Log.d(TAG, "maybeStartPreview: slot=" + slot.label
                + ", generation=" + requestGeneration
                + ", devicePath=" + requestedDevicePath);
        cameraHandler.post(() -> startPreview(slot, requestGeneration, requestedDevicePath));
    }

    private void startPreview(PreviewSlot slot, int requestGeneration, String requestedDevicePath) {
        Surface surface = null;
        long handle = 0L;
        try {
            android.graphics.SurfaceTexture texture = slot.previewView.getSurfaceTexture();
            if (texture == null || !isSlotRequestCurrent(slot, requestGeneration, requestedDevicePath)) {
                Log.d(TAG, "startPreview aborted before open: slot=" + slot.label
                        + ", textureNull=" + (texture == null)
                        + ", requestCurrent=" + isSlotRequestCurrent(slot, requestGeneration, requestedDevicePath));
                return;
            }

            Log.d(TAG, "startPreview: slot=" + slot.label
                    + ", generation=" + requestGeneration
                    + ", devicePath=" + requestedDevicePath
                    + ", viewSize=" + slot.previewView.getWidth() + "x" + slot.previewView.getHeight());
            texture.setDefaultBufferSize(slot.previewView.getWidth(), slot.previewView.getHeight());
            int requestWidth = clampPreviewSize(slot.previewView.getWidth(), MAX_PREVIEW_WIDTH);
            int requestHeight = clampPreviewSize(slot.previewView.getHeight(), MAX_PREVIEW_HEIGHT);
            surface = new Surface(texture);
            handle = V4l2CameraNative.open(
                    requestedDevicePath,
                    requestWidth,
                    requestHeight,
                    PIXEL_FORMAT_MJPEG
            );
            if (handle == 0L) {
                Log.e(TAG, "startPreview: native open failed, slot=" + slot.label
                        + ", devicePath=" + requestedDevicePath
                        + ", error=" + V4l2CameraNative.getLastNativeError());
                notifyCameraError();
                return;
            }

            if (!isSlotRequestCurrent(slot, requestGeneration, requestedDevicePath)) {
                Log.d(TAG, "startPreview aborted after open: stale request, slot=" + slot.label
                        + ", handle=" + handle + ", devicePath=" + requestedDevicePath);
                V4l2CameraNative.close(handle);
                return;
            }

            int result = V4l2CameraNative.startPreview(handle, surface);
            if (result != 0) {
                Log.e(TAG, "startPreview: native start failed, slot=" + slot.label
                        + ", handle=" + handle + ", result=" + result);
                V4l2CameraNative.close(handle);
                notifyCameraError();
                return;
            }

            if (!isSlotRequestCurrent(slot, requestGeneration, requestedDevicePath)) {
                Log.d(TAG, "startPreview aborted after start: stale request, slot=" + slot.label
                        + ", handle=" + handle + ", devicePath=" + requestedDevicePath);
                V4l2CameraNative.stopPreview(handle);
                V4l2CameraNative.close(handle);
                return;
            }

            slot.previewSurface = surface;
            slot.nativeHandle = handle;
            surface = null;
            Log.d(TAG, "startPreview success: slot=" + slot.label
                    + ", handle=" + handle + ", devicePath=" + requestedDevicePath);
        } catch (RuntimeException e) {
            Log.e(TAG, "startPreview failed for " + requestedDevicePath, e);
            if (handle != 0L) {
                V4l2CameraNative.close(handle);
            }
            notifyCameraError();
        } finally {
            if (isSlotRequestCurrent(slot, requestGeneration, requestedDevicePath)) {
                slot.starting = false;
            }
            if (surface != null) {
                surface.release();
            }
            Log.d(TAG, "startPreview finished: slot=" + slot.label
                    + ", generation=" + requestGeneration
                    + ", devicePath=" + requestedDevicePath
                    + ", activeHandle=" + slot.nativeHandle);
        }
    }

    private void closeSlot(PreviewSlot slot) {
        Log.d(TAG, "closeSlot: slot=" + slot.label
                + ", devicePath=" + slot.devicePath
                + ", handle=" + slot.nativeHandle
                + ", starting=" + slot.starting);
        slot.generation++;
        slot.starting = false;

        if (slot.nativeHandle != 0L) {
            V4l2CameraNative.stopPreview(slot.nativeHandle);
            V4l2CameraNative.close(slot.nativeHandle);
            slot.nativeHandle = 0L;
        }

        if (slot.previewSurface != null) {
            slot.previewSurface.release();
            slot.previewSurface = null;
        }

        slot.devicePath = null;
        slot.disconnectReported = false;
        Log.d(TAG, "closeSlot finished: slot=" + slot.label + ", generation=" + slot.generation);
    }

    private boolean isSlotRequestCurrent(PreviewSlot slot, int requestGeneration, String requestedDevicePath) {
        return slot.generation == requestGeneration
                && requestedDevicePath != null
                && requestedDevicePath.equals(slot.devicePath);
    }

    private int clampPreviewSize(int viewSize, int maxSize) {
        if (viewSize <= 0) {
            return maxSize;
        }
        return Math.min(viewSize, maxSize);
    }

    private void pollTopology() {
        if (!availabilityMonitorRegistered) {
            Log.d(TAG, "pollTopology skipped: monitor not registered");
            return;
        }

        String[] topology = getSelectedCameraIds();
        Log.d(TAG, "pollTopology: last=" + Arrays.toString(lastTopology)
                + ", current=" + Arrays.toString(topology));
        notifyDisconnectedIfNeeded(leftSlot, topology);
        notifyDisconnectedIfNeeded(rightSlot, topology);
        if (!Arrays.equals(lastTopology, topology)) {
            lastTopology = topology;
            Log.i(TAG, "pollTopology: topology changed -> " + Arrays.toString(topology));
            mainHandler.post(this::notifyTopologyChanged);
        }
    }

    private void notifyDisconnectedIfNeeded(PreviewSlot slot, String[] topology) {
        if (slot.devicePath == null || slot.disconnectReported) {
            return;
        }
        for (String devicePath : topology) {
            if (slot.devicePath.equals(devicePath)) {
                return;
            }
        }

        slot.disconnectReported = true;
        String disconnectedPath = slot.devicePath;
        Log.w(TAG, "notifyDisconnectedIfNeeded: slot=" + slot.label + ", devicePath=" + disconnectedPath);
        mainHandler.post(() -> notifyCameraDisconnected(disconnectedPath));
    }

    private void notifyTopologyChanged() {
        if (statusCallback != null) {
            Log.i(TAG, "notifyTopologyChanged");
            statusCallback.onCameraTopologyChanged();
        }
    }

    private void notifyCameraError() {
        if (statusCallback != null) {
            Log.e(TAG, "notifyCameraError");
            mainHandler.post(statusCallback::onCameraError);
        }
    }

    private void notifyCameraDisconnected(String devicePath) {
        if (statusCallback != null) {
            Log.w(TAG, "notifyCameraDisconnected: " + devicePath);
            statusCallback.onCameraDisconnected(devicePath);
        }
    }

    private static final class PreviewSlot {
        private final String label;
        private TextureView previewView;
        private String devicePath;
        private Surface previewSurface;
        private long nativeHandle;
        private boolean starting;
        private boolean disconnectReported;
        private int generation;

        private PreviewSlot(String label) {
            this.label = label;
        }
    }

    private static final class VideoNodeInfo {
        private final String devPath;
        private final String videoName;
        private final int videoNumber;
        private final String displayName;
        private final int index;
        private final String deviceRealPath;
        private final String groupKey;
        private final boolean isUsb;

        private VideoNodeInfo(
                String devPath,
                String videoName,
                int videoNumber,
                String displayName,
                int index,
                String deviceRealPath,
                String groupKey,
                boolean isUsb
        ) {
            this.devPath = devPath;
            this.videoName = videoName;
            this.videoNumber = videoNumber;
            this.displayName = displayName;
            this.index = index;
            this.deviceRealPath = deviceRealPath;
            this.groupKey = groupKey;
            this.isUsb = isUsb;
        }

        @Override
        public String toString() {
            return "VideoNodeInfo{"
                    + "devPath='" + devPath + '\''
                    + ", name='" + displayName + '\''
                    + ", index=" + index
                    + ", usb=" + isUsb
                    + ", groupKey='" + groupKey + '\''
                    + ", realPath='" + deviceRealPath + '\''
                    + '}';
        }
    }
}

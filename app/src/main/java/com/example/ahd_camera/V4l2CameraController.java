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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class V4l2CameraController implements CameraPreviewController {

    private static final String TAG = "V4l2CameraController";
    private static final int PIXEL_FORMAT_NV12 = 1;
    private static final int MAX_PREVIEW_WIDTH = 1920;
    private static final int MAX_PREVIEW_HEIGHT = 1080;
    // 驱动侧会对状态变化做一次快速复核；应用每 250 ms 取样两次即可在约
    // 0.5 秒内完成稳定的单/双摄切换，而无需等待原先约 3 秒。
    private static final long TOPOLOGY_POLL_MS = 250L;
    private static final int TOPOLOGY_STABLE_POLLS_REQUIRED = 2;
    private static final long UNAVAILABLE_NODE_RETRY_MS = 5_000L;
    private static final String SYSFS_VIDEO4LINUX_DIR = "/sys/class/video4linux";
    private static final String[] EXCLUDED_NODE_NAME_KEYWORDS = {
            "luma",
            "meta",
            "embedded",
            "stat"
    };
    private static final String PRIMARY_PLATFORM_KEYWORD = "rkcif";
    private static final String SECONDARY_PLATFORM_KEYWORD = "rkisp";
    private static final String MAIN_PATH_KEYWORD = "mainpath";
    // 媒体拓扑已确认：m01(3-0059) 经 rkcif 输出，m00(3-0058) 经 rkisp mainpath 输出。
    // /dev/video 编号由现有扫描逻辑决定，不在这里写死。
    private static final String PRIMARY_AHD_SUBDEV = "/dev/v4l-subdev2"; // m01_b_ark7128
    private static final String SECONDARY_AHD_SUBDEV = "/dev/v4l-subdev6"; // m00_b_ark7128

    private final CameraStatusCallback statusCallback;
    private final Handler mainHandler = new Handler(android.os.Looper.getMainLooper());
    // 页面启动扫描与后台轮询都可能读取 ARK7128 的分页 I2C 状态寄存器；必须
    // 串行，避免同一时刻发出两组 subdev ioctl 导致驱动页选择相互干扰。
    private final Object topologyQueryLock = new Object();
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
    private String[] pendingTopology = new String[0];
    private int pendingTopologyPollCount;
    private boolean nativeUnavailableLogged;
    // 某些板载摄像头拔出后 /dev/videoX 仍存在。失败节点短暂排除，先恢复
    // 单摄全屏；冷却后自动重试，从而兼顾无信号检测和重新插入恢复。
    private final Map<String, Long> unavailableNodesUntil = new ConcurrentHashMap<>();
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
        pendingTopology = new String[0];
        pendingTopologyPollCount = 0;
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
        pendingTopology = new String[0];
        pendingTopologyPollCount = 0;
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
        synchronized (topologyQueryLock) {
            List<String> selected = selectCameraNodes();
            int count = Math.min(selected.size(), 2);
            String[] result = selected.subList(0, count).toArray(new String[0]);
            Log.d(TAG, "getSelectedCameraIds: selected=" + Arrays.toString(result)
                    + ", candidates=" + selected);
            return result;
        }
    }

    private List<String> selectCameraNodes() {
        File sysfsDir = new File(SYSFS_VIDEO4LINUX_DIR);
        File[] videoEntries = sysfsDir.listFiles(file -> file.isDirectory() && file.getName().startsWith("video"));
        if (videoEntries == null || videoEntries.length == 0) {
            Log.w(TAG, "selectCameraNodes: no sysfs video entries under " + SYSFS_VIDEO4LINUX_DIR);
            return Collections.emptyList();
        }

        Arrays.sort(videoEntries, Comparator.comparing(File::getName));

        List<VideoNodeInfo> platformCandidates = new ArrayList<>();
        for (File entry : videoEntries) {
            VideoNodeInfo info = readVideoNodeInfo(entry);
            if (info == null) {
                continue;
            }
            Log.d(TAG, "selectCameraNodes inspect: " + info);
            if (isTemporarilyUnavailable(info.devPath)) {
                Log.d(TAG, "selectCameraNodes skip temporarily unavailable node: " + info.devPath);
                continue;
            }
            if (!looksLikeCaptureNode(info.displayName)) {
                Log.d(TAG, "selectCameraNodes skip non-preview node: " + info.devPath
                        + ", name=" + info.displayName);
                continue;
            }
            // 本机只使用板载 AHD 链路。USB 摄像头、采集卡等 video 节点不参与
            // 拓扑判断，因此 USB 设备插拔不会导致正在显示的 AHD 画面重建。
            if (info.isUsb) {
                Log.d(TAG, "selectCameraNodes ignore usb video node: " + info.devPath
                        + ", name=" + info.displayName);
                continue;
            }
            platformCandidates.add(info);
        }

        List<String> selected = new ArrayList<>();
        if (!platformCandidates.isEmpty()) {
            // 不依赖 /dev/videoX 的编号：主画面固定跟随 rkcif 链路，第二画面优先
            // 使用 rkisp 的 mainpath。内核重新枚举导致节点号变化时仍能选中同一角色。
            VideoNodeInfo primaryNode = choosePlatformNode(
                    platformCandidates, PRIMARY_PLATFORM_KEYWORD, false);
            VideoNodeInfo secondaryNode = choosePlatformNode(
                    platformCandidates, SECONDARY_PLATFORM_KEYWORD, true);

            appendIfAhdInputPresent(selected, primaryNode, PRIMARY_AHD_SUBDEV, "primary-rkcif");
            appendIfAhdInputPresent(selected, secondaryNode, SECONDARY_AHD_SUBDEV,
                    "secondary-rkisp-mainpath");

            // 对名称不标准的板级驱动保留单路兜底，但不能把同一 pipeline 下的
            // video1/video2 等节点误认为多台摄像头。
            // 仅在无法识别板级角色时才兜底。两个角色均识别但均无信号时，必须
            // 返回空列表，不能重新把无信号的 video 节点当成单摄打开。
            if (selected.isEmpty() && primaryNode == null && secondaryNode == null) {
                platformCandidates.sort(videoNodeOrder(false));
                appendDistinctNode(selected, platformCandidates.get(0), "platform-fallback");
            }
        }

        if (!selected.isEmpty()) {
            Log.i(TAG, "selectCameraNodes choose=" + selected
                    + ", platformCandidates=" + platformCandidates);
            return selected;
        }

        Log.w(TAG, "selectCameraNodes: no matching AHD platform capture nodes");
        return Collections.emptyList();
    }

    private VideoNodeInfo choosePlatformNode(
            List<VideoNodeInfo> candidates,
            String platformKeyword,
            boolean preferMainPath
    ) {
        List<VideoNodeInfo> matches = new ArrayList<>();
        for (VideoNodeInfo info : candidates) {
            String identity = (info.displayName + " " + info.deviceRealPath + " " + info.groupKey)
                    .toLowerCase(Locale.US);
            if (identity.contains(platformKeyword)) {
                matches.add(info);
            }
        }
        if (matches.isEmpty()) {
            return null;
        }
        matches.sort(videoNodeOrder(preferMainPath));
        VideoNodeInfo chosen = matches.get(0);
        Log.i(TAG, "choosePlatformNode: keyword=" + platformKeyword
                + ", preferMainPath=" + preferMainPath
                + ", chosen=" + chosen
                + ", matches=" + matches);
        return chosen;
    }

    private Comparator<VideoNodeInfo> videoNodeOrder(boolean preferMainPath) {
        return Comparator
                .comparingInt((VideoNodeInfo info) -> preferMainPath && isMainPathNode(info) ? 0 : 1)
                .thenComparingInt(info -> info.index == Integer.MAX_VALUE
                        ? Integer.MAX_VALUE : info.index)
                .thenComparingInt(info -> info.videoNumber);
    }

    private boolean isMainPathNode(VideoNodeInfo info) {
        return info.displayName != null
                && info.displayName.toLowerCase(Locale.US).contains(MAIN_PATH_KEYWORD);
    }

    private void appendDistinctNode(List<String> selected, VideoNodeInfo node, String role) {
        if (node == null || selected.contains(node.devPath)) {
            return;
        }
        selected.add(node.devPath);
        Log.i(TAG, "appendDistinctNode: role=" + role + ", node=" + node);
    }

    private void appendIfAhdInputPresent(
            List<String> selected, VideoNodeInfo node, String subDevicePath, String role
    ) {
        if (node == null) {
            return;
        }
        int presence = V4l2CameraNative.getAhdInputPresence(subDevicePath);
        if (presence == 0) {
            Log.i(TAG, "appendIfAhdInputPresent: no AHD signal, role=" + role
                    + ", subdev=" + subDevicePath + ", node=" + node.devPath);
            return;
        }
        if (presence < 0) {
            // 查询失败时不要把仍在显示的画面误判为拔出；下一轮轮询会重试。
            Log.w(TAG, "appendIfAhdInputPresent: status unavailable, keep node, role="
                    + role + ", subdev=" + subDevicePath);
        } else {
            Log.d(TAG, "appendIfAhdInputPresent: AHD signal present, role=" + role
                    + ", subdev=" + subDevicePath);
        }
        appendDistinctNode(selected, node, role);
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

    private boolean looksLikeCaptureNode(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) {
            return true;
        }
        String normalized = displayName.toLowerCase(Locale.US);
        for (String excluded : EXCLUDED_NODE_NAME_KEYWORDS) {
            if (normalized.contains(excluded)) {
                return false;
            }
        }
        return true;
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
        if (V4l2CameraNative.isPreviewActive(leftSlot.nativeHandle)) {
            count++;
        }
        if (V4l2CameraNative.isPreviewActive(rightSlot.nativeHandle)) {
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
            // 分屏只改变显示区域，不改变每路摄像头的取流规格，避免主路从
            // 1280x720 被控件半宽误改为 960x720。
            int requestWidth = MAX_PREVIEW_WIDTH;
            int requestHeight = MAX_PREVIEW_HEIGHT;
            surface = new Surface(texture);
            handle = V4l2CameraNative.open(
                    requestedDevicePath,
                    requestWidth,
                    requestHeight,
                    PIXEL_FORMAT_NV12
            );
            if (handle == 0L) {
                Log.e(TAG, "startPreview: native open failed, slot=" + slot.label
                        + ", devicePath=" + requestedDevicePath
                        + ", error=" + V4l2CameraNative.getLastNativeError());
                markNodeUnavailable(requestedDevicePath);
                notifyCameraDisconnected(requestedDevicePath);
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
                markNodeUnavailable(requestedDevicePath);
                notifyCameraDisconnected(requestedDevicePath);
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
            if (slot == leftSlot) V4l2CameraNative.setRemoteFrameSource(handle);
            surface = null;
            Log.d(TAG, "startPreview success: slot=" + slot.label
                    + ", handle=" + handle + ", devicePath=" + requestedDevicePath);
        } catch (RuntimeException e) {
            Log.e(TAG, "startPreview failed for " + requestedDevicePath, e);
            if (handle != 0L) {
                V4l2CameraNative.close(handle);
            }
            markNodeUnavailable(requestedDevicePath);
            notifyCameraDisconnected(requestedDevicePath);
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
            if (slot == leftSlot) V4l2CameraNative.setRemoteFrameSource(0L);
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

    private void pollTopology() {
        if (!availabilityMonitorRegistered) {
            Log.d(TAG, "pollTopology skipped: monitor not registered");
            return;
        }

        String[] topology = getSelectedCameraIds();
        Log.d(TAG, "pollTopology: last=" + Arrays.toString(lastTopology)
                + ", current=" + Arrays.toString(topology));
        notifyStoppedPreviewIfNeeded(leftSlot);
        notifyStoppedPreviewIfNeeded(rightSlot);

        if (Arrays.equals(lastTopology, topology)) {
            pendingTopology = new String[0];
            pendingTopologyPollCount = 0;
            return;
        }

        if (Arrays.equals(pendingTopology, topology)) {
            pendingTopologyPollCount++;
        } else {
            pendingTopology = Arrays.copyOf(topology, topology.length);
            pendingTopologyPollCount = 1;
        }

        Log.i(TAG, "pollTopology: pending=" + Arrays.toString(pendingTopology)
                + ", stablePolls=" + pendingTopologyPollCount + "/"
                + TOPOLOGY_STABLE_POLLS_REQUIRED);
        if (pendingTopologyPollCount < TOPOLOGY_STABLE_POLLS_REQUIRED) {
            return;
        }

        lastTopology = Arrays.copyOf(pendingTopology, pendingTopology.length);
        pendingTopology = new String[0];
        pendingTopologyPollCount = 0;
        notifyDisconnectedIfNeeded(leftSlot, lastTopology);
        notifyDisconnectedIfNeeded(rightSlot, lastTopology);
        Log.i(TAG, "pollTopology: accepted topology -> " + Arrays.toString(lastTopology));
        mainHandler.post(this::notifyTopologyChanged);
    }

    private void notifyStoppedPreviewIfNeeded(PreviewSlot slot) {
        if (slot.nativeHandle == 0L || slot.starting || slot.disconnectReported
                || V4l2CameraNative.isPreviewActive(slot.nativeHandle)) {
            return;
        }
        // MIPI CRC/帧边界错误也会结束预览线程，但这不代表 AHD 线已拔出。
        // 物理拔出只由上面的稳定 hotplug 拓扑确认；这里仅做同一拓扑的延迟恢复。
        slot.disconnectReported = true;
        Log.w(TAG, "notifyStoppedPreviewIfNeeded: preview stopped without confirmed unplug, slot="
                + slot.label + ", devicePath=" + slot.devicePath);
        mainHandler.postDelayed(this::notifyTopologyChanged, 1500L);
    }

    private void markNodeUnavailable(String devicePath) {
        if (devicePath == null || devicePath.isEmpty()) {
            return;
        }
        long retryAt = android.os.SystemClock.elapsedRealtime() + UNAVAILABLE_NODE_RETRY_MS;
        unavailableNodesUntil.put(devicePath, retryAt);
        Log.w(TAG, "markNodeUnavailable: node=" + devicePath + ", retryAt=" + retryAt);
    }

    private boolean isTemporarilyUnavailable(String devicePath) {
        Long retryAt = unavailableNodesUntil.get(devicePath);
        if (retryAt == null) {
            return false;
        }
        if (android.os.SystemClock.elapsedRealtime() >= retryAt) {
            unavailableNodesUntil.remove(devicePath, retryAt);
            return false;
        }
        return true;
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

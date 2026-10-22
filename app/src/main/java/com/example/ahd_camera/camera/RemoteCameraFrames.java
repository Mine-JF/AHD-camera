package com.example.ahd_camera.camera;

import com.example.ahd_camera.V4l2CameraNative;

/** Latest-frame mailbox: slow encoding drops old camera frames instead of increasing latency. */
public final class RemoteCameraFrames {
    private static byte[] latest;
    private static long sequence;
    private static int activeStreams;

    private RemoteCameraFrames() { }

    public static synchronized void setActive(boolean enabled) {
        activeStreams = Math.max(0, activeStreams + (enabled ? 1 : -1));
        if (activeStreams == 0) latest = null;
        V4l2CameraNative.setRemoteFramesEnabled(activeStreams > 0);
        notifyAllFrames();
    }

    public static synchronized void offer(byte[] frame) {
        if (activeStreams == 0) return;
        latest = frame;
        sequence++;
        notifyAllFrames();
    }

    public static synchronized Frame waitForNext(long afterSequence, long timeoutMs)
            throws InterruptedException {
        if (activeStreams > 0 && sequence == afterSequence) RemoteCameraFrames.class.wait(timeoutMs);
        return activeStreams > 0 && latest != null && sequence != afterSequence
                ? new Frame(latest, sequence) : null;
    }

    private static void notifyAllFrames() {
        RemoteCameraFrames.class.notifyAll();
    }

    public static final class Frame {
        public final byte[] nv12;
        public final long sequence;

        Frame(byte[] nv12, long sequence) {
            this.nv12 = nv12;
            this.sequence = sequence;
        }
    }
}

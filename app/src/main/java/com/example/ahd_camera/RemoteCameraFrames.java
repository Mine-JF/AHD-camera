package com.example.ahd_camera;

/** Latest-frame mailbox: slow encoding drops old camera frames instead of increasing latency. */
final class RemoteCameraFrames {
    private static byte[] latest;
    private static long sequence;
    private static int activeStreams;

    private RemoteCameraFrames() { }

    static synchronized void setActive(boolean enabled) {
        activeStreams = Math.max(0, activeStreams + (enabled ? 1 : -1));
        if (activeStreams == 0) latest = null;
        V4l2CameraNative.setRemoteFramesEnabled(activeStreams > 0);
        notifyAllFrames();
    }

    static synchronized void offer(byte[] frame) {
        if (activeStreams == 0) return;
        latest = frame;
        sequence++;
        notifyAllFrames();
    }

    static synchronized Frame waitForNext(long afterSequence, long timeoutMs)
            throws InterruptedException {
        if (activeStreams > 0 && sequence == afterSequence) RemoteCameraFrames.class.wait(timeoutMs);
        return activeStreams > 0 && latest != null && sequence != afterSequence
                ? new Frame(latest, sequence) : null;
    }

    private static void notifyAllFrames() {
        RemoteCameraFrames.class.notifyAll();
    }

    static final class Frame {
        final byte[] nv12;
        final long sequence;

        Frame(byte[] nv12, long sequence) {
            this.nv12 = nv12;
            this.sequence = sequence;
        }
    }
}

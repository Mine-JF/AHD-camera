package com.example.phone_ahd;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Finds boards by UDP broadcast and mDNS; manual IP remains the fallback. */
final class PhoneBoardDiscovery {
    interface Callback { void onFinished(List<Board> boards); }

    static final class Board {
        final String name;
        final String host;
        Board(String name, String host) { this.name = name; this.host = host; }
        @Override public String toString() { return name + "  (" + host + ")"; }
    }

    private static final String TAG = "PhoneBoardDiscovery";
    private static final int PORT = 39003;
    private static final String SERVICE_TYPE = "_ahdremote._tcp.";
    private static final String REQUEST = "AHD_DISCOVER_V1";
    private static final String RESPONSE_PREFIX = "AHD_BOARD_V1|";
    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile int generation;
    private volatile DatagramSocket socket;

    PhoneBoardDiscovery(Context context) { this.context = context.getApplicationContext(); }

    void cancel() {
        generation++;
        Log.i(TAG, "cancel discovery, generation=" + generation);
        DatagramSocket current = socket;
        if (current != null) current.close();
    }

    void discover(Callback callback) {
        cancel();
        int requestGeneration = generation;
        Log.i(TAG, "start discovery, generation=" + requestGeneration);
        new Thread(() -> runDiscovery(requestGeneration, callback), "phone-board-discovery").start();
    }

    private void runDiscovery(int requestGeneration, Callback callback) {
        Map<String, Board> found = new ConcurrentHashMap<>();
        NsdManager nsd = context.getSystemService(NsdManager.class);
        AtomicBoolean finished = new AtomicBoolean();
        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            private volatile boolean started;
            @Override public void onDiscoveryStarted(String type) {
                Log.i(TAG, "mDNS discovery started: " + type);
                started = true;
                if (finished.get()) stop();
            }
            @Override public void onServiceFound(NsdServiceInfo service) {
                Log.i(TAG, "mDNS service found: " + service.getServiceName()
                        + ", type=" + service.getServiceType());
                if (finished.get() || !service.getServiceType().contains("_ahdremote._tcp")) return;
                try {
                    nsd.resolveService(service, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo info, int error) {
                            Log.w(TAG, "mDNS resolve failed: " + error);
                        }
                        @Override public void onServiceResolved(NsdServiceInfo info) {
                            InetAddress address = info.getHost();
                            if (!finished.get() && address instanceof Inet4Address
                                    && info.getPort() == 39001) {
                                String host = address.getHostAddress();
                                found.put(host, new Board(info.getServiceName(), host));
                                Log.i(TAG, "mDNS found " + info.getServiceName() + " at " + host);
                            }
                        }
                    });
                } catch (RuntimeException error) {
                    Log.w(TAG, "mDNS resolve unavailable", error);
                }
            }
            @Override public void onServiceLost(NsdServiceInfo service) { }
            @Override public void onDiscoveryStopped(String type) { }
            @Override public void onStartDiscoveryFailed(String type, int error) {
                Log.w(TAG, "mDNS discovery failed: " + error);
            }
            @Override public void onStopDiscoveryFailed(String type, int error) {
                Log.w(TAG, "mDNS stop failed: " + error);
            }
            void stop() {
                if (started) {
                    try { nsd.stopServiceDiscovery(this); }
                    catch (RuntimeException error) { Log.w(TAG, "mDNS stop unavailable", error); }
                    started = false;
                }
            }
        };
        // Keep a reference through the DiscoveryListener interface; stop after UDP has timed out.
        WifiManager wifi = context.getSystemService(WifiManager.class);
        WifiManager.MulticastLock multicast = null;
        if (wifi != null) {
            try {
                multicast = wifi.createMulticastLock("ahd-board-discovery");
                multicast.setReferenceCounted(false);
                multicast.acquire();
            } catch (RuntimeException error) {
                Log.w(TAG, "multicast lock unavailable", error);
            }
        }
        try {
            if (nsd != null) nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (RuntimeException error) {
            Log.w(TAG, "mDNS discovery unavailable", error);
        }

        DatagramSocket udp = null;
        try {
            if (generation != requestGeneration) return;
            udp = new DatagramSocket();
            socket = udp;
            Log.i(TAG, "UDP socket ready: localPort=" + udp.getLocalPort());
            udp.setBroadcast(true);
            udp.setSoTimeout(350);
            byte[] request = REQUEST.getBytes(StandardCharsets.UTF_8);
            sendRequest(udp, request, InetAddress.getByName("255.255.255.255"));
            if (wifi != null) {
                try {
                    DhcpInfo dhcp = wifi.getDhcpInfo();
                    if (dhcp != null && dhcp.netmask != 0) {
                        int broadcast = (dhcp.ipAddress & dhcp.netmask) | ~dhcp.netmask;
                        byte[] address = new byte[]{(byte) broadcast, (byte) (broadcast >> 8),
                                (byte) (broadcast >> 16), (byte) (broadcast >> 24)};
                        sendRequest(udp, request, InetAddress.getByAddress(address));
                        Log.i(TAG, "Wi-Fi broadcast=" + InetAddress.getByAddress(address)
                                .getHostAddress());
                    } else {
                        Log.w(TAG, "Wi-Fi DHCP broadcast address unavailable");
                    }
                } catch (RuntimeException error) {
                    Log.w(TAG, "Wi-Fi subnet discovery unavailable", error);
                }
            }
            long deadline = android.os.SystemClock.elapsedRealtime() + 4000;
            byte[] bytes = new byte[256];
            while (generation == requestGeneration
                    && android.os.SystemClock.elapsedRealtime() < deadline) {
                DatagramPacket response = new DatagramPacket(bytes, bytes.length);
                try {
                    udp.receive(response);
                    String message = new String(response.getData(), response.getOffset(),
                            response.getLength(), StandardCharsets.UTF_8);
                    if (!message.startsWith(RESPONSE_PREFIX)) continue;
                    String[] parts = message.split("\\|", 3);
                    if (parts.length != 3 || !"39001".equals(parts[2])) continue;
                    String host = response.getAddress().getHostAddress();
                    found.put(host, new Board(parts[1], host));
                    Log.i(TAG, "UDP found " + parts[1] + " at " + host);
                } catch (SocketTimeoutException ignored) { }
            }
        } catch (IOException error) {
            if (generation == requestGeneration) Log.w(TAG, "UDP discovery unavailable", error);
        } finally {
            if (socket == udp) socket = null;
            if (udp != null) udp.close();
            finished.set(true);
            if (nsd != null) {
                try { nsd.stopServiceDiscovery(listener); }
                catch (RuntimeException error) { Log.w(TAG, "mDNS stop unavailable", error); }
            }
            if (multicast != null && multicast.isHeld()) {
                try { multicast.release(); }
                catch (RuntimeException error) { Log.w(TAG, "multicast release failed", error); }
            }
        }
        if (generation != requestGeneration) return;
        List<Board> boards = new ArrayList<>(found.values());
        boards.sort(Comparator.comparing(board -> board.name));
        Log.i(TAG, "discovery finished, boards=" + boards.size());
        mainHandler.post(() -> {
            if (generation == requestGeneration) callback.onFinished(boards);
        });
    }

    private static void sendRequest(DatagramSocket socket, byte[] request, InetAddress address) {
        try {
            socket.send(new DatagramPacket(request, request.length, address, PORT));
            Log.i(TAG, "UDP query sent to " + address.getHostAddress() + ":" + PORT);
        }
        catch (IOException error) { Log.w(TAG, "UDP broadcast failed: " + address, error); }
    }
}

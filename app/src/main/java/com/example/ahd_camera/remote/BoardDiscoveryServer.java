package com.example.ahd_camera.remote;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** Advertises the board on the local network without changing its control protocol. */
final class BoardDiscoveryServer {
    static final int PORT = 39003;
    static final String SERVICE_TYPE = "_ahdremote._tcp.";
    static final String REQUEST = "AHD_DISCOVER_V1";
    static final String RESPONSE_PREFIX = "AHD_BOARD_V1|";
    private static final String TAG = "BoardDiscovery";

    private final Context context;
    private final AtomicBoolean running = new AtomicBoolean();
    private final String name;
    private DatagramSocket socket;
    private NsdManager.RegistrationListener registration;
    private ConnectivityManager.NetworkCallback networkCallback;

    BoardDiscoveryServer(Context context) {
        this.context = context.getApplicationContext();
        String id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        String suffix = id == null ? "board" : id.substring(Math.max(0, id.length() - 4));
        name = ("AHD " + Build.MODEL + " " + suffix).replace('|', '-');
    }

    void start() {
        if (!running.compareAndSet(false, true)) return;
        Log.i(TAG, "starting discovery: name=" + name + ", udpPort=" + PORT
                + ", service=" + SERVICE_TYPE);
        new Thread(this::serveUdp, "board-discovery").start();
        registerMdns();
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        if (connectivity != null) {
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { registerMdns(); }
            };
            try { connectivity.registerDefaultNetworkCallback(networkCallback); }
            catch (RuntimeException error) { Log.w(TAG, "network callback unavailable", error); }
        }
    }

    private synchronized void registerMdns() {
        if (!running.get() || registration != null) return;
        NsdManager manager = context.getSystemService(NsdManager.class);
        if (manager == null) return;
        Log.i(TAG, "registering mDNS service: " + name);
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(name);
        info.setServiceType(SERVICE_TYPE);
        info.setPort(BoardRemoteServer.CONTROL_PORT);
        registration = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                Log.i(TAG, "mDNS registered: " + serviceInfo.getServiceName());
            }
            @Override public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.w(TAG, "mDNS registration failed: " + errorCode);
                synchronized (BoardDiscoveryServer.this) {
                    if (registration == this) registration = null;
                }
            }
            @Override public void onServiceUnregistered(NsdServiceInfo serviceInfo) { }
            @Override public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) { }
        };
        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
        } catch (RuntimeException error) {
            Log.w(TAG, "mDNS registration unavailable", error);
            registration = null;
        }
    }

    private void serveUdp() {
        try (DatagramSocket listener = new DatagramSocket(PORT)) {
            socket = listener;
            Log.i(TAG, "UDP discovery listening on port " + PORT);
            listener.setSoTimeout(1000);
            byte[] buffer = new byte[256];
            while (running.get()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    listener.receive(packet);
                    String request = new String(packet.getData(), packet.getOffset(),
                            packet.getLength(), StandardCharsets.UTF_8);
                    if (!REQUEST.equals(request)) continue;
                    byte[] response = (RESPONSE_PREFIX + name + "|" + BoardRemoteServer.CONTROL_PORT)
                            .getBytes(StandardCharsets.UTF_8);
                    listener.send(new DatagramPacket(response, response.length,
                            packet.getAddress(), packet.getPort()));
                    Log.i(TAG, "UDP discovery reply to " + packet.getAddress().getHostAddress());
                } catch (SocketTimeoutException ignored) {
                    // Poll running so the listener can shut down cleanly.
                }
            }
        } catch (IOException error) {
            if (running.get()) Log.w(TAG, "UDP discovery unavailable", error);
        } finally {
            socket = null;
        }
    }

    void stop() {
        running.set(false);
        if (socket != null) socket.close();
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        if (connectivity != null && networkCallback != null) {
            try { connectivity.unregisterNetworkCallback(networkCallback); }
            catch (RuntimeException error) { Log.w(TAG, "network callback unregister failed", error); }
            networkCallback = null;
        }
        NsdManager manager = context.getSystemService(NsdManager.class);
        if (manager != null && registration != null) {
            try { manager.unregisterService(registration); }
            catch (RuntimeException error) { Log.w(TAG, "mDNS unregister failed", error); }
            registration = null;
        }
    }
}

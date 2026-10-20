package com.example.phone_ahd;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import com.example.ahd_camera.R;

/** Orientation-adaptive phone remote: live board preview and touch controls. */
public final class PhoneRemoteActivity extends Activity {
    private static final String STATE_RECONNECT_HOST = "reconnect_host";
    private static final String TAG = "PhoneRemoteActivity";
    private PhoneRemoteClient client;
    private PhoneBoardDiscovery discovery;
    private EditText boardIp;
    private TextView status;
    private String lastRequestedHost;
    private final Handler reconnectHandler = new Handler(Looper.getMainLooper());
    private boolean started;
    private int searchNumber;
    private final Runnable reconnectRunnable = () -> {
        if (started && lastRequestedHost != null && !client.isRunning()) {
            status.setText("正在重新连接 " + lastRequestedHost);
            client.connect(lastRequestedHost);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setContentView(R.layout.activity_phone_remote);

        boardIp = findViewById(R.id.phone_board_ip);
        status = findViewById(R.id.phone_connection_status);
        status.setMovementMethod(new ScrollingMovementMethod());
        TextureView preview = findViewById(R.id.phone_preview);
        String savedHost = getPreferences(0).getString("board_ip", "").trim();
        boardIp.setText(savedHost);
        if (!savedHost.isEmpty()) lastRequestedHost = savedHost;
        Log.i(TAG, "created: savedHost=" + savedHost + ", restored=" + (state != null));
        discovery = new PhoneBoardDiscovery(this);
        client = new PhoneRemoteClient(preview, text -> {
            status.setText(text);
            if (text.startsWith("已连接") || text.startsWith("控制已连接")) {
                reconnectHandler.removeCallbacks(reconnectRunnable);
            } else if (text.contains("断开") || text.contains("连接失败")
                    || text.contains("连接超时") || text.contains("解码失败")) {
                if (text.startsWith("视频") && client.isRunning()) client.disconnect();
                scheduleReconnect();
            }
        });

        findViewById(R.id.phone_connect).setOnClickListener(v -> {
            String host = boardIp.getText().toString().trim();
            if (host.isEmpty()) {
                status.setText("请填写主板 IP");
                return;
            }
            discovery.cancel();
            connectToHost(host);
        });
        findViewById(R.id.phone_discover).setOnClickListener(v -> startDiscovery());

        bindHoldButton(R.id.phone_up, "UP");
        bindHoldButton(R.id.phone_down, "DOWN");
        bindHoldButton(R.id.phone_left, "LEFT");
        bindHoldButton(R.id.phone_right, "RIGHT");
        bindHoldButton(R.id.phone_focus_plus, "FOCUS_PLUS");
        bindHoldButton(R.id.phone_focus_minus, "FOCUS_MINUS");
        bindOkHomeButton();
        bindBackButton();
        View controls = findViewById(R.id.phone_controls_content);
        TextView toggleControls = findViewById(R.id.phone_toggle_controls);
        if (controls != null && toggleControls != null) {
            toggleControls.setOnClickListener(v -> {
                boolean visible = controls.getVisibility() != View.VISIBLE;
                controls.setVisibility(visible ? View.VISIBLE : View.GONE);
                toggleControls.setText(visible ? "隐藏控件" : "显示控件");
            });
        }
        if (state != null) {
            String reconnectHost = state.getString(STATE_RECONNECT_HOST);
            if (reconnectHost != null && !reconnectHost.isEmpty()) {
                lastRequestedHost = reconnectHost;
                status.setText("屏幕旋转，正在重新连接 " + reconnectHost);
            }
        }
    }

    private void scheduleReconnect() {
        if (!started || lastRequestedHost == null) return;
        reconnectHandler.removeCallbacks(reconnectRunnable);
        reconnectHandler.postDelayed(reconnectRunnable, 2_000);
    }

    private void connectToHost(String host) {
        Log.i(TAG, "connectToHost: " + host);
        boardIp.setText(host);
        getPreferences(0).edit().putString("board_ip", host).apply();
        status.setText("正在连接 " + host);
        lastRequestedHost = host;
        reconnectHandler.removeCallbacks(reconnectRunnable);
        client.connect(host);
    }

    private void startDiscovery() {
        int request = ++searchNumber;
        Log.i(TAG, "search start #" + request + ", connected=" + client.isRunning()
                + ", lastHost=" + lastRequestedHost);
        status.setText("正在搜索同一局域网中的 AHD 主板…");
        discovery.discover(boards -> {
            Log.i(TAG, "search result #" + request + ": count=" + boards.size()
                    + ", started=" + started + ", currentSearch=" + searchNumber);
            if (!started || request != searchNumber) return;
            if (boards.isEmpty()) {
                status.setText("未发现主板；当前连接不受影响，可手动输入 IP");
            } else if (boards.size() == 1) {
                PhoneBoardDiscovery.Board board = boards.get(0);
                if (client.isRunning() && board.host.equals(lastRequestedHost)) {
                    status.setText("已连接 " + board.name + " (" + board.host + ")");
                } else {
                    status.setText("发现 " + board.name + "，正在连接…");
                    connectToHost(board.host);
                }
            } else {
                String[] labels = new String[boards.size()];
                for (int i = 0; i < boards.size(); i++) labels[i] = boards.get(i).toString();
                status.setText("发现 " + boards.size() + " 台主板，请选择");
                new AlertDialog.Builder(this).setTitle("选择 AHD 主板")
                        .setItems(labels, (dialog, which) -> connectToHost(boards.get(which).host))
                        .setNegativeButton("取消", null).show();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        Log.i(TAG, "onStart: lastHost=" + lastRequestedHost + ", running=" + client.isRunning());
        if (client != null && !client.isRunning()) {
            if (lastRequestedHost == null) startDiscovery();
            else connectToHost(lastRequestedHost);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (lastRequestedHost != null) {
            outState.putString(STATE_RECONNECT_HOST, lastRequestedHost);
        }
        super.onSaveInstanceState(outState);
    }

    @SuppressWarnings("ClickableViewAccessibility")
    private void bindHoldButton(int id, String code) {
        findViewById(id).setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                client.send(code + "_DOWN");
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                client.send(code + "_UP");
                return true;
            }
            return true;
        });
    }

    public void onRemoteButtonClick(View view) {
        int id = view.getId();
        if (id == R.id.phone_distance_reset) client.send("RESET_DISTANCE");
        else if (id == R.id.phone_light) client.send("LIGHT");
        else if (id == R.id.phone_menu) client.send("MENU");
        else if (id == R.id.phone_rotate) client.send("ROTATE");
        else if (id == R.id.phone_photo) client.send("PHOTO");
        else if (id == R.id.phone_record) client.send("RECORD");
    }

    @SuppressWarnings("ClickableViewAccessibility")
    private void bindOkHomeButton() {
        View button = findViewById(R.id.phone_ok_home);
        button.setOnClickListener(v -> client.send("OK"));
        button.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) return true;
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                if (event.getEventTime() - event.getDownTime() >= 650) {
                    client.send("HOME");
                } else {
                    v.performClick();
                }
                return true;
            }
            return event.getActionMasked() == MotionEvent.ACTION_CANCEL;
        });
    }

    @SuppressWarnings("ClickableViewAccessibility")
    private void bindBackButton() {
        View button = findViewById(R.id.phone_back);
        button.setOnClickListener(v -> client.send("BACK"));
        button.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) return true;
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                if (event.getEventTime() - event.getDownTime() >= 2000) {
                    client.send("BACK_LONG");
                } else {
                    v.performClick();
                }
                return true;
            }
            return event.getActionMasked() == MotionEvent.ACTION_CANCEL;
        });
    }

    @Override
    protected void onStop() {
        started = false;
        searchNumber++;
        Log.i(TAG, "onStop: cancel search and disconnect");
        discovery.cancel();
        reconnectHandler.removeCallbacks(reconnectRunnable);
        client.disconnect();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        client.shutdown();
        super.onDestroy();
    }
}

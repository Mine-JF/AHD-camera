package com.example.ahd_camera;

import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public class MediaPreviewActivity extends AppCompatActivity {

    static final String EXTRA_IS_VIDEO = "is_video";
    static final String EXTRA_TITLE = "title";

    private static final String TAG = "MediaPreviewActivity";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        Log.d(TAG, "onCreate: enter");
        Utils.prepareFullscreenWindow(getWindow());
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_preview);

        ImageView imageView = findViewById(R.id.media_preview_image);
        VideoView videoView = findViewById(R.id.media_preview_video);
        TextView titleView = findViewById(R.id.media_preview_title);

        Uri uri = getIntent().getData();
        boolean isVideo = getIntent().getBooleanExtra(EXTRA_IS_VIDEO, false);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        titleView.setText(title == null ? "" : title);
        Log.d(TAG, "onCreate: uri=" + uri + ", isVideo=" + isVideo + ", title=" + title);

        if (uri == null) {
            titleView.setText(getString(R.string.gallery_open_failed));
            return;
        }

        if (isVideo) {
            imageView.setVisibility(View.GONE);
            videoView.setVisibility(View.VISIBLE);
            videoView.setVideoURI(uri);
            videoView.setOnPreparedListener(mp -> {
                Log.d(TAG, "onPrepared: start video");
                mp.setLooping(true);
                videoView.start();
            });
        } else {
            videoView.setVisibility(View.GONE);
            imageView.setVisibility(View.VISIBLE);
            imageView.setImageURI(uri);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Utils.hideSystemUI(getWindow());
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE
                || event.getKeyCode() == KeyEvent.KEYCODE_BACK)) {
            Log.d(TAG, "dispatchKeyEvent: close preview");
            finish();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}

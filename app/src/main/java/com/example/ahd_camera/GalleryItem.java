package com.example.ahd_camera;

import android.net.Uri;

final class GalleryItem {

    static final int TYPE_IMAGE = 1;
    static final int TYPE_VIDEO = 3;

    final Uri uri;
    final String displayName;
    final String subtitle;
    final int mediaType;
    final long dateModifiedSec;

    GalleryItem(Uri uri, String displayName, String subtitle, int mediaType, long dateModifiedSec) {
        this.uri = uri;
        this.displayName = displayName;
        this.subtitle = subtitle;
        this.mediaType = mediaType;
        this.dateModifiedSec = dateModifiedSec;
    }

    boolean isVideo() {
        return mediaType == TYPE_VIDEO;
    }
}

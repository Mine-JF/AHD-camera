package com.example.ahd_camera;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

/**
 * 负责把预览区和 HUD 叠加成一张最终位图。
 * 这里不能直接对包含 TextureView 的根布局调用 draw()，
 * 否则 TextureView 的相机画面不会进入位图，最终就会得到黑屏。
 */
final class PreviewFrameComposer {

    private static final String TAG = "PreviewFrameComposer";

    private PreviewFrameComposer() {
    }

    static Bitmap compose(View previewRoot, View overlayView) {
        if (previewRoot == null || previewRoot.getWidth() <= 0 || previewRoot.getHeight() <= 0) {
            throw new IllegalStateException("preview root is not ready");
        }

        Bitmap result = Bitmap.createBitmap(
                previewRoot.getWidth(),
                previewRoot.getHeight(),
                Bitmap.Config.ARGB_8888
        );
        Canvas canvas = new Canvas(result);
        canvas.drawColor(Color.BLACK);

        Log.d(TAG, "compose: previewSize=" + previewRoot.getWidth() + "x" + previewRoot.getHeight());
        renderViewTree(previewRoot, canvas, false);

        if (overlayView != null && overlayView.getVisibility() == View.VISIBLE) {
            renderViewTree(overlayView, canvas, false);
        }
        return result;
    }

    /**
     * 递归绘制 View 树。
     * applyOwnOffset=false 用于根节点，避免根节点重复偏移。
     * 对 ViewGroup 不直接 draw()，只画背景后递归子 View，防止 TextureView 被黑屏结果覆盖。
     */
    private static void renderViewTree(View view, Canvas canvas, boolean applyOwnOffset) {
        if (view == null || view.getVisibility() != View.VISIBLE || view.getWidth() <= 0 || view.getHeight() <= 0) {
            return;
        }

        int saveCount = canvas.save();
        if (applyOwnOffset) {
            canvas.translate(view.getLeft(), view.getTop());
        }
        canvas.clipRect(0, 0, view.getWidth(), view.getHeight());

        if (view instanceof TextureView) {
            drawTextureView((TextureView) view, canvas);
            canvas.restoreToCount(saveCount);
            return;
        }

        drawViewBackground(view, canvas);

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                renderViewTree(group.getChildAt(i), canvas, true);
            }
        } else {
            view.draw(canvas);
        }

        canvas.restoreToCount(saveCount);
    }

    private static void drawTextureView(TextureView textureView, Canvas canvas) {
        Bitmap textureBitmap = textureView.getBitmap();
        if (textureBitmap == null) {
            Log.w(TAG, "drawTextureView: getBitmap returned null for id=" + textureView.getId());
            return;
        }
        try {
            int saveCount = canvas.save();
            float rotation = textureView.getRotation();
            float centerX = textureView.getWidth() / 2f;
            float centerY = textureView.getHeight() / 2f;
            if (rotation != 0f) {
                canvas.rotate(rotation, centerX, centerY);
            }
            canvas.drawBitmap(textureBitmap, null,
                    new android.graphics.Rect(0, 0, textureView.getWidth(), textureView.getHeight()), null);
            canvas.restoreToCount(saveCount);
        } finally {
            textureBitmap.recycle();
        }
    }

    private static void drawViewBackground(View view, Canvas canvas) {
        Drawable background = view.getBackground();
        if (background == null) {
            return;
        }
        background.setBounds(0, 0, view.getWidth(), view.getHeight());
        background.draw(canvas);
    }
}

package com.loma.mylibrary;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.FrameLayout;

public class CustomWebViewController {

    private static final String TAG = "CustomWebViewController";
    public static CustomWebViewController instance;

    public static final int FILE_CHOOSER_REQUEST = 2001;
    public static final int CAMERA_REQUEST       = 2002;

    private Activity activity;
    private CustomWebView webView;
    private FrameLayout container;

    public CustomWebViewController(Activity activity) {
        this.activity = activity;
        instance = this;
        Log.d(TAG, "CustomWebViewController created");
    }

    public void CreateWebView() {
        activity.runOnUiThread(() -> {
            if (container != null) return;

            container = new FrameLayout(activity);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            );
            activity.addContentView(container, params);

            webView = new CustomWebView(activity, activity);
            webView.setGalleryLauncher(this::openGallery);
            webView.setCameraLauncher(this::openCamera);

            container.addView(webView);
            Log.d(TAG, "WebView created");
        });
    }

    private void openGallery() {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.setType("image/*");
                // Explicitly restrict to only these image types
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "image/jpeg",
                        "image/png",
                        "image/gif",
                        "image/webp",
                        "image/heic"
                });
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

                activity.startActivityForResult(
                        Intent.createChooser(intent, "Select Image"),
                        FILE_CHOOSER_REQUEST
                );
            } catch (Exception e) {
                Log.e(TAG, "Gallery launch failed: " + e.getMessage());
                if (webView != null) webView.onGalleryResult(null);
            }
        });
    }

    private void openCamera() {
        Log.d(TAG, "openCamera called");
        if (webView == null) return;

        Uri cameraUri = webView.buildCameraUri();
        if (cameraUri == null) {
            Log.e(TAG, "Failed to build camera URI");
            return;
        }

        final Uri finalUri = cameraUri;
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                intent.putExtra(MediaStore.EXTRA_OUTPUT, finalUri);
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                Log.d(TAG, "Launching camera intent...");
                activity.startActivityForResult(intent, CAMERA_REQUEST);
                Log.d(TAG, "Camera intent launched");
            } catch (Exception e) {
                Log.e(TAG, "Camera launch failed: " + e.getMessage());
                if (webView != null) webView.onCameraResult(false);
            }
        });
    }

    public void OnActivityResult(int requestCode, int resultCode, Intent data) {
        Log.d(TAG, "OnActivityResult: req=" + requestCode + " result=" + resultCode + " data=" + data);
        if (webView == null) { Log.e(TAG, "webView is null!"); return; }

        if (requestCode == FILE_CHOOSER_REQUEST) {
            Uri uri = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                uri = data.getData();
                if (uri != null) {
                    // Validate MIME type — reject anything that isn't an image
                    String mimeType = activity.getContentResolver().getType(uri);
                    Log.d(TAG, "Selected MIME type: " + mimeType);
                    if (mimeType == null || !mimeType.startsWith("image/")) {
                        Log.w(TAG, "Rejected non-image file: " + mimeType);
                        uri = null; // treat as cancelled
                    } else {
                        try {
                            activity.getContentResolver().takePersistableUriPermission(
                                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                            );
                        } catch (Exception e) {
                            Log.w(TAG, "Could not persist URI permission: " + e.getMessage());
                        }
                    }
                }
            }
            Log.d(TAG, "Gallery URI: " + uri);
            webView.onGalleryResult(uri);
        } else if (requestCode == CAMERA_REQUEST) {
            boolean success = resultCode == Activity.RESULT_OK;
            Log.d(TAG, "Camera success: " + success);
            webView.onCameraResult(success);
        }
    }

    public void LoadUrl(String url) {
        activity.runOnUiThread(() -> {
            if (webView == null) CreateWebView();
            webView.loadWebUrl(url);
        });
    }

    public void SetMargins(int left, int top, int right, int bottom) {
        activity.runOnUiThread(() -> {
            if (container == null) return;
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            );
            params.setMargins(left, top, right, bottom);
            container.setLayoutParams(params);
        });
    }

    public boolean OnBackPressed() {
        return webView != null && webView.handleBackPress();
    }
}
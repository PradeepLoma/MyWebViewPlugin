package com.loma.mylibrary;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.ValueCallback;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Created By Pradeep Rai on 3/5/2026.
 */

/**
 * ImagePickerHelper
 * <p>
 * Single source of truth for:
 * - Launching the system photo picker (no camera, no permissions needed)
 * - Converting the picked content:// URI → base64 data URI (so JS fetch() works)
 * - Delivering the result to either the WebChromeClient callback or JS bridge path
 * <p>
 * Logging: uses BridgeLog which mirrors every Log call to Unity via
 * UnityPlayer.UnitySendMessage so logs appear inside the Unity console.
 */
public class ImagePickerHelper {

    // ─── Constants ────────────────────────────────────────────────────────────

    public static final int REQUEST_CODE = 7001;
    private static final String TAG = "ImagePickerHelper";

    // Max image dimension we'll downscale to before base64-encoding.
    // Keeps the payload sent to JS under ~1 MB for typical photos.
    private static final int MAX_DIM = 1280;

    // ─── State ────────────────────────────────────────────────────────────────

    private final Activity activity;

    /**
     * Set by WebChromeClient.onShowFileChooser — standard WebView path
     */
    private ValueCallback<Uri[]> pendingWebCallback;

    /**
     * Set by the JS bridge path when onShowFileChooser never fires
     */
    private Runnable onPickedJsBridgePath;

    // ─── Construction ─────────────────────────────────────────────────────────

    public ImagePickerHelper(Activity activity) {
        this.activity = activity;
        BridgeLog.i(TAG, "ImagePickerHelper initialised");
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Called from WebChromeClient.onShowFileChooser.
     * Stores the callback and opens the picker.
     */
    public boolean handleFileChooser(ValueCallback<Uri[]> callback) {
        BridgeLog.i(TAG, "handleFileChooser — storing WebChromeClient callback");
        cancelPendingCallback();        // safety: cancel any leaked previous callback
        pendingWebCallback = callback;
        onPickedJsBridgePath = null;
        openPicker();
        return true;
    }

    /**
     * Called from the JS bridge (NativeBridge.openFilePicker).
     * No WebChromeClient callback here — result is delivered via JS injection.
     *
     * @param onPickedCallback Runnable called on the main thread once we have
     *                         the base64 data URI ready; caller reads
     *                         {@link #getLastDataUri()} to retrieve it.
     */
    public void handleJsBridgePick(Runnable onPickedCallback) {
        BridgeLog.i(TAG, "handleJsBridgePick — JS bridge path");
        cancelPendingCallback();
        pendingWebCallback = null;
        onPickedJsBridgePath = onPickedCallback;
        openPicker();
    }

    /**
     * Forward onActivityResult from the Activity here.
     *
     * @return true if this helper consumed the result.
     */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_CODE) return false;

        BridgeLog.i(TAG, "onActivityResult consumed — resultCode=" + resultCode);

        Uri uri = null;
        if (resultCode == Activity.RESULT_OK && data != null) {
            uri = data.getData();
            if (uri != null) {
                String mime = activity.getContentResolver().getType(uri);
                BridgeLog.i(TAG, "Picked MIME: " + mime);
                if (mime == null || !mime.startsWith("image/")) {
                    BridgeLog.w(TAG, "Rejected non-image: " + mime);
                    uri = null;
                }
            }
        }

        if (uri == null) {
            BridgeLog.w(TAG, "No valid URI — cancelled or error");
            deliverCancel();
            return true;
        }

        // Convert content:// → base64 data URI on a background thread,
        // then deliver on main thread.
        final Uri finalUri = uri;
        new Thread(() -> {
            String dataUri = toDataUri(finalUri);
            new Handler(Looper.getMainLooper()).post(() -> deliverResult(finalUri, dataUri));
        }).start();

        return true;
    }

    // ─── Picker launch ────────────────────────────────────────────────────────

    private void openPicker() {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Intent intent = buildIntent();
                activity.startActivityForResult(intent, REQUEST_CODE);
                BridgeLog.i(TAG, "Photo picker launched — SDK=" + Build.VERSION.SDK_INT);
            } catch (Exception e) {
                BridgeLog.e(TAG, "Failed to launch picker: " + e.getMessage());
                deliverCancel();
            }
        });
    }

    /**
     * Gallery-only intent. Zero runtime permissions required on all API levels:
     * <p>
     * API 33+ → android.provider.action.PICK_IMAGES  (system Photo Picker)
     * API <33 → ACTION_GET_CONTENT                   (user-initiated, no permission needed)
     * <p>
     * We never use ACTION_OPEN_DOCUMENT (triggers READ_MEDIA_IMAGES on some OEMs)
     * and we never use createChooser() (adds the Camera tile).
     */
    private Intent buildIntent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent i = new Intent("android.provider.action.PICK_IMAGES");
            i.setType("image/*");
            return i;
        } else {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES,
                    new String[]{"image/jpeg", "image/png", "image/gif",
                            "image/webp", "image/heic"});
            i.addCategory(Intent.CATEGORY_OPENABLE);
            return i;
        }
    }

    // ─── Result delivery ──────────────────────────────────────────────────────

    private void deliverResult(Uri contentUri, String dataUri) {
        if (dataUri == null) {
            BridgeLog.e(TAG, "base64 conversion failed — cancelling");
            deliverCancel();
            return;
        }

        if (pendingWebCallback != null) {
            // Standard path: WebChromeClient is waiting for the Uri[]
            BridgeLog.i(TAG, "Delivering to WebChromeClient callback");
            pendingWebCallback.onReceiveValue(new Uri[]{contentUri});
            pendingWebCallback = null;
        } else if (onPickedJsBridgePath != null) {
            // JS bridge path: caller will inject dataUri via evaluateJavascript
            lastDataUri = dataUri;
            BridgeLog.i(TAG, "Delivering to JS bridge path, dataUri length=" + dataUri.length());
            Runnable r = onPickedJsBridgePath;
            onPickedJsBridgePath = null;
            r.run();
        } else {
            BridgeLog.w(TAG, "No pending callback — result dropped");
        }
    }

    private void deliverCancel() {
        if (pendingWebCallback != null) {
            pendingWebCallback.onReceiveValue(null);
            pendingWebCallback = null;
        }
        onPickedJsBridgePath = null;
    }

    private void cancelPendingCallback() {
        if (pendingWebCallback != null) {
            BridgeLog.w(TAG, "Cancelling leaked WebChromeClient callback");
            pendingWebCallback.onReceiveValue(null);
            pendingWebCallback = null;
        }
        onPickedJsBridgePath = null;
    }

    // ─── base64 conversion ────────────────────────────────────────────────────

    /**
     * Holds the last successfully converted data URI (used by JS bridge path)
     */
    private String lastDataUri;

    public String getLastDataUri() {
        return lastDataUri;
    }

    /**
     * Reads the image, downscales if larger than MAX_DIM, re-encodes as JPEG,
     * and returns a data URI string safe for use inside JavaScript.
     * <p>
     * Runs on a background thread — do NOT call on the main thread.
     */
    private String toDataUri(Uri uri) {
        try {
            // First pass: get dimensions without decoding pixels
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            try (InputStream probe = activity.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(probe, null, opts);
            }

            int w = opts.outWidth;
            int h = opts.outHeight;
            int sampleSize = 1;
            while (w / sampleSize > MAX_DIM || h / sampleSize > MAX_DIM) {
                sampleSize *= 2;
            }

            // Second pass: decode at reduced resolution
            opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize;
            Bitmap bmp;
            try (InputStream stream = activity.getContentResolver().openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(stream, null, opts);
            }

            if (bmp == null) {
                BridgeLog.e(TAG, "BitmapFactory returned null");
                return null;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos);
            bmp.recycle();

            String b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
            return "data:image/jpeg;base64," + b64;

        } catch (Exception e) {
            BridgeLog.e(TAG, "toDataUri failed: " + e.getMessage());
            return null;
        }
    }
}
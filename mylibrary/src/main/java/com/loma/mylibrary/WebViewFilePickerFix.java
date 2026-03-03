package com.loma.mylibrary;

/**
 * Created By Pradeep Rai on 3/2/2026.
 */
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Android 15 compatible file picker fix for gree/unity-webview
 *
 * HOW TO USE:
 * 1. Add this file to your mylibrary Android Studio project (package: net.gree.unitywebview)
 * 2. In CustomUnityPlayerActivity.onCreate(), call:
 *      webViewFilePickerFix = new WebViewFilePickerFix(this);
 * 3. In CustomUnityPlayerActivity.onActivityResult(), call:
 *      if (webViewFilePickerFix.onActivityResult(requestCode, resultCode, data)) return;
 *
 * The gree WebView's onShowFileChooser is patched via JS injection.
 * Call injectIntoWebView(webView) after each page load from WebViewObject callbacks.
 */
public class WebViewFilePickerFix {

    private static final String TAG = "WebViewFilePickerFix";
    public static final int FILE_CHOOSER_REQUEST = 9001;
    public static final int CAMERA_REQUEST = 9002;

    public static WebViewFilePickerFix instance;

    private final Activity activity;
    private ValueCallback<Uri[]> pendingCallback;
    private Uri pendingCameraUri;
    private WebView attachedWebView;

    public WebViewFilePickerFix(Activity activity) {
        this.activity = activity;
        instance = this;
        Log.d(TAG, "WebViewFilePickerFix initialized");
    }

    /**
     * Attach to a WebView and inject the JS bridge + file input interceptor.
     * Call this from your Unity C# code after the WebView finishes loading.
     * Example C# call:
     *   webViewObject.EvaluateJS("window.__nativePickerReady=true");
     * But better — call this from Java side after page load.
     */
    public void attachToWebView(WebView webView) {
        this.attachedWebView = webView;
        webView.addJavascriptInterface(new NativeBridge(), "AndroidFilePicker");
        Log.d(TAG, "JS bridge attached to WebView");
        injectInterceptor(webView);
    }

    /**
     * Injects file input interceptor JS into the WebView.
     * Call this every time a page finishes loading.
     */
    public void injectInterceptor(WebView webView) {
        if (webView == null) return;

        String js =
                "(function() {" +
                        "  if (window.__androidPickerInjected) return;" +
                        "  window.__androidPickerInjected = true;" +
                        "  function interceptInputs() {" +
                        "    var inputs = document.querySelectorAll('input[type=file]');" +
                        "    inputs.forEach(function(input) {" +
                        "      if (input.dataset.androidIntercepted) return;" +
                        "      input.dataset.androidIntercepted = 'true';" +
                        "      input.addEventListener('click', function(e) {" +
                        "        e.preventDefault();" +
                        "        e.stopPropagation();" +
                        "        window.__pendingFileInput = input;" +
                        "        var capture = input.getAttribute('capture') || '';" +
                        "        var type = (capture === 'camera' || capture === 'environment') ? 'camera' : 'gallery';" +
                        "        console.log('AndroidFilePicker: intercepted type=' + type);" +
                        "        if (window.AndroidFilePicker) {" +
                        "          window.AndroidFilePicker.openPicker(type);" +
                        "        }" +
                        "      }, true);" +
                        "    });" +
                        "  }" +
                        "  interceptInputs();" +
                        "  new MutationObserver(interceptInputs)" +
                        "    .observe(document.body, {childList:true, subtree:true});" +
                        "  console.log('AndroidFilePicker: interceptor installed');" +
                        "})();";

        webView.post(() -> webView.evaluateJavascript(js, null));
        Log.d(TAG, "Interceptor JS injected");
    }

    // Called from onShowFileChooser (standard path, may not fire on Android 15)
    public boolean handleFileChooser(ValueCallback<Uri[]> callback,
                                     WebChromeClient.FileChooserParams params) {
        Log.d(TAG, "handleFileChooser — standard path");
        if (pendingCallback != null) pendingCallback.onReceiveValue(null);
        pendingCallback = callback;
        boolean capture = params.isCaptureEnabled();
        new Handler(Looper.getMainLooper()).post(() -> {
            if (capture) openCamera(); else openGallery();
        });
        return true;
    }

    private class NativeBridge {
        @JavascriptInterface
        public void openPicker(String type) {
            Log.d(TAG, "NativeBridge.openPicker: type=" + type);
            // No filePathCallback in JS bridge path — we inject result back via JS
            activity.runOnUiThread(() -> {
                if ("camera".equals(type)) openCamera();
                else openGallery();
            });
        }
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
                deliverResult(null);
            }
        });
    }

    private void openCamera() {
        Log.d(TAG, "openCamera called");
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                File photoFile = createImageFile();
                pendingCameraUri = FileProvider.getUriForFile(
                        activity,
                        activity.getPackageName() + ".unitywebview.fileprovider",
                        photoFile
                );
                Intent intent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
                intent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, pendingCameraUri);
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                activity.startActivityForResult(intent, CAMERA_REQUEST);
                Log.d(TAG, "Camera launched");
            } catch (Exception e) {
                Log.e(TAG, "Camera failed: " + e.getMessage());
                deliverResult(null);
            }
        });
    }

    private File createImageFile() throws IOException {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File dir = activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        return File.createTempFile("JPEG_" + ts + "_", ".jpg", dir);
    }

    /**
     * Called from CustomUnityPlayerActivity.onActivityResult
     * Returns true if this class handled the result.
     */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            Uri uri = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                uri = data.getData();
                if (uri != null) {
                    String mimeType = activity.getContentResolver().getType(uri);
                    Log.d(TAG, "Selected MIME type: " + mimeType);
                    if (mimeType == null || !mimeType.startsWith("image/")) {
                        Log.w(TAG, "Rejected non-image file: " + mimeType);
                        uri = null;
                    } else {
                        try {
                            activity.getContentResolver().takePersistableUriPermission(
                                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                            );
                        } catch (Exception e) {
                            Log.w(TAG, "Could not persist URI: " + e.getMessage());
                        }
                    }
                }
            }
            final Uri finalUri = uri;
            if (pendingCallback != null) {
                deliverResult(finalUri != null ? new Uri[]{finalUri} : null);
            } else if (finalUri != null && attachedWebView != null) {
                injectFileResult(finalUri.toString(), "image.jpg");
            }
            return true;
        } else if (requestCode == CAMERA_REQUEST) {
            boolean success = resultCode == Activity.RESULT_OK;
            Log.d(TAG, "Camera success: " + success);

            if (pendingCallback != null) {
                deliverResult(success && pendingCameraUri != null ? new Uri[]{pendingCameraUri} : null);
            } else if (success && pendingCameraUri != null && attachedWebView != null) {
                injectFileResult(pendingCameraUri.toString(), "camera.jpg");
            }
            return true;
        }

        return false;
    }

    private void deliverResult(Uri[] results) {
        if (pendingCallback != null) {
            Log.d(TAG, "Delivering to callback: " + (results != null ? results.length + " files" : "null"));
            pendingCallback.onReceiveValue(results);
            pendingCallback = null;
        }
    }

    private void injectFileResult(String uriStr, String fileName) {
        Log.d(TAG, "Injecting file result via JS: " + uriStr);
        String js =
                "(function() {" +
                        "  var input = window.__pendingFileInput;" +
                        "  if (!input) { console.log('AndroidFilePicker: no pending input'); return; }" +
                        "  fetch('" + uriStr + "')" +
                        "    .then(function(r) { return r.blob(); })" +
                        "    .then(function(blob) {" +
                        "      var dt = new DataTransfer();" +
                        "      var file = new File([blob], '" + fileName + "', {type: blob.type || 'image/jpeg'});" +
                        "      dt.items.add(file);" +
                        "      input.files = dt.files;" +
                        "      input.dispatchEvent(new Event('change', {bubbles:true}));" +
                        "      input.dispatchEvent(new Event('input', {bubbles:true}));" +
                        "      window.__pendingFileInput = null;" +
                        "      window.__androidPickerInjected = false;" +
                        "      console.log('AndroidFilePicker: file injected OK');" +
                        "    })" +
                        "    .catch(function(e) { console.log('AndroidFilePicker: inject failed: '+e); });" +
                        "})();";

        attachedWebView.post(() -> attachedWebView.evaluateJavascript(js, null));
    }
}

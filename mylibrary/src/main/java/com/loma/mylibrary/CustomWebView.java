package com.loma.mylibrary;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Environment;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class CustomWebView extends WebView {

    private static final String TAG = "CustomWebView";

    private Activity activity;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraImageUri;

    private Runnable openGalleryRunnable;
    private Runnable openCameraRunnable;

    public CustomWebView(Context context, Activity activity) {
        super(context);
        this.activity = activity;
        init();
    }

    public void setGalleryLauncher(Runnable r) { this.openGalleryRunnable = r; }
    public void setCameraLauncher(Runnable r)  { this.openCameraRunnable = r; }
    public ValueCallback<Uri[]> getFilePathCallback() { return filePathCallback; }
    public void clearFilePathCallback() { filePathCallback = null; }
    public Uri getCameraImageUri() { return cameraImageUri; }

    public Uri buildCameraUri() {
        try {
            File photoFile = createImageFile();
            cameraImageUri = FileProvider.getUriForFile(
                    activity,
                    activity.getPackageName() + ".fileprovider",
                    photoFile
            );
            return cameraImageUri;
        } catch (IOException e) {
            Log.e(TAG, "Failed to create image file", e);
            return null;
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void init() {
        setOverScrollMode(OVER_SCROLL_NEVER);
        setBackgroundColor(0x00000000);

        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true);

        WebSettings settings = getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setAllowFileAccess(true);
        settings.setSupportZoom(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(true);
        settings.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 6.0; Nexus 5) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/137.0.0.0 Mobile Safari/537.36"
        );

        addJavascriptInterface(new NativeBridge(), "NativeFilePicker");

        setWebViewClient(new CustomWebViewClient());
        setWebChromeClient(new CustomWebChromeClient());
    }

    // ─── JavaScript Bridge ────────────────────────────────────────────────────

    private class NativeBridge {

        @JavascriptInterface
        public void openFilePicker(String type) {
            Log.d(TAG, "NativeBridge.openFilePicker called: type=" + type);

            activity.runOnUiThread(() -> {
                // Mark the pending input in JS so we can inject result back later
                evaluateJavascript(
                        "(function() {" +
                                "  window.__nativePickerPending = true;" +
                                "  var inputs = document.querySelectorAll('input[type=file]');" +
                                "  inputs.forEach(function(el) { window.__pendingInput = el; });" +
                                "})();",
                        null
                );

                if ("camera".equals(type)) {
                    if (openCameraRunnable != null) {
                        openCameraRunnable.run();
                    }
                } else {
                    if (openGalleryRunnable != null) {
                        openGalleryRunnable.run();
                    }
                }
            });
        }
    }

    // ─── JS Injection ─────────────────────────────────────────────────────────

    private void injectFileInputInterceptor() {
        String js =
                "(function() {" +
                        "  function interceptFileInputs() {" +
                        "    var inputs = document.querySelectorAll('input[type=file]');" +
                        "    inputs.forEach(function(input) {" +
                        "      if (input.dataset.nativeIntercepted) return;" +
                        "      input.dataset.nativeIntercepted = 'true';" +
                        "      input.setAttribute('accept', 'image/*');"+
                        "      input.addEventListener('click', function(e) {" +
                        "        e.preventDefault();" +
                        "        e.stopPropagation();" +
                        "        var capture = input.getAttribute('capture') || '';" +
                        "        var type = (capture === 'camera' || capture === 'environment') ? 'camera' : 'gallery';" +
                        "        console.log('NativeFilePicker: intercepted, type=' + type);" +
                        "        if (window.NativeFilePicker) {" +
                        "          window.NativeFilePicker.openFilePicker(type);" +
                        "        }" +
                        "      }, true);" +
                        "    });" +
                        "  }" +
                        "  interceptFileInputs();" +
                        "  var observer = new MutationObserver(function() {" +
                        "    interceptFileInputs();" +
                        "  });" +
                        "  observer.observe(document.body, { childList: true, subtree: true });" +
                        "  window.userIdentifier = 'NATIVE_APP';" +
                        "})();";

        evaluateJavascript(js, null);
        Log.d(TAG, "File input interceptor injected");
    }

    // ─── Results ──────────────────────────────────────────────────────────────

    public void onGalleryResult(Uri uri) {
        Log.d(TAG, "onGalleryResult: " + uri);

        if (filePathCallback != null) {
            // Standard path — onShowFileChooser fired correctly
            filePathCallback.onReceiveValue(uri != null ? new Uri[]{uri} : null);
            filePathCallback = null;
        } else if (uri != null) {
            // JS bridge path — inject file back into the webpage input
            Log.d(TAG, "filePathCallback null, injecting via JS: " + uri);
            final String uriStr = uri.toString();
            activity.runOnUiThread(() ->
                    evaluateJavascript(
                            "(function() {" +
                                    "  var input = window.__pendingInput;" +
                                    "  if (!input) { console.log('NativeFilePicker: no pending input'); return; }" +
                                    "  fetch('" + uriStr + "')" +
                                    "    .then(function(r) { return r.blob(); })" +
                                    "    .then(function(blob) {" +
                                    "      var dt = new DataTransfer();" +
                                    "      var file = new File([blob], 'image.jpg', {type: blob.type || 'image/jpeg'});" +
                                    "      dt.items.add(file);" +
                                    "      input.files = dt.files;" +
                                    "      input.dispatchEvent(new Event('change', {bubbles: true}));" +
                                    "      input.dispatchEvent(new Event('input', {bubbles: true}));" +
                                    "      window.__pendingInput = null;" +
                                    "      window.__nativePickerPending = false;" +
                                    "      console.log('NativeFilePicker: file injected successfully');" +
                                    "    })" +
                                    "    .catch(function(err) {" +
                                    "      console.log('NativeFilePicker: fetch failed: ' + err);" +
                                    "    });" +
                                    "})();",
                            null
                    )
            );
        } else {
            Log.w(TAG, "Gallery cancelled and no filePathCallback");
        }
    }

    public void onCameraResult(boolean success) {
        Log.d(TAG, "onCameraResult: success=" + success);

        if (filePathCallback != null) {
            // Standard path
            filePathCallback.onReceiveValue(
                    success && cameraImageUri != null ? new Uri[]{cameraImageUri} : null
            );
            filePathCallback = null;
        } else if (success && cameraImageUri != null) {
            // JS bridge path
            final String uriStr = cameraImageUri.toString();
            Log.d(TAG, "filePathCallback null, injecting camera via JS: " + uriStr);
            activity.runOnUiThread(() ->
                    evaluateJavascript(
                            "(function() {" +
                                    "  var input = window.__pendingInput;" +
                                    "  if (!input) { console.log('NativeFilePicker: no pending input for camera'); return; }" +
                                    "  fetch('" + uriStr + "')" +
                                    "    .then(function(r) { return r.blob(); })" +
                                    "    .then(function(blob) {" +
                                    "      var dt = new DataTransfer();" +
                                    "      var file = new File([blob], 'camera.jpg', {type: 'image/jpeg'});" +
                                    "      dt.items.add(file);" +
                                    "      input.files = dt.files;" +
                                    "      input.dispatchEvent(new Event('change', {bubbles: true}));" +
                                    "      input.dispatchEvent(new Event('input', {bubbles: true}));" +
                                    "      window.__pendingInput = null;" +
                                    "      window.__nativePickerPending = false;" +
                                    "      console.log('NativeFilePicker: camera file injected successfully');" +
                                    "    })" +
                                    "    .catch(function(err) {" +
                                    "      console.log('NativeFilePicker: camera fetch failed: ' + err);" +
                                    "    });" +
                                    "})();",
                            null
                    )
            );
        } else {
            Log.w(TAG, "Camera cancelled or failed and no filePathCallback");
        }
    }

    // ─── WebView Helpers ──────────────────────────────────────────────────────

    public void loadWebUrl(String url) { loadUrl(url); }

    public boolean handleBackPress() {
        if (canGoBack()) { goBack(); return true; }
        return false;
    }

    private File createImageFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File storageDir = activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        return File.createTempFile("JPEG_" + timeStamp + "_", ".jpg", storageDir);
    }

    // ─── WebViewClient ────────────────────────────────────────────────────────

    private class CustomWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (!url.startsWith("http") && !url.startsWith("https")) {
                try {
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (ActivityNotFoundException e) {
                    e.printStackTrace();
                }
                return true;
            }
            return false;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            Log.d(TAG, "onPageFinished: " + url);
            injectFileInputInterceptor();
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            if (error.getPrimaryError() == SslError.SSL_UNTRUSTED) {
                handler.proceed();
            } else {
                handler.cancel();
            }
        }
    }

    // ─── WebChromeClient ──────────────────────────────────────────────────────

    private class CustomWebChromeClient extends WebChromeClient {

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            request.grant(request.getResources());
        }

        @Override
        public boolean onShowFileChooser(WebView webView,
                                         ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            Log.d(TAG, "onShowFileChooser called — standard path");

            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(null);
            }
            filePathCallback = callback;

            boolean captureEnabled = params.isCaptureEnabled();
            if (captureEnabled && openCameraRunnable != null) {
                openCameraRunnable.run();
            } else if (openGalleryRunnable != null) {
                openGalleryRunnable.run();
            } else {
                Log.e(TAG, "No launcher runnable set!");
                filePathCallback.onReceiveValue(null);
                filePathCallback = null;
            }
            return true;
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog,
                                      boolean isUserGesture, Message resultMsg) {
            WebView newWebView = new WebView(view.getContext());
            newWebView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageStarted(WebView view, String url, Bitmap favicon) {
                    loadUrl(url);
                }
            });
            WebViewTransport transport = (WebViewTransport) resultMsg.obj;
            transport.setWebView(newWebView);
            resultMsg.sendToTarget();
            return true;
        }
    }
}
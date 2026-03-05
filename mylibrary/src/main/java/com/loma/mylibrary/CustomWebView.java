package com.loma.mylibrary;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Message;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.ArrayList;
import java.util.List;

/**
 * CustomWebView
 * <p>
 * Responsibilities:
 * - Render the web content inside Unity
 * - Intercept file <input> clicks via a single JS bridge ("NativeImagePicker")
 * - Delegate all image-picking logic to ImagePickerHelper
 * - Inject the selected image back into the page as a base64 data URI
 * (avoids the content:// fetch() failure)
 * <p>
 * What was removed vs. the old version:
 * - WebViewFilePickerFix (duplicate — deleted entirely)
 * - Camera / audio / video permission grants
 * - fetch(content://) JS path (now uses base64 data URI)
 * - Duplicate NativeBridge / openGallery / buildPhotoPickerIntent
 */
public class CustomWebView extends WebView {

    private static final String TAG = "CustomWebView";

    private final Activity activity;
    private final ImagePickerHelper pickerHelper;

    // Tracks which <input> element is waiting for a file, set by JS.
    // We keep this as an index rather than a JS object reference for simplicity.
    private String pendingInputSelector = null;

    public CustomWebView(Context context, Activity activity, ImagePickerHelper pickerHelper) {
        super(context);
        this.activity = activity;
        this.pickerHelper = pickerHelper;
        init();
    }

    // ─── Init ─────────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private void init() {
        setOverScrollMode(OVER_SCROLL_NEVER);
        setBackgroundColor(0x00000000);

        WebSettings s = getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setAllowFileAccess(true);
        s.setSupportZoom(false);
        // Do NOT set setMediaPlaybackRequiresUserGesture(false) — that triggers
        // the browser to request microphone/camera access.
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        s.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 6.0; Nexus 5) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/137.0.0.0 Mobile Safari/537.36"
        );

        // Single JS bridge — no duplicates
        addJavascriptInterface(new NativeImagePickerBridge(), "NativeImagePicker");

        setWebViewClient(new CustomWebViewClient());
        setWebChromeClient(new CustomWebChromeClient());

        BridgeLog.i(TAG, "CustomWebView initialised");
    }

    // ─── JS Bridge ────────────────────────────────────────────────────────────

    private class NativeImagePickerBridge {

        /**
         * Called from JS when the user taps a file input.
         *
         * @param inputId A unique ID we assigned to the <input> element so we
         *                can re-target it when we inject the result.
         */
        @JavascriptInterface
        public void openPicker(String inputId) {
            BridgeLog.i(TAG, "NativeImagePickerBridge.openPicker — inputId=" + inputId);
            pendingInputSelector = inputId;

            // Ask the helper to launch the picker. When the result comes back,
            // the helper calls our lambda, which injects the base64 data URI.
            pickerHelper.handleJsBridgePick(() -> {
                String dataUri = pickerHelper.getLastDataUri();
                if (dataUri != null) {
                    injectBase64IntoInput(pendingInputSelector, dataUri);
                } else {
                    BridgeLog.w(TAG, "dataUri null — nothing injected");
                }
                pendingInputSelector = null;
            });
        }
    }

    // ─── JS Injection ─────────────────────────────────────────────────────────

    /**
     * Injected on every page load. Intercepts all file inputs and routes them
     * through the native bridge instead of the default OS picker UI.
     * <p>
     * Key design points:
     * - We assign a unique __pickerId to each input so we can re-identify it later.
     * - We store nothing on window except __pickerIdCounter to avoid conflicts
     * with the host page's JS.
     * - MutationObserver re-runs interceptInputs() when new inputs are added
     * dynamically (e.g. React/Vue SPAs).
     */
    private void injectFileInputInterceptor() {
        String js =
                "(function() {" +
                        "  if (window.__nativePickerInstalled) return;" +
                        "  window.__nativePickerInstalled = true;" +
                        "  window.__pickerIdCounter = 0;" +
                        "" +
                        "  function interceptInputs() {" +
                        "    var inputs = document.querySelectorAll('input[type=file]');" +
                        "    inputs.forEach(function(input) {" +
                        "      if (input.dataset.nativePickerId) return;" +  // already intercepted
                        "      var id = 'np_' + (++window.__pickerIdCounter);" +
                        "      input.dataset.nativePickerId = id;" +
                        "      input.setAttribute('accept', 'image/*');" +
                        "      input.addEventListener('click', function(e) {" +
                        "        e.preventDefault();" +
                        "        e.stopPropagation();" +
                        "        console.log('NativeImagePicker: click intercepted id=' + id);" +
                        "        if (window.NativeImagePicker) {" +
                        "          window.NativeImagePicker.openPicker(id);" +
                        "        }" +
                        "      }, true);" +
                        "    });" +
                        "  }" +
                        "" +
                        "  interceptInputs();" +
                        "  new MutationObserver(interceptInputs)" +
                        "    .observe(document.body, { childList: true, subtree: true });" +
                        "  console.log('NativeImagePicker: interceptor installed');" +
                        "})();";

        post(() -> evaluateJavascript(js, null));
        BridgeLog.i(TAG, "File input interceptor injected");
    }

    /**
     * After the user picks an image, we have a base64 data URI.
     * This method injects it into the waiting <input> element by:
     * 1. Looking up the input by its __pickerId attribute
     * 2. Creating a File object from the base64 data
     * 3. Assigning it to input.files via DataTransfer
     * 4. Dispatching change + input events so the page's listeners fire
     * <p>
     * Using a data URI here (not content://) means fetch() is not involved at
     * all — we construct the Blob entirely inside JS from the base64 string.
     */
    private void injectBase64IntoInput(String inputId, String dataUri) {
        if (inputId == null || dataUri == null) return;
        BridgeLog.i(TAG, "Injecting base64 into input id=" + inputId
                + " dataUri.length=" + dataUri.length());

        // Escape the inputId for safe use inside a JS string
        String safeId = inputId.replace("'", "\\'");

        String js =
                "(function() {" +
                        "  var input = document.querySelector('[data-native-picker-id=\"" + safeId + "\"]');" +
                        "  if (!input) {" +
                        "    console.log('NativeImagePicker: input not found for id=" + safeId + "');" +
                        "    return;" +
                        "  }" +
                        // Convert base64 data URI → Uint8Array → Blob without fetch()
                        "  var dataUri = '" + dataUri + "';" +
                        "  var parts = dataUri.split(',');" +
                        "  var mime  = parts[0].split(':')[1].split(';')[0];" +
                        "  var raw   = atob(parts[1]);" +
                        "  var arr   = new Uint8Array(raw.length);" +
                        "  for (var i = 0; i < raw.length; i++) arr[i] = raw.charCodeAt(i);" +
                        "  var blob = new Blob([arr], { type: mime });" +
                        "  var file = new File([blob], 'image.jpg', { type: mime });" +
                        "  var dt   = new DataTransfer();" +
                        "  dt.items.add(file);" +
                        "  input.files = dt.files;" +
                        "  input.dispatchEvent(new Event('change', { bubbles: true }));" +
                        "  input.dispatchEvent(new Event('input',  { bubbles: true }));" +
                        "  console.log('NativeImagePicker: file injected OK for id=" + safeId + "');" +
                        "})();";

        post(() -> evaluateJavascript(js, null));
    }

    // ─── Public helpers ───────────────────────────────────────────────────────

    public void loadWebUrl(String url) {
        loadUrl(url);
    }

    public boolean handleBackPress() {
        if (canGoBack()) {
            goBack();
            return true;
        }
        return false;
    }

    // ─── WebViewClient ────────────────────────────────────────────────────────

    private class CustomWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            String url = req.getUrl().toString();
            if (!url.startsWith("http")) {
                try {
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (ActivityNotFoundException e) {
                    BridgeLog.e(TAG, "No handler for URL: " + url);
                }
                return true;
            }
            return false;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            BridgeLog.i(TAG, "onPageFinished: " + url);
            injectFileInputInterceptor();
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, android.net.http.SslError err) {
            // Only proceed for untrusted-CA errors (e.g. self-signed dev certs).
            // All other SSL errors are cancelled for security.
            if (err.getPrimaryError() == android.net.http.SslError.SSL_UNTRUSTED) {
                handler.proceed();
            } else {
                handler.cancel();
            }
        }
    }

    // ─── WebChromeClient ──────────────────────────────────────────────────────

    private class CustomWebChromeClient extends WebChromeClient {

        /**
         * Grant only image-related WebRTC resources.
         * Audio capture and video capture are explicitly denied.
         */
        @Override
        public void onPermissionRequest(PermissionRequest request) {
            List<String> allowed = new ArrayList<>();
            for (String res : request.getResources()) {
                if (res.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE) ||
                        res.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                    BridgeLog.w(TAG, "Denied WebView permission: " + res);
                    continue;
                }
                allowed.add(res);
            }
            if (!allowed.isEmpty()) {
                request.grant(allowed.toArray(new String[0]));
            } else {
                request.deny();
            }
        }

        /**
         * Standard WebView file chooser path.
         * Delegates to ImagePickerHelper which stores the callback and opens the picker.
         * The helper calls callback.onReceiveValue(uri[]) when the user picks.
         */
        @Override
        public boolean onShowFileChooser(WebView webView,
                                         ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            BridgeLog.i(TAG, "onShowFileChooser — delegating to ImagePickerHelper");
            return pickerHelper.handleFileChooser(callback);
        }

        /**
         * Handle target=_blank links / window.open() by loading the URL in
         * the same WebView instead of spawning a new one.
         */
        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog,
                                      boolean isUserGesture, Message resultMsg) {
            WebView popup = new WebView(view.getContext());
            popup.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageStarted(WebView v, String url, Bitmap fav) {
                    loadUrl(url);   // redirect to main WebView
                }
            });
            WebViewTransport transport = (WebViewTransport) resultMsg.obj;
            transport.setWebView(popup);
            resultMsg.sendToTarget();
            return true;
        }
    }
}
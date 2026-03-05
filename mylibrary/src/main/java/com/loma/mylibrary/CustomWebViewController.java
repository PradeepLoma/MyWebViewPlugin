package com.loma.mylibrary;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * CustomWebViewController
 *
 * The single entry point called from Unity (via JNI / C# plugin wrappers).
 *
 * Owns:
 *  - ImagePickerHelper  (single instance, single request code 7001)
 *  - CustomWebView      (single WebView instance)
 *
 * WebViewFilePickerFix has been removed — all picker logic lives in
 * ImagePickerHelper to eliminate the duplicate-class problem.
 */
public class CustomWebViewController {

    private static final String TAG = "CustomWebViewController";

    /** Singleton used by CustomUnityPlayerActivity.onActivityResult */
    public static CustomWebViewController instance;

    private final Activity activity;
    private final ImagePickerHelper pickerHelper;

    private CustomWebView webView;
    private FrameLayout container;

    // ─── Construction ─────────────────────────────────────────────────────────

    public CustomWebViewController(Activity activity) {
        this.activity = activity;
        this.pickerHelper = new ImagePickerHelper(activity);
        instance = this;
        BridgeLog.i(TAG, "CustomWebViewController created");
    }

    // ─── Unity-facing API ─────────────────────────────────────────────────────

    /** Call once from Unity to create the WebView and add it to the Activity. */
    public void CreateWebView() {
        activity.runOnUiThread(() -> {
            if (container != null) {
                BridgeLog.w(TAG, "CreateWebView called again — ignored");
                return;
            }

            container = new FrameLayout(activity);
            activity.addContentView(container, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            webView = new CustomWebView(activity, activity, pickerHelper);
            container.addView(webView);
            BridgeLog.i(TAG, "WebView created and added to Activity");
        });
    }

    /** Load a URL into the WebView. Creates the WebView first if needed. */
    public void LoadUrl(String url) {
        activity.runOnUiThread(() -> {
            if (webView == null) CreateWebView();
            webView.loadWebUrl(url);
            BridgeLog.i(TAG, "LoadUrl: " + url);
        });
    }

    /**
     * Adjust the WebView margins (e.g. to leave room for Unity UI elements).
     * All values in pixels.
     */
    public void SetMargins(int left, int top, int right, int bottom) {
        activity.runOnUiThread(() -> {
            if (container == null) return;
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            p.setMargins(left, top, right, bottom);
            container.setLayoutParams(p);
        });
    }

    /** @return true if the WebView consumed the back press (navigated back). */
    public boolean OnBackPressed() {
        return webView != null && webView.handleBackPress();
    }

    /** Evaluate arbitrary JavaScript in the WebView. Safe to call from any thread. */
    public void EvaluateJS(final String js) {
        activity.runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript(js, null);
                BridgeLog.i(TAG, "EvaluateJS called, length=" + js.length());
            } else {
                BridgeLog.w(TAG, "EvaluateJS called but webView is null");
            }
        });
    }

    // ─── Activity lifecycle ───────────────────────────────────────────────────

    /**
     * Must be called from CustomUnityPlayerActivity.onActivityResult.
     * Routes the result to ImagePickerHelper using its single REQUEST_CODE.
     */
    public void OnActivityResult(int requestCode, int resultCode, Intent data) {
        BridgeLog.i(TAG, "OnActivityResult req=" + requestCode + " result=" + resultCode);
        pickerHelper.onActivityResult(requestCode, resultCode, data);
    }
}
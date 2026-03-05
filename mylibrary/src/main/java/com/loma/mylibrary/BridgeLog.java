package com.loma.mylibrary;

import android.util.Log;

/**
 * Created By Pradeep Rai on 3/5/2026.
 */


/**
 * BridgeLog
 *
 * Drop-in replacement for android.util.Log that:
 *  1. Always calls the real android.util.Log (visible in adb logcat at Info+ level)
 *  2. Forwards every message to Unity's console via UnityPlayer.UnitySendMessage
 *     so you can read logs without ADB during development.
 *
 * Unity side setup (C#):
 * ──────────────────────
 * Create a GameObject named exactly "AndroidLogReceiver" in your first scene
 * and attach this component:
 *
 *   public class AndroidLogReceiver : MonoBehaviour {
 *       void OnAndroidLog(string message) {
 *           Debug.Log("[Native] " + message);
 *       }
 *   }
 *
 * That's it — every BridgeLog call will appear in the Unity console.
 *
 * Production:
 * ───────────
 * Set UNITY_LOGGING_ENABLED = false to disable the UnitySendMessage overhead
 * in a release build while keeping android Log calls.
 */
public class BridgeLog {

    private static final boolean UNITY_LOGGING_ENABLED = false;
    private static final String UNITY_GAME_OBJECT     = "AndroidLogReceiver";
    private static final String UNITY_METHOD           = "OnAndroidLog";

    // ─── Public API ───────────────────────────────────────────────────────────

    public static void i(String tag, String msg) {
        Log.i(tag, msg);
        sendToUnity("I", tag, msg);
    }

    public static void d(String tag, String msg) {
        // Use Log.i so Unity's ADB filter (which blocks Debug level) doesn't eat it
        Log.i(tag, msg);
        sendToUnity("D", tag, msg);
    }

    public static void w(String tag, String msg) {
        Log.w(tag, msg);
        sendToUnity("W", tag, msg);
    }

    public static void e(String tag, String msg) {
        Log.e(tag, msg);
        sendToUnity("E", tag, msg);
    }

    // ─── Internal ─────────────────────────────────────────────────────────────

    private static void sendToUnity(String level, String tag, String msg) {
        if (!UNITY_LOGGING_ENABLED) return;
        try {
            // Reflective call so the library compiles without classes.jar in scope.
            // Unity injects UnityPlayer into the classpath at runtime.
            Class<?> player = Class.forName("com.unity3d.player.UnityPlayer");
            java.lang.reflect.Method send = player.getMethod(
                    "UnitySendMessage", String.class, String.class, String.class);
            send.invoke(null,
                    UNITY_GAME_OBJECT,
                    UNITY_METHOD,
                    "[" + level + "][" + tag + "] " + msg);
        } catch (Exception ignored) {
            // If UnityPlayer is not present (unit tests, standalone debug), silently skip.
        }
    }
}

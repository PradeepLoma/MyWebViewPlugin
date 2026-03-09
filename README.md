# MyWebViewPlugin

An Android library providing a `CustomWebView` with enhanced capabilities, specifically designed to bridge native Android features with web content. This plugin is ideal for Unity projects that need to handle file uploads (especially images) seamlessly within a WebView.

## Features

- **Unity 6 Compatible**: Designed to work as a native Android plugin for Unity.
- **Native Image Picker Integration**: Automatically intercepts `<input type="file" accept="image/*">` elements in web pages and opens a native Android image picker.
- **Base64 Injection**: Selected images are injected back into the web page as base64 data URIs.
- **Dynamic Content Support**: Uses a JavaScript `MutationObserver` to ensure dynamically added file inputs are intercepted.
- **Permission Management**: Automatically handles Android permissions for camera and storage access.
- **Simplified Controller**: Includes `CustomWebViewController` to manage the WebView's lifecycle and navigation from Unity.

## Project Structure

- `:app`: A sample Android application for testing.
- `:mylibrary`: The core library module containing the `CustomWebView` and `CustomWebViewController`.

## Getting Started

### Prerequisites

- Android SDK 24+
- Unity 6 (or compatible version)
- Android Studio for building the plugin

### Building the Library for Unity

To generate the `.aar` file for your Unity project (**KK Mega777**):

1. Open a terminal in the project root.
2. Run the following command:
   ```bash
   ./gradlew :mylibrary:assembleRelease
   ```
3. The generated artifact will be located at:
   `mylibrary/build/outputs/aar/mylibrary-release.aar`

## Unity Integration Guide

### 1. Import the Plugin
Copy the `mylibrary-release.aar` file into your Unity project's folder:
`Assets/Plugins/Android/`

### 2. Usage in C# (Unity 6)
Use the following example to interface with the plugin from your Unity scripts:

```csharp
using UnityEngine;

public class WebViewManager : MonoBehaviour
{
    private AndroidJavaObject controller;

    void Start()
    {
        if (Application.platform == RuntimePlatform.Android)
        {
            using (var activityClass = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
            {
                // Get the current Unity Activity
                AndroidJavaObject activity = activityClass.GetStatic<AndroidJavaObject>("currentActivity");
                
                // Initialize the CustomWebViewController
                controller = new AndroidJavaObject("com.loma.mylibrary.CustomWebViewController", activity);
                
                // Create the WebView instance
                controller.Call("CreateWebView");
                
                // Set Margins if needed (Left, Top, Right, Bottom)
                controller.Call("SetMargins", 0, 0, 0, 0);
                
                // Load your URL
                controller.Call("LoadUrl", "https://your-website.com");
            }
        }
    }

    public void HandleBackPress()
    {
        if (controller != null && controller.Call<bool>("OnBackPressed"))
        {
            // WebView handled the navigation
            Debug.Log("WebView went back");
        }
        else
        {
            // Handle normal app exit or other logic
            Debug.Log("No more history in WebView");
        }
    }
}
```

## ProGuard Configuration

If you are using Minification in Unity (R8/ProGuard), add these rules to your `userSerialization.xml` or `proguard-user.txt`:

```proguard
-keep class com.loma.mylibrary.CustomWebView { *; }
-keep class com.loma.mylibrary.CustomWebViewController { *; }
-keep class com.loma.mylibrary.ImagePickerHelper { *; }
```


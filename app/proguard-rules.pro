# Keep WebView JS bridge methods — R8 will obfuscate these otherwise, silently breaking the bridge
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

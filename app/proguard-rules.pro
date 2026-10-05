# JS bridge: WebView addJavascriptInterface (video sniffing must survive R8)
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# FFmpegKit (native loader + callback classes)
-keep class com.arthenica.ffmpegkit.** { *; }
-dontwarn com.arthenica.ffmpegkit.**

# Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    static *** CREATOR;
}

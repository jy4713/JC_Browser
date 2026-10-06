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

# libtorrent4j (SWIG JNI 바인딩 — 난독화/제거 금지)
-keep class org.libtorrent4j.** { *; }
-dontwarn org.libtorrent4j.**

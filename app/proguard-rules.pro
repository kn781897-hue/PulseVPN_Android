# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# ProGuard rules for PulseVPN

# Keep Xray/V2Ray classes
-keep class libv2ray.** { *; }
-keep class com.LondonX.tun2socks.** { *; }

# Keep Firebase classes (though many are included automatically)
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.ktx.**
-dontwarn com.google.firebase.analytics.ktx.**
-dontwarn com.google.firebase.database.ktx.**

# Keep models if any (e.g. from Firebase)
-keep class com.nikolay.myvpn.models.** { *; }

# General optimizations
-optimizationpasses 5
-allowaccessmodification
-dontpreverify

# Keep the line number information for debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
-renamesourcefileattribute SourceFile

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

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
-dontobfuscate

# Prevent R8 from stripping Media3 MediaSource factories loaded via reflection
-keep class androidx.media3.exoplayer.hls.HlsMediaSource$Factory { *; }
-keep class androidx.media3.exoplayer.dash.DashMediaSource$Factory { *; }
-keep class androidx.media3.exoplayer.rtsp.RtspMediaSource$Factory { *; }
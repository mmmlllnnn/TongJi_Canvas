# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep ML Kit classes
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }

# Keep CameraX classes
-keep class androidx.camera.** { *; }

# Keep Compose classes
-keep class androidx.compose.** { *; }

# Keep WebView classes
-keep class androidx.webkit.** { *; }

# Keep OkHttp classes
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Keep Navigation classes
-keep class androidx.navigation.** { *; }

# Keep Lifecycle classes
-keep class androidx.lifecycle.** { *; }

# Keep Activity classes
-keep class androidx.activity.** { *; }

# Keep Core classes
-keep class androidx.core.** { *; }

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
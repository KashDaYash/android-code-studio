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

# ============================================================
# Keep OpenJDK / javac tools classes used by Java LSP & project model
# Fixes: ClassNotFoundException: openjdk.tools.javac.file.CacheFSInfo
# ============================================================
-keep class openjdk.tools.javac.** { *; }
-keep class openjdk.tools.** { *; }
-keep class com.sun.tools.javac.** { *; }
-keep class com.sun.tools.** { *; }
-keep class openjdk.** { *; }

# Keep the singleton wrapper used by the IDE
-keep class com.tom.rv2ide.javac.services.fs.CacheFSInfoSingleton { *; }
-keep class com.tom.rv2ide.javac.services.** { *; }

# Prevent R8 from stripping reflective / Context-registered javac factories
-keepclassmembers class * {
    public static *** preRegister(...);
    public static *** instance(...);
}

# Consumer rules for javac-services
# Ensures openjdk.tools.javac classes survive R8 and are available at runtime

-keep class openjdk.tools.javac.** { *; }
-keep class openjdk.tools.** { *; }
-keep class com.sun.tools.javac.** { *; }
-keep class openjdk.** { *; }

-keep class com.tom.rv2ide.javac.services.** { *; }
-keep class com.tom.rv2ide.javac.services.fs.CacheFSInfoSingleton { *; }

-keepclassmembers class * {
    public static *** preRegister(...);
    public static *** instance(...);
}

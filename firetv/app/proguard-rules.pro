# R8 rules for the release build.

# org.json ships in the Android framework (android.jar); the app only *uses* it, so there is
# nothing of it to shrink. These rules keep R8 from warning about the test-only org.json
# artefact and keep reflective-looking access to the framework classes intact.
-dontwarn org.json.**
-keep class org.json.** { *; }

# Strip Log.i / Log.d (the WIZSYNC chatter) from release builds. R8 removes calls whose
# return value is unused once they are declared side-effect free. Log.w / Log.e stay.
-assumenosideeffects class android.util.Log {
    public static int i(...);
    public static int d(...);
    public static int v(...);
}

# Keep stack traces readable in crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The service and activity are referenced from the manifest; AGP keeps them, but be explicit
# about the MediaProjection callback subclasses the framework calls into.
-keep class * extends android.media.projection.MediaProjection$Callback { *; }

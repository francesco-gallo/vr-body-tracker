# Project-specific ProGuard rules.

# Gson uses reflection to (de)serialize AppConfig from SharedPreferences.
# Without these rules, R8 would rename fields/enum constants and silently
# break persisted user settings across obfuscated builds.
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.vrproject.bodytracker.AppConfig { *; }
-keep class com.vrproject.bodytracker.JointOffset { *; }
-keep class com.vrproject.bodytracker.TrackerModelType { *; }
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-dontwarn sun.misc.**


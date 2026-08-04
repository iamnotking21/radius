# Radius Android — R8 rules.
#
# !! UNVERIFIED !! Never run. A release build has never been produced (no JDK, blocker B5).
#
# Deliberately close to empty. R8 handles Compose, Hilt and Kotlin without help these days, and
# a pile of speculative -keep rules is how a release build silently stops shrinking.
#
# Add a rule only when a release build actually breaks, and write down what broke.

# Kotlin coroutines: the debug agent probes for this class and R8's warning is noise.
-dontwarn kotlinx.coroutines.debug.**

# SQLCipher ships native code loaded reflectively by the JNI layer.
-keep class net.zetetic.database.** { *; }
-keep class net.sqlcipher.** { *; }

# Keep the shared core's public contract surface readable in crash reports. This module's API is a
# contract consumed by two clients; an obfuscated stack trace from it costs more than the bytes.
-keepnames class com.radius.shared.** { *; }

# NEVER add a rule that keeps debug logging in release. Ephemeral ids must not reach logcat in a
# shipped build — see RawSighting.toString().
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

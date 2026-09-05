# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# disable obfuscation
-dontobfuscate

# Keep JNI interface
-keep class com.osfans.trime.core.* { *; }

# sherpa-onnx (local voice input / SenseVoice): its Kotlin data classes are read/written by name
# from native (JNI) code, not just from our Kotlin call sites, so R8 must not strip/rename fields.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }

# remove kotlin null checks
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkNotNull(...);
    static void checkExpressionValueIsNotNull(...);
    static void checkNotNullExpressionValue(...);
    static void checkReturnedValueIsNotNull(...);
    static void checkFieldIsNotNull(...);
    static void checkParameterIsNotNull(...);
    static void checkNotNullParameter(...);
}

# WorkManager instantiates its built-in InputMerger implementations
# (OverwritingInputMerger/ArrayCreatingInputMerger) via reflection on every WorkRequest, not just
# chained ones. The library's own consumer rule only keeps the class (`-keep class * extends
# androidx.work.InputMerger`), which stops renaming/removal but not shrinking of the no-arg
# constructor, so R8 strips it and every WorkManager job (voice model download, background sync)
# fails at runtime with NoSuchMethodException before the worker even starts.
-keep class * extends androidx.work.InputMerger {
    public <init>();
}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

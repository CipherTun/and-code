# Project-specific R8 rules.
-keepattributes *Annotation*,Signature,SourceFile,LineNumberTable

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Compile-time-only Error Prone annotations referenced by Tink.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi

# kotlinx.serialization.
#
# Replaces the Gson rules left behind by the migration (Gson is no longer a dependency). R8 cannot
# see the reflective link from a @Serializable class to its Companion and generated $serializer, so
# lookups that are not resolved statically at the call site — generic and polymorphic types in
# particular — need these keeps to survive minification. Rules are the ones published with the
# library.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-dontnote kotlinx.serialization.**

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# AndroidCode REST payloads are matched by @SerialName, and persisted JSON must keep stable field names
# across app updates, so these must not be renamed.
-keep class com.yugahashimoto.androidcode.core.api.** { *; }
-keep class com.yugahashimoto.androidcode.data.connection.ConnectionProfile { *; }
-keep class com.yugahashimoto.androidcode.data.settings.Draft { *; }
-keep class com.yugahashimoto.androidcode.runtime.local.LocalRuntimeMetadata { *; }

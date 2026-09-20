# =============================================================================
# iTellyTV — ProGuard / R8 keep rules
#
# `proguard-android-optimize.txt` is the AGP default; this file
# adds the project-specific keeps that R8 cannot infer.
# =============================================================================

# --- General Android ---
# Keep the default constructor of Activities, Services, Receivers —
# R8 needs them to instantiate by reflection from the manifest.
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}
-keepclassmembers class * extends android.app.Service {
    public void *(android.content.Intent);
}

# Keep custom Application subclasses' no-arg constructors.
-keep public class * extends android.app.Application

# --- Kotlin metadata ---
# Kotlin reflection / coroutines introspect on @Metadata.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions
-keep class kotlin.Metadata { *; }

# kotlinx.coroutines internal classes are accessed by name in some
# ContinuationImpl paths; keep the debug metadata so stack traces
# remain useful when minified.
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# --- kotlinx.serialization ---
# @Serializable classes (none yet in iTellyTV but we use the
# runtime for ChannelEntity Mappers) need their generated
# $$serializer companion preserved.
-keepattributes RuntimeVisibleAnnotations
-keepclassmembers @kotlinx.serialization.Serializable class * {
    static **$* *;
    static <fields>;
    public synthetic <methods>;
}
-keep,includedescriptorclasses class **$$serializer { *; }

# --- Room ---
# Room generates an `_Impl` class for each @Database at compile
# time and references it via reflection from Room.databaseBuilder().
# R8 must not strip or rename these.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class **_Impl { *; }
-keep @androidx.room.Database class * { *; }
-keep @androidx.room.Dao class * { *; }
-dontwarn androidx.room.paging.**

# --- Media3 / ExoPlayer ---
# Media3's DefaultHttpDataSource reads system properties by name
# and uses a service loader; keep the relevant surface.
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.common.** { *; }
-keep class androidx.media3.datasource.** { *; }
-keep class androidx.media3.session.** { *; }
-dontwarn androidx.media3.**

# --- AndroidX Leanback ---
# Leanback's BrowseSupportFragment / DetailsSupportFragment rely on
# fragment-style class-name lookup at runtime.
-keep class androidx.leanback.** { *; }
-dontwarn androidx.leanback.**

# --- Foreground Service + MediaSession ---
# PlaybackService is referenced from the manifest (intent-filter on
# androidx.media3.session.MediaSessionService) but is not referenced
# from any application code yet — R8's whole-program analyser sees
# it as dead code and would happily strip the class and its manifest
# entry. Keep the class entry so the manifest entry survives.
-keep class com.example.itellytv.player.PlaybackService {
    public <init>();
}

# --- Parcelable ---
# ChannelEntity implements Parcelable via the writeToParcel /
# CREATOR pattern; R8 needs to keep the CREATOR field and the
# writeToParcel signature exactly.
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
    public void writeToParcel(android.os.Parcel, int);
    public int describeContents();
}

# --- Native (okio / Media3 codecs) ---
# We don't use OkHttp but Media3 ships okio and native decoders
# via JNI; suppress missing-class warnings from optional deps.
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Logging ---
# Strip verbose INFO/DEBUG logs at runtime to shave a few KB.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}

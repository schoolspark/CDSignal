# --- Android Application Components ---
# Prevents R8 from renaming classes that the Android OS needs to call by name.
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.Application
-keep public class * extends androidx.lifecycle.ViewModel

# --- App Utilities ---
# Keep our utility classes, like AudioEngine and NetworkEngine, from being obfuscated.
-keep class in.chinmoydas.signal.utils.** { *; }

# --- App Data/API Models ---
# Keep all data classes used by Gson/Retrofit.
-keep class in.chinmoydas.signal.LoginResponse { *; }
-keep class in.chinmoydas.signal.PeerResponse { *; }
-keep class in.chinmoydas.signal.ChannelResponse { *; }
-keep class in.chinmoydas.signal.ChannelUser { *; }
-keep class in.chinmoydas.signal.ResetResponse { *; }

# --- Room & Other Data Layer ---
-keep class in.chinmoydas.signal.data.** { *; }

# --- ViewModel Layer ---
-keep class in.chinmoydas.signal.viewmodel.** { *; }

# --- General Coroutines Rule ---
-keepclassmembers class * extends kotlin.coroutines.jvm.internal.SuspendLambda { <methods>; }

# --- Retrofit & OkHttp ---
-keep,allowobfuscation @retrofit2.http.POST class *
-keep,allowobfuscation @retrofit2.http.GET class *
-keep,allowobfuscation @retrofit2.http.PUT class *
-keep,allowobfuscation @retrofit2.http.DELETE class *
-keep,allowobfuscation @retrofit2.http.PATCH class *
-keepclassmembers interface * { @retrofit2.http.* *; }
-dontwarn retrofit2.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# --- Gson ---
-keep class com.google.gson.stream.** { *; }
-keep class com.google.gson.** { *; }

# --- Room ---
-keep class androidx.room.RoomDatabase { *; }
-keepclassmembers class **_Impl {
    public <init>(...);
}

# --- Coroutines ---
-keepclassmembers class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepclassmembers class kotlinx.coroutines.flow.** { *; }
-keep class kotlinx.coroutines.CompletedExceptionally { *; }
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# --- ZXing & JourneyApps Scanner ---
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.journeyapps.barcodescanner.**
-dontwarn com.google.zxing.**

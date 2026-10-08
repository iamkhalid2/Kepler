# Consumer rules shipped by AndroidX, Room 3, WorkManager, Media3, Ktor, OkHttp, kotlinx.serialization and Coil
# already cover their own reflection. The rules below are belt-and-braces for the pieces Kepler loads by name,
# plus readable crash traces.

# Room instantiates the generated database by name.
-keep class app.kepler.core.database.db.*_Impl { <init>(); }
-keep class * extends androidx.room3.RoomDatabase { <init>(); }

# WorkManager creates the worker by class name from its persisted request.
-keep class app.kepler.engine.tts.NarrationWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }

# Android and Media3 bind the playback service by name (manifest, MediaBrowser clients, Android Auto).
-keep class app.kepler.engine.playback.PlaybackService { *; }

# Readium navigator fragments are re-created by class name after process death.
-keep class org.readium.r2.navigator.** extends androidx.fragment.app.Fragment { <init>(...); }

# Serializers resolved at run time (Ktor content negotiation looks them up through the Companion).
-keepclassmembers @kotlinx.serialization.Serializable class app.kepler.** {
    static ** Companion;
    *** serializer(...);
}
-keepclasseswithmembers class app.kepler.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations
-renamesourcefileattribute SourceFile

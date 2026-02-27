# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# ═══════════════════════════════════════════════════════════════
# Media3 — keep only public session/player API
# ═══════════════════════════════════════════════════════════════
-keep class androidx.media3.session.MediaSession { *; }
-keep class androidx.media3.session.MediaSessionService { *; }
-keep class androidx.media3.session.MediaController { *; }
-keep class androidx.media3.session.SessionCommand { *; }
-keep class androidx.media3.session.SessionResult { *; }
-keep class androidx.media3.common.MediaItem { *; }
-keep class androidx.media3.common.MediaItem$* { *; }
-keep class androidx.media3.common.MediaMetadata { *; }
-keep class androidx.media3.common.MediaMetadata$* { *; }
-keep class androidx.media3.common.PlaybackException { *; }
-keep class androidx.media3.common.Player$* { *; }
-keep class androidx.media3.common.AudioAttributes { *; }
-keep class androidx.media3.common.AudioAttributes$* { *; }
-keep class androidx.media3.common.C { *; }
-dontwarn androidx.media3.**

# ═══════════════════════════════════════════════════════════════
# Room — keep entity fields and constructors only
# ═══════════════════════════════════════════════════════════════
-keep class * extends androidx.room.RoomDatabase
-keepclassmembers @androidx.room.Entity class * {
    @androidx.room.PrimaryKey <fields>;
    @androidx.room.ColumnInfo <fields>;
    <init>();
}

# ═══════════════════════════════════════════════════════════════
# Kotlinx Serialization — keep serializer companions
# ═══════════════════════════════════════════════════════════════
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers @kotlinx.serialization.Serializable class com.sonicmusic.app.domain.model.** {
    <init>(...);
    <fields>;
    *** Companion;
    *** serializer(...);
}
-keepclassmembers @kotlinx.serialization.Serializable class com.sonicmusic.app.data.remote.model.** {
    <init>(...);
    <fields>;
    *** Companion;
    *** serializer(...);
}

# Keep ContentType enum for serialization
-keepclassmembers class com.sonicmusic.app.domain.model.ContentType {
    <init>(...);
    <fields>;
}
# Keep Song for serialization
-keepclassmembers class com.sonicmusic.app.domain.model.Song {
    <init>(...);
    <fields>;
}

# ═══════════════════════════════════════════════════════════════
# Hilt
# ═══════════════════════════════════════════════════════════════
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.HiltAndroidApp

# Keep ViewModel constructors for Hilt
-keepclassmembers @dagger.hilt.android.lifecycle.HiltViewModel class * {
    @javax.inject.Inject <init>(...);
}

# ═══════════════════════════════════════════════════════════════
# Kotlin metadata
# ═══════════════════════════════════════════════════════════════
-keepattributes Signature
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeInvisibleAnnotations

# ═══════════════════════════════════════════════════════════════
# Gson (for Retrofit)
# ═══════════════════════════════════════════════════════════════
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ═══════════════════════════════════════════════════════════════
# NewPipe Extractor — keep only public API
# ═══════════════════════════════════════════════════════════════
-keep class org.schabi.newpipe.extractor.stream.** { *; }
-keep class org.schabi.newpipe.extractor.services.youtube.** { *; }
-keep class org.schabi.newpipe.extractor.NewPipe { *; }
-keep class org.schabi.newpipe.extractor.ServiceList { *; }
-keep class org.schabi.newpipe.extractor.StreamingService { *; }
-keep class org.schabi.newpipe.extractor.Info { *; }
-keep class org.schabi.newpipe.extractor.InfoItem { *; }
-keep class org.schabi.newpipe.extractor.InfoItem$* { *; }
-keep class org.schabi.newpipe.extractor.search.** { *; }
-keep class org.schabi.newpipe.extractor.playlist.** { *; }
-keep class org.schabi.newpipe.extractor.channel.** { *; }
-keep class org.schabi.newpipe.extractor.kiosk.** { *; }
-keep class org.schabi.newpipe.extractor.linkhandler.** { *; }
-keep class org.schabi.newpipe.extractor.ListExtractor { *; }
-keep class org.schabi.newpipe.extractor.ListExtractor$* { *; }
-keep class org.schabi.newpipe.extractor.Page { *; }
-keep class org.schabi.newpipe.extractor.Downloader { *; }
-keep class org.schabi.newpipe.extractor.localization.** { *; }
-keep class org.schabi.newpipe.extractor.exceptions.** { *; }
-dontwarn org.schabi.newpipe.extractor.**

# Rhino JavaScript engine (required by NewPipe)
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**

# JSoup (required by NewPipe)
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# ═══════════════════════════════════════════════════════════════
# Strip verbose/debug/info logs in release builds
# ═══════════════════════════════════════════════════════════════
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# ═══════════════════════════════════════════════════════════════
# Coil (Image Loading)
# ═══════════════════════════════════════════════════════════════
-keep class coil.** { *; }
-dontwarn coil.**
-keep class okio.** { *; }
-dontwarn okio.**

# ═══════════════════════════════════════════════════════════════
# DataStore (Preferences)
# ═══════════════════════════════════════════════════════════════
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

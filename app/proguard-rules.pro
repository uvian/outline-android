# Keep Kotlinx Serialization models & generated serializers
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.outline.notes.data.remote.**$$serializer { *; }
-keepclassmembers class com.outline.notes.data.remote.** {
    *** Companion;
}
-keepclasseswithmembers class com.outline.notes.data.remote.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.outline.notes.ui.components.**$$serializer { *; }
-keepclassmembers class com.outline.notes.ui.components.** {
    *** Companion;
}
-keepclasseswithmembers class com.outline.notes.ui.components.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep WebView JS Bridge class & methods (public visibility required by Android WebView)
-keep class com.outline.notes.ui.components.EditorWebViewPool$* {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Room entities, DAOs, and Database
-keep class com.outline.notes.data.local.** { *; }

# Keep WorkManager Workers & AppWidgetProviders
-keep class com.outline.notes.data.sync.SyncWorker { *; }
-keep class com.outline.notes.widget.** { *; }

# OkHttp optional platform classes
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

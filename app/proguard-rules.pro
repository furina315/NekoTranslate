# NekoTranslate release (R8) rules.
# JNI bridge rules (LlamaNative natives, TokenCallback.onToken called from C++) ship with :llama
# in llama/consumer-rules.pro and are applied automatically; repeated here defensively.
-keep class com.nekohome.translate.llama.LlamaNative { *; }
-keep interface com.nekohome.translate.llama.TokenCallback { *; }
-keepclassmembers class * implements com.nekohome.translate.llama.TokenCallback {
    boolean onToken(byte[]);
}

# ViewModels are created reflectively by the default ViewModelProvider factory.
-keepclassmembers class * extends androidx.lifecycle.AndroidViewModel {
    <init>(android.app.Application);
}
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>();
}

# DataStore Preferences uses a shaded protobuf-lite that reads message fields reflectively.
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite {
    <fields>;
}

# Keep source file / line numbers for readable crash stack traces (names stay obfuscated).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# JNI entry points and the callback interface are looked up by name from native code.
-keep class com.nekohome.translate.llama.LlamaNative { *; }
-keep interface com.nekohome.translate.llama.TokenCallback { *; }

# Keep rules for the JNI bridge, applied to the app's R8 run.
# JNI entry points are bound by name (Java_com_nekohome_translate_llama_LlamaNative_*).
-keep class com.nekohome.translate.llama.LlamaNative { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# llama_jni.cpp calls GetObjectClass(callback) + GetMethodID("onToken", "([B)Z") on whatever
# object is passed as the callback, so the interface AND every implementation (including
# desugared lambdas) must keep onToken with its exact name and signature.
-keep interface com.nekohome.translate.llama.TokenCallback { *; }
-keepclassmembers class * implements com.nekohome.translate.llama.TokenCallback {
    boolean onToken(byte[]);
}

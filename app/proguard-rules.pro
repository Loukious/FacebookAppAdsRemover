# Keep module entry class (extends XposedModule, needs public no-arg constructor).
-keep class * extends io.github.libxposed.api.XposedModule { public <init>(); }

# Rewrite java_init.list when entry classes are obfuscated.
-adaptresourcefilecontents META-INF/xposed/java_init.list

# dexplore relies heavily on reflection.
-keep class io.github.neonorbit.** { *; }

# OkHttp / Okio keep their own consumer rules; nothing extra needed.

# ---------------------------------------------------------------------------
# Release shrinking is for the settings UI's libraries only (Compose,
# AndroidX). Everything that runs inside Facebook is kept byte-for-byte in
# name and shape: hooks are found and called by reflection, Xposed loads the
# entry class by name, and DexKit / OkHttp / jsoup are used from hook code.
-dontobfuscate
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-keep class tn.loukious.facebookappadsremover.** { *; }
-keep class io.github.libxposed.** { *; }
-keep class org.luckypray.dexkit.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class org.jsoup.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-dontwarn org.jspecify.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

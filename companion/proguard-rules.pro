# Dashwheel Companion release keep rules. Compose, coroutines, zxing and
# kotlinx.serialization ship their own consumer rules.
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlinx.coroutines.**
# Road watch's detector (LiteRT): its Java side is called from native code.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
# LocationListener: the empty provider/status overrides must survive R8, or
# Android 10 phones crash with AbstractMethodError (default methods only from
# Android 11; issue #156).
-keepclassmembers class * implements android.location.LocationListener { public <methods>; }

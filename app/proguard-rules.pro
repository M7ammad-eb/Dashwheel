# Dashwheel release keep rules.
#
# Most libraries ship their own consumer rules (Compose, MapLibre, OkHttp,
# coroutines). The entries below cover the ones that reach into native code or
# reflection and are not fully covered by consumer rules.

# Filament (Car3D.kt): JNI callbacks into Java by name.
-keep class com.google.android.filament.** { *; }
-dontwarn com.google.android.filament.**

# MapLibre GL + navigation: native bridge and Gson-mapped route models.
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**

# Keep the app's own JSON-facing enums by name: tile kinds and theme modes are
# persisted with Enum.name / valueOf, which R8 would otherwise rename.
-keepclassmembers enum com.openauto.dash.** { *; }

# Kotlin metadata / coroutines internals that R8 may warn about.
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlinx.coroutines.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# The QF car app's Parcelables, received in its broadcasts and read back by
# class name (CarBox): keep the name, the CREATOR and the fields.
-keep class com.qf.vehicle.entity.** { *; }

# The tile displays' helper runs by name in a process of its own (app_process, SystemDisplays).
-keep class com.openauto.dash.SystemDisplayHelper { public static void main(java.lang.String[]); }

# Run by name through the privileged shell (app_process) when the home window was left floating (StuckSplit).
-keep class com.openauto.dash.LaunchParamsReset { public static void main(java.lang.String[]); }

# Run by name through the privileged shell (app_process) to allow a USB device without the dialog (UsbTpms, UsbObd).
-keep class com.openauto.dash.UsbGrant { public static void main(java.lang.String[]); }

# LocationListener: onStatusChanged / onProviderEnabled / onProviderDisabled are
# default methods only from Android 11. R8 drops the empty overrides as
# redundant, and an Android 10 unit then calls the missing method and crashes
# with AbstractMethodError (issue #156). Keep every override.
-keepclassmembers class * implements android.location.LocationListener { public <methods>; }

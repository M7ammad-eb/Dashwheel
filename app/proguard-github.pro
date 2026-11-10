# Keep rules for the GitHub edition only (see the "github" flavor in build.gradle.kts).
# The Play edition does not ship these libraries.

# dadb (pure-Kotlin ADB client used by the priv-app self-install).
# Its classes live in the package `dadb` (the Maven group is dev.mobile): the old rule matched nothing.
-keep class dadb.** { *; }
-dontwarn dadb.**

# Shizuku (ShizukuShell.kt): its binder API, and the shell service Shizuku starts by
# name in a process of its own, with the AIDL stub it answers through.
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-keep class com.openauto.dash.ShizukuShellService { *; }
-keep class com.openauto.dash.IShizukuShell** { *; }

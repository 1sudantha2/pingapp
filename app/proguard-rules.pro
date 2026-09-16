# R8 / ProGuard rules for Ping Booster

# The background engine is small and hot: keep it intact so R8 does not
# over-optimise the socket loop, and keep the entry points Android inflates.
-keep class com.pingbooster.app.PingService { *; }
-keep class com.pingbooster.app.PingTileService { *; }
-keep class com.pingbooster.app.StatusWidgetProvider { *; }

# Silence warnings for optional platform classes.
-dontwarn org.jetbrains.annotations.**

# Keep line numbers for readable crash reports, drop the rest of the debug info.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

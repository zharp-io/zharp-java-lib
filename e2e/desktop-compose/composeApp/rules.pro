# Zharp uses OpenTelemetry's SDK, loaded through ServiceLoader and reflection.
-keep class io.zharp.** { *; }
-keep class io.opentelemetry.** { *; }
-dontwarn io.opentelemetry.**
-dontwarn io.zharp.**
-dontwarn com.google.auto.value.**
-dontwarn okhttp3.**
-dontwarn okio.**

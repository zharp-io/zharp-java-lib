package io.zharp.internal;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;
import java.lang.reflect.Method;
import java.util.Map;

/** {@code Zharp.init()}: OpenTelemetry's SDK in this process, configured as the agent configures its own. */
public final class LibraryMode {
    private LibraryMode() {}

    public static synchronized void start(Map<String, String> options) {
        if (ZharpRuntime.agentPresent()) {
            ZharpCore.announce("The Zharp agent is running: Zharp.init() isn't needed, and the agent's settings are used.");
            return;
        }
        if (ZharpRuntime.core() != null) {
            ZharpCore.announce("Zharp.init() was called again — the first call's settings stay in effect.");
            return;
        }
        if (Settings.disabled()) {
            ZharpCore.announce("Disabled by ZHARP_DISABLED — nothing will be sent");
            return;
        }
        Settings settings = Settings.from(Settings.of(options));
        if (!settings.hasKey()) {
            ZharpCore.announce("No key found — pass Zharp.init(ZharpOptions.builder().key(…)) or set ZHARP_KEY. Nothing will be sent.");
            return;
        }
        BuildInfo.init(LibraryMode.class.getClassLoader());
        ZharpCore core = new ZharpCore(settings);
        ZharpRuntime.install(core);

        OpenTelemetrySdk sdk;
        try {
            sdk = configured(core).setResultAsGlobal().build().getOpenTelemetrySdk();
        } catch (IllegalStateException alreadySet) {
            // Something else registered a global OpenTelemetry first: Zharp's SDK still runs, on its own.
            sdk = configured(core).build().getOpenTelemetrySdk();
        }
        core.attach(sdk);

        installAppender("io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender", sdk);
        installAppender("io.opentelemetry.instrumentation.log4j.appender.v2_17.OpenTelemetryAppender", sdk);
        if (settings.logCorrelation && LogbackStamp.available()) LogbackStamp.install();
        if (settings.captureUncaught) UncaughtHandler.install(core);
        String build = BuildInfo.buildId();
        ZharpCore.announce("Application monitoring active" + (settings.tracing ? "" : " (logs only)") + " — " + settings.describeTarget() + " (library mode" + (build != null ? ", build " + build : "") + ")");
    }

    private static AutoConfiguredOpenTelemetrySdkBuilder configured(ZharpCore core) {
        AutoConfiguredOpenTelemetrySdkBuilder builder = AutoConfiguredOpenTelemetrySdk.builder();
        ZharpAutoConfiguration.customize(builder, core);
        return builder;
    }

    /** OpenTelemetry's Logback / Log4j appender, when the app has it: their records go to Zharp too. */
    private static void installAppender(String className, OpenTelemetry sdk) {
        try {
            Class<?> appender = Class.forName(className, true, LibraryMode.class.getClassLoader());
            Method install = appender.getMethod("install", OpenTelemetry.class);
            install.invoke(null, sdk);
        } catch (ClassNotFoundException e) {
            // Not on the classpath.
        } catch (Throwable e) {
            ZharpCore.announce("Could not connect " + className + ": " + e);
        }
    }
}

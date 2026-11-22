package io.zharp.spring;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.zharp.internal.BuildInfo;
import io.zharp.internal.LogbackStamp;
import io.zharp.internal.Settings;
import io.zharp.internal.UncaughtHandler;
import io.zharp.internal.Version;
import io.zharp.internal.ZharpCore;
import io.zharp.internal.ZharpRuntime;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Zharp on top of OpenTelemetry's Spring Boot starter, no agent: the same processors, exporters
 * and exception handling as the agent (one configuration, io.zharp.internal.ZharpAutoConfiguration),
 * uncaught exceptions on any thread, and the stamp on Logback's lines.
 */
@AutoConfiguration(beforeName = "io.opentelemetry.instrumentation.spring.autoconfigure.OpenTelemetryAutoConfiguration")
public class ZharpAutoConfiguration {
    @Bean
    public ZharpCore zharpCore(Environment environment) {
        Settings settings = ZharpSpringSettings.from(environment);
        ZharpCore core = new ZharpCore(settings);
        if (settings.hasKey() && !Settings.disabled()) ZharpRuntime.install(core);
        else ZharpEnvironmentPostProcessor.announceMissingKey(settings);
        return core;
    }

    @Bean
    public AutoConfigurationCustomizerProvider zharpOpenTelemetryCustomizer(ZharpCore core) {
        return customizer -> {
            if (core.settings.hasKey() && !Settings.disabled()) io.zharp.internal.ZharpAutoConfiguration.customize(customizer, core);
        };
    }

    /** Once the app is up: reports go through the starter's SDK, and the app's own output is stamped. */
    @Bean
    public ApplicationListener<ApplicationStartedEvent> zharpStarted(ZharpCore core, ObjectProvider<OpenTelemetry> openTelemetry) {
        return event -> {
            Settings s = core.settings;
            if (!s.hasKey() || Settings.disabled() || core.sdk() != null) return;
            OpenTelemetry otel = openTelemetry.getIfAvailable();
            if (!(otel instanceof OpenTelemetrySdk)) return;
            core.attach((OpenTelemetrySdk) otel);
            BuildInfo.init(event.getSpringApplication().getClassLoader());
            if (s.captureUncaught) UncaughtHandler.install(core);
            if (s.logCorrelation && LogbackStamp.available()) LogbackStamp.install();
            String build = BuildInfo.buildId();
            ZharpCore.announce("Application monitoring active" + (s.tracing ? "" : " (logs only)") + " — " + s.describeTarget()
                    + (s.logs ? ", logs on" : "") + (build != null ? ", build " + build : "") + " (Spring Boot starter " + Version.VERSION + ")");
        };
    }
}

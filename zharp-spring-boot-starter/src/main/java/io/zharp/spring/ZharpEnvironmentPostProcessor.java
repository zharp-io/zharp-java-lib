package io.zharp.spring;

import io.zharp.internal.Settings;
import io.zharp.internal.ZharpCore;
import io.zharp.internal.ZharpRuntime;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Before OpenTelemetry's Spring Boot starter reads its {@code otel.*} properties: Zharp's
 * settings become them — where to send, OTLP over HTTP, traces and logs (no metrics), the W3C
 * propagators, the service name. With no key, or ZHARP_DISABLED, the SDK is turned off.
 */
public final class ZharpEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    static final String SOURCE = "zharp";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(SOURCE)) return;
        Settings s = ZharpSpringSettings.from(environment);
        ZharpRuntime.settings(s);
        Map<String, Object> p = new HashMap<>();
        if (Settings.disabled() || !s.hasKey()) {
            p.put("otel.sdk.disabled", "true");
        } else {
            p.put("otel.exporter.otlp.protocol", "http/protobuf");
            p.put("otel.exporter.otlp.traces.endpoint", s.endpoint + "/v1/traces");
            p.put("otel.exporter.otlp.logs.endpoint", s.endpoint + "/v1/logs");
            p.put("otel.exporter.otlp.compression", "gzip");
            p.put("otel.traces.exporter", s.tracing ? "otlp" : "none");
            p.put("otel.logs.exporter", "otlp");
            p.put("otel.metrics.exporter", "none");
            p.put("otel.propagators", "tracecontext,baggage");
            p.put("otel.service.name", s.service);
        }
        // First: Zharp's settings win over otel.* ones, as with the agent.
        environment.getPropertySources().addFirst(new MapPropertySource(SOURCE, p));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    static void announceMissingKey(Settings s) {
        ZharpCore.announce(Settings.disabled()
                ? "Disabled by ZHARP_DISABLED — nothing will be sent"
                : "No key found — set zharp.key (or ZHARP_KEY), the application's ingest key. Nothing will be sent.");
    }
}

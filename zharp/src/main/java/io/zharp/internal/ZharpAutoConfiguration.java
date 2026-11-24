package io.zharp.internal;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.logs.internal.SdkLoggerProviderUtil;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.internal.SdkTracerProviderUtil;
import java.util.HashMap;
import java.util.Map;

/**
 * OpenTelemetry's SDK, configured for Zharp — one configuration for the agent, the Spring Boot
 * starter and library mode: where to send and with what key, under which service, OTLP over HTTP
 * for traces and logs (no metrics), the W3C propagators, Zharp's processors and exporters, and how
 * exceptions are written.
 */
public final class ZharpAutoConfiguration {
    private ZharpAutoConfiguration() {}

    public static void customize(AutoConfigurationCustomizer c, ZharpCore core) {
        Settings s = core.settings;
        // Zharp's settings win over OTEL_* ones: the data goes to Zharp, under Zharp's key.
        c.addPropertiesCustomizer(config -> {
            Map<String, String> p = new HashMap<>();
            p.put("otel.exporter.otlp.protocol", "http/protobuf");
            p.put("otel.exporter.otlp.traces.protocol", "http/protobuf");
            p.put("otel.exporter.otlp.logs.protocol", "http/protobuf");
            p.put("otel.exporter.otlp.traces.endpoint", s.endpoint + "/v1/traces");
            p.put("otel.exporter.otlp.logs.endpoint", s.endpoint + "/v1/logs");
            p.put("otel.exporter.otlp.compression", "gzip");
            p.put("otel.traces.exporter", s.tracing ? "otlp" : "none");
            p.put("otel.logs.exporter", "otlp");
            p.put("otel.metrics.exporter", "none");
            p.put("otel.propagators", "tracecontext,baggage");
            p.put("otel.service.name", s.service);
            if (s.tracing) {
                p.put("otel.traces.sampler", "parentbased_traceidratio");
                p.put("otel.traces.sampler.arg", String.valueOf(s.sampleRate));
            }
            return p;
        });
        c.addResourceCustomizer((resource, config) -> resource.merge(resource(s)));
        c.addTracerProviderCustomizer((builder, config) -> {
            resolver(() -> SdkTracerProviderUtil.setExceptionAttributeResolver(builder, new ZharpExceptionResolver()));
            if (!s.tracing) builder.setSampler(new RecordOnlySampler());
            return builder.addSpanProcessor(new ZharpSpanProcessor(core));
        });
        c.addLoggerProviderCustomizer((builder, config) -> {
            resolver(() -> SdkLoggerProviderUtil.setExceptionAttributeResolver(builder, new ZharpExceptionResolver()));
            return builder;
        });
        c.addLogRecordProcessorCustomizer((processor, config) -> new ZharpLogProcessor(core, processor));
        if (s.desktop) {
            // Written to disk before sending, sent again on the next launch if it didn't arrive.
            c.addSpanExporterCustomizer((exporter, config) -> new ZharpSpanExporter(new DesktopSender.Spans(Desktop.sender(s))));
            c.addLogRecordExporterCustomizer((exporter, config) -> new ZharpLogExporter(new DesktopSender.Logs(Desktop.sender(s))));
        } else {
            c.addSpanExporterCustomizer((exporter, config) -> new ZharpSpanExporter(authorized(exporter, s)));
            c.addLogRecordExporterCustomizer((exporter, config) -> new ZharpLogExporter(authorized(exporter, s)));
        }
        if (!s.tracing) c.addPropagatorCustomizer((propagator, config) -> new ExtractOnlyPropagator(propagator));
    }

    /**
     * Exceptions written Zharp's way. An SDK older than 1.50 (a framework's pinned version) has no
     * hook for it: OpenTelemetry's own writing is used, and a failure is matched to its log lines less exactly.
     */
    private static void resolver(Runnable install) {
        try {
            install.run();
        } catch (LinkageError olderSdk) {
            ZharpCore.announce("OpenTelemetry SDK older than 1.50 on the classpath: import io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom to align it.");
        }
    }

    /** The resource every span and log carries: who sends, and which SDK. */
    public static Resource resource(Settings s) {
        AttributesBuilder b = Attributes.builder()
                .put("service.name", s.service)
                .put("telemetry.sdk.name", "zharp")
                .put("telemetry.sdk.language", "java")
                .put("telemetry.sdk.version", Version.VERSION)
                .put("telemetry.distro.name", "zharp")
                .put("telemetry.distro.version", Version.VERSION);
        if (s.release != null) b.put("service.version", s.release);
        if (s.environment != null) b.put("deployment.environment.name", s.environment);
        if (s.desktop) b.putAll(Desktop.resource(s));
        return Resource.create(b.build());
    }

    private static SpanExporter authorized(SpanExporter exporter, Settings s) {
        if (s.key != null && exporter instanceof OtlpHttpSpanExporter) {
            return ((OtlpHttpSpanExporter) exporter).toBuilder().addHeader("Authorization", "Bearer " + s.key).build();
        }
        return exporter;
    }

    private static LogRecordExporter authorized(LogRecordExporter exporter, Settings s) {
        if (s.key != null && exporter instanceof OtlpHttpLogRecordExporter) {
            return ((OtlpHttpLogRecordExporter) exporter).toBuilder().addHeader("Authorization", "Bearer " + s.key).build();
        }
        return exporter;
    }
}

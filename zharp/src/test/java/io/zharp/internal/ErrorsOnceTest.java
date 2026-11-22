package io.zharp.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.SdkLoggerProviderBuilder;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.logs.internal.SdkLoggerProviderUtil;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.internal.SdkTracerProviderUtil;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The SDK wired as the agent wires it, with in-memory exporters: each failure is one error log. */
class ErrorsOnceTest {
    private final InMemorySpanExporter spans = InMemorySpanExporter.create();
    private final InMemoryLogRecordExporter logs = InMemoryLogRecordExporter.create();
    private OpenTelemetrySdk sdk;
    private ZharpCore core;
    private Tracer tracer;
    private Logger appLog;

    @BeforeEach
    void setUp() {
        HashMap<String, String> values = new HashMap<>();
        values.put("zharp.key", "test");
        values.put("zharp.logs", "true");
        core = new ZharpCore(Settings.from(Settings.of(values)));
        SdkTracerProviderBuilder traces = SdkTracerProvider.builder()
                .addSpanProcessor(new ZharpSpanProcessor(core))
                .addSpanProcessor(SimpleSpanProcessor.create(new ZharpSpanExporter(spans)));
        SdkTracerProviderUtil.setExceptionAttributeResolver(traces, new ZharpExceptionResolver());
        SdkLoggerProviderBuilder logBuilder = SdkLoggerProvider.builder()
                .addLogRecordProcessor(new ZharpLogProcessor(core, SimpleLogRecordProcessor.create(new ZharpLogExporter(logs))));
        SdkLoggerProviderUtil.setExceptionAttributeResolver(logBuilder, new ZharpExceptionResolver());
        sdk = OpenTelemetrySdk.builder().setTracerProvider(traces.build()).setLoggerProvider(logBuilder.build()).build();
        core.attach(sdk);
        tracer = sdk.getTracer("io.opentelemetry.tomcat-10.0");
        appLog = sdk.getLogsBridge().get("org.apache.catalina.core.ContainerBase.[Tomcat]");
    }

    @AfterEach
    void tearDown() {
        sdk.close();
    }

    private List<LogRecordData> errors() {
        return logs.getFinishedLogRecordItems().stream().filter(l -> l.getAttributes().get(Keys.EXCEPTION_TYPE) != null).collect(Collectors.toList());
    }

    @Test
    void aFailedRequestIsReportedOnceThoughRecordedAndLoggedTwice() {
        IllegalStateException boom = new IllegalStateException("boom");
        Span server = tracer.spanBuilder("GET /boom").setSpanKind(SpanKind.SERVER).startSpan();
        try (Scope ignored = server.makeCurrent()) {
            Span controller = tracer.spanBuilder("Controller.boom").startSpan();
            controller.recordException(boom);
            controller.setStatus(StatusCode.ERROR);
            controller.end();
            // The container logs what escaped the request.
            appLog.logRecordBuilder().setSeverity(Severity.ERROR).setBody("Servlet.service() threw exception").setException(new javax.naming.NamingException("unrelated")).emit();
            appLog.logRecordBuilder().setSeverity(Severity.ERROR).setBody("Servlet.service() threw exception").setException(boom).emit();
        }
        server.recordException(boom);
        server.setAttribute(AttributeKey.longKey("http.response.status_code"), 500L);
        server.end();

        List<LogRecordData> errors = errors();
        List<LogRecordData> booms = errors.stream().filter(l -> "boom".equals(l.getAttributes().get(Keys.EXCEPTION_MESSAGE))).collect(Collectors.toList());
        assertEquals(1, booms.size(), errors.toString());
        assertEquals("false", booms.get(0).getAttributes().get(Keys.HANDLED));
        assertEquals("tomcat", booms.get(0).getAttributes().get(Keys.MECHANISM));
        // The container's line is still sent (logs are on), without the exception.
        assertEquals(3, logs.getFinishedLogRecordItems().size());
        // Every exception event stays on its span, deferred, and Zharp's internal ids never leave.
        for (SpanData span : spans.getFinishedSpanItems()) {
            span.getEvents().stream().filter(e -> e.getName().equals("exception")).forEach(e -> {
                assertEquals("true", e.getAttributes().get(Keys.DEFERRED));
                assertNull(e.getAttributes().get(Keys.EIDS));
            });
        }
    }

    @Test
    void aCaughtQueryFailureIsReportedOnceWithItsQuery() {
        Span server = tracer.spanBuilder("GET /dup").setSpanKind(SpanKind.SERVER).startSpan();
        try (Scope ignored = server.makeCurrent()) {
            Span query = tracer.spanBuilder("INSERT users").setSpanKind(SpanKind.CLIENT).setAttribute("db.system", "postgresql").setAttribute("db.statement", "insert into users(email) values (?)").startSpan();
            query.recordException(new SQLException("ERROR: duplicate key value violates unique constraint \"users_email_key\"", "23505"));
            query.setStatus(StatusCode.ERROR);
            query.end();
        }
        server.setAttribute(AttributeKey.longKey("http.response.status_code"), 409L);
        server.end();

        List<LogRecordData> errors = errors();
        assertEquals(1, errors.size(), errors.toString());
        assertEquals("UniqueViolation", errors.get(0).getAttributes().get(Keys.EXCEPTION_TYPE));
        assertEquals("true", errors.get(0).getAttributes().get(Keys.HANDLED));
        assertEquals("insert into users(email) values (?)", errors.get(0).getAttributes().get(Keys.DB_STATEMENT));
        assertEquals("23505", errors.get(0).getAttributes().get(Keys.DB_ERROR_CODE));
    }

    @Test
    void captureErrorOfSomethingAlreadyReportedIsSkipped() {
        RuntimeException e = new RuntimeException("once");
        core.report(e, true, Severity.ERROR, null, io.opentelemetry.context.Context.root());
        core.report(e, true, Severity.ERROR, null, io.opentelemetry.context.Context.root());
        assertEquals(1, errors().size());
    }
}

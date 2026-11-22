package io.zharp.internal;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Zharp's state in one process: the settings, the user/tag store, the error tracker, and the
 * SDK it reports through. One per OpenTelemetry SDK — the agent's, the Spring Boot starter's, or
 * the one library mode starts.
 */
public final class ZharpCore implements ZharpBackend {
    public final Settings settings;
    public final ContextStore store = new ContextStore();
    public final ErrorTracker errors;

    private volatile OpenTelemetrySdk sdk;
    private volatile Logger errorLogger;
    /** Reports made before the SDK was built (a crash during startup), sent once it is. */
    private final List<Runnable> early = new ArrayList<>();

    public ZharpCore(Settings settings) {
        this.settings = settings;
        this.errors = new ErrorTracker(this);
    }

    /** The SDK is built: reports go through its logger from now on. */
    public void attach(OpenTelemetrySdk sdk) {
        this.sdk = sdk;
        this.errorLogger = sdk.getSdkLoggerProvider().loggerBuilder(Keys.SCOPE_ERRORS).setInstrumentationVersion(Version.VERSION).build();
        List<Runnable> waiting;
        synchronized (early) {
            waiting = new ArrayList<>(early);
            early.clear();
        }
        for (Runnable r : waiting) r.run();
    }

    public OpenTelemetrySdk sdk() {
        return sdk;
    }

    @Override
    public io.opentelemetry.api.OpenTelemetry openTelemetry() {
        return sdk;
    }

    @Override
    public void setUser(Context context, Attributes user) {
        store.setUser(context, user);
    }

    @Override
    public void setTag(String key, Object value) {
        store.setTag(key, value);
    }

    /** An error report, in the span it happened in. */
    void emit(Severity severity, Attributes attributes, SpanContext span) {
        Logger logger = errorLogger;
        if (logger == null) {
            synchronized (early) {
                if (early.size() < 100) early.add(() -> emit(severity, attributes, span));
            }
            return;
        }
        String type = attributes.get(Keys.EXCEPTION_TYPE);
        String message = attributes.get(Keys.EXCEPTION_MESSAGE);
        LogRecordBuilder b = logger.logRecordBuilder()
                .setSeverity(severity)
                .setSeverityText(severity == Severity.FATAL ? "FATAL" : "ERROR")
                .setBody(type + ": " + (message == null ? "" : message))
                .setAllAttributes(attributes);
        if (span != null && span.isValid()) b.setContext(Context.root().with(Span.wrap(span)));
        b.emit();
    }

    /**
     * Report a Throwable: {@code captureError}, an uncaught exception, a crash. Once per failure —
     * a Throwable (or one it wraps) already reported is skipped. A failed query it came from
     * gives it the statement and database.
     */
    @Override
    public void report(Throwable error, boolean handled, Severity severity, Attributes extra, Context context) {
        try {
            Exceptions.Info info = Exceptions.describe(error);
            Set<Long> ids = Exceptions.parseIds(info.eids);
            if (errors.isReported(ids)) return;
            errors.markReported(ids);
            SpanContext span = Span.fromContext(context).getSpanContext();
            AttributesBuilder b = Attributes.builder();
            if (extra != null) b.putAll(extra);
            b.put(Keys.EXCEPTION_TYPE, info.type)
                    .put(Keys.EXCEPTION_MESSAGE, info.message)
                    .put(Keys.EXCEPTION_STACKTRACE, info.stacktrace)
                    .put(Keys.HANDLED, handled ? "true" : "false");
            if (info.sqlState != null) b.put(Keys.DB_ERROR_CODE, info.sqlState);
            if (span.isValid()) {
                ErrorTracker.Query q = errors.queryOf(span.getTraceId(), ids);
                if (q != null) {
                    errors.markReported(q.ids);
                    b.putAll(ErrorTracker.queryAttributes(q));
                }
                b.putAll(store.attributesFor(span.getTraceId()));
            } else {
                b.putAll(store.attributesFor(null));
            }
            b.putAll(ContextStore.fromBaggage(context));
            emit(severity, b.build(), span);
        } catch (RuntimeException ignored) {
            // Never throw into the application.
        }
    }

    /** Send everything waiting, within {@code timeoutMillis}. */
    @Override
    public void flush(long timeoutMillis) {
        OpenTelemetrySdk s = sdk;
        if (s == null) return;
        try {
            CompletableResultCode traces = s.getSdkTracerProvider().forceFlush();
            CompletableResultCode logs = s.getSdkLoggerProvider().forceFlush();
            CompletableResultCode.ofAll(java.util.Arrays.asList(traces, logs)).join(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException ignored) {
            // As above.
        }
    }

    /** One line on stderr, prefixed so it can be searched for. */
    public static void announce(String message) {
        System.err.println("[Zharp] " + message);
    }
}

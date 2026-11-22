package io.zharp.internal;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.LogRecordProcessor;
import io.opentelemetry.sdk.logs.ReadWriteLogRecord;
import java.util.Set;

/**
 * Every log record on its way to the exporter (it wraps the exporting processor):
 *
 * <ul>
 *   <li>the API's instructions to the agent (setUser, setTag, flush) are carried out and dropped;
 *   <li>user, session and tags are added;
 *   <li>with logs off (the default) only exceptions logged at ERROR and above, Zharp.log and
 *       Zharp's own reports go on — the Zharp agent collects the rest from the app's output;
 *   <li>an exception that was already reported, or that fails the request it was logged in, is
 *       sent once (see {@link ErrorTracker}).
 * </ul>
 */
public final class ZharpLogProcessor implements LogRecordProcessor {
    /** Loggers through which containers and frameworks report what escaped a request: unhandled. */
    private static final String[] UNCAUGHT_LOGGERS = {
        "org.apache.catalina.core.ContainerBase", "org.apache.catalina.core.StandardWrapperValve", "org.eclipse.jetty.ee",
        "org.eclipse.jetty.server.HttpChannel", "org.eclipse.jetty.servlet.ServletHandler", "io.undertow.request",
        "org.springframework.web.server.adapter.HttpWebHandlerAdapter", "reactor.netty", "io.ktor.server.Application",
        "org.springframework.boot.SpringApplication", "org.springframework.boot.autoconfigure.web.reactive.error",
        "org.springframework.boot.web.reactive.error", "org.springframework.web.server.handler",
    };
    private static final AttributeKey<String> CONTROL_USER_EMAIL = AttributeKey.stringKey("zharp.control.user.email");

    private final ZharpCore core;
    private final LogRecordProcessor delegate;

    public ZharpLogProcessor(ZharpCore core, LogRecordProcessor delegate) {
        this.core = core;
        this.delegate = delegate;
    }

    @Override
    public void onEmit(Context context, ReadWriteLogRecord record) {
        String scope;
        try {
            scope = record.getInstrumentationScopeInfo().getName();
            if (Keys.SCOPE_CONTROL.equals(scope)) {
                control(context, record);
                return;
            }
        } catch (RuntimeException e) {
            delegate.onEmit(context, record);
            return;
        }
        try {
            SpanContext span = record.getSpanContext();
            String traceId = span.isValid() ? span.getTraceId() : null;
            addContext(context, record, traceId);
            if (Keys.SCOPE_ERRORS.equals(scope)) {
                delegate.onEmit(context, record);
                return;
            }
            boolean error = record.getAttribute(Keys.EXCEPTION_TYPE) != null || record.getAttribute(Keys.EXCEPTION_STACKTRACE) != null;
            boolean api = Keys.SCOPE_API.equals(scope);
            if (!error) {
                if (api || core.settings.logs) delegate.onEmit(context, record);
                return;
            }
            Severity severity = record.getSeverity();
            boolean errorLevel = severity == null || severity == Severity.UNDEFINED_SEVERITY_NUMBER || severity.getSeverityNumber() >= Severity.ERROR.getSeverityNumber();
            if (!errorLevel) {
                // A warning that carries an exception is a log line, not an error.
                if (api || core.settings.logs) {
                    record.setAttribute(Keys.STRIP, "true");
                    delegate.onEmit(context, record);
                }
                return;
            }
            Set<Long> ids = Exceptions.parseIds(record.getAttribute(Keys.EIDS));
            if (record.getAttribute(Keys.HANDLED) == null) record.setAttribute(Keys.HANDLED, uncaughtLogger(scope) ? "false" : "true");
            if (!ids.isEmpty() && core.errors.isReported(ids)) {
                release(context, record, false);
                return;
            }
            if (api) {
                // Zharp.captureError / Zharp.log.error(…, error): reported now.
                core.errors.markReported(ids);
                if (traceId != null) {
                    ErrorTracker.Query q = core.errors.queryOf(traceId, ids);
                    if (q != null) {
                        core.errors.markReported(q.ids);
                        record.setAllAttributes(ErrorTracker.queryAttributes(q));
                    }
                }
                delegate.onEmit(context, record);
                return;
            }
            if (traceId != null && core.errors.hold(traceId, this, context, record, ids)) return;
            core.errors.markReported(ids);
            delegate.onEmit(context, record);
        } catch (RuntimeException e) {
            delegate.onEmit(context, record);
        }
    }

    /** A held record goes out: with its exception ({@code keep}), or as a plain line when logs are on. */
    void release(Context context, ReadWriteLogRecord record, boolean keep) {
        try {
            if (keep) {
                delegate.onEmit(context, record);
            } else if (core.settings.logs || Keys.SCOPE_API.equals(record.getInstrumentationScopeInfo().getName())) {
                record.setAttribute(Keys.STRIP, "true");
                delegate.onEmit(context, record);
            }
        } catch (RuntimeException ignored) {
            // Never throw into the application.
        }
    }

    private void addContext(Context context, ReadWriteLogRecord record, String traceId) {
        Attributes stored = core.store.attributesFor(traceId);
        Attributes baggage = ContextStore.fromBaggage(context);
        stored.forEach((k, v) -> setIfAbsent(record, k, v));
        baggage.forEach((k, v) -> setIfAbsent(record, k, v));
    }

    @SuppressWarnings("unchecked")
    private static void setIfAbsent(ReadWriteLogRecord record, AttributeKey<?> key, Object value) {
        if (record.getAttribute(key) == null) record.setAttribute((AttributeKey<Object>) key, value);
    }

    static boolean uncaughtLogger(String name) {
        if (name == null) return false;
        for (String prefix : UNCAUGHT_LOGGERS) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    /** The API's instructions, from the application's class loader (see io.zharp.Zharp). */
    private void control(Context context, ReadWriteLogRecord record) {
        Attributes a = record.getAttributes();
        String command = record.getBodyValue() == null ? "" : record.getBodyValue().asString();
        switch (command) {
            case "setUser":
                core.store.setUser(context, ContextStore.userAttributes(a.get(Keys.USER_ID), a.get(CONTROL_USER_EMAIL) != null ? a.get(CONTROL_USER_EMAIL) : a.get(Keys.USER_EMAIL), a.get(Keys.USER_NAME)));
                break;
            case "setTag":
                a.forEach((k, v) -> {
                    if (k.getKey().startsWith("zharp.control.tag.")) {
                        String name = k.getKey().substring("zharp.control.tag.".length());
                        core.store.setTag(name, "zharp.control.remove".equals(v) ? null : v);
                    }
                });
                break;
            case "flush":
                Long timeout = a.get(AttributeKey.longKey("zharp.control.timeout"));
                core.flush(timeout == null ? 5000 : timeout);
                break;
            default:
                break;
        }
    }

    @Override
    public CompletableResultCode forceFlush() {
        return delegate.forceFlush();
    }

    @Override
    public CompletableResultCode shutdown() {
        core.errors.releaseAll();
        return delegate.shutdown();
    }
}

package io.zharp;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.zharp.internal.Exceptions;
import io.zharp.internal.Keys;
import io.zharp.internal.Version;
import io.zharp.internal.ApiRuntime;
import io.zharp.internal.ZharpBackend;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;

/**
 * Zharp for Java.
 *
 * <p>With the Zharp agent ({@code -javaagent:zharp-agent.jar}) or the Spring Boot starter, everything
 * is set up already: these calls go through them. Without either, {@link #init()} starts
 * OpenTelemetry's SDK for Zharp in this process ("library mode").
 *
 * <pre>{@code
 * Zharp.setUser(ZharpUser.of(user.getId()).withEmail(user.getEmail()));
 * Zharp.captureError(e);
 * Zharp.log.info("Order placed", Map.of("orderId", 42));
 * }</pre>
 *
 * Nothing here throws into the application.
 */
public final class Zharp {
    /** {@code Zharp.log.info("Order placed", Map.of("orderId", 42))} — and trace, debug, warn, error, fatal. */
    public static final Log log = new Log();

    private Zharp() {}

    // ── setup ───────────────────────────────────────────────────────────

    /** Library mode, configured from the environment ({@code ZHARP_KEY}, {@code ZHARP_SERVICE}…). */
    public static void init() {
        init(ZharpOptions.builder().build());
    }

    /**
     * Library mode: start sending traces, logs and errors from this process, for apps that run
     * without the agent (tests, CLIs, hosts that forbid agents). Call it once, first thing in
     * {@code main}. With the agent or the Spring Boot starter it does nothing — they already did.
     */
    public static void init(ZharpOptions options) {
        try {
            // Library mode lives in io.zharp:zharp (the SDK and its exporter); io.zharp:zharp-api alone has none.
            Class<?> library;
            try {
                library = Class.forName("io.zharp.internal.LibraryMode", true, Zharp.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                if (!ApiRuntime.agentPresent()) ApiRuntime.announce("Zharp.init() needs io.zharp:zharp (library mode) — io.zharp:zharp-api only works through the Zharp agent or the Spring Boot starter.");
                return;
            }
            library.getMethod("start", java.util.Map.class).invoke(null, options.values);
        } catch (Throwable e) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            ApiRuntime.announce("Could not start: " + cause);
            if (cause instanceof LinkageError) {
                ApiRuntime.announce("A class Zharp needs isn't in this runtime. A jlink'd runtime (Compose's createDistributable, jpackage) needs the modules java.logging, java.management, java.naming, java.net.http and jdk.unsupported.");
            }
        }
    }

    /** Whether Zharp is sending from this process: the agent, the starter or library mode. */
    public static boolean isActive() {
        return ApiRuntime.backend() != null || ApiRuntime.agentPresent();
    }

    /** The OpenTelemetry Zharp sends through, for instrumenting your own code. */
    public static OpenTelemetry openTelemetry() {
        ZharpBackend core = ApiRuntime.backend();
        if (core != null && core.openTelemetry() != null) return core.openTelemetry();
        return GlobalOpenTelemetry.get();
    }

    // ── errors ──────────────────────────────────────────────────────────

    /** Report an error you caught: sent as handled, in the trace of the request it happened in. Once per error. */
    public static void captureError(Throwable error) {
        captureError(error, null);
    }

    public static void captureError(Throwable error, Map<String, ?> attributes) {
        if (error == null) return;
        try {
            ZharpBackend core = ApiRuntime.backend();
            if (core != null) {
                core.report(error, true, Severity.ERROR, attributes(attributes), Context.current());
            } else if (ApiRuntime.agentPresent()) {
                emitError(Severity.ERROR, null, error, attributes(attributes).toBuilder().put(Keys.HANDLED, "true").build());
            }
        } catch (Throwable e) {
            ApiRuntime.debug("Zharp API", e);
        }
    }

    // ── context ─────────────────────────────────────────────────────────

    /**
     * The user the current request is for: {@code enduser.id} (and email, name) on every span and
     * log of the request from here on, and on the request's own span. Outside a request it applies
     * to everything the process sends.
     */
    public static void setUser(ZharpUser user) {
        try {
            Attributes attributes = user == null ? null : userAttributes(user);
            ZharpBackend core = ApiRuntime.backend();
            if (core != null) {
                core.setUser(Context.current(), attributes);
            } else if (ApiRuntime.agentPresent()) {
                AttributesBuilder b = Attributes.builder();
                if (attributes != null) b.putAll(attributes);
                control("setUser", b.build());
            }
        } catch (Throwable e) {
            ApiRuntime.debug("Zharp API", e);
        }
    }

    public static void setUser(String id) {
        setUser(id == null ? null : ZharpUser.of(id));
    }

    /** Forget the user — for the current request, or the process's when called outside one. */
    public static void clearUser() {
        setUser((ZharpUser) null);
    }

    /** An attribute on every span and log sent from now on, e.g. {@code setTag("region", "eu-west-1")}. Null removes it. */
    public static void setTag(String key, Object value) {
        if (key == null || key.isEmpty()) return;
        try {
            ZharpBackend core = ApiRuntime.backend();
            if (core != null) {
                core.setTag(key, value);
            } else if (ApiRuntime.agentPresent()) {
                control("setTag", Attributes.of(AttributeKey.stringKey("zharp.control.tag." + key), value == null ? "zharp.control.remove" : String.valueOf(value)));
            }
        } catch (Throwable e) {
            ApiRuntime.debug("Zharp API", e);
        }
    }

    // ── spans ───────────────────────────────────────────────────────────

    /** Run {@code work} inside a span named {@code name}. A throw fails the span and is passed on unchanged. */
    public static <T> T startSpan(String name, Function<Span, T> work) {
        Span span = openTelemetry().getTracer(Keys.SCOPE_API, Version.VERSION).spanBuilder(name).startSpan();
        try (Scope ignored = span.makeCurrent()) {
            return work.apply(span);
        } catch (RuntimeException | Error e) {
            span.setStatus(StatusCode.ERROR, String.valueOf(e.getMessage()));
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /** {@link #startSpan(String, Function)} for work that throws checked exceptions. */
    public static <T> T startSpan(String name, Callable<T> work) throws Exception {
        Span span = openTelemetry().getTracer(Keys.SCOPE_API, Version.VERSION).spanBuilder(name).startSpan();
        try (Scope ignored = span.makeCurrent()) {
            return work.call();
        } catch (Exception | Error e) {
            span.setStatus(StatusCode.ERROR, String.valueOf(e.getMessage()));
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    public static void startSpan(String name, Runnable work) {
        startSpan(name, span -> {
            work.run();
            return null;
        });
    }

    /** Send everything waiting now (default within 5 seconds). Call it before a short-lived process exits. */
    public static void flush() {
        flush(5_000);
    }

    public static void flush(long timeoutMillis) {
        try {
            ZharpBackend core = ApiRuntime.backend();
            if (core != null) core.flush(timeoutMillis);
            else if (ApiRuntime.agentPresent()) control("flush", Attributes.of(AttributeKey.longKey("zharp.control.timeout"), timeoutMillis));
        } catch (Throwable e) {
            ApiRuntime.debug("Zharp API", e);
        }
    }

    // ── logs ────────────────────────────────────────────────────────────

    /** Logs sent straight to Zharp, whatever ZHARP_LOGS says. A Throwable among the attributes of an error log makes it an error with its stack. */
    public static final class Log {
        private Log() {}

        public void trace(String message) {
            emit(Severity.TRACE, message, null);
        }

        public void trace(String message, Map<String, ?> attributes) {
            emit(Severity.TRACE, message, attributes);
        }

        public void debug(String message) {
            emit(Severity.DEBUG, message, null);
        }

        public void debug(String message, Map<String, ?> attributes) {
            emit(Severity.DEBUG, message, attributes);
        }

        public void info(String message) {
            emit(Severity.INFO, message, null);
        }

        public void info(String message, Map<String, ?> attributes) {
            emit(Severity.INFO, message, attributes);
        }

        public void warn(String message) {
            emit(Severity.WARN, message, null);
        }

        public void warn(String message, Map<String, ?> attributes) {
            emit(Severity.WARN, message, attributes);
        }

        public void error(String message) {
            emit(Severity.ERROR, message, null);
        }

        public void error(String message, Map<String, ?> attributes) {
            emit(Severity.ERROR, message, attributes);
        }

        public void error(String message, Throwable error) {
            emitWithError(Severity.ERROR, message, error, null);
        }

        public void fatal(String message) {
            emit(Severity.FATAL, message, null);
        }

        public void fatal(String message, Map<String, ?> attributes) {
            emit(Severity.FATAL, message, attributes);
        }

        public void fatal(String message, Throwable error) {
            emitWithError(Severity.FATAL, message, error, null);
        }

        private void emit(Severity severity, String message, Map<String, ?> attributes) {
            Throwable error = null;
            if (attributes != null && severity.getSeverityNumber() >= Severity.ERROR.getSeverityNumber()) {
                for (Object v : attributes.values()) {
                    if (v instanceof Throwable) {
                        error = (Throwable) v;
                        break;
                    }
                }
            }
            emitWithError(severity, message, error, attributes);
        }

        private void emitWithError(Severity severity, String message, Throwable error, Map<String, ?> attributes) {
            try {
                if (!isActive()) return;
                Attributes a = attributes(attributes);
                if (error != null) {
                    emitError(severity, message, error, a);
                    return;
                }
                logger().logRecordBuilder().setSeverity(severity).setSeverityText(text(severity)).setBody(message == null ? "" : message).setAllAttributes(a).emit();
            } catch (Throwable e) {
                ApiRuntime.debug("Zharp API", e);
            }
        }
    }

    // ── internals ───────────────────────────────────────────────────────

    private static Logger logger() {
        return openTelemetry().getLogsBridge().loggerBuilder(Keys.SCOPE_API).setInstrumentationVersion(Version.VERSION).build();
    }

    private static String text(Severity severity) {
        String name = severity.name();
        int underscore = name.indexOf('_');
        return underscore > 0 ? name.substring(0, underscore) : name;
    }

    /** An error log: the exception written by the SDK that sends it (the agent's, or this process's). */
    private static void emitError(Severity severity, String message, Throwable error, Attributes extra) {
        ZharpBackend core = ApiRuntime.backend();
        if (core != null && message == null) {
            core.report(error, !"false".equals(extra.get(Keys.HANDLED)), severity, extra, Context.current());
            return;
        }
        Exceptions.Info info = Exceptions.describe(error);
        AttributesBuilder b = extra.toBuilder()
                .put(Keys.EXCEPTION_TYPE, info.type)
                .put(Keys.EXCEPTION_MESSAGE, info.message)
                .put(Keys.EXCEPTION_STACKTRACE, info.stacktrace);
        if (b.build().get(Keys.HANDLED) == null) b.put(Keys.HANDLED, "true");
        LogRecordBuilder record = logger().logRecordBuilder()
                .setSeverity(severity)
                .setSeverityText(text(severity))
                .setBody(message != null ? message : info.type + ": " + info.message)
                .setAllAttributes(b.build());
        // Last: the SDK's own writing of it (with the ids it knows the error by) wins.
        try {
            record.setException(error);
        } catch (NoSuchMethodError olderApi) {
            // OpenTelemetry API before 1.50 (a framework's pinned version): the attributes above stand.
        }
        record.emit();
    }

    private static Attributes userAttributes(ZharpUser user) {
        if (user.id == null || user.id.isEmpty()) return null;
        AttributesBuilder b = Attributes.builder().put(Keys.USER_ID, user.id);
        if (user.email != null && !user.email.isEmpty()) b.put(Keys.USER_EMAIL, user.email);
        if (user.name != null && !user.name.isEmpty()) b.put(Keys.USER_NAME, user.name);
        return b.build();
    }

    private static void control(String command, Attributes attributes) {
        openTelemetry().getLogsBridge().loggerBuilder(Keys.SCOPE_CONTROL).build().logRecordBuilder().setBody(command).setAllAttributes(attributes).emit();
    }

    private static Attributes attributes(Map<String, ?> map) {
        if (map == null || map.isEmpty()) return Attributes.empty();
        AttributesBuilder b = Attributes.builder();
        for (Map.Entry<String, ?> e : map.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Throwable) continue;
            if (v instanceof Boolean) b.put(AttributeKey.booleanKey(e.getKey()), (Boolean) v);
            else if (v instanceof Integer || v instanceof Long || v instanceof Short) b.put(AttributeKey.longKey(e.getKey()), ((Number) v).longValue());
            else if (v instanceof Number) b.put(AttributeKey.doubleKey(e.getKey()), ((Number) v).doubleValue());
            else if (v != null) b.put(AttributeKey.stringKey(e.getKey()), String.valueOf(v));
        }
        return b.build();
    }
}

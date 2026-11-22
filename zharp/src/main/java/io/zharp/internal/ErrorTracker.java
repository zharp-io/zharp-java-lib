package io.zharp.internal;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.logs.ReadWriteLogRecord;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * One report per failure.
 *
 * OpenTelemetry's instrumentations record an exception on every span it leaves — the controller
 * method's, the servlet's, the server's — and a container logs it too. The exception events stay
 * on their spans for the trace view, marked {@code zharp.deferred} so the server makes no error of
 * them (see ZharpSpanExporter), and the SDK reports each failure itself, once, as an error log:
 *
 * <ul>
 *   <li>A request that failed (5xx, or a failed root span outside HTTP) is reported when its span
 *       ends, with the outermost exception recorded in it, as unhandled.
 *   <li>A failed query is reported with the statement, database and SQLSTATE: merged into the
 *       request's report when it is what failed the request, otherwise as handled when the request
 *       ends (the app caught it).
 *   <li>An exception logged during a request is held until the request ends: if it is the one the
 *       request failed with, the line is sent without it; otherwise it is reported as handled.
 *   <li>Throwables are known by identity, causes included, so a wrapper and what it wraps count as
 *       one failure.
 * </ul>
 */
public final class ErrorTracker {
    private static final int MAX_TRACES = 2_000;
    private static final int MAX_REPORTED = 10_000;
    /** How long a failure outside a request waits for an uncaught-exception handler to claim it. */
    private static final long LONE_WAIT_MS = 1_500;

    private static final AttributeKey<Long> HTTP_STATUS = AttributeKey.longKey("http.response.status_code");
    private static final AttributeKey<Long> HTTP_STATUS_OLD = AttributeKey.longKey("http.status_code");
    private static final AttributeKey<String> DB_SYSTEM_NAME = AttributeKey.stringKey("db.system.name");
    private static final AttributeKey<String> DB_QUERY_TEXT = AttributeKey.stringKey("db.query.text");
    private static final AttributeKey<String> DB_NAMESPACE = AttributeKey.stringKey("db.namespace");
    private static final AttributeKey<String> ESCAPED = AttributeKey.stringKey("exception.escaped");
    private static final Pattern SCOPE_VERSION = Pattern.compile("-\\d+(\\.\\d+)*$");

    private final ZharpCore core;
    private final Map<String, TraceState> traces = Collections.synchronizedMap(new LinkedHashMap<String, TraceState>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, TraceState> eldest) {
            if (size() <= MAX_TRACES) return false;
            releaseLater(eldest.getValue());
            return true;
        }
    });
    private final Map<Long, Boolean> reported = Collections.synchronizedMap(new LinkedHashMap<Long, Boolean>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
            return size() > MAX_REPORTED;
        }
    });
    private volatile ScheduledExecutorService timer;

    ErrorTracker(ZharpCore core) {
        this.core = core;
    }

    /** An exception recorded on a span. */
    static final class Recorded {
        final Set<Long> ids;
        final Attributes attributes;
        final SpanContext span;
        final String scope;
        final long endNanos;

        Recorded(Set<Long> ids, Attributes attributes, SpanContext span, String scope, long endNanos) {
            this.ids = ids;
            this.attributes = attributes;
            this.span = span;
            this.scope = scope;
            this.endNanos = endNanos;
        }
    }

    /** A query whose span failed. */
    static final class Query {
        final Set<Long> ids;
        final Attributes exception;
        final String system;
        final String statement;
        final String database;
        final SpanContext span;
        boolean done;

        Query(Set<Long> ids, Attributes exception, String system, String statement, String database, SpanContext span) {
            this.ids = ids;
            this.exception = exception;
            this.system = system;
            this.statement = statement;
            this.database = database;
            this.span = span;
        }

        String code() {
            String code = exception != null ? exception.get(Keys.DB_ERROR_CODE) : null;
            return code;
        }
    }

    /** A log record with an exception, written during a request, waiting for the request to end. */
    static final class Held {
        final ZharpLogProcessor processor;
        final Context context;
        final ReadWriteLogRecord record;
        final Set<Long> ids;

        Held(ZharpLogProcessor processor, Context context, ReadWriteLogRecord record, Set<Long> ids) {
            this.processor = processor;
            this.context = context;
            this.record = record;
            this.ids = ids;
        }
    }

    static final class TraceState {
        final List<Recorded> exceptions = new ArrayList<>();
        final List<Query> queries = new ArrayList<>();
        final List<Held> held = new ArrayList<>();
        /** The decision for each held record, made once whichever processor holds it. */
        final Map<ReadWriteLogRecord, Boolean> keep = new IdentityHashMap<>();
    }

    private TraceState state(String traceId) {
        synchronized (traces) {
            TraceState s = traces.get(traceId);
            if (s == null) {
                s = new TraceState();
                traces.put(traceId, s);
            }
            return s;
        }
    }

    // ── what was reported ────────────────────────────────────────────────

    public boolean isReported(Set<Long> ids) {
        synchronized (reported) {
            for (Long id : ids) {
                if (reported.containsKey(id)) return true;
            }
        }
        return false;
    }

    public void markReported(Set<Long> ids) {
        synchronized (reported) {
            for (Long id : ids) reported.put(id, Boolean.TRUE);
        }
    }

    private static boolean meet(Set<Long> a, Set<Long> b) {
        for (Long id : a) {
            if (b.contains(id)) return true;
        }
        return false;
    }

    // ── spans ────────────────────────────────────────────────────────────

    void spanEnded(SpanData span, boolean localRoot) {
        String traceId = span.getTraceId();
        boolean db = dbSystem(span.getAttributes()) != null;
        for (EventData event : span.getEvents()) {
            if (!"exception".equals(event.getName())) continue;
            Attributes attributes = event.getAttributes();
            Set<Long> ids = Exceptions.parseIds(attributes.get(Keys.EIDS));
            if (db) {
                if (!core.settings.captureQueryErrors || span.getStatus().getStatusCode() != StatusCode.ERROR) continue;
                Attributes s = span.getAttributes();
                Query q = new Query(ids, attributes, dbSystem(s), first(s.get(Keys.DB_STATEMENT), s.get(DB_QUERY_TEXT)),
                        first(s.get(Keys.DB_NAME), s.get(DB_NAMESPACE)), span.getSpanContext());
                TraceState st = state(traceId);
                synchronized (st) {
                    st.queries.add(q);
                }
            } else {
                Recorded r = new Recorded(ids, attributes, span.getSpanContext(), span.getInstrumentationScopeInfo().getName(), span.getEndEpochNanos());
                TraceState st = state(traceId);
                synchronized (st) {
                    st.exceptions.add(r);
                }
            }
        }
        if (!localRoot) return;
        boolean failed = failed(span);
        boolean httpServer = span.getKind() == SpanKind.SERVER && status(span) != null;
        if (failed && !httpServer && traces.containsKey(traceId)) {
            // Outside a request a failure may still be on its way to an uncaught-exception handler,
            // which reports it itself: give it a moment.
            timer().schedule(() -> finish(traceId, span, true), LONE_WAIT_MS, TimeUnit.MILLISECONDS);
        } else {
            finish(traceId, span, failed);
        }
    }

    private static String first(String a, String b) {
        return a != null && !a.isEmpty() ? a : b;
    }

    private static String dbSystem(Attributes attributes) {
        return first(attributes.get(Keys.DB_SYSTEM), attributes.get(DB_SYSTEM_NAME));
    }

    private static Long status(SpanData span) {
        Long status = span.getAttributes().get(HTTP_STATUS);
        return status != null ? status : span.getAttributes().get(HTTP_STATUS_OLD);
    }

    static boolean failed(SpanData span) {
        Long status = status(span);
        if (status != null && status > 0) return status >= 500;
        return span.getStatus().getStatusCode() == StatusCode.ERROR;
    }

    /** The request (or job) in this process is over: report what it failed with, and what it caught. */
    void finish(String traceId, SpanData root, boolean failed) {
        TraceState st = traces.remove(traceId);
        if (st == null) return;
        synchronized (st) {
            Recorded chosen = failed ? pick(st, root) : null;
            if (chosen != null && !isReported(chosen.ids)) {
                Query query = queryOf(st, chosen.ids);
                markReported(chosen.ids);
                if (query != null) {
                    query.done = true;
                    markReported(query.ids);
                }
                core.emit(Severity.ERROR, requestReport(chosen, query), chosen.span);
            } else if (chosen == null && failed) {
                // A request answered 5xx with only a failed query in it: the app's own error handling
                // turned the query's failure into the response.
                for (int i = st.queries.size() - 1; i >= 0; i--) {
                    Query q = st.queries.get(i);
                    if (!q.done && !isReported(q.ids)) {
                        q.done = true;
                        markReported(q.ids);
                        core.emit(Severity.ERROR, queryReport(q, false, root.getInstrumentationScopeInfo().getName()), q.span);
                        break;
                    }
                }
            }
            release(st);
            for (Query q : st.queries) {
                if (q.done || isReported(q.ids)) continue;
                q.done = true;
                markReported(q.ids);
                core.emit(Severity.ERROR, queryReport(q, true, null), q.span);
            }
        }
    }

    /** Held log records: each is sent with its exception unless that exception was reported already. */
    private void release(TraceState st) {
        for (Held h : st.held) {
            Boolean keep = st.keep.get(h.record);
            if (keep == null) {
                keep = !isReported(h.ids);
                if (keep) {
                    markReported(h.ids);
                    Query q = queryOf(st, h.ids);
                    if (q != null) {
                        q.done = true;
                        markReported(q.ids);
                        h.record.setAllAttributes(queryAttributes(q));
                    }
                }
                st.keep.put(h.record, keep);
            }
            h.processor.release(h.context, h.record, keep);
        }
        st.held.clear();
    }

    private void releaseLater(TraceState st) {
        synchronized (st) {
            release(st);
        }
    }

    /** Hold an error log written during a request until it ends. False when there's no request to wait for. */
    boolean hold(String traceId, ZharpLogProcessor processor, Context context, ReadWriteLogRecord record, Set<Long> ids) {
        if (!core.store.hasOpenRoot(traceId)) return false;
        TraceState st = state(traceId);
        synchronized (st) {
            st.held.add(new Held(processor, context, record, ids));
        }
        return true;
    }

    /** After a trace's root ended: anything that slipped in meanwhile goes out as it is. */
    void drain(String traceId) {
        TraceState st = traces.get(traceId);
        if (st == null) return;
        synchronized (st) {
            if (st.exceptions.isEmpty() && st.queries.isEmpty()) {
                traces.remove(traceId);
            }
            release(st);
        }
    }

    /** Shutdown: everything held goes out. */
    void releaseAll() {
        List<TraceState> all;
        synchronized (traces) {
            all = new ArrayList<>(traces.values());
        }
        for (TraceState st : all) releaseLater(st);
    }

    /** A failed query of this trace that is the same failure as {@code ids}, if any. */
    Query queryOf(String traceId, Set<Long> ids) {
        TraceState st = traces.get(traceId);
        if (st == null) return null;
        synchronized (st) {
            Query q = queryOf(st, ids);
            if (q != null) q.done = true;
            return q;
        }
    }

    private static Query queryOf(TraceState st, Set<Long> ids) {
        for (int i = st.queries.size() - 1; i >= 0; i--) {
            Query q = st.queries.get(i);
            if (!q.done && meet(q.ids, ids)) return q;
        }
        return null;
    }

    /** What the request failed with: the exception on its own span, else the outermost one recorded under it. */
    private static Recorded pick(TraceState st, SpanData root) {
        Recorded best = null;
        for (Recorded r : st.exceptions) {
            if (r.span.getSpanId().equals(root.getSpanId())) best = r;
        }
        if (best != null) return best;
        for (Recorded r : st.exceptions) {
            if (best == null || r.endNanos >= best.endNanos) best = r;
        }
        return best;
    }

    // ── reports ──────────────────────────────────────────────────────────

    /** "io.opentelemetry.spring-webmvc-6.0" is "spring-webmvc". */
    static String mechanismOf(String scope) {
        if (scope == null) return "unknown";
        String name = scope.startsWith("io.opentelemetry.") ? scope.substring("io.opentelemetry.".length()) : scope;
        return SCOPE_VERSION.matcher(name).replaceAll("");
    }

    private static Attributes requestReport(Recorded r, Query q) {
        AttributesBuilder b = r.attributes.toBuilder();
        b.remove(Keys.EIDS);
        b.remove(ESCAPED);
        b.put(Keys.HANDLED, "false");
        b.put(Keys.MECHANISM, mechanismOf(r.scope));
        if (q != null) {
            b.putAll(queryAttributes(q));
            String driverType = q.exception != null ? q.exception.get(Keys.EXCEPTION_TYPE) : null;
            b.put(Keys.EXCEPTION_TYPE, Exceptions.dbErrorType(q.system, q.code(), driverType));
            if (q.exception != null && q.exception.get(Keys.EXCEPTION_MESSAGE) != null) b.put(Keys.EXCEPTION_MESSAGE, q.exception.get(Keys.EXCEPTION_MESSAGE));
        }
        return b.build();
    }

    static Attributes queryAttributes(Query q) {
        AttributesBuilder b = Attributes.builder();
        if (q.system != null) b.put(Keys.DB_SYSTEM, q.system);
        if (q.statement != null) b.put(Keys.DB_STATEMENT, q.statement.length() > 4000 ? q.statement.substring(0, 4000) : q.statement);
        if (q.database != null) b.put(Keys.DB_NAME, q.database);
        if (q.code() != null) b.put(Keys.DB_ERROR_CODE, q.code());
        return b.build();
    }

    private static Attributes queryReport(Query q, boolean handled, String mechanismScope) {
        AttributesBuilder b = q.exception.toBuilder();
        b.remove(Keys.EIDS);
        b.remove(ESCAPED);
        b.putAll(queryAttributes(q));
        b.put(Keys.EXCEPTION_TYPE, Exceptions.dbErrorType(q.system, q.code(), q.exception.get(Keys.EXCEPTION_TYPE)));
        b.put(Keys.HANDLED, handled ? "true" : "false");
        b.put(Keys.MECHANISM, mechanismScope == null ? "db" : mechanismOf(mechanismScope));
        return b.build();
    }

    private ScheduledExecutorService timer() {
        ScheduledExecutorService t = timer;
        if (t == null) {
            synchronized (this) {
                if (timer == null) {
                    timer = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread thread = new Thread(r, "zharp-errors");
                        thread.setDaemon(true);
                        return thread;
                    });
                }
                t = timer;
            }
        }
        return t;
    }
}

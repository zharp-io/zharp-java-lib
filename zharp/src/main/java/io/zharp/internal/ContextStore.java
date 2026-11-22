package io.zharp.internal;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who a request is for, and the tags set on everything (as in the Node SDK's context.ts).
 *
 * The browser SDK sends {@code enduser.id} and {@code session.id} in the W3C {@code baggage}
 * header next to {@code traceparent}; they are copied onto every span and log of the request.
 * {@code Zharp.setUser()} covers a back end that knows the user itself: OpenTelemetry's context is
 * immutable, so the user is kept per trace and every span and log in that trace picks it up from
 * then on — and the request's own span, which is still open, is told too.
 */
public final class ContextStore {
    private static final int MAX_TRACES = 10_000;

    private volatile Attributes globalUser;
    private final Map<String, Object> tags = new ConcurrentHashMap<>();
    private final Map<String, Attributes> byTrace = Collections.synchronizedMap(bounded());
    /** The first span of each trace in this process: its SERVER span, for a request. */
    private final Map<String, ReadWriteSpan> localRoots = Collections.synchronizedMap(bounded());

    private static <V> Map<String, V> bounded() {
        return new LinkedHashMap<String, V>(64, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > MAX_TRACES;
            }
        };
    }

    public static Attributes userAttributes(String id, String email, String name) {
        if (id == null || id.isEmpty()) return null;
        AttributesBuilder b = Attributes.builder().put(Keys.USER_ID, id);
        if (email != null && !email.isEmpty()) b.put(Keys.USER_EMAIL, email);
        if (name != null && !name.isEmpty()) b.put(Keys.USER_NAME, name);
        return b.build();
    }

    /** The user for the request in progress (the span in {@code context}), or for the whole process outside one. */
    public void setUser(Context context, Attributes user) {
        Span span = Span.fromContext(context);
        SpanContext sc = span.getSpanContext();
        if (!sc.isValid()) {
            globalUser = user;
            return;
        }
        String traceId = sc.getTraceId();
        if (user != null) {
            byTrace.put(traceId, user);
            span.setAllAttributes(user);
            ReadWriteSpan root = localRoots.get(traceId);
            if (root != null && root != span) root.setAllAttributes(user);
        } else {
            byTrace.remove(traceId);
        }
    }

    public void setTag(String key, Object value) {
        if (key == null || key.isEmpty()) return;
        if (value == null) tags.remove(key);
        else tags.put(key, value);
    }

    /** What every span and log in {@code traceId} carries, before baggage. */
    public Attributes attributesFor(String traceId) {
        Attributes user = traceId != null ? byTrace.get(traceId) : null;
        if (user == null) user = globalUser;
        if (tags.isEmpty()) return user != null ? user : Attributes.empty();
        AttributesBuilder b = Attributes.builder();
        for (Map.Entry<String, Object> tag : tags.entrySet()) put(b, tag.getKey(), tag.getValue());
        if (user != null) b.putAll(user);
        return b.build();
    }

    /** {@code enduser.id} and {@code session.id} from the caller's baggage. */
    public static Attributes fromBaggage(Context context) {
        Baggage baggage = Baggage.fromContext(context);
        if (baggage.isEmpty()) return Attributes.empty();
        AttributesBuilder b = Attributes.builder();
        String user = baggage.getEntryValue("enduser.id");
        String session = baggage.getEntryValue("session.id");
        if (user != null && !user.isEmpty()) b.put(Keys.USER_ID, user);
        if (session != null && !session.isEmpty()) b.put(Keys.SESSION_ID, session);
        return b.build();
    }

    /** Whether {@code span} starts its trace in this process, and remember it if so. */
    public boolean noteStart(ReadWriteSpan span, Context parent) {
        SpanContext parentSpan = Span.fromContext(parent).getSpanContext();
        if (parentSpan.isValid() && !parentSpan.isRemote()) return false;
        String traceId = span.getSpanContext().getTraceId();
        synchronized (localRoots) {
            if (!localRoots.containsKey(traceId)) localRoots.put(traceId, span);
        }
        return true;
    }

    public boolean isLocalRoot(ReadableSpan span) {
        return localRoots.get(span.getSpanContext().getTraceId()) == span;
    }

    public boolean hasOpenRoot(String traceId) {
        return traceId != null && localRoots.containsKey(traceId);
    }

    /** The trace's work in this process is over: forget its user. */
    public void noteEnd(ReadableSpan span) {
        String traceId = span.getSpanContext().getTraceId();
        synchronized (localRoots) {
            if (localRoots.get(traceId) != span) return;
            localRoots.remove(traceId);
        }
        byTrace.remove(traceId);
    }

    @SuppressWarnings("unchecked")
    static void put(AttributesBuilder b, String key, Object value) {
        if (value instanceof Boolean) b.put(AttributeKey.booleanKey(key), (Boolean) value);
        else if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte)
            b.put(AttributeKey.longKey(key), ((Number) value).longValue());
        else if (value instanceof Number) b.put(AttributeKey.doubleKey(key), ((Number) value).doubleValue());
        else if (value instanceof Throwable) b.put(AttributeKey.stringKey(key), value.getClass().getName() + ": " + ((Throwable) value).getMessage());
        else if (value != null) b.put(AttributeKey.stringKey(key), String.valueOf(value));
    }
}

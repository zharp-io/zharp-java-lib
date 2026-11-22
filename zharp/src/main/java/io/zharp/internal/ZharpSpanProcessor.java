package io.zharp.internal;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

/**
 * Every span: the user, session and tags as it starts; its exceptions, failed queries and — when it
 * is the request's own span — the request's outcome as it ends (see {@link ErrorTracker}).
 */
public final class ZharpSpanProcessor implements SpanProcessor {
    private final ZharpCore core;

    public ZharpSpanProcessor(ZharpCore core) {
        this.core = core;
    }

    @Override
    public void onStart(Context parent, ReadWriteSpan span) {
        try {
            core.store.noteStart(span, parent);
            Attributes stored = core.store.attributesFor(span.getSpanContext().getTraceId());
            if (!stored.isEmpty()) span.setAllAttributes(stored);
            // The browser's word on its own session and user is final.
            Attributes baggage = ContextStore.fromBaggage(parent);
            if (!baggage.isEmpty()) span.setAllAttributes(baggage);
        } catch (RuntimeException ignored) {
            // Never let telemetry break a request.
        }
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        try {
            boolean root = core.store.isLocalRoot(span);
            core.errors.spanEnded(span.toSpanData(), root);
            if (root) {
                core.store.noteEnd(span);
                core.errors.drain(span.getSpanContext().getTraceId());
            }
        } catch (RuntimeException ignored) {
            // As above.
        }
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }
}

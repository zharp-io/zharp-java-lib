package io.zharp.internal;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Spans on their way out:
 *
 * <ul>
 *   <li>each {@code exception} event is kept for the trace view, marked {@code zharp.deferred}:
 *       errors are reported once, as logs (see ErrorTracker), and the server would otherwise make
 *       one of every event too;
 *   <li>{@code zharp.build_id} on each exception event, so the code that ran can be shown;
 *   <li>secrets in URLs ({@code ?token=…}, {@code user:password@}) are filtered.
 * </ul>
 */
public final class ZharpSpanExporter implements SpanExporter {
    private static final AttributeKey<String> URL_FULL = AttributeKey.stringKey("url.full");
    private static final AttributeKey<String> HTTP_URL = AttributeKey.stringKey("http.url");
    private static final AttributeKey<String> HTTP_TARGET = AttributeKey.stringKey("http.target");
    private static final AttributeKey<String> URL_QUERY = AttributeKey.stringKey("url.query");

    private final SpanExporter delegate;

    public ZharpSpanExporter(SpanExporter delegate) {
        this.delegate = delegate;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        List<SpanData> out = new ArrayList<>(spans.size());
        for (SpanData span : spans) {
            try {
                out.add(rewrite(span));
            } catch (RuntimeException e) {
                out.add(span);
            }
        }
        return delegate.export(out);
    }

    static SpanData rewrite(SpanData span) {
        boolean events = false;
        for (EventData e : span.getEvents()) {
            if ("exception".equals(e.getName())) {
                events = true;
                break;
            }
        }
        Attributes redacted = redactUrls(span.getAttributes());
        if (!events && redacted == null) return span;
        return new Rewritten(span, events, redacted);
    }

    /** The span's attributes with secrets in its URLs filtered, or null when there were none. */
    static Attributes redactUrls(Attributes attributes) {
        AttributesBuilder b = null;
        for (AttributeKey<String> key : new AttributeKey[] {URL_FULL, HTTP_URL, HTTP_TARGET}) {
            String value = attributes.get(key);
            if (value == null) continue;
            String redacted = Secrets.redactUrl(value);
            if (!redacted.equals(value)) {
                if (b == null) b = attributes.toBuilder();
                b.put(key, redacted);
            }
        }
        String query = attributes.get(URL_QUERY);
        if (query != null) {
            String redacted = Secrets.redactQuery(query);
            if (!redacted.equals(query)) {
                if (b == null) b = attributes.toBuilder();
                b.put(URL_QUERY, redacted);
            }
        }
        return b == null ? null : b.build();
    }

    private static final class Rewritten extends DelegatingSpanData {
        private final List<EventData> events;
        private final Attributes attributes;

        Rewritten(SpanData span, boolean rewriteEvents, Attributes attributes) {
            super(span);
            this.attributes = attributes;
            if (!rewriteEvents) {
                this.events = span.getEvents();
                return;
            }
            String buildId = BuildInfo.buildId();
            List<EventData> list = new ArrayList<>(span.getEvents().size());
            for (EventData event : span.getEvents()) {
                if (!"exception".equals(event.getName())) {
                    list.add(event);
                    continue;
                }
                AttributesBuilder b = event.getAttributes().toBuilder();
                b.remove(Keys.EIDS);
                b.put(Keys.DEFERRED, "true");
                if (buildId != null) b.put(Keys.BUILD_ID, buildId);
                Attributes a = b.build();
                list.add(EventData.create(event.getEpochNanos(), event.getName(), a, Math.max(event.getTotalAttributeCount(), a.size())));
            }
            this.events = list;
        }

        @Override
        public List<EventData> getEvents() {
            return events;
        }

        @Override
        public Attributes getAttributes() {
            return attributes != null ? attributes : super.getAttributes();
        }
    }

    @Override
    public CompletableResultCode flush() {
        return delegate.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
        return delegate.shutdown();
    }

    @Override
    public String toString() {
        return "ZharpSpanExporter{" + delegate + "}";
    }
}

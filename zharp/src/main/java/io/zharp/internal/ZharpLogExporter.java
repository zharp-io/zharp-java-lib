package io.zharp.internal;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.common.Value;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.logs.data.Body;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.resources.Resource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Log records on their way out: Zharp's internal markers removed, a line whose exception was
 * already reported sent without it, and {@code zharp.build_id} on every error.
 */
public final class ZharpLogExporter implements LogRecordExporter {
    private final LogRecordExporter delegate;

    public ZharpLogExporter(LogRecordExporter delegate) {
        this.delegate = delegate;
    }

    @Override
    public CompletableResultCode export(Collection<LogRecordData> logs) {
        List<LogRecordData> out = new ArrayList<>(logs.size());
        for (LogRecordData log : logs) {
            try {
                out.add(rewrite(log));
            } catch (RuntimeException e) {
                out.add(log);
            }
        }
        return delegate.export(out);
    }

    static LogRecordData rewrite(LogRecordData log) {
        Attributes a = log.getAttributes();
        boolean strip = a.get(Keys.STRIP) != null;
        boolean error = !strip && (a.get(Keys.EXCEPTION_TYPE) != null || a.get(Keys.EXCEPTION_STACKTRACE) != null);
        String buildId = error ? BuildInfo.buildId() : null;
        if (!strip && a.get(Keys.EIDS) == null && buildId == null) return log;
        AttributesBuilder b = a.toBuilder();
        b.remove(Keys.EIDS);
        if (strip) {
            b.remove(Keys.STRIP);
            b.remove(Keys.EXCEPTION_TYPE);
            b.remove(Keys.EXCEPTION_MESSAGE);
            b.remove(Keys.EXCEPTION_STACKTRACE);
            b.remove(Keys.HANDLED);
        }
        if (buildId != null) b.put(Keys.BUILD_ID, buildId);
        return new Rewritten(log, b.build());
    }

    @SuppressWarnings("deprecation")
    private static final class Rewritten implements LogRecordData {
        private final LogRecordData d;
        private final Attributes attributes;

        Rewritten(LogRecordData d, Attributes attributes) {
            this.d = d;
            this.attributes = attributes;
        }

        @Override
        public Resource getResource() {
            return d.getResource();
        }

        @Override
        public InstrumentationScopeInfo getInstrumentationScopeInfo() {
            return d.getInstrumentationScopeInfo();
        }

        @Override
        public long getTimestampEpochNanos() {
            return d.getTimestampEpochNanos();
        }

        @Override
        public long getObservedTimestampEpochNanos() {
            return d.getObservedTimestampEpochNanos();
        }

        @Override
        public SpanContext getSpanContext() {
            return d.getSpanContext();
        }

        @Override
        public Severity getSeverity() {
            return d.getSeverity();
        }

        @Override
        public String getSeverityText() {
            return d.getSeverityText();
        }

        @Override
        public Body getBody() {
            return d.getBody();
        }

        @Override
        public Value<?> getBodyValue() {
            return d.getBodyValue();
        }

        @Override
        public Attributes getAttributes() {
            return attributes;
        }

        @Override
        public int getTotalAttributeCount() {
            return Math.max(attributes.size(), d.getTotalAttributeCount() - (d.getAttributes().size() - attributes.size()));
        }

        @Override
        public String getEventName() {
            return d.getEventName();
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
        return "ZharpLogExporter{" + delegate + "}";
    }
}

package io.zharp.internal;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.util.List;

/**
 * Logs only ({@code ZHARP_TRACING=false}): every span is recorded — so a request still has a trace
 * id for its logs, a user set with setUser() stays with its request, and a failed request is still
 * reported — and none is sampled, so nothing exports it.
 */
public final class RecordOnlySampler implements Sampler {
    @Override
    public SamplingResult shouldSample(Context parentContext, String traceId, String name, SpanKind spanKind, Attributes attributes, List<LinkData> parentLinks) {
        return SamplingResult.recordOnly();
    }

    @Override
    public String getDescription() {
        return "ZharpRecordOnlySampler";
    }
}

package io.zharp.internal;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.util.Collection;
import java.util.Collections;

/** Logs only: reads {@code traceparent} and {@code baggage} from incoming requests, writes them nowhere. */
public final class ExtractOnlyPropagator implements TextMapPropagator {
    private final TextMapPropagator inner;

    public ExtractOnlyPropagator(TextMapPropagator inner) {
        this.inner = inner;
    }

    @Override
    public Collection<String> fields() {
        return Collections.emptyList();
    }

    @Override
    public <C> void inject(Context context, C carrier, TextMapSetter<C> setter) {}

    @Override
    public <C> Context extract(Context context, C carrier, TextMapGetter<C> getter) {
        return inner.extract(context, carrier, getter);
    }
}

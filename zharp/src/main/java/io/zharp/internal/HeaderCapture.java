package io.zharp.internal;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.api.incubator.instrumenter.InstrumenterCustomizer;
import io.opentelemetry.instrumentation.api.incubator.instrumenter.InstrumenterCustomizerProvider;
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor;
import java.util.List;

/**
 * Request and response headers on every incoming request's span, as OpenTelemetry names them
 * ({@code http.request.header.user-agent}) and as the Node SDK records them: each a string, every
 * secret header as {@code [Filtered]} (it was sent; its value never leaves the app), at most 40 per
 * side, values cut at 512 characters. {@code ZHARP_CAPTURE_HEADERS=false} turns it off;
 * {@code ZHARP_CAPTURE_HEADERS_REDACT=x-tenant,x-account} filters more names.
 */
public final class HeaderCapture implements InstrumenterCustomizerProvider {
    private static final int MAX_VALUE = 512;

    @Override
    public void customize(InstrumenterCustomizer customizer) {
        try {
            if (!customizer.hasType(InstrumenterCustomizer.InstrumentationType.HTTP_SERVER)) return;
            Settings settings = ZharpRuntime.settings();
            if (!settings.captureHeaders) return;
            customizer.addAttributesExtractor(new Extractor(settings.redactHeaders));
        } catch (Throwable ignored) {
            // An older instrumentation API: no headers.
        }
    }

    static final class Extractor implements AttributesExtractor<Object, Object> {
        private final List<String> redact;

        Extractor(List<String> redact) {
            this.redact = redact;
        }

        @Override
        public void onStart(AttributesBuilder attributes, Context parentContext, Object request) {
            put(attributes, "request", HeaderReader.read(request, true));
        }

        @Override
        public void onEnd(AttributesBuilder attributes, Context context, Object request, Object response, Throwable error) {
            if (response != null) put(attributes, "response", HeaderReader.read(response, false));
        }

        private void put(AttributesBuilder attributes, String side, List<String[]> headers) {
            for (String[] h : headers) {
                String value = Secrets.isSecretHeader(h[0], redact) ? Secrets.FILTERED : (h[1].length() > MAX_VALUE ? h[1].substring(0, MAX_VALUE) + "…" : h[1]);
                attributes.put(AttributeKey.stringKey("http." + side + ".header." + h[0]), value);
            }
        }
    }
}

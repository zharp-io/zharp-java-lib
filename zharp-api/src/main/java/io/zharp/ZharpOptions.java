package io.zharp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings for {@link Zharp#init(ZharpOptions)}. Anything left unset is read from the
 * environment ({@code ZHARP_KEY}, {@code ZHARP_SERVICE}, {@code ZHARP_ENVIRONMENT}…) or the
 * matching system property ({@code -Dzharp.key=…}).
 *
 * <pre>{@code
 * Zharp.init(ZharpOptions.builder()
 *     .service("orders-api")
 *     .environment("production")
 *     .build());
 * }</pre>
 */
public final class ZharpOptions {
    final Map<String, String> values;

    private ZharpOptions(Map<String, String> values) {
        this.values = values;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<String, String> values = new HashMap<>();

        /** The application's ingest key (Application settings in Zharp). Default: {@code ZHARP_KEY}. */
        public Builder key(String key) {
            return set("zharp.key", key);
        }

        /** The service's name in Zharp. Default: {@code ZHARP_SERVICE}, else the jar or main class. */
        public Builder service(String service) {
            return set("zharp.service", service);
        }

        /** {@code production}, {@code staging}… Default: {@code ZHARP_ENVIRONMENT}. */
        public Builder environment(String environment) {
            return set("zharp.environment", environment);
        }

        /** The version being run, as {@code service.version}. Default: {@code ZHARP_RELEASE}. */
        public Builder release(String release) {
            return set("zharp.release", release);
        }

        /** Where to send. Default: {@code https://api.zharp.io/api/v1/otlp}. */
        public Builder endpoint(String endpoint) {
            return set("zharp.endpoint", endpoint);
        }

        /** Send logs (Zharp.log and, when their bridges are present, Logback and Log4j). Default off: the Zharp agent collects the app's output. */
        public Builder logs(boolean logs) {
            return set("zharp.logs", String.valueOf(logs));
        }

        /** {@code false}: logs only — no traces are sent. */
        public Builder tracing(boolean tracing) {
            return set("zharp.tracing", String.valueOf(tracing));
        }

        /** The share of traces kept, 0 to 1. Default 1. */
        public Builder sampleRate(double rate) {
            return set("zharp.sample.rate", String.valueOf(rate));
        }

        /** The request's ids at the end of each log line ({@code trace_id=… span_id=…}). Default on. */
        public Builder logCorrelation(boolean on) {
            return set("zharp.log.correlation", String.valueOf(on));
        }

        /** Report exceptions no code caught, on any thread. Default on. */
        public Builder captureUncaught(boolean on) {
            return set("zharp.capture.uncaught", String.valueOf(on));
        }

        /** More header names to send as {@code [Filtered]}. */
        public Builder redactHeaders(List<String> names) {
            return set("zharp.capture.headers.redact", String.join(",", names));
        }

        private Builder set(String name, String value) {
            if (value != null) values.put(name, value);
            return this;
        }

        public ZharpOptions build() {
            return new ZharpOptions(new HashMap<>(values));
        }
    }
}

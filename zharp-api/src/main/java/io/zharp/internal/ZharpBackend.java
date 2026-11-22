package io.zharp.internal;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.context.Context;

/**
 * What the API calls when Zharp's SDK runs in this class loader: library mode (io.zharp:zharp) or
 * the Spring Boot starter. With the agent, the API goes through OpenTelemetry's bridged API instead.
 */
public interface ZharpBackend {
    void report(Throwable error, boolean handled, Severity severity, Attributes extra, Context context);

    void setUser(Context context, Attributes user);

    void setTag(String key, Object value);

    void flush(long timeoutMillis);

    /** The SDK, or null before it is built. */
    OpenTelemetry openTelemetry();
}

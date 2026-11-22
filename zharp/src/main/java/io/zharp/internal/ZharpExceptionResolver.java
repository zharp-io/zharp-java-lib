package io.zharp.internal;

import io.opentelemetry.sdk.common.internal.ExceptionAttributeResolver;

/**
 * How every exception the SDK records is written — on a span ({@code recordException}, by any
 * instrumentation) and on a log record ({@code setException}, by the Logback and Log4j appenders
 * and Zharp's own calls): the JVM's stack form, a failed query's private values left out, the
 * query's SQLSTATE as {@code db.error.code}, and which Throwables it was ({@code zharp.eids}, used
 * to report each failure once and removed before anything is sent).
 */
public final class ZharpExceptionResolver implements ExceptionAttributeResolver {
    @Override
    public void setExceptionAttributes(AttributeSetter setter, Throwable throwable, int maxAttributeLength) {
        try {
            // On the app thread, whose class loader can see the build ID (a Spring Boot jar, a WAR).
            BuildInfo.buildId();
            Exceptions.Info info = Exceptions.describe(throwable);
            setter.setAttribute(Keys.EXCEPTION_TYPE, info.type);
            setter.setAttribute(Keys.EXCEPTION_MESSAGE, info.message);
            setter.setAttribute(Keys.EXCEPTION_STACKTRACE, info.stacktrace);
            setter.setAttribute(Keys.EIDS, info.eids);
            if (info.sqlState != null && !info.sqlState.isEmpty()) setter.setAttribute(Keys.DB_ERROR_CODE, info.sqlState);
        } catch (RuntimeException e) {
            ExceptionAttributeResolver.getDefault().setExceptionAttributes(setter, throwable, maxAttributeLength);
        }
    }
}

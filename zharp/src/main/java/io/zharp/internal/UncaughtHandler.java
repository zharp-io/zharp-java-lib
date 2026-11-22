package io.zharp.internal;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.context.Context;

/**
 * Exceptions no code caught, on any thread: reported as unhandled (the main thread's as fatal),
 * sent, then handed to whichever handler was there before — or printed as the JVM would.
 */
public final class UncaughtHandler implements Thread.UncaughtExceptionHandler {
    private static final long FLUSH_MS = 2_000;

    private final ZharpCore core;
    private final Thread.UncaughtExceptionHandler previous;

    private UncaughtHandler(ZharpCore core, Thread.UncaughtExceptionHandler previous) {
        this.core = core;
        this.previous = previous;
    }

    public static void install(ZharpCore core) {
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        if (current instanceof UncaughtHandler) return;
        Thread.setDefaultUncaughtExceptionHandler(new UncaughtHandler(core, current));
    }

    @Override
    public void uncaughtException(Thread thread, Throwable error) {
        try {
            boolean main = "main".equals(thread.getName());
            core.report(error, false, main ? Severity.FATAL : Severity.ERROR, Attributes.of(Keys.MECHANISM, "uncaught", io.opentelemetry.api.common.AttributeKey.stringKey("thread.name"), thread.getName()), Context.current());
            core.flush(FLUSH_MS);
        } catch (Throwable ignored) {
            // Never stand in the way of the crash.
        }
        if (previous != null) {
            previous.uncaughtException(thread, error);
        } else if (!(error instanceof ThreadDeath)) {
            System.err.print("Exception in thread \"" + thread.getName() + "\" ");
            error.printStackTrace(System.err);
        }
    }
}

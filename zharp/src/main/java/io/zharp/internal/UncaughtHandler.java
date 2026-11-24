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
            // A desktop app's crash: the main thread, or a Compose window's event thread (Compose closes
            // the window). Elsewhere in a desktop app (a Swing event, a worker) the app keeps running,
            // so it isn't held up: the report is on disk as soon as it's exported, and sent then.
            boolean desktop = core.settings.desktop;
            boolean fatal = desktop ? Desktop.endsApp(thread) : "main".equals(thread.getName());
            core.report(error, false, fatal ? Severity.FATAL : Severity.ERROR, Attributes.of(Keys.MECHANISM, "uncaught", io.opentelemetry.api.common.AttributeKey.stringKey("thread.name"), thread.getName()), Context.current());
            if (!desktop || fatal) core.flush(FLUSH_MS);
            else core.flushLater();
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

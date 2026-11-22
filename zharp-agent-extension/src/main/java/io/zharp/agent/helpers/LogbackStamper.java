package io.zharp.agent.helpers;

import ch.qos.logback.classic.spi.ILoggingEvent;
import io.zharp.internal.Stamp;

/** Injected into the app's class loader with Logback: stamps a laid-out event. */
public final class LogbackStamper {
    private LogbackStamper() {}

    public static String stamp(Object event, String text) {
        if (!(event instanceof ILoggingEvent)) return text;
        return Stamp.stamp(text, Stamp.ids(((ILoggingEvent) event).getMDCPropertyMap()));
    }
}

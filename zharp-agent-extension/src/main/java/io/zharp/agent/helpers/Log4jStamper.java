package io.zharp.agent.helpers;

import io.zharp.internal.Stamp;
import org.apache.logging.log4j.core.LogEvent;

/** Injected into the app's class loader with Log4j 2: stamps a laid-out event in its buffer. */
public final class Log4jStamper {
    private Log4jStamper() {}

    public static void stamp(Object event, StringBuilder text) {
        if (!(event instanceof LogEvent) || text == null) return;
        Stamp.stamp(text, Stamp.ids(((LogEvent) event).getContextData().toMap()));
    }
}

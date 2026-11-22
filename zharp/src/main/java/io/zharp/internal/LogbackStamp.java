package io.zharp.internal;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggerContextListener;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.Layout;
import ch.qos.logback.core.LayoutBase;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import ch.qos.logback.core.spi.AppenderAttachable;
import java.util.Iterator;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

/**
 * Without the agent (library mode, the Spring Boot starter): the stamp on Logback's pattern
 * layouts, by wrapping each console/file appender's layout. Kept on through Logback being
 * reconfigured (Spring Boot does at startup).
 */
public final class LogbackStamp {
    private LogbackStamp() {}

    public static boolean available() {
        try {
            Class.forName("ch.qos.logback.classic.LoggerContext", false, LogbackStamp.class.getClassLoader());
            return LoggerFactory.getILoggerFactory() instanceof LoggerContext;
        } catch (Throwable e) {
            return false;
        }
    }

    public static void install() {
        try {
            ILoggerFactory factory = LoggerFactory.getILoggerFactory();
            if (!(factory instanceof LoggerContext)) return;
            LoggerContext context = (LoggerContext) factory;
            wrapAll(context);
            for (LoggerContextListener l : context.getCopyOfListenerList()) {
                if (l instanceof Listener) return;
            }
            context.addListener(new Listener());
        } catch (Throwable ignored) {
            // Lines go out unstamped.
        }
    }

    static void wrapAll(LoggerContext context) {
        for (ch.qos.logback.classic.Logger logger : context.getLoggerList()) {
            for (Iterator<Appender<ILoggingEvent>> it = logger.iteratorForAppenders(); it.hasNext(); ) wrap(it.next(), 0);
        }
    }

    @SuppressWarnings("unchecked")
    private static void wrap(Appender<ILoggingEvent> appender, int depth) {
        if (depth > 3) return;
        if (appender instanceof AppenderAttachable) {
            for (Iterator<Appender<ILoggingEvent>> it = ((AppenderAttachable<ILoggingEvent>) appender).iteratorForAppenders(); it.hasNext(); ) wrap(it.next(), depth + 1);
        }
        if (!(appender instanceof OutputStreamAppender)) return;
        Encoder<ILoggingEvent> encoder = ((OutputStreamAppender<ILoggingEvent>) appender).getEncoder();
        if (!(encoder instanceof LayoutWrappingEncoder)) return;
        LayoutWrappingEncoder<ILoggingEvent> wrapping = (LayoutWrappingEncoder<ILoggingEvent>) encoder;
        Layout<ILoggingEvent> layout = wrapping.getLayout();
        if (layout == null || layout instanceof StampingLayout) return;
        StampingLayout stamping = new StampingLayout(layout);
        stamping.setContext(appender.getContext());
        stamping.start();
        wrapping.setLayout(stamping);
    }

    static final class StampingLayout extends LayoutBase<ILoggingEvent> {
        private final Layout<ILoggingEvent> inner;

        StampingLayout(Layout<ILoggingEvent> inner) {
            this.inner = inner;
        }

        @Override
        public String doLayout(ILoggingEvent event) {
            String text = inner.doLayout(event);
            return Stamp.stamp(text, Stamp.ids(event.getMDCPropertyMap()));
        }

        @Override
        public String getFileHeader() {
            return inner.getFileHeader();
        }

        @Override
        public String getPresentationHeader() {
            return inner.getPresentationHeader();
        }

        @Override
        public String getPresentationFooter() {
            return inner.getPresentationFooter();
        }

        @Override
        public String getFileFooter() {
            return inner.getFileFooter();
        }

        @Override
        public String getContentType() {
            return inner.getContentType();
        }
    }

    static final class Listener implements LoggerContextListener {
        @Override
        public boolean isResetResistant() {
            return true;
        }

        @Override
        public void onStart(LoggerContext context) {
            wrapAll(context);
        }

        @Override
        public void onReset(LoggerContext context) {}

        @Override
        public void onStop(LoggerContext context) {}

        @Override
        public void onLevelChange(ch.qos.logback.classic.Logger logger, ch.qos.logback.classic.Level level) {}
    }
}

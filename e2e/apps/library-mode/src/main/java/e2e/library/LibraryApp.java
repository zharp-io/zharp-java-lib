package e2e.library;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.zharp.Zharp;
import io.zharp.ZharpUser;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A command-line job in library mode. The caller's trace comes in TRACEPARENT and BAGGAGE (the
 * OpenTelemetry convention for passing context to a process); the job continues it.
 */
public class LibraryApp {
    private static final Logger log = LoggerFactory.getLogger(LibraryApp.class);

    public static void main(String[] args) throws Exception {
        Zharp.init();

        Map<String, String> carrier = new HashMap<>();
        if (System.getenv("TRACEPARENT") != null) carrier.put("traceparent", System.getenv("TRACEPARENT"));
        if (System.getenv("BAGGAGE") != null) carrier.put("baggage", System.getenv("BAGGAGE"));
        Context caller = Zharp.openTelemetry().getPropagators().getTextMapPropagator().extract(Context.root(), carrier, MapGetter.INSTANCE);

        try (Scope ignored = caller.makeCurrent()) {
            Zharp.startSpan("GET /hello", span -> {
                log.info("hello from the e2e app");
                return null;
            });
        }

        Zharp.startSpan("load user", span -> {
            Zharp.setUser(ZharpUser.of("7").withEmail("7@example.com"));
            try {
                Integer.parseInt("not a number");
            } catch (NumberFormatException e) {
                Zharp.captureError(e);
            }
            try {
                Object value = null;
                value.toString();
            } catch (NullPointerException e) {
                log.error("could not read the value", e);
            }
            return null;
        });

        Thread worker = new Thread(() -> Zharp.startSpan("worker job", span -> {
            throw new UnsupportedOperationException("crash in a worker thread");
        }), "e2e-worker");
        worker.start();
        worker.join();

        // The job itself fails: an uncaught exception on the main thread, inside its span.
        Zharp.startSpan("main job", span -> {
            throw new IllegalStateException("boom from the controller");
        });
    }

    enum MapGetter implements TextMapGetter<Map<String, String>> {
        INSTANCE;

        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    }
}

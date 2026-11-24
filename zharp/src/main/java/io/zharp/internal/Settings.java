package io.zharp.internal;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Where to send, under which name, and what to capture: explicit values first, then the
 * {@code zharp.*} system property, then the {@code ZHARP_*} environment variable — the same
 * variables as the Node and Python SDKs.
 *
 * <pre>
 * key            ZHARP_KEY, ZHARP_API_KEY                  (zharp.key)
 * service        ZHARP_SERVICE, OTEL_SERVICE_NAME           (zharp.service)
 * environment    ZHARP_ENVIRONMENT, ZHARP_ENV               (zharp.environment)
 * release        ZHARP_RELEASE, ZHARP_VERSION               (zharp.release)
 * endpoint       ZHARP_ENDPOINT                             (zharp.endpoint)
 * logs           ZHARP_LOGS              default false      (zharp.logs)
 * tracing        ZHARP_TRACING           default true       (zharp.tracing)
 * log stamp      ZHARP_LOG_CORRELATION   default true       (zharp.log.correlation)
 * headers        ZHARP_CAPTURE_HEADERS   default true       (zharp.capture.headers)
 * failed queries ZHARP_CAPTURE_QUERY_ERRORS default true    (zharp.capture.query.errors)
 * uncaught       ZHARP_CAPTURE_UNCAUGHT  default true       (zharp.capture.uncaught)
 * sample rate    ZHARP_SAMPLE_RATE       default 1          (zharp.sample.rate)
 * desktop app    ZHARP_DESKTOP           default false      (zharp.desktop)
 * desktop data   ZHARP_DATA_DIR                             (zharp.data.dir)
 * off            ZHARP_DISABLED                             (zharp.disabled)
 * </pre>
 */
public final class Settings {
    public static final String DEFAULT_ENDPOINT = "https://api.zharp.io/api/v1/otlp";

    private static final Pattern TRUE = Pattern.compile("^(true|1|yes|on)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FALSE = Pattern.compile("^(false|0|no|off|none)$", Pattern.CASE_INSENSITIVE);

    public final String key;
    public final String service;
    public final String environment;
    public final String release;
    public final String endpoint;
    public final boolean logs;
    public final boolean tracing;
    public final boolean logCorrelation;
    public final boolean captureHeaders;
    public final List<String> redactHeaders;
    public final boolean captureQueryErrors;
    public final boolean captureUncaught;
    public final double sampleRate;
    public final boolean debug;
    /** A desktop app: crashes kept on disk until sent, windows as screens, the device and session (see Desktop). */
    public final boolean desktop;
    public final String dataDir;

    /** Values given in code or configuration (the Spring Boot starter's zharp.* properties), by property name. */
    public interface Source {
        String get(String property);
    }

    private final Source explicit;

    private Settings(Source explicit) {
        this.explicit = explicit;
        this.key = pick(value("zharp.key", "ZHARP_KEY"), value("zharp.api-key", "ZHARP_API_KEY"));
        this.service = orElse(pick(value("zharp.service", "ZHARP_SERVICE"), env("OTEL_SERVICE_NAME")), defaultService());
        this.environment = pick(value("zharp.environment", "ZHARP_ENVIRONMENT"), value("zharp.env", "ZHARP_ENV"));
        this.desktop = flag("zharp.desktop", "ZHARP_DESKTOP", false);
        this.dataDir = value("zharp.data.dir", "ZHARP_DATA_DIR");
        // A packaged desktop app (jpackage, which Compose's native distributions use) knows its version.
        this.release = pick(value("zharp.release", "ZHARP_RELEASE"), value("zharp.version", "ZHARP_VERSION"), desktop ? System.getProperty("jpackage.app-version") : null);
        String ep = orElse(value("zharp.endpoint", "ZHARP_ENDPOINT"), DEFAULT_ENDPOINT);
        while (ep.endsWith("/")) ep = ep.substring(0, ep.length() - 1);
        this.endpoint = ep;
        this.logs = flag("zharp.logs", "ZHARP_LOGS", false);
        this.tracing = flag("zharp.tracing", "ZHARP_TRACING", true);
        this.logCorrelation = flag("zharp.log.correlation", "ZHARP_LOG_CORRELATION", true);
        this.captureHeaders = flag("zharp.capture.headers", "ZHARP_CAPTURE_HEADERS", true);
        this.redactHeaders = list(value("zharp.capture.headers.redact", "ZHARP_CAPTURE_HEADERS_REDACT"));
        this.captureQueryErrors = flag("zharp.capture.query.errors", "ZHARP_CAPTURE_QUERY_ERRORS", true);
        this.captureUncaught = flag("zharp.capture.uncaught", "ZHARP_CAPTURE_UNCAUGHT", true);
        double rate = 1.0;
        String r = value("zharp.sample.rate", "ZHARP_SAMPLE_RATE");
        if (r != null) {
            try {
                rate = Double.parseDouble(r);
            } catch (NumberFormatException ignored) {
                rate = 1.0;
            }
        }
        this.sampleRate = Math.min(1.0, Math.max(0.0, rate));
        this.debug = flag("zharp.debug", "ZHARP_DEBUG", false);
    }

    /** From system properties and the environment only. */
    public static Settings fromEnvironment() {
        return new Settings(property -> null);
    }

    /** {@code explicit} first (code, or Spring properties), then system properties and the environment. */
    public static Settings from(Source explicit) {
        return new Settings(explicit == null ? property -> null : explicit);
    }

    /** {@code ZHARP_DISABLED=true}: start nothing, whatever the code asks for. */
    public static boolean disabled() {
        return TRUE.matcher(orElse(pick(System.getProperty("zharp.disabled"), env("ZHARP_DISABLED")), "")).matches();
    }

    public boolean hasKey() {
        return key != null;
    }

    private String value(String property, String variable) {
        return pick(explicit.get(property), System.getProperty(property), env(variable));
    }

    private boolean flag(String property, String variable, boolean fallback) {
        Boolean b = flagOf(value(property, variable));
        return b == null ? fallback : b;
    }

    public static Boolean flagOf(String value) {
        if (value == null) return null;
        if (TRUE.matcher(value.trim()).matches()) return Boolean.TRUE;
        if (FALSE.matcher(value.trim()).matches()) return Boolean.FALSE;
        return null;
    }

    public static String env(String name) {
        try {
            String value = System.getenv(name);
            return value == null || value.trim().isEmpty() ? null : value.trim();
        } catch (SecurityException e) {
            return null;
        }
    }

    /** The first non-blank value. */
    public static String pick(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return null;
    }

    private static String orElse(String value, String fallback) {
        return value != null ? value : fallback;
    }

    private static List<String> list(String value) {
        if (value == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String part : value.split(",")) {
            if (!part.trim().isEmpty()) out.add(part.trim());
        }
        return out;
    }

    /**
     * A service name when none is set: a packaged app's launcher, the jar the JVM was started with ("orders-api-1.4.2.jar"
     * is "orders-api"), else the main class ("com.shop.OrdersApplication" is "orders-application"),
     * else "java-app".
     */
    static String defaultService() {
        // A packaged app (jpackage, Compose's native distributions): its launcher's name.
        String launcher = System.getProperty("jpackage.app-path");
        if (launcher != null && !launcher.trim().isEmpty()) {
            String name = new File(launcher.trim()).getName().replaceAll("(?i)\\.exe$", "");
            if (!name.isEmpty()) return name.toLowerCase(Locale.ROOT);
        }
        String command = System.getProperty("sun.java.command");
        if (command != null && !command.trim().isEmpty()) {
            String first = command.trim().split("\\s+")[0];
            if (first.endsWith(".jar") || first.endsWith(".war")) {
                String name = new File(first).getName();
                name = name.substring(0, name.length() - 4);
                // A version on the end is the build's, not the service's.
                name = name.replaceAll("-\\d+(\\.\\d+)*([.-][A-Za-z0-9.]+)?$", "");
                if (!name.isEmpty()) return name.toLowerCase(Locale.ROOT);
            } else if (first.matches("[\\w.$]+")) {
                String cls = first.substring(first.lastIndexOf('.') + 1);
                if (cls.endsWith("Kt")) cls = cls.substring(0, cls.length() - 2);
                String kebab = cls.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
                if (!kebab.isEmpty() && !kebab.equals("main")) return kebab;
            }
        }
        return "java-app";
    }

    /** {@code service "orders-api", environment "production"} — for the startup line. */
    public String describeTarget() {
        return "service \"" + service + "\", " + (environment != null ? "environment \"" + environment + "\"" : "no environment set");
    }

    /** Settings as a map, for tests and debugging. Never includes the key. */
    @Override
    public String toString() {
        return "Settings{service=" + service + ", environment=" + environment + ", endpoint=" + endpoint + ", logs=" + logs + ", tracing=" + tracing + "}";
    }

    /** For callers holding a plain map (the Spring Boot starter's bound properties). */
    public static Source of(Map<String, String> values) {
        return values::get;
    }
}

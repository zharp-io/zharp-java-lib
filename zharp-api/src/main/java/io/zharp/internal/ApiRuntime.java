package io.zharp.internal;

/** Where the API sends its calls: a backend in this class loader, the agent, or nowhere. */
public final class ApiRuntime {
    /** System property the agent sets, so the API in the application knows to go through it. */
    public static final String AGENT_PROPERTY = "io.zharp.agent.version";

    private static volatile ZharpBackend backend;

    private ApiRuntime() {}

    public static ZharpBackend backend() {
        return backend;
    }

    public static void install(ZharpBackend b) {
        backend = b;
    }

    public static boolean agentPresent() {
        return System.getProperty(AGENT_PROPERTY) != null;
    }

    private static final boolean DEBUG = flag(System.getProperty("zharp.debug")) || flag(System.getenv("ZHARP_DEBUG"));

    private static boolean flag(String value) {
        return value != null && value.trim().matches("(?i)true|1|yes|on");
    }

    /** ZHARP_DEBUG=true: what went wrong inside Zharp, on stderr. Otherwise nothing. */
    public static void debug(String what, Throwable error) {
        if (!DEBUG) return;
        System.err.println("[Zharp] " + what + ": " + error);
        error.printStackTrace(System.err);
    }

    public static void debug(String message) {
        if (DEBUG) System.err.println("[Zharp] " + message);
    }

    /** One line on stderr, prefixed so it can be searched for. */
    public static void announce(String message) {
        System.err.println("[Zharp] " + message);
    }
}

package io.zharp.internal;

/** The process's Zharp core, where library mode or the Spring Boot starter started one. */
public final class ZharpRuntime {
    private static volatile ZharpCore core;
    private static volatile Settings settings;

    private ZharpRuntime() {}

    public static ZharpCore core() {
        return core;
    }

    public static void install(ZharpCore c) {
        core = c;
        settings = c.settings;
    }

    public static Settings settings() {
        Settings s = settings;
        if (s == null) {
            s = Settings.fromEnvironment();
            settings = s;
        }
        return s;
    }

    public static void settings(Settings s) {
        settings = s;
    }

    /** System property the agent sets, so the API in the application knows to go through it. */
    public static final String AGENT_PROPERTY = "io.zharp.agent.version";

    public static boolean agentPresent() {
        return System.getProperty(AGENT_PROPERTY) != null;
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

    private static final boolean DEBUG = Boolean.TRUE.equals(Settings.flagOf(Settings.pick(System.getProperty("zharp.debug"), Settings.env("ZHARP_DEBUG"))));
}

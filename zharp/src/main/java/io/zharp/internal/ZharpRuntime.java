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
        ApiRuntime.install(c);
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
    public static final String AGENT_PROPERTY = ApiRuntime.AGENT_PROPERTY;

    public static boolean agentPresent() {
        return ApiRuntime.agentPresent();
    }

    public static void debug(String what, Throwable error) {
        ApiRuntime.debug(what, error);
    }

    public static void debug(String message) {
        ApiRuntime.debug(message);
    }
}

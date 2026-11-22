package io.zharp.agent;

import io.zharp.internal.Settings;
import io.zharp.internal.ZharpCore;
import io.zharp.internal.ZharpRuntime;

/** The agent's Zharp core: made when the SDK is configured, attached once the SDK is built. */
final class ZharpAgent {
    static volatile ZharpCore core;

    private ZharpAgent() {}

    static synchronized ZharpCore core() {
        if (core == null) {
            Settings settings = Settings.fromEnvironment();
            core = new ZharpCore(settings);
            ZharpRuntime.settings(settings);
        }
        return core;
    }
}

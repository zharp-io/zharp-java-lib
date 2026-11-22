package io.zharp.agent;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.javaagent.extension.AgentListener;
import io.zharp.internal.BuildInfo;
import io.zharp.internal.Settings;
import io.zharp.internal.UncaughtHandler;
import io.zharp.internal.Version;
import io.zharp.internal.ZharpCore;
import io.zharp.internal.ZharpRuntime;

/** Once the agent's SDK is built: reports go through it, uncaught exceptions are caught, and the API is told the agent is here. */
public final class ZharpAgentListener implements AgentListener {
    @Override
    public void afterAgent(AutoConfiguredOpenTelemetrySdk autoConfigured) {
        ZharpCore core = ZharpAgent.core();
        Settings s = core.settings;
        if (Settings.disabled() || !s.hasKey()) return;
        OpenTelemetrySdk sdk = autoConfigured.getOpenTelemetrySdk();
        core.attach(sdk);
        ZharpRuntime.install(core);
        BuildInfo.init(null);
        if (s.captureUncaught) UncaughtHandler.install(core);
        System.setProperty(ZharpRuntime.AGENT_PROPERTY, Version.VERSION);
        String build = BuildInfo.buildId();
        ZharpCore.announce("Application monitoring active" + (s.tracing ? "" : " (logs only)") + " — " + s.describeTarget()
                + (s.logs ? ", logs on" : "") + (build != null ? ", build " + build : "") + " (agent " + Version.VERSION + ")");
    }
}

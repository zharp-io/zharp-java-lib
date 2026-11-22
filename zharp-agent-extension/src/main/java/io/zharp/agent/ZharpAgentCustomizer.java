package io.zharp.agent;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.zharp.internal.Settings;
import io.zharp.internal.ZharpAutoConfiguration;
import io.zharp.internal.ZharpCore;
import java.util.HashMap;
import java.util.Map;

/**
 * The agent's SDK, configured for Zharp from ZHARP_* (see Settings). With no key, or with
 * ZHARP_DISABLED, the SDK is turned off: nothing is sent.
 */
public final class ZharpAgentCustomizer implements AutoConfigurationCustomizerProvider {
    @Override
    public void customize(AutoConfigurationCustomizer customizer) {
        ZharpCore core = ZharpAgent.core();
        Settings s = core.settings;
        if (Settings.disabled() || !s.hasKey()) {
            ZharpCore.announce(Settings.disabled()
                    ? "Disabled by ZHARP_DISABLED — nothing will be sent"
                    : "No key found — set ZHARP_KEY (the application's ingest key). Nothing will be sent.");
            customizer.addPropertiesCustomizer(config -> {
                Map<String, String> off = new HashMap<>();
                off.put("otel.sdk.disabled", "true");
                return off;
            });
            return;
        }
        // Defaults the app may still override with OTEL_* / -Dotel.*.
        customizer.addPropertiesSupplier(() -> {
            Map<String, String> p = new HashMap<>();
            p.put("otel.instrumentation.micrometer.enabled", "false");
            p.put("otel.instrumentation.runtime-telemetry.enabled", "false");
            return p;
        });
        ZharpAutoConfiguration.customize(customizer, core);
    }

    @Override
    public int order() {
        // After the agent's own customizers: Zharp's settings are the last word.
        return Integer.MAX_VALUE;
    }
}

package io.zharp.spring;

import io.zharp.internal.Settings;
import org.springframework.core.env.Environment;

/**
 * Zharp's settings from Spring's environment: {@code zharp.key}, {@code zharp.service},
 * {@code zharp.environment}, {@code zharp.endpoint}, {@code zharp.logs}… in application
 * properties or YAML — and, through Spring's relaxed binding, the same ZHARP_* environment
 * variables as everywhere else. The service defaults to {@code spring.application.name}.
 */
final class ZharpSpringSettings {
    private ZharpSpringSettings() {}

    static Settings from(Environment environment) {
        return Settings.from(property -> {
            String value = environment.getProperty(property);
            if (value == null && "zharp.service".equals(property)) value = environment.getProperty("spring.application.name");
            return value;
        });
    }
}

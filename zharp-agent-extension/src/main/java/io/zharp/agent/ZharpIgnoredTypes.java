package io.zharp.agent;

import io.opentelemetry.javaagent.extension.ignore.IgnoredTypesBuilder;
import io.opentelemetry.javaagent.extension.ignore.IgnoredTypesConfigurer;

/** The agent leaves logging libraries alone by default; the layouts Zharp stamps are let through. */
public final class ZharpIgnoredTypes implements IgnoredTypesConfigurer {
    @Override
    public void configure(IgnoredTypesBuilder builder) {
        builder.allowClass("ch.qos.logback.classic.PatternLayout");
        builder.allowClass("org.apache.logging.log4j.core.layout.PatternLayout$");
    }
}

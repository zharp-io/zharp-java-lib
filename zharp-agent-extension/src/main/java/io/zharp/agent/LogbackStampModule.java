package io.zharp.agent;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.zharp.internal.Settings;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * The stamp on Logback's pattern layouts — the console and file lines Spring Boot and most apps
 * write — as {@code … trace_id=… span_id=…}, from the event's MDC (OpenTelemetry's Logback MDC
 * instrumentation puts the ids there, async appenders included). ZHARP_LOG_CORRELATION=false turns it off.
 */
public final class LogbackStampModule extends InstrumentationModule {
    public LogbackStampModule() {
        super("zharp-logback-stamp");
    }

    @Override
    public boolean defaultEnabled(ConfigProperties config) {
        return Settings.fromEnvironment().logCorrelation;
    }

    @Override
    public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
        return AgentElementMatchers.hasClassesNamed("ch.qos.logback.classic.PatternLayout");
    }

    @Override
    public boolean isHelperClass(String className) {
        return className.startsWith("io.zharp.");
    }

    @Override
    public List<String> getAdditionalHelperClassNames() {
        return Arrays.asList("io.zharp.internal.Stamp", "io.zharp.agent.helpers.LogbackStamper");
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return Collections.singletonList(new Layout());
    }

    static final class Layout implements TypeInstrumentation {
        @Override
        public ElementMatcher<TypeDescription> typeMatcher() {
            return named("ch.qos.logback.classic.PatternLayout");
        }

        @Override
        public void transform(TypeTransformer transformer) {
            transformer.applyAdviceToMethod(named("doLayout").and(takesArguments(1)).and(returns(String.class)), LogbackStampModule.class.getName() + "$DoLayoutAdvice");
        }
    }

    @SuppressWarnings("unused")
    public static final class DoLayoutAdvice {
        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void exit(@Advice.Argument(0) Object event, @Advice.Return(readOnly = false) String text) {
            text = io.zharp.agent.helpers.LogbackStamper.stamp(event, text);
        }
    }
}

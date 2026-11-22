package io.zharp.agent;

import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
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
 * The stamp on Log4j 2's PatternLayout: both of its serializers lay an event out into a
 * StringBuilder, stamped there with the ids from the event's context data (OpenTelemetry's Log4j
 * context-data instrumentation adds them).
 */
public final class Log4jStampModule extends InstrumentationModule {
    public Log4jStampModule() {
        super("zharp-log4j-stamp");
    }

    @Override
    public boolean defaultEnabled(ConfigProperties config) {
        return Settings.fromEnvironment().logCorrelation;
    }

    @Override
    public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
        return AgentElementMatchers.hasClassesNamed("org.apache.logging.log4j.core.layout.PatternLayout");
    }

    @Override
    public boolean isHelperClass(String className) {
        return className.startsWith("io.zharp.");
    }

    @Override
    public List<String> getAdditionalHelperClassNames() {
        return Arrays.asList("io.zharp.internal.Stamp", "io.zharp.agent.helpers.Log4jStamper");
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return Collections.singletonList(new Serializers());
    }

    static final class Serializers implements TypeInstrumentation {
        @Override
        public ElementMatcher<TypeDescription> typeMatcher() {
            // PatternSerializer and PatternSelectorSerializer before 2.13; NoFormat-, PatternFormatter-
            // and PatternSelectorSerializer since (PatternSerializer became their interface).
            return nameStartsWith("org.apache.logging.log4j.core.layout.PatternLayout$").and(not(isInterface()));
        }

        @Override
        public void transform(TypeTransformer transformer) {
            transformer.applyAdviceToMethod(named("toSerializable").and(takesArguments(2)).and(not(isAbstract())), Log4jStampModule.class.getName() + "$ToSerializableAdvice");
        }
    }

    @SuppressWarnings("unused")
    public static final class ToSerializableAdvice {
        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void exit(@Advice.Argument(0) Object event, @Advice.Return StringBuilder text) {
            io.zharp.agent.helpers.Log4jStamper.stamp(event, text);
        }
    }
}

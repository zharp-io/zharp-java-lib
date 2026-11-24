package io.zharp.gradle;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.gradle.api.DomainObjectCollection;
import org.gradle.api.Project;
import org.gradle.api.file.SourceDirectorySet;

/**
 * The Kotlin Multiplatform plugin's model, read reflectively so this plugin doesn't depend on
 * Kotlin's Gradle plugin (and works with whichever version the build uses): {@code kotlin.targets},
 * each target's {@code platformType} and main compilation, and {@code kotlin.sourceSets}.
 */
final class KotlinMultiplatform {
    private KotlinMultiplatform() {}

    /** The resources of each JVM target's main compilation ({@code jvmMain}, {@code desktopMain}…), as targets are added. */
    @SuppressWarnings("unchecked")
    static void eachJvmMainResources(Project project, Consumer<SourceDirectorySet> action) {
        Object kotlin = project.getExtensions().findByName("kotlin");
        if (kotlin == null) return;
        DomainObjectCollection<Object> targets = (DomainObjectCollection<Object>) call(kotlin, "getTargets");
        if (targets == null) return;
        targets.all(target -> {
            Object type = call(target, "getPlatformType");
            if (type == null || !"jvm".equals(type.toString())) return;
            DomainObjectCollection<Object> compilations = (DomainObjectCollection<Object>) call(target, "getCompilations");
            if (compilations == null) return;
            compilations.all(compilation -> {
                if (!"main".equals(call(compilation, "getName"))) return;
                Object sourceSet = call(compilation, "getDefaultSourceSet");
                Object resources = sourceSet == null ? null : call(sourceSet, "getResources");
                if (resources instanceof SourceDirectorySet) action.accept((SourceDirectorySet) resources);
            });
        });
    }

    /** Every main source set's Kotlin and Java folders (commonMain, desktopMain, jvmMain…). */
    @SuppressWarnings("unchecked")
    static List<File> mainSourceDirs(Project project) {
        List<File> dirs = new ArrayList<>();
        if (!project.getPlugins().hasPlugin("org.jetbrains.kotlin.multiplatform")) return dirs;
        Object kotlin = project.getExtensions().findByName("kotlin");
        Object sets = kotlin == null ? null : call(kotlin, "getSourceSets");
        if (!(sets instanceof Iterable)) return dirs;
        for (Object set : (Iterable<Object>) sets) {
            Object name = call(set, "getName");
            if (!(name instanceof String) || !((String) name).endsWith("Main")) continue;
            Object kotlinDirs = call(set, "getKotlin");
            if (kotlinDirs instanceof SourceDirectorySet) dirs.addAll(((SourceDirectorySet) kotlinDirs).getSrcDirs());
            dirs.add(project.file("src/" + name + "/java"));
        }
        return dirs;
    }

    private static Object call(Object target, String method) {
        try {
            Method m = target.getClass().getMethod(method);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Exception e) {
            return null;
        }
    }
}

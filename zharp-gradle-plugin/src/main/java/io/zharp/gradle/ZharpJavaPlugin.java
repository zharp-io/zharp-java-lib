package io.zharp.gradle;

import io.zharp.build.ArtifactUploader;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;

/**
 * {@code plugins { id("io.zharp.java") }} — on the app, or the root project; either way it covers the
 * whole build:
 *
 * <ul>
 *   <li>every Java/Kotlin project in the build — Kotlin Multiplatform ones too (their JVM targets) —
 *       gets {@code generateZharpBuildId}, writing the same build ID into its jar
 *       ({@code META-INF/zharp/build.properties});
 *   <li>{@code uploadZharpSources} uploads the sources of all of them, once, under that ID, with paths
 *       from the root project — after any of them is assembled, or packaged by Compose for Desktop.
 * </ul>
 */
public final class ZharpJavaPlugin implements Plugin<Project> {
    static final String GENERATE = "generateZharpBuildId";
    static final String UPLOAD = "uploadZharpSources";

    @Override
    public void apply(Project project) {
        ZharpExtension extension = project.getExtensions().create("zharp", ZharpExtension.class);
        ProviderFactory providers = project.getProviders();
        extension.getAppId().convention(env(providers, "ZHARP_APP_ID").orElse(env(providers, "ZHARP_APP")).orElse(providers.gradleProperty("zharp.appId")));
        extension.getAuthToken().convention(env(providers, "ZHARP_AUTH_TOKEN").orElse(providers.gradleProperty("zharp.authToken")));
        extension.getUrl().convention(env(providers, "ZHARP_URL").orElse(providers.gradleProperty("zharp.url")).orElse(ArtifactUploader.DEFAULT_URL));
        extension.getRelease().convention(env(providers, "ZHARP_RELEASE").orElse(providers.gradleProperty("zharp.release")));
        extension.getIncludeSources().convention(env(providers, "ZHARP_INCLUDE_SOURCES").orElse(providers.gradleProperty("zharp.includeSources")).map(v -> !"false".equals(v.trim())).orElse(true));
        extension.getFailOnUploadError().convention(true);

        Project root = project.getRootProject();
        // Applied in several projects of one build: the first one does the work for all of them.
        if (root.getExtensions().getExtraProperties().has("zharpBuild")) return;
        root.getExtensions().getExtraProperties().set("zharpBuild", project.getPath());

        FileCollection sources = project.files((Callable<List<File>>) () -> sourceDirs(root));
        TaskProvider<UploadSourcesTask> upload = project.getTasks().register(UPLOAD, UploadSourcesTask.class, task -> {
            task.setGroup("zharp");
            task.setDescription("Uploads the Java and Kotlin sources of every project in the build to Zharp under the build ID.");
            task.onlyIf("zharp.includeSources is on", t -> extension.getIncludeSources().get());
            task.getSourceDirs().from(sources);
            task.getRootDir().set(root.getProjectDir());
            task.getAppId().set(extension.getAppId());
            task.getAuthToken().set(extension.getAuthToken());
            task.getUrl().set(extension.getUrl());
            task.getRelease().set(extension.getRelease());
            task.getFailOnUploadError().set(extension.getFailOnUploadError());
        });

        root.allprojects(each -> {
            each.getPlugins().withType(JavaPlugin.class, java -> {
                SourceSet main = each.getExtensions().getByType(SourceSetContainer.class).getByName(SourceSet.MAIN_SOURCE_SET_NAME);
                main.getResources().srcDir(generateTask(each, root, sources).flatMap(GenerateBuildIdTask::getOutputDir));
                each.getTasks().named("assemble", task -> task.finalizedBy(upload));
            });
            // Kotlin Multiplatform (Compose Multiplatform's desktop app): no Java plugin; each JVM
            // target's main compilation gets the build ID in its resources.
            each.getPlugins().withId("org.jetbrains.kotlin.multiplatform", kmp -> {
                TaskProvider<GenerateBuildIdTask> generate = generateTask(each, root, sources);
                KotlinMultiplatform.eachJvmMainResources(each, resources -> resources.srcDir(generate.flatMap(GenerateBuildIdTask::getOutputDir)));
                each.getTasks().named("assemble", task -> task.finalizedBy(upload));
            });
            // Compose for Desktop's packaging (createDistributable, packageDmg, packageReleaseMsi…) doesn't run assemble.
            each.getTasks().configureEach(task -> {
                if (COMPOSE_PACKAGING.matcher(task.getName()).matches()) task.finalizedBy(upload);
            });
        });
    }

    static final java.util.regex.Pattern COMPOSE_PACKAGING = java.util.regex.Pattern.compile(
            "^(create|package)(Release)?(Distributable|DistributionForCurrentOS|UberJarForCurrentOS|Dmg|Pkg|Msi|Exe|Deb|Rpm|AppImage)$");

    private static TaskProvider<GenerateBuildIdTask> generateTask(Project each, Project root, FileCollection sources) {
        if (each.getTasks().getNames().contains(GENERATE)) return each.getTasks().named(GENERATE, GenerateBuildIdTask.class);
        return each.getTasks().register(GENERATE, GenerateBuildIdTask.class, task -> {
            task.setGroup("zharp");
            task.setDescription("Writes the build ID Zharp knows this build's sources by into the jar.");
            task.getSourceDirs().from(sources);
            task.getRootDir().set(root.getProjectDir());
            task.getOutputDir().set(each.getLayout().getBuildDirectory().dir("generated/zharp/resources"));
        });
    }

    private static Provider<String> env(ProviderFactory providers, String name) {
        return providers.environmentVariable(name).filter(v -> !v.trim().isEmpty());
    }

    /** Every Java/Kotlin project's main source folders (generated ones are skipped by the bundle). */
    static List<File> sourceDirs(Project root) {
        List<File> dirs = new ArrayList<>();
        for (Project p : root.getAllprojects()) {
            SourceSetContainer sets = p.getExtensions().findByType(SourceSetContainer.class);
            if (sets == null || sets.findByName(SourceSet.MAIN_SOURCE_SET_NAME) == null) continue;
            dirs.addAll(sets.getByName(SourceSet.MAIN_SOURCE_SET_NAME).getJava().getSrcDirs());
            dirs.add(p.file("src/main/kotlin"));
        }
        for (Project p : root.getAllprojects()) dirs.addAll(KotlinMultiplatform.mainSourceDirs(p));
        return dirs;
    }
}

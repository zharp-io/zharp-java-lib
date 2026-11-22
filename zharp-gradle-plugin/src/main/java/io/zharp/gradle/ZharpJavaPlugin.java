package io.zharp.gradle;

import io.zharp.build.ArtifactUploader;
import java.io.File;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;

/**
 * {@code plugins { id("io.zharp.java") }} on a Java or Kotlin JVM app:
 *
 * <ul>
 *   <li>{@code generateZharpBuildId} writes the build ID into the jar ({@code META-INF/zharp/build.properties});
 *   <li>{@code uploadZharpSources} uploads the app's sources under it, after {@code assemble}.
 * </ul>
 */
public final class ZharpJavaPlugin implements Plugin<Project> {
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

        project.getPlugins().withType(JavaPlugin.class, java -> configure(project, extension));
    }

    private static Provider<String> env(ProviderFactory providers, String name) {
        return providers.environmentVariable(name).filter(v -> !v.trim().isEmpty());
    }

    private static void configure(Project project, ZharpExtension extension) {
        SourceSet main = project.getExtensions().getByType(SourceSetContainer.class).getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        File root = project.getRootDir();
        ConfigurableFileCollection sources = project.files(main.getJava().getSrcDirs(), project.file("src/main/kotlin"));

        TaskProvider<GenerateBuildIdTask> generate = project.getTasks().register("generateZharpBuildId", GenerateBuildIdTask.class, task -> {
            task.setGroup("zharp");
            task.setDescription("Writes the build ID Zharp knows this build's sources by into the jar.");
            task.getSourceDirs().from(sources);
            task.getRootDir().set(root);
            task.getOutputDir().set(project.getLayout().getBuildDirectory().dir("generated/zharp/resources"));
        });
        main.getResources().srcDir(generate.flatMap(GenerateBuildIdTask::getOutputDir));

        TaskProvider<UploadSourcesTask> upload = project.getTasks().register("uploadZharpSources", UploadSourcesTask.class, task -> {
            task.setGroup("zharp");
            task.setDescription("Uploads the app's Java and Kotlin sources to Zharp under the build ID.");
            task.dependsOn(generate);
            task.onlyIf("zharp.includeSources is on", t -> extension.getIncludeSources().get());
            task.getSourceDirs().from(sources);
            task.getBuildIdDir().set(generate.flatMap(GenerateBuildIdTask::getOutputDir));
            task.getRootDir().set(root);
            task.getAppId().set(extension.getAppId());
            task.getAuthToken().set(extension.getAuthToken());
            task.getUrl().set(extension.getUrl());
            task.getRelease().set(extension.getRelease());
            task.getFailOnUploadError().set(extension.getFailOnUploadError());
        });
        project.getTasks().named("assemble", task -> task.finalizedBy(upload));
    }
}

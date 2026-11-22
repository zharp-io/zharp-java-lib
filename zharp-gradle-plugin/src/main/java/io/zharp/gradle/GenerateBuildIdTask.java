package io.zharp.gradle;

import io.zharp.build.SourceBundle;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Writes {@code META-INF/zharp/build.properties} (the build ID) into the jar's resources. The ID
 * comes from the sources themselves, so it changes exactly when the code does, and the task stays
 * cacheable. The Zharp agent, starter and library send it as {@code zharp.build_id} on every error.
 */
@CacheableTask
public abstract class GenerateBuildIdTask extends DefaultTask {
    @InputFiles
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSourceDirs();

    /** Where bundle paths start from (the root project). Part of the ID through the relative paths. */
    @Internal
    public abstract Property<File> getRootDir();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @TaskAction
    public void generate() throws IOException {
        String id = SourceBundle.buildId(SourceBundle.collect(getSourceDirs().getFiles(), getRootDir().get()));
        File file = new File(getOutputDir().get().getAsFile(), SourceBundle.RESOURCE);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), SourceBundle.properties(id).getBytes(StandardCharsets.UTF_8));
    }
}

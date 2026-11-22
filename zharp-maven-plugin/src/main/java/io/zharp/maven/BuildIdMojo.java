package io.zharp.maven;

import io.zharp.build.SourceBundle;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * Writes {@code META-INF/zharp/build.properties} — the build ID — into the jar's resources. The ID
 * comes from the sources themselves, so it changes exactly when the code does. The Zharp agent,
 * starter and library send it as {@code zharp.build_id} on every error.
 */
@Mojo(name = "build-id", defaultPhase = LifecyclePhase.GENERATE_RESOURCES, threadSafe = true)
public class BuildIdMojo extends ZharpMojo {
    @Override
    public void execute() throws MojoExecutionException {
        try {
            String id = SourceBundle.buildId(SourceBundle.collect(sourceDirs(), rootDir()));
            File out = outputDirectory();
            File file = new File(out, SourceBundle.RESOURCE);
            file.getParentFile().mkdirs();
            Files.write(file.toPath(), SourceBundle.properties(id).getBytes(StandardCharsets.UTF_8));
            Resource resource = new Resource();
            resource.setDirectory(out.getAbsolutePath());
            resource.setIncludes(Collections.singletonList("META-INF/zharp/**"));
            project.addResource(resource);
            getLog().info("Zharp: build " + id);
        } catch (IOException e) {
            throw new MojoExecutionException("Zharp: couldn't write the build ID: " + e.getMessage(), e);
        }
    }
}

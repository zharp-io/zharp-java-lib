package io.zharp.gradle;

import io.zharp.build.ArtifactUploader;
import io.zharp.build.SourceBundle;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/**
 * Uploads the app's own Java and Kotlin sources as a {@code source-bundle} artifact under the
 * build ID, so its errors show the exact code of that build — no repository needed. With no token
 * it's a dry run that says what it would send.
 */
@DisableCachingByDefault(because = "Uploads to Zharp")
public abstract class UploadSourcesTask extends DefaultTask {
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSourceDirs();

    @Internal
    public abstract DirectoryProperty getBuildIdDir();

    @Internal
    public abstract Property<File> getRootDir();

    @Input
    @Optional
    public abstract Property<String> getAppId();

    /** A secret: kept out of task inputs (and so out of build caches and scans). */
    @Internal
    public abstract Property<String> getAuthToken();

    @Input
    public abstract Property<String> getUrl();

    @Input
    @Optional
    public abstract Property<String> getRelease();

    @Input
    public abstract Property<Boolean> getFailOnUploadError();

    public UploadSourcesTask() {
        getOutputs().upToDateWhen(task -> false);
    }

    @TaskAction
    public void upload() throws Exception {
        File properties = new File(getBuildIdDir().get().getAsFile(), SourceBundle.RESOURCE);
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(properties)) {
            p.load(in);
        }
        String buildId = p.getProperty(SourceBundle.PROPERTY);
        Map<String, String> files = SourceBundle.collect(getSourceDirs().getFiles(), getRootDir().get());
        if (files.isEmpty()) {
            getLogger().lifecycle("Zharp: no Java or Kotlin sources found to upload.");
            return;
        }
        String token = getAuthToken().getOrElse("").trim();
        String appId = getAppId().getOrElse("").trim();
        if (token.isEmpty()) {
            getLogger().lifecycle("Zharp: build " + buildId + " — dry run (no ZHARP_AUTH_TOKEN / zharp.authToken): would upload " + files.size() + " source file(s) as kind=source-bundle.");
            return;
        }
        if (appId.isEmpty()) {
            throw new GradleException("Zharp: set zharp { appId = \"…\" } (or ZHARP_APP_ID) to upload the sources; a token is set but no application ID.");
        }
        byte[] bundle = SourceBundle.gzippedJson(files);
        try {
            new ArtifactUploader(getUrl().get(), token, "zharp-java-gradle/" + Version.VERSION).uploadSourceBundle(appId, buildId, getRelease().getOrNull(), bundle);
            getLogger().lifecycle("Zharp: uploaded the app's sources (" + files.size() + " files, " + bundle.length / 1024 + " kB gzipped) for build " + buildId + " — errors show the code of this build.");
        } catch (Exception error) {
            String message = "Zharp: couldn't upload the sources for build " + buildId + ": " + error.getMessage();
            if (getFailOnUploadError().get()) throw new GradleException(message, error);
            getLogger().warn(message);
        }
    }
}

package io.zharp.maven;

import io.zharp.build.ArtifactUploader;
import io.zharp.build.SourceBundle;
import java.util.Map;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Uploads the app's own Java and Kotlin sources as a {@code source-bundle} artifact under the
 * build ID (written by {@code build-id}), so its errors show the exact code of that build — no
 * repository needed. With no token it's a dry run that says what it would send.
 *
 * <p>Every setting can come from the environment: {@code ZHARP_APP_ID} (or {@code ZHARP_APP}),
 * {@code ZHARP_AUTH_TOKEN} (a workspace API key with {@code artifacts:write}), {@code ZHARP_URL}.
 */
@Mojo(name = "upload-sources", defaultPhase = LifecyclePhase.PACKAGE, threadSafe = true)
public class UploadSourcesMojo extends ZharpMojo {
    /** The application's ID in Zharp. */
    @Parameter(property = "zharp.appId")
    private String appId;

    /** A workspace API key with the artifacts:write scope. A secret: set ZHARP_AUTH_TOKEN rather than writing it in the POM. */
    @Parameter(property = "zharp.authToken")
    private String authToken;

    /** Zharp's API. Default https://api.zharp.io. */
    @Parameter(property = "zharp.url")
    private String url;

    @Parameter(property = "zharp.release")
    private String release;

    /** Upload the sources (default on). */
    @Parameter(property = "zharp.includeSources", defaultValue = "true")
    private boolean includeSources;

    /** Fail the build when the upload fails (default on). */
    @Parameter(property = "zharp.failOnUploadError", defaultValue = "true")
    private boolean failOnUploadError;

    @Override
    public void execute() throws MojoExecutionException {
        if (!includeSources || "false".equals(env("ZHARP_INCLUDE_SOURCES"))) return;
        // Once per build: the first module to get here uploads the sources of all of them.
        if (!session.getRepositorySession().getData().set(UploadSourcesMojo.class.getName(), null, Boolean.TRUE)) return;
        String buildId;
        Map<String, String> files;
        try {
            files = SourceBundle.collect(sourceDirs(), rootDir());
            // The same ID build-id wrote into every module: it comes from these sources.
            buildId = SourceBundle.buildId(files);
        } catch (Exception e) {
            throw new MojoExecutionException("Zharp: " + e.getMessage(), e);
        }
        if (files.isEmpty()) {
            getLog().info("Zharp: no Java or Kotlin sources found to upload.");
            return;
        }
        String token = first(authToken, env("ZHARP_AUTH_TOKEN"));
        String app = first(appId, env("ZHARP_APP_ID"), env("ZHARP_APP"));
        if (token == null) {
            getLog().info("Zharp: build " + buildId + " — dry run (no ZHARP_AUTH_TOKEN / zharp.authToken): would upload " + files.size() + " source file(s) as kind=source-bundle.");
            return;
        }
        if (app == null) throw new MojoExecutionException("Zharp: set <appId> (or ZHARP_APP_ID) to upload the sources; a token is set but no application ID.");
        try {
            byte[] bundle = SourceBundle.gzippedJson(files);
            new ArtifactUploader(first(url, env("ZHARP_URL")), token, "zharp-maven-plugin/0.2.0").uploadSourceBundle(app, buildId, first(release, env("ZHARP_RELEASE")), bundle);
            getLog().info("Zharp: uploaded the app's sources (" + files.size() + " files, " + bundle.length / 1024 + " kB gzipped) for build " + buildId + " — errors show the code of this build.");
        } catch (Exception e) {
            String message = "Zharp: couldn't upload the sources for build " + buildId + ": " + e.getMessage();
            if (failOnUploadError) throw new MojoExecutionException(message, e);
            getLog().warn(message);
        }
    }
}

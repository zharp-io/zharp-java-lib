package io.zharp.maven;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/** What both goals share: the project's sources, where bundle paths start from, and where the build ID goes. */
abstract class ZharpMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    protected MavenSession session;

    /** Where the build ID file is written (added to the jar's resources). */
    @Parameter(defaultValue = "${project.build.directory}/generated-resources/zharp", required = true)
    protected File outputDirectory;

    File outputDirectory() {
        return outputDirectory;
    }

    /**
     * The Java source roots and src/main/kotlin of every module in the build (the reactor), so one
     * build ID covers them all and a library module's frames show their code too. Generated sources
     * (target/) are left out by the bundle.
     */
    List<File> sourceDirs() {
        List<File> dirs = new ArrayList<>();
        List<MavenProject> modules = session.getProjects() == null || session.getProjects().isEmpty() ? java.util.Collections.singletonList(project) : session.getProjects();
        for (MavenProject module : modules) {
            for (String root : module.getCompileSourceRoots()) dirs.add(new File(root));
            dirs.add(new File(module.getBasedir(), "src/main/java"));
            dirs.add(new File(module.getBasedir(), "src/main/kotlin"));
        }
        return dirs;
    }

    /** Paths in the bundle start from the top of the build (the multi-module root), like a repository's. */
    File rootDir() {
        File top = session.getRequest().getMultiModuleProjectDirectory();
        if (top == null) top = session.getTopLevelProject() != null ? session.getTopLevelProject().getBasedir() : project.getBasedir();
        return top;
    }

    static String env(String name) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    static String first(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return null;
    }
}

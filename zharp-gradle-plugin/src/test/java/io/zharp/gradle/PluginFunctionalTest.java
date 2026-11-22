package io.zharp.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A real build with the plugin: the build ID lands in the jar, and without a token the upload is a dry run. */
class PluginFunctionalTest {
    @TempDir
    File project;

    private void write(String path, String text) throws IOException {
        File file = new File(project, path);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void buildIdInTheJarAndADryRunUpload() throws IOException {
        write("settings.gradle", "rootProject.name = 'shop'\n");
        write("build.gradle", "plugins { id 'java'\n id 'io.zharp.java' }\n");
        write("src/main/java/io/shop/Cart.java", "package io.shop;\npublic class Cart { public int total() { return 1; } }\n");

        BuildResult result = GradleRunner.create()
                .withProjectDir(project)
                .withPluginClasspath()
                .withArguments("assemble", "--stacktrace")
                .withEnvironment(java.util.Collections.singletonMap("PATH", System.getenv("PATH")))
                .build();

        Matcher dryRun = Pattern.compile("Zharp: build ([0-9a-f-]{36}) — dry run").matcher(result.getOutput());
        assertTrue(dryRun.find(), result.getOutput());
        String id = null;
        try (JarFile jar = new JarFile(new File(project, "build/libs/shop.jar"))) {
            for (Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
                JarEntry entry = e.nextElement();
                if (entry.getName().equals("META-INF/zharp/build.properties")) {
                    String text = new String(readAll(jar, entry), StandardCharsets.UTF_8);
                    Matcher m = Pattern.compile("io\\.zharp\\.build\\.id=(\\S+)").matcher(text);
                    if (m.find()) id = m.group(1);
                }
            }
        }
        assertEquals(dryRun.group(1), id);
    }

    private static byte[] readAll(JarFile jar, JarEntry entry) throws IOException {
        try (java.io.InputStream in = jar.getInputStream(entry)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }
}

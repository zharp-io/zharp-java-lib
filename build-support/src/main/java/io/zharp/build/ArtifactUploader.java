package io.zharp.build;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * {@code POST <url>/api/v1/applications/<appId>/artifacts} as multipart/form-data — the text
 * fields, then the file (part {@code file}) — with a workspace API key that has the
 * {@code artifacts:write} scope ({@code Authorization: Bearer zh_…}). The same request as the
 * Android Gradle plugin and {@code @zharp/cli}. Network errors, 429 and 5xx are retried.
 */
public final class ArtifactUploader {
    public static final String DEFAULT_URL = "https://api.zharp.io";

    public static final class Failed extends IOException {
        public final boolean retryable;

        Failed(String message, boolean retryable) {
            super(message);
            this.retryable = retryable;
        }
    }

    private final String url;
    private final String token;
    private final String userAgent;
    private final long[] retryDelays;

    public ArtifactUploader(String url, String token, String userAgent) {
        this(url, token, userAgent, new long[] {2_000, 5_000});
    }

    ArtifactUploader(String url, String token, String userAgent, long[] retryDelays) {
        String u = url == null || url.trim().isEmpty() ? DEFAULT_URL : url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        this.url = u;
        this.token = token;
        this.userAgent = userAgent;
        this.retryDelays = retryDelays;
    }

    /** A source bundle, uploaded under {@code buildId}. */
    public void uploadSourceBundle(String appId, String buildId, String release, byte[] gzippedJson) throws IOException, InterruptedException {
        java.util.LinkedHashMap<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("kind", "source-bundle");
        fields.put("debugId", buildId);
        fields.put("name", "sources.json");
        if (release != null && !release.isEmpty()) fields.put("release", release);
        upload(appId, fields, gzippedJson, "sources.json.gz", "application/gzip");
    }

    public void upload(String appId, Map<String, String> fields, byte[] file, String fileName, String contentType) throws IOException, InterruptedException {
        URL target = new URL(url + "/api/v1/applications/" + URLEncoder.encode(appId, "UTF-8") + "/artifacts");
        for (int attempt = 0; ; attempt++) {
            try {
                send(target, fields, file, fileName, contentType);
                return;
            } catch (IOException error) {
                boolean retryable = !(error instanceof Failed) || ((Failed) error).retryable;
                if (!retryable || attempt >= retryDelays.length) throw error;
                Thread.sleep(retryDelays[attempt]);
            }
        }
    }

    private void send(URL target, Map<String, String> fields, byte[] file, String fileName, String contentType) throws IOException {
        String boundary = "zharp-" + UUID.randomUUID().toString().replace("-", "");
        StringBuilder head = new StringBuilder();
        for (Map.Entry<String, String> f : fields.entrySet()) {
            head.append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"").append(f.getKey()).append("\"\r\n\r\n")
                    .append(f.getValue()).append("\r\n");
        }
        head.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(fileName).append("\"\r\n")
                .append("Content-Type: ").append(contentType).append("\r\n\r\n");
        byte[] headBytes = head.toString().getBytes(StandardCharsets.UTF_8);
        byte[] tailBytes = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

        HttpURLConnection connection = (HttpURLConnection) target.openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(30_000);
            connection.setReadTimeout(5 * 60_000);
            connection.setFixedLengthStreamingMode(headBytes.length + file.length + tailBytes.length);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setRequestProperty("User-Agent", userAgent);
            connection.setRequestProperty("Accept", "application/json");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(headBytes);
                out.write(file);
                out.write(tailBytes);
            }
            int status = connection.getResponseCode();
            if (status >= 200 && status < 300) return;
            String body = read(connection.getErrorStream());
            String hint;
            switch (status) {
                case 401: hint = " (the token isn't a valid Zharp API key — use a workspace API key, not the app's ingest key)"; break;
                case 403: hint = " (the API key needs the artifacts:write scope, in the application's workspace)"; break;
                case 404: hint = " (no application with that ID in the key's workspace)"; break;
                case 413: hint = " (the file is larger than Zharp accepts)"; break;
                default: hint = "";
            }
            throw new Failed("HTTP " + status + hint + (body.isEmpty() ? "" : ": " + body), status == 429 || status >= 500);
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = stream.read(buffer)) > 0 && out.size() < 2000) out.write(buffer, 0, n);
            String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            return text.length() > 500 ? text.substring(0, 500) : text;
        }
    }
}

package io.zharp.gradle;

import org.gradle.api.provider.Property;

/**
 * <pre>
 * zharp {
 *     appId = "123"   // the application's ID in Zharp
 *     // authToken: a workspace API key with the artifacts:write scope. Keep it out of build files:
 *     // ZHARP_AUTH_TOKEN in CI, or zharp.authToken in ~/.gradle/gradle.properties.
 * }
 * </pre>
 *
 * Every option also reads from the environment ({@code ZHARP_APP_ID} or {@code ZHARP_APP},
 * {@code ZHARP_AUTH_TOKEN}, {@code ZHARP_URL}) and Gradle properties ({@code zharp.appId},
 * {@code zharp.authToken}, {@code zharp.url}). Without a token the upload is a dry run.
 */
public abstract class ZharpExtension {
    /** The application's ID in Zharp. */
    public abstract Property<String> getAppId();

    /** A workspace API key ({@code zh_…}) with the {@code artifacts:write} scope. A secret. */
    public abstract Property<String> getAuthToken();

    /** Zharp's API. Default {@code https://api.zharp.io}. */
    public abstract Property<String> getUrl();

    /** The release the bundle belongs to (optional; shown with it in Zharp). */
    public abstract Property<String> getRelease();

    /** Upload the app's sources (default on). {@code false} still writes the build ID. */
    public abstract Property<Boolean> getIncludeSources();

    /** Fail the build when the upload fails (default on), so a release never ships without its code unnoticed. */
    public abstract Property<Boolean> getFailOnUploadError();
}

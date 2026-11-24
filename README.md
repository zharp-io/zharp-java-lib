# Zharp for Java

Errors, traces and logs from Java and Kotlin servers, sent to [Zharp](https://zharp.io): Spring Boot,
Spring, plain servlets, Ktor, Quarkus, Micronaut, Javalin — anything on the JVM. Java 8+.

| Artifact | What it is |
|---|---|
| `io.zharp:zharp-agent` | **The agent** (recommended): OpenTelemetry's Java agent with Zharp's settings and additions, one jar. No code changes. |
| `io.zharp:zharp-api` | The API — `Zharp.captureError`, `setUser`, `setTag`, `log`, `startSpan`, `flush` — through the agent or the starter. Tiny: OpenTelemetry's API only. |
| `io.zharp:zharp` | Library mode, for apps that run without the agent: the API plus OpenTelemetry's SDK and exporter, started by `Zharp.init()`. |
| `io.zharp:zharp-spring-boot-starter` | Spring Boot without an agent (GraalVM native images, hosts that forbid agents). |
| `io.zharp.java` (Gradle), `io.zharp:zharp-maven-plugin` | Build plugins: errors show the code of the build that ran. |

Built on OpenTelemetry's Java instrumentation 2.34.0 and SDK 1.68.0.

## What you get

- **Errors with their stack, once each.** A request that fails is reported once — not again for
  every span the exception crossed, nor for the container's log line about it. Uncaught exceptions
  on any thread are reported. Exceptions you log at ERROR are reported as handled.
- **Failed queries** with the statement, the database and the SQLSTATE (Postgres errors named by
  their code: `UniqueViolation`). Row values are never sent.
- **Every request and response header**, secrets (`authorization`, `cookie`, API keys, tokens…) as `[Filtered]`.
- **Traces** of every library OpenTelemetry's agent knows: Spring MVC and WebFlux, Servlet,
  JAX-RS, Ktor, JDBC, Hibernate, R2DBC, Kafka, RabbitMQ, gRPC, HTTP clients, Redis, Mongo…
- **The user and session** of the browser that made the request (from `@zharp/browser`'s `baggage`), or `Zharp.setUser`.
- **Logs** off by default — the Zharp agent collects your app's output — with every Logback and
  Log4j 2 line written during a request ending in `trace_id=… span_id=…`, so it's linked to it.
  `ZHARP_LOGS=true` sends them straight to Zharp instead.

## Spring Boot, Spring, any Java app: the agent

1. Download the agent once:

   ```sh
   curl -fLo zharp-agent.jar https://github.com/zharp-io/zharp-java-lib/releases/latest/download/zharp-agent.jar
   ```

   (or `io.zharp:zharp-agent:0.1.0` from Maven Central).

2. Start the app with it and your app's key:

   ```sh
   export ZHARP_KEY=zpk_…            # Application settings in Zharp
   export ZHARP_SERVICE=orders-api   # optional: defaults to the jar's name
   export ZHARP_ENVIRONMENT=production
   java -javaagent:zharp-agent.jar -jar app.jar
   ```

   The first line it prints says it's on: `[Zharp] Application monitoring active — service "orders-api", environment "production"`.

### Docker

```dockerfile
FROM eclipse-temurin:21-jre
ADD https://github.com/zharp-io/zharp-java-lib/releases/latest/download/zharp-agent.jar /opt/zharp-agent.jar
COPY build/libs/app.jar /app.jar
ENV JAVA_TOOL_OPTIONS="-javaagent:/opt/zharp-agent.jar"
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

Pass `ZHARP_KEY` at run time (`docker run -e ZHARP_KEY=zpk_… …`), not in the image.

### `JAVA_TOOL_OPTIONS`: when you can't change the command

Every JVM started with it picks the agent up — Tomcat, Jetty, WildFly, a platform's start script:

```sh
export JAVA_TOOL_OPTIONS="-javaagent:/opt/zharp-agent.jar"
```

For a standalone Tomcat, `CATALINA_OPTS="-javaagent:/opt/zharp-agent.jar"` in `bin/setenv.sh` does the same.

### Gradle `bootRun` / `run`

```kotlin
// build.gradle.kts
val zharpAgent = configurations.create("zharpAgent")
dependencies { zharpAgent("io.zharp:zharp-agent:0.1.0") }

tasks.named<JavaExec>("bootRun") {   // "run" for the application plugin (Kotlin, Ktor…)
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-javaagent:${zharpAgent.singleFile}") })
}
```

### Maven `spring-boot:run`

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-dependency-plugin</artifactId>
  <executions>
    <execution>
      <id>zharp-agent</id>
      <phase>initialize</phase>
      <goals><goal>copy</goal></goals>
      <configuration>
        <artifactItems>
          <artifactItem>
            <groupId>io.zharp</groupId>
            <artifactId>zharp-agent</artifactId>
            <version>0.1.0</version>
            <destFileName>zharp-agent.jar</destFileName>
          </artifactItem>
        </artifactItems>
        <outputDirectory>${project.build.directory}</outputDirectory>
      </configuration>
    </execution>
  </executions>
</plugin>
<plugin>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-maven-plugin</artifactId>
  <configuration>
    <agents><agent>${project.build.directory}/zharp-agent.jar</agent></agents>
  </configuration>
</plugin>
```

## Desktop apps: Compose for Desktop, Swing, JavaFX

Library mode with `desktop(true)`, first thing in `main`:

```kotlin
// composeApp/build.gradle.kts: desktopMain (or jvmMain) dependencies
implementation("io.zharp:zharp:0.2.0")

fun main() {
    Zharp.init(ZharpOptions.builder().key("zpk_…").desktop(true).build())
    application { Window(onCloseRequest = ::exitApplication, title = "Shop") { App() } }
}
```

- Batches are written to disk before they're sent; what didn't arrive (offline, a crash, quitting)
  goes on the next launch. The folder: `%LOCALAPPDATA%\Zharp\<service>`, `~/Library/Caches/Zharp/<service>`,
  `~/.cache/zharp/<service>`, or `dataDir(…)`.
- A crash on a Compose window's thread is reported as fatal; each frame or dialog brought to the
  front is the screen (`Zharp.setTag("screen.name", …)` inside one window).
- OS, device, session and the packaged app's version (Compose's `packageVersion`) on every error.
- The Gradle plugin (`io.zharp.java`) works in Kotlin Multiplatform modules and uploads after
  Compose's `createDistributable` / `package*` tasks. Keep ProGuard's `obfuscate` off (Compose's
  default) and keep `io.zharp.**` and `io.opentelemetry.**`.

## Logback and Log4j 2

Nothing to add: with the agent, every line Logback or Log4j 2 writes during a request through a
pattern layout (Spring Boot's console format, `%d %-5level %logger - %msg%n`…) ends with the
request's `trace_id=… span_id=…`, so the Zharp agent links what it collects. JSON encoders get
`trace_id` and `span_id` fields from the MDC.

To send logs straight to Zharp (with their level, logger, thread and exception):

```sh
export ZHARP_LOGS=true
```

## The API: `io.zharp:zharp-api`

```kotlin
implementation("io.zharp:zharp-api:0.1.0")    // Gradle
```
```xml
<dependency><groupId>io.zharp</groupId><artifactId>zharp-api</artifactId><version>0.1.0</version></dependency>
```

```java
import io.zharp.Zharp;
import io.zharp.ZharpUser;

Zharp.setUser(ZharpUser.of(user.getId()).withEmail(user.getEmail()));  // this request's user
Zharp.setTag("region", "eu-west-1");                                   // on everything from now on

try {
    charge(order);
} catch (PaymentException e) {
    Zharp.captureError(e);                                             // handled, in this request's trace
}

Zharp.log.info("Order placed", Map.of("orderId", order.getId()));      // always sent
Zharp.startSpan("rebuild-index", span -> index.rebuild());
Zharp.flush();                                                         // before a short-lived process exits
```

With the agent or the starter these go through them (the starter already includes the API). Nothing here throws into your app.

## Library mode: no agent

For tests, CLIs and jobs, or wherever agents aren't allowed, use `io.zharp:zharp` instead of `zharp-api` (same classes, plus the SDK) — and first thing in `main`:

```kotlin
implementation("io.zharp:zharp:0.1.0")
```


```java
Zharp.init();   // reads ZHARP_KEY, ZHARP_SERVICE… — or:
Zharp.init(ZharpOptions.builder().key("zpk_…").service("nightly-export").environment("production").build());
```

Only what you wrap with `Zharp.startSpan` is traced; errors (`captureError`, uncaught exceptions on
any thread) and `Zharp.log` are sent, and Logback lines are stamped. With
`io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0` (or the Log4j 2 one) on the
classpath, `ZHARP_LOGS=true` sends their records too. A job started by another service continues
its trace from `TRACEPARENT` / `BAGGAGE` with OpenTelemetry's propagator
(`Zharp.openTelemetry().getPropagators()`).

## Spring Boot without the agent: the starter

```kotlin
dependencyManagement {
    // OpenTelemetry's versions, not the older ones Spring Boot manages.
    imports { mavenBom("io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom:2.34.0") }
}
dependencies {
    implementation("io.zharp:zharp-spring-boot-starter:0.1.0")
}
```

```properties
# application.properties — or ZHARP_KEY, ZHARP_SERVICE… in the environment
zharp.key=${ZHARP_KEY}
zharp.environment=production
# zharp.service defaults to spring.application.name
# zharp.logs=true
```

It's OpenTelemetry's Spring Boot starter (Spring MVC and WebFlux, `RestTemplate`/`WebClient`,
JDBC, Logback, Kafka, MongoDB, R2DBC) configured for Zharp, with everything above: errors once,
failed queries, headers, users, the log-line stamp. Spring Boot 2.7 and 3.x. The agent covers more
libraries; the starter also works in GraalVM native images.

## The code that ran: build plugins

Errors show the source around each of your frames. Link a repository in Zharp, or upload each
build's sources with a build plugin. The plugin writes a build ID into the jar
(`META-INF/zharp/build.properties`) and uploads your `.java`/`.kt` files under it; the agent, the
starter and library mode send that ID with every error, so the code shown is that build's.

In a multi-module build the plugin covers every module: each module's jar gets the same build ID,
and one upload holds the sources of all of them, with paths from the root project — so an error
thrown in a library module shows that module's code too.

```kotlin
// Gradle: on the app (or the root project) — it covers every project in the build
plugins { id("io.zharp.java") version "0.1.0" }
zharp { appId = "123" }   // the application's ID in Zharp
```

```xml
<!-- Maven: in the parent POM's <build><plugins>, so every module gets the build ID -->
<plugin>
  <groupId>io.zharp</groupId>
  <artifactId>zharp-maven-plugin</artifactId>
  <version>0.1.0</version>
  <configuration><appId>123</appId></configuration>
  <executions>
    <execution><goals><goal>build-id</goal><goal>upload-sources</goal></goals></execution>
  </executions>
</plugin>
```

The upload needs a workspace API key with the `artifacts:write` scope in `ZHARP_AUTH_TOKEN` (CI
secrets, or `zharp.authToken` in `~/.gradle/gradle.properties`). Without one the build still gets
its ID and says what it would upload. The app ID can also come from `ZHARP_APP_ID`; `ZHARP_URL`
points at another Zharp. Gradle runs the upload after `assemble`, Maven at `package`.
`includeSources = false` / `<includeSources>false</includeSources>` writes the ID only.

## Settings

| Variable (or `-D` system property) | Default | |
|---|---|---|
| `ZHARP_KEY` (`ZHARP_API_KEY`) | — | The application's ingest key. Without it nothing is sent. |
| `ZHARP_SERVICE` | jar or main class name | `service.name` |
| `ZHARP_ENVIRONMENT` (`ZHARP_ENV`) | — | `deployment.environment.name` |
| `ZHARP_RELEASE` (`ZHARP_VERSION`) | — | `service.version` |
| `ZHARP_ENDPOINT` | `https://api.zharp.io/api/v1/otlp` | |
| `ZHARP_LOGS` | `false` | Send Logback / Log4j 2 records. |
| `ZHARP_TRACING` | `true` | `false`: logs only — requests still get trace ids, no spans are sent. |
| `ZHARP_LOG_CORRELATION` | `true` | The `trace_id=… span_id=…` stamp. |
| `ZHARP_CAPTURE_HEADERS` | `true` | Request and response headers. |
| `ZHARP_CAPTURE_HEADERS_REDACT` | — | More header names to filter, comma-separated. |
| `ZHARP_CAPTURE_QUERY_ERRORS` | `true` | Failed queries reported with their statement. |
| `ZHARP_CAPTURE_UNCAUGHT` | `true` | Uncaught exceptions on any thread. |
| `ZHARP_SAMPLE_RATE` | `1` | Share of traces kept. |
| `ZHARP_DISABLED` | — | `true`: send nothing. |
| `ZHARP_DEBUG` | — | Print what goes wrong inside Zharp. |

System properties use the lower-case dotted names: `-Dzharp.key=…`, `-Dzharp.logs=true`. With the
agent, OpenTelemetry's own `OTEL_*` / `-Dotel.*` settings still work for everything Zharp doesn't
set (turning an instrumentation off, for one: `-Dotel.instrumentation.jdbc.enabled=false`).

## Building

```sh
./gradlew build                         # library, agent (zharp-agent/build/release/zharp-agent.jar), starter, Gradle plugin, unit tests
(cd zharp-maven-plugin && ./mvnw verify)
```

End-to-end tests run each app in `e2e/` against a local Zharp backend: `e2e/build.sh` (see `e2e/run.mjs`).

Publishing to Maven Central (the same account and signing key as `io.zharp:android`): `./gradlew publishToMavenCentral` and
`cd zharp-maven-plugin && ./mvnw -Prelease deploy`, with the credentials and signing key from
`~/.gradle/gradle.properties` / `~/.m2/settings.xml`. Attach `zharp-agent/build/release/zharp-agent.jar`
to the GitHub release so the download URL above works.

## License

MIT

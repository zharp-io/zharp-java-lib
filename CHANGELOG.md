# Changelog

## 0.2.0 — unreleased

- **Desktop apps** (`ZharpOptions.desktop(true)` / `ZHARP_DESKTOP=true`, library mode): Compose
  Multiplatform for Desktop, Swing and JavaFX. Every batch is written to disk before it is sent and
  deleted once Zharp has it; what didn't arrive (offline, a crash, a quit mid-send) is sent by the
  next launch (200 files, 8 MB, 3 days). An exception on a Compose window's thread is reported as
  the crash it is; other threads' uncaught exceptions don't hold the app up. Each frame/dialog that
  comes to the front is the screen (`screen.name`, a `screen <title>` span). OS, `host.arch`, a
  per-install `device.id` and a per-launch `session.id` on everything; the release defaults to a
  packaged app's version (`jpackage.app-version`), the service to its launcher's name.
  `dataDir(…)` / `ZHARP_DATA_DIR` moves the folder. A jlink'd runtime (Compose's
  `createDistributable`) needs `modules("java.instrument", "java.sql", "jdk.unsupported")`, as
  `./gradlew suggestRuntimeModules` lists.
- **Gradle plugin:** Kotlin Multiplatform modules (each JVM target's main resources get the build
  ID; every `*Main` source set is uploaded), and Compose for Desktop's packaging tasks
  (`createDistributable`, `packageDmg`, `packageReleaseMsi`…) upload the sources too.
- Library mode in an app without Logback: `Zharp.init()` failed (Logback's types were resolved
  while starting the log stamp); the stamp is only set up when Logback is there. `Zharp.init`
  now says what failed rather than `InvocationTargetException`.
- The build plugins' shared uploader (`build-support/…/io/zharp/build`) is in the repository: a
  `build/` ignore rule had kept it out.

## 0.1.0 — 2026-11-22

First release: `io.zharp:zharp-agent`, `io.zharp:zharp-api`, `io.zharp:zharp`, `io.zharp:zharp-spring-boot-starter`,
Gradle plugin `io.zharp.java` and `io.zharp:zharp-maven-plugin`. Built on OpenTelemetry's Java
agent and instrumentation 2.34.0 and SDK 1.68.0; Java 8+.

- **Agent** (`java -javaagent:zharp-agent.jar -jar app.jar`): OpenTelemetry's Java agent with
  Zharp's settings and additions in one jar. Traces every library the agent knows (Spring MVC and
  WebFlux, Servlet, JAX-RS, Ktor, JDBC, Hibernate, R2DBC, Kafka, HTTP clients, Redis, Mongo…),
  configured by `ZHARP_KEY`, `ZHARP_SERVICE`, `ZHARP_ENVIRONMENT` and the other `ZHARP_*` variables.
- **Errors, once each:** a request that fails is reported once with the exception it failed with —
  not again for every span the exception crossed, and not again for the container's log line about
  it. Wrappers (`ServletException`, `ExecutionException`…) are reported as what they wrap.
  Uncaught exceptions on any thread are reported (the main thread's as fatal). Exceptions logged
  at ERROR are reported as handled, even with logs off.
- **Failed queries:** the statement, database and SQLSTATE (`db.error.code`) go with the error;
  Postgres errors are named by their code (`UniqueViolation`). Row values in Postgres's
  `Detail:` lines are left out.
- **Headers:** every request and response header on incoming requests, secrets as `[Filtered]`
  (the same list as the other SDKs). `ZHARP_CAPTURE_HEADERS=false` turns it off;
  `ZHARP_CAPTURE_HEADERS_REDACT` filters more names.
- **User and session** from the browser SDK's `baggage`, or `Zharp.setUser(…)` per request.
- **Logs** are off by default; `ZHARP_LOGS=true` sends Logback, Log4j 2 and `Zharp.log` records.
  Either way each Logback / Log4j 2 line written during a request ends with
  `trace_id=… span_id=…`, so the Zharp agent links the lines it collects
  (`ZHARP_LOG_CORRELATION=false` turns it off). `ZHARP_TRACING=false`: logs only.
- **API** (`io.zharp:zharp-api`): `Zharp.captureError`, `setUser` / `clearUser`, `setTag`,
  `Zharp.log.info(…)` and the other levels, `startSpan`, `flush`, through the agent or the
  starter; OpenTelemetry's API is its only dependency. **Library mode** (`io.zharp:zharp`): the
  same API plus OpenTelemetry's SDK and exporter, started by `Zharp.init()`, with no agent.
- **Spring Boot starter** (`io.zharp:zharp-spring-boot-starter`): the same, without an agent, on
  OpenTelemetry's Spring Boot starter; `zharp.key`, `zharp.service`… in application properties.
- **Build plugins** (`io.zharp.java`, `zharp-maven-plugin`): a build ID in the jar
  (`META-INF/zharp/build.properties`) and the app's sources uploaded under it, so errors show the
  code of the build that ran. Every error carries `zharp.build_id`. In a multi-module build every
  module's jar gets the same ID and one upload holds all modules' sources.

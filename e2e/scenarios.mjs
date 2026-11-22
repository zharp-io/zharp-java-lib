// The test matrix: how each app starts and what it can show.
import { execSync, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import * as z from './lib/zharp.mjs';
import { readEntry } from './lib/jar.mjs';

const repo = fileURLToPath(new URL('..', import.meta.url)).replace(/[\\/]$/, '');
const agent = `${repo}/zharp-agent/build/release/zharp-agent.jar`;
const apps = `${repo}/e2e/apps`;
const ALL = ['/hello', '/users/7', '/boom', '/handled', '/logged', '/thread-crash'];
// mvnw.cmd needs JAVA_HOME: the JDK of the `java` on the PATH.
if (!process.env.JAVA_HOME) {
  const r = spawnSync('java', ['-XshowSettings:properties', '-version'], { encoding: 'utf8' });
  const settings = `${r.stdout}${r.stderr}`;
  const home = /java\.home = (.+)/.exec(settings)?.[1]?.trim();
  if (home) process.env.JAVA_HOME = home;
}
const mavenApps =`${repo}/e2e/maven-apps`;
const mvnw = process.platform === 'win32' ? `"${mavenApps}/mvnw.cmd"` : './mvnw';
const gradlew = process.platform === 'win32' ? `"${apps}/gradlew.bat"` : './gradlew';

/** The build ID a jar carries (META-INF/zharp/build.properties: at the root of a Spring Boot jar, else in its classes). */
function buildIdOf(jar) {
  for (const entry of ['META-INF/zharp/build.properties', 'BOOT-INF/classes/META-INF/zharp/build.properties', 'WEB-INF/classes/META-INF/zharp/build.properties']) {
    try {
      const text = readEntry(jar, entry) ?? '';
      const id = /io\.zharp\.build\.id=(\S+)/.exec(text)?.[1];
      if (id) return id;
    } catch {}
  }
  return null;
}

/** Build with a build plugin, uploading the sources to this run's app with a throwaway artifacts:write key. */
function withUpload(build, jar, cwd = apps) {
  return {
    async before(ctx) {
      ctx.key = await z.createUploadKey(`java-e2e-${Date.now() % 100000}`);
      if (!ctx.key.key) throw new Error('could not make an artifacts:write key');
      const env = { ...process.env, ZHARP_AUTH_TOKEN: ctx.key.key, ZHARP_APP_ID: String(ctx.app.id), ZHARP_URL: z.API.replace(/\/api\/v1$/, '') };
      const out = execSync(build, { cwd, env, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
      ctx.uploadOutput = out.split('\n').filter((l) => /Zharp/.test(l)).join('\n');
      console.log(`  ${ctx.uploadOutput.trim()}`);
      ctx.buildId = buildIdOf(jar);
    },
    async after(ctx) {
      if (ctx.key?.id) await z.deleteKey(ctx.key.id);
    },
  };
}

export const SCENARIOS = [
  {
    name: 'boot3-mvc',
    framework: 'spring-boot',
    port: 18081,
    db: true,
    logs: true,
    route: 'GET /users/{id}',
    paths: [...ALL, 'POST /users', '/dup-caught'],
    // Built with the Gradle plugin (io.zharp.java): build ID in the jar, sources uploaded.
    ...withUpload(`${gradlew} :boot3-mvc:clean :boot3-mvc:assemble --console=plain`, `${apps}/boot3-mvc/build/libs/boot3-mvc.jar`),
    buildId: true,
    command: () => ['java', [`-javaagent:${agent}`, '-jar', `${apps}/boot3-mvc/build/libs/boot3-mvc.jar`]],
  },
  {
    name: 'boot3-starter',
    framework: 'spring-boot',
    port: 18082,
    db: true,
    logs: true,
    route: 'GET /users/{id}',
    paths: [...ALL, 'POST /users', '/dup-caught'],
    command: () => ['java', ['-jar', `${apps}/boot3-starter/build/libs/boot3-starter.jar`]],
  },
  {
    name: 'boot3-webflux',
    framework: 'spring-boot',
    port: 18083,
    logs: true,
    route: 'GET /users/{id}',
    paths: ALL,
    command: () => ['java', [`-javaagent:${agent}`, '-jar', `${apps}/boot3-webflux/build/libs/boot3-webflux.jar`]],
  },
  {
    name: 'boot2',
    framework: 'spring-boot',
    port: 18084,
    db: true,
    logs: true,
    route: 'GET /users/{id}',
    paths: [...ALL, 'POST /users', '/dup-caught'],
    // Built with Maven and the Maven plugin (zharp-maven-plugin): build ID in the jar, sources uploaded.
    ...withUpload(`${mvnw} -B -f boot2/pom.xml clean package`, `${mavenApps}/boot2/target/boot2.jar`, mavenApps),
    buildId: true,
    command: () => ['java', [`-javaagent:${agent}`, '-jar', `${mavenApps}/boot2/target/boot2.jar`]],
  },
  {
    // Also the Log4j 2 row: the app logs through Log4j 2 only.
    name: 'servlet-jetty-log4j2',
    framework: 'java',
    port: 18085,
    db: true,
    logs: true,
    // Servlet mappings ("/users/*") are routes the server turns into "/users/:id" from the path.
    route: 'GET /users/:id',
    paths: [...ALL, 'POST /users', '/dup-caught'],
    command: () => ['java', [`-javaagent:${agent}`, '-cp', `${apps}/servlet-jetty/build/install/servlet-jetty/lib/*`, 'e2e.servlet.ServletApp']],
  },
  {
    name: 'ktor',
    framework: 'ktor',
    port: 18086,
    logs: true,
    route: 'GET /users/{id}',
    paths: ALL,
    command: () => ['java', [`-javaagent:${agent}`, '-cp', `${apps}/ktor/build/install/ktor/lib/*`, 'e2e.ktor.KtorAppKt']],
  },
  {
    // No agent: Zharp.init() in main. A job rather than a server, so no routes or headers; the
    // caller's trace and user come in TRACEPARENT / BAGGAGE.
    name: 'library-mode',
    framework: 'java',
    cli: true,
    logs: true,
    headers: false,
    paths: ['/hello', '/boom', '/handled', '/logged', '/thread-crash'],
    command: () => ['java', ['-cp', `${apps}/library-mode/build/install/library-mode/lib/*`, 'e2e.library.LibraryApp']],
  },
];

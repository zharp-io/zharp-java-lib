// End-to-end for desktop apps: the Compose Multiplatform app in e2e/desktop-compose, built and
// packaged (createDistributable, so it runs as users get it: jpackage's launcher) in Docker, run
// headless under Xvfb against a local Zharp backend, then what Zharp stored is checked.
//
//   ZHARP_E2E_TOKEN_FILE=…/token.txt node e2e/desktop.mjs
//
// Needs: Docker, the image from e2e/desktop-compose/Dockerfile (tagged zharp-desktop-e2e:jdk17),
// Zharp's artifacts in build/e2e-repo (./gradlew publishAllPublicationsToE2eRepository), and the
// backend at ZHARP_E2E_API (default http://localhost:3000), reachable from containers as
// host.docker.internal; E2E_DOCKER=0 runs it on this machine instead (CI). E2E_MOCK=1 (or no backend answering): a stand-in receiver (lib/receiver.mjs)
// instead, and what the SDK sent is checked rather than what Zharp made of it.
// Launches, in one container (so the app's data folder persists between them):
//   1. handled — Zharp.captureError, then a second window; online
//   2. crash   — an exception on the Compose window's thread, with Zharp unreachable: kept on disk
//   3. thread  — an uncaught exception on a worker thread; online: sends it, and launch 2's crash
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { writeFileSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { startReceiver } from './lib/receiver.mjs';

const repo = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const image = process.env.E2E_IMAGE ?? 'zharp-desktop-e2e:jdk17';
const inDocker = process.env.E2E_DOCKER !== '0';
const toContainer = (url) => !inDocker ? url : url.replace('://localhost', '://host.docker.internal').replace('://127.0.0.1', '://host.docker.internal');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const apiBase = (process.env.ZHARP_E2E_API ?? 'http://localhost:3000');
const backendUp = process.env.E2E_MOCK !== '1' && await fetch(`${apiBase}/api/v1/health`).then((r) => r.ok, () => false);
const z = backendUp ? await import('./lib/zharp.mjs') : null;
const receiver = backendUp ? null : await startReceiver(18099);
console.log(backendUp ? `against the backend at ${apiBase}` : 'no backend: against the stand-in receiver on :18099');
const app = backendUp
  ? await z.createApp(`java-e2e-desktop-${Date.now() % 100000}`, 'Kotlin (desktop)')
  : { id: 1, ingestKey: 'zpk_e2e_desktop', ingestUrl: 'http://localhost:18099/api/v1/otlp' };
const key = backendUp ? await z.createUploadKey(`java-e2e-desktop-${Date.now() % 100000}`) : { id: 0, key: 'zh_e2e_upload' };
const apiUrl = backendUp ? apiBase : 'http://localhost:18099';
const results = [];
const ok = (name, pass, detail = '') => {
  results.push({ name, pass: !!pass });
  console.log(`  ${pass ? 'PASS' : 'FAIL'}  ${name}${detail ? `  — ${detail}` : ''}`);
};

const script = `
set -e
cd "${inDocker ? '/src' : repo}/e2e/desktop-compose"
./gradlew --no-daemon --console=plain :composeApp:createDistributable ${process.env.E2E_OBFUSCATE === 'true' ? ':composeApp:createReleaseDistributable' : ''} 2>&1 | grep -E 'Zharp|BUILD|error|FAIL' || true
bin=composeApp/build/compose/binaries/main${process.env.E2E_OBFUSCATE === 'true' ? '-release' : ''}/app/zharp-desktop-e2e/bin/zharp-desktop-e2e
test -x $bin
show='Zharp]|e2e desktop app|Exception|Error'
echo "=== launch 1: handled"
E2E_ACTION=handled timeout 60 xvfb-run -a $bin 2>&1 | grep -E "$show" | head -12 || true
echo "=== launch 2: crash, offline"
ZHARP_ENDPOINT=http://127.0.0.1:9/api/v1/otlp E2E_ACTION=crash timeout 60 xvfb-run -a $bin 2>&1 | grep -E "$show" | head -12 || true
echo "outbox after the offline crash: $(ls ~/.cache/zharp/*/outbox 2>/dev/null | tr '\\n' ' ')"
echo "=== launch 3: thread, online"
E2E_ACTION=thread timeout 60 xvfb-run -a $bin 2>&1 | grep -E "$show" | head -12 || true
sleep 3
echo "outbox after launch 3: [$(ls ~/.cache/zharp/*/outbox 2>/dev/null | tr '\\n' ' ')]"
`;

let output = '';
const env = {
  ZHARP_APP_ID: String(app.id), ZHARP_AUTH_TOKEN: key.key, ZHARP_URL: toContainer(apiUrl),
  ZHARP_KEY: app.ingestKey, ZHARP_ENDPOINT: toContainer(app.ingestUrl), ZHARP_ENVIRONMENT: 'e2e',
  E2E_OBFUSCATE: process.env.E2E_OBFUSCATE ?? 'false',
};
// Asynchronous: the stand-in receiver answers from this process while the app runs.
const run = promisify(execFile);
try {
  output = (inDocker
    ? await run('docker', ['run', '--rm', '--add-host=host.docker.internal:host-gateway',
      '-v', `${repo}:/src`, '-v', 'zharp-desktop-e2e-gradle:/root/.gradle',
      ...Object.entries(env).flatMap(([k, v]) => ['-e', `${k}=${v}`]),
      image, 'bash', '-c', script], { encoding: 'utf8', maxBuffer: 64 << 20 })
    // E2E_DOCKER=0: on this machine (a CI runner with Xvfb and a JDK).
    : await run('bash', ['-c', script], { encoding: 'utf8', maxBuffer: 64 << 20, env: { ...process.env, ...env } })).stdout;
} catch (error) {
  output = (error.stdout ?? '') + (error.stderr ?? '');
}
console.log(output);

try {
  if (!backendUp) {
    await sleep(2000);
    const { got } = receiver;
    mkdirSync(new URL('./.runs/', import.meta.url), { recursive: true });
    writeFileSync(new URL('./.runs/desktop-mock.json', import.meta.url), JSON.stringify({ ...got, output }, null, 1));
    const errors = got.logs.filter((r) => r.attributes['exception.type']);
    const bundle = got.artifacts.find((a) => a.kind === 'source-bundle');
    const expected = [
      ['java.lang.NumberFormatException', 'true', 'captureError', /at e2e\.desktop\.Cart\.total\(Cart\.kt:\d+\)/, 'ERROR'],
      ['java.lang.IllegalStateException', 'false', 'crash on the Compose window thread (sent on the next launch)', /at e2e\.desktop\.Cart\.checkout\(Cart\.kt:\d+\)/, 'FATAL'],
      ['java.lang.IllegalArgumentException', 'false', 'uncaught on a worker thread', /at e2e\.desktop\.Cart\.applyCoupon\(Cart\.kt:\d+\)/, 'ERROR'],
    ];
    for (const [type, handled, what, frame, severity] of expected) {
      const m = errors.filter((r) => r.attributes['exception.type'] === type);
      const r = m[0];
      ok(`${what}: ${type}, handled=${handled}`, m.length === 1 && r.attributes['zharp.handled'] === handled, m.map((x) => `${x.severityText} handled=${x.attributes['zharp.handled']} thread=${x.attributes['thread.name']}`).join(' / ') || 'missing');
      if (!r) continue;
      ok(`  …${severity}`, r.severityText === severity, r.severityText);
      ok('  …file, function and line', frame.test(r.attributes['exception.stacktrace'] ?? ''), (r.attributes['exception.stacktrace'] ?? '').split('\n').find((l) => /Cart\.kt/.test(l))?.trim());
      ok("  …the build ID its sources were uploaded under", bundle && r.attributes['zharp.build_id'] === bundle.debugId, `${r.attributes['zharp.build_id']} vs bundle ${bundle?.debugId}`);
      ok('  …release from the packaged app, OS, device, session', r.resource['service.version'] === '1.4.2' && r.resource['os.type'] === 'linux' && r.resource['device.id'] && r.attributes['session.id'] && r.resource['service.name'] === 'zharp-desktop-e2e',
        JSON.stringify({ service: r.resource['service.name'], release: r.resource['service.version'], os: `${r.resource['os.name']} ${r.resource['os.version']}`, device: r.resource['device.id'], session: r.attributes['session.id'] }));
      ok('  …the screen (window title)', r.attributes['screen.name'] === 'Checkout', r.attributes['screen.name']);
    }
    ok('each failure sent once, nothing else', errors.length === expected.length, errors.map((r) => r.attributes['exception.type']).join(', '));
    ok('three launches, three sessions', new Set(errors.map((r) => r.attributes['session.id'])).size === 3);
    ok('one device', new Set(errors.map((r) => r.resource['device.id'])).size === 1);
    ok('screens as spans (Checkout, Settings)', ['screen Checkout', 'screen Settings'].every((n) => got.spans.some((s) => s.name === n)), [...new Set(got.spans.map((s) => s.name))].join(', '));
    ok('sources uploaded from the KMP module (commonMain + desktopMain)', bundle && bundle.files.some((f) => f.endsWith('commonMain/kotlin/e2e/desktop/Cart.kt')) && bundle.files.some((f) => f.endsWith('desktopMain/kotlin/e2e/desktop/main.kt')), bundle ? bundle.files.join(', ') : 'no bundle');
    ok('the key on every request', got.logs.every((r) => r.auth === `Bearer ${app.ingestKey}`));
    ok('logs off by default (only errors sent)', got.logs.every((r) => r.attributes['exception.type']), `${got.logs.length} records`);
  } else {
  let issues = [];
    for (let i = 0; i < 20; i++) {
      issues = await z.issues(app.id);
      if (issues.reduce((n, x) => n + (x.events ?? 0), 0) >= 3) break;
      await z.sleep(2000);
    }
    await z.sleep(3000);
    issues = await z.issues(app.id);
    const events = [];
    for (const issue of issues) events.push({ issue, event: (await z.issue(app.id, issue.id)).latestEvent });
    const find = (type, handled) => events.filter((e) => e.event?.exceptionType === type && e.event?.isHandled === handled);
    const total = issues.reduce((n, x) => n + (x.events ?? 0), 0);
    mkdirSync(new URL('./.runs/', import.meta.url), { recursive: true });
    writeFileSync(new URL('./.runs/desktop.json', import.meta.url), JSON.stringify({ app: app.id, issues, events, output }, null, 1));

    const expected = [
      ['java.lang.NumberFormatException', true, 'captureError', /e2e\.desktop\.Cart\.total/],
      ['java.lang.IllegalStateException', false, 'crash on the Compose window thread (sent on the next launch)', /e2e\.desktop\.Cart\.checkout/],
      ['java.lang.IllegalArgumentException', false, 'uncaught on a worker thread', /e2e\.desktop\.Cart\.applyCoupon/],
    ];
    for (const [type, handled, what, fn] of expected) {
      const m = find(type, handled);
      ok(`${what}: ${type}, handled=${handled}`, m.length === 1, m.length ? m[0].event.stacktrace.split('\n').slice(0, 2).join(' | ').slice(0, 160) : 'missing');
      const e = m[0]?.event;
      if (!e) continue;
      const top = (e.frames ?? []).find((f) => fn.test(f.function));
      ok('  …file, function and line', top && top.file === 'Cart.kt' && top.line > 0, top ? `${top.function} ${top.file}:${top.line}` : (e.frames ?? []).slice(0, 3).map((f) => f.function).join(', '));
      ok("  …the build's code (contextFrom: build)", top?.contextFrom === 'build', `${top?.contextFrom ?? '-'} ${top?.bundlePath ?? ''}`);
      ok('  …release from the packaged app, OS, device, session', e.release === '1.4.2' && /linux/i.test(e.osName ?? '') && e.deviceId && e.sessionId, JSON.stringify({ release: e.release, os: e.osName, osVersion: e.osVersion, device: e.deviceId, session: e.sessionId }));
      if (handled) ok('  …the screen (window title)', e.attributes?.['screen.name'] === 'Checkout', e.attributes?.['screen.name']);
    }
    ok('each failure counted once', total === expected.length, issues.map((i) => `${i.exceptionType}×${i.events}`).join(', '));
  }
  ok('crash kept on disk while offline', /outbox after the offline crash: \S+\.logs/.test(output), (output.match(/outbox after the offline crash:.*/) ?? [''])[0]);
  ok('outbox emptied on the next launch', /outbox after launch 3: \[\]/.test(output), (output.match(/outbox after launch 3:.*/) ?? [''])[0]);
  ok('Gradle plugin uploaded the sources', /uploaded the app's sources/.test(output), (output.match(/Zharp: uploaded.*/) ?? ['none'])[0]);
  ok('startup line says desktop', /Application monitoring active \(desktop app\)/.test(output));
} finally {
  if (receiver) receiver.close();
  else if (process.env.KEEP !== '1') {
    await z.deleteApp(app.id);
    await z.deleteKey(key.id);
  } else console.log(`kept app ${app.id}`);
}
const failed = results.filter((r) => !r.pass);
console.log(`\n${failed.length ? 'FAIL' : 'PASS'}  desktop: ${results.length - failed.length} passed${failed.length ? `, failed: ${failed.map((r) => r.name).join('; ')}` : ''}`);
process.exitCode = failed.length ? 1 : 0;

// End-to-end: each app runs against a local Zharp backend, gets the standard requests, and what
// Zharp stored is checked. Every app and key made here is deleted afterwards (KEEP=1 keeps apps).
//
//   ZHARP_E2E_TOKEN_FILE=…/token.txt node e2e/run.mjs [scenario…]
//
// Needs: the backend at ZHARP_E2E_API (default http://localhost:3000), Postgres from
// `docker run -p 15432:5432 -e POSTGRES_USER=e2e -e POSTGRES_PASSWORD=e2e -e POSTGRES_DB=e2e postgres:16-alpine`,
// and the apps built (e2e/build.sh).
import { writeFileSync, mkdirSync } from 'node:fs';
import * as z from './lib/zharp.mjs';
import * as d from './lib/drive.mjs';
import { collect, check } from './lib/checks.mjs';
import { SCENARIOS } from './scenarios.mjs';

const only = process.argv.slice(2);
const chosen = SCENARIOS.filter((s) => !only.length || only.includes(s.name));
const summary = [];
mkdirSync(new URL('./.runs/', import.meta.url), { recursive: true });

for (const s of chosen) {
  console.log(`\n=== ${s.name}`);
  const app = await z.createApp(`java-e2e-${s.name}-${Date.now() % 100000}`, s.framework);
  const ctx = { app, extra: {} };
  let proc;
  try {
    if (s.before) await s.before(ctx);
    const env = { ZHARP_KEY: app.ingestKey, ZHARP_ENDPOINT: app.ingestUrl, ZHARP_SERVICE: app.serviceName, ZHARP_ENVIRONMENT: 'e2e', ZHARP_LOGS: s.logs ? 'true' : 'false', PORT: String(s.port), ...(s.env ?? {}), ...ctx.extra.env };
    const [command, args, cwd] = s.command(ctx);
    proc = d.start(command, args, { cwd, env, label: s.name });
    const base = `http://localhost:${s.port}`;
    await d.waitFor(`${base}/hello`, proc.out);
    const call = d.browserCall();
    await fetch(`${base}/hello`, { headers: call.headers });
    for (const path of s.paths) {
      if (path === '/hello') continue;
      if (path === 'POST /users') {
        await fetch(`${base}/users?email=dup@example.com`, { method: 'POST' });
        await fetch(`${base}/users?email=dup@example.com`, { method: 'POST' });
      } else {
        await fetch(`${base}${path}`).catch(() => {});
      }
    }
    await z.sleep(1500);
    await fetch(`${base}/exit`).catch(() => {});
    const clean = await d.waitExit(proc);
    if (!clean) console.log('  (had to kill it)');
    const got = await collect(app, s, call);
    writeFileSync(new URL(`./.runs/${s.name}.json`, import.meta.url), JSON.stringify({ app: app.id, call, ...got, output: proc.out.text }, null, 1));
    const results = check(s, got, call, proc.out.text);
    for (const r of results) console.log(`  ${r.pass ? 'PASS' : 'FAIL'}  ${r.name}${r.detail ? `  — ${r.detail}` : ''}`);
    summary.push({ name: s.name, passed: results.filter((r) => r.pass).length, failed: results.filter((r) => !r.pass).map((r) => r.name) });
  } catch (error) {
    console.log(`  ERROR ${error.message.slice(0, 2000)}`);
    summary.push({ name: s.name, passed: 0, failed: ['run: ' + error.message.slice(0, 200)] });
  } finally {
    if (proc && !proc.out.exited) await d.waitExit(proc, 1);
    if (s.after) await s.after(ctx).catch(() => {});
    if (process.env.KEEP !== '1') await z.deleteApp(app.id);
  }
}

console.log('\n=== summary');
for (const r of summary) console.log(`${r.failed.length ? 'FAIL' : 'PASS'}  ${r.name}: ${r.passed} passed${r.failed.length ? `, failed: ${r.failed.join('; ')}` : ''}`);
process.exitCode = summary.some((r) => r.failed.length) ? 1 : 0;

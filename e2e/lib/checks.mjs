// What every e2e app must show in Zharp after the standard requests (see run.mjs).
import * as z from './zharp.mjs';

/** The failures the standard requests cause, by exception type, and whether each is handled. */
export function expectedErrors(s) {
  const out = [];
  if (s.paths.includes('/boom')) out.push({ type: 'java.lang.IllegalStateException', handled: false, what: 'request failed' });
  if (s.paths.includes('/handled')) out.push({ type: 'java.lang.NumberFormatException', handled: true, what: 'captureError' });
  if (s.paths.includes('/logged')) out.push({ type: 'java.lang.NullPointerException', handled: true, what: 'logged exception' });
  if (s.paths.includes('/thread-crash')) out.push({ type: 'java.lang.UnsupportedOperationException', handled: false, what: 'uncaught on a thread' });
  if (s.db) {
    out.push({ type: 'UniqueViolation', handled: false, what: 'failed query failed the request', query: true });
    out.push({ type: 'UniqueViolation', handled: true, what: 'failed query, caught', query: true });
  }
  return out;
}

/** Poll until the issues and traces have arrived (or time runs out). */
export async function collect(app, s, call) {
  const want = expectedErrors(s).length;
  let issues = [];
  let traces = [];
  for (let i = 0; i < 30; i++) {
    await z.sleep(2000);
    issues = await z.issues(app.id);
    traces = await z.traces(app.id);
    const events = issues.reduce((n, x) => n + (x.events ?? 0), 0);
    if (events >= want && (!call || traces.some((t) => t.traceId === call.traceId))) break;
  }
  await z.sleep(3000); // anything sent twice would arrive by now
  issues = await z.issues(app.id);
  traces = await z.traces(app.id);
  const details = [];
  for (const issue of issues) {
    const detail = await z.issue(app.id, issue.id);
    detail.allEvents = [detail.latestEvent];
    if ((issue.events ?? 0) > 1) {
      const list = await z.issueEvents(app.id, issue.id);
      detail.allEvents = [];
      for (const e of list) detail.allEvents.push((await z.issueEvent(app.id, issue.id, e.eventId ?? e.id)) ?? e);
    }
    details.push(detail);
  }
  const logs = await z.logs(app.id);
  const trace = call ? await z.trace(call.traceId) : null;
  return { issues, traces, details, logs, trace };
}

export function check(s, got, call, output, ctx = {}) {
  const results = [];
  const ok = (name, pass, detail = '') => results.push({ name, pass: !!pass, detail });
  const events = got.details.flatMap((d) => d.allEvents ?? [d.latestEvent]).filter(Boolean);

  if (s.logsOnly) ok('logs only: no spans sent', got.traces.length === 0, `${got.traces.length} spans`);
  if (s.route && !s.logsOnly) ok('routes named', got.traces.some((t) => t.name === s.route), got.traces.map((t) => t.name).filter((n) => n.startsWith('GET') || n.startsWith('POST')).join(', '));

  // One issue event per failure, with the right type and handled flag; nothing else.
  const expected = expectedErrors(s);
  const total = got.issues.reduce((n, x) => n + (x.events ?? 0), 0);
  for (const e of expected) {
    const match = events.filter((ev) => ev.exceptionType === e.type && ev.isHandled === e.handled);
    ok(`error: ${e.what} (${e.type}, handled=${e.handled})`, match.length === 1, match.length ? `stack starts: ${match[0].stacktrace.split('\n')[0].slice(0, 100)}` : 'missing');
    if (e.query && match[0]) ok(`  …with its query`, match[0].attributes?.['db.statement'] && match[0].attributes?.['db.error.code'] === '23505', JSON.stringify({ statement: match[0].attributes?.['db.statement'], code: match[0].attributes?.['db.error.code'] }));
  }
  ok('each failure counted once (no extra events)', total === expected.length, `${total} events for ${expected.length} failures: ${got.issues.map((i) => `${i.exceptionType}×${i.events}`).join(', ')}`);
  const withStack = events.filter((ev) => /\n\s*at [\w$.]+\(/.test(ev.stacktrace));
  ok('stacks in JVM form', withStack.length === events.length, `${withStack.length}/${events.length}`);

  if (call && !s.logsOnly) {
    const spans = got.trace?.spans ?? [];
    const span = spans.find((sp) => sp.parentSpanId === call.parentId) ?? spans.find((sp) => sp.kind === 'SERVER');
    const a = span?.attributes ?? {};
    ok("caller's traceparent continued", span && span.parentSpanId === call.parentId, span ? `parent ${span.parentSpanId}` : 'no server span in the trace');
    if (s.headers !== false) {
      ok('headers captured, secrets filtered', a['http.request.header.authorization'] === '[Filtered]' && a['http.request.header.cookie'] === '[Filtered]' && a['http.request.header.x-api-key'] === '[Filtered]' && a['http.request.header.x-custom-header'] === 'visible-value',
        JSON.stringify({ auth: a['http.request.header.authorization'], cookie: a['http.request.header.cookie'], custom: a['http.request.header.x-custom-header'], resp: Object.keys(a).filter((k) => k.startsWith('http.response.header.')).length + ' response headers' }));
    }
    ok('user and session from baggage', a['enduser.id'] === 'u-42' && a['session.id'] === 's-42', JSON.stringify({ user: a['enduser.id'], session: a['session.id'] }));
  }
  if ((s.paths.includes('/users/7') || s.cli) && !s.logsOnly) {
    const users = got.traces.find((t) => t.userId === '7');
    ok('Zharp.setUser on the request', users?.userId === '7' && users?.userEmail === '7@example.com', users ? `${users.userId} ${users.userEmail}` : 'no user on the users request');
  }
  if (!s.logs && !s.cli) {
    const plain = got.logs.filter((l) => !(l.attributes?.['exception.type']) && !/Exception|Error|Violation/.test(l.body ?? ''));
    ok('logs off (default): only errors sent', plain.length === 0, `${got.logs.length} records, ${plain.length} plain lines: ${plain.slice(0, 3).map((l) => l.body).join(' | ')}`);
  }
  if (s.logs) {
    const hello = got.logs.filter((l) => /hello from the e2e app/.test(l.body ?? ''));
    ok('logs sent and linked (ZHARP_LOGS=true)', call ? hello.some((l) => l.traceId === call.traceId) : hello.length > 0, `${hello.length} hello lines, trace ids ${hello.map((l) => (l.traceId || '-').slice(0, 8)).join(',')}`);
    const dup = hello.filter((l) => call && l.traceId === call.traceId).length;
    if (call) ok('log line sent once', dup === 1, `${dup} copies`);
  }
  if (s.stamp !== false && call) {
    ok('log line stamped in the output', new RegExp(`hello from the e2e app.*trace_id=${call.traceId} span_id=[0-9a-f]{16}`).test(output), (output.split('\n').find((l) => l.includes('hello from the e2e app')) ?? '').slice(-120));
  }
  if (s.buildId) {
    const frames = events.flatMap((ev) => ev.frames ?? []);
    const fromBuild = frames.filter((f) => f.contextFrom === 'build');
    ok('build ID written into the jar', !!ctx.buildId, ctx.buildId);
    ok('sources uploaded', /uploaded the app's sources/.test(ctx.uploadOutput ?? ''), (ctx.uploadOutput ?? '').trim());
    ok('zharp.build_id on errors', events.every((ev) => ev.attributes?.['zharp.build_id'] === ctx.buildId), events.map((ev) => ev.attributes?.['zharp.build_id']).join(','));
    ok("issue frames show the build's code (contextFrom: build)", fromBuild.length > 0, fromBuild.slice(0, 3).map((f) => `${f.function}:${f.line}`).join(', '));
  }
  return results;
}

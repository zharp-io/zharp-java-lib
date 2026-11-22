// Starting an app, waiting for it, and sending the requests every e2e app answers.
import { spawn } from 'node:child_process';
import { sleep } from './zharp.mjs';

export function start(command, args, { cwd, env, label }) {
  const child = spawn(command, args, { cwd, env: { ...process.env, ...env }, shell: process.platform === 'win32' && !command.endsWith('.exe') && command !== 'java' });
  const out = { text: '', exited: false, code: null };
  child.stdout.on('data', (d) => (out.text += d));
  child.stderr.on('data', (d) => (out.text += d));
  child.on('exit', (code) => {
    out.exited = true;
    out.code = code;
  });
  return { child, out, label };
}

export async function waitFor(url, out, ms = 120_000) {
  const until = Date.now() + ms;
  while (Date.now() < until) {
    if (out.exited) throw new Error(`exited before answering:\n${out.text.slice(-3000)}`);
    try {
      const res = await fetch(url);
      if (res.status < 500) return;
    } catch {}
    await sleep(500);
  }
  throw new Error(`no answer from ${url}:\n${out.text.slice(-3000)}`);
}

export async function waitExit(proc, ms = 30_000) {
  const until = Date.now() + ms;
  while (!proc.out.exited && Date.now() < until) await sleep(250);
  if (!proc.out.exited) {
    proc.child.kill();
    if (process.platform === 'win32') spawn('taskkill', ['/pid', String(proc.child.pid), '/T', '/F']);
    return false;
  }
  return true;
}

const hex = (n) => [...crypto.getRandomValues(new Uint8Array(n))].map((b) => b.toString(16).padStart(2, '0')).join('');

/** A browser's request: its traceparent (a trace and a parent span we know), user and session in baggage, and headers with secrets. */
export function browserCall() {
  const traceId = hex(16);
  const parentId = hex(8);
  return {
    traceId,
    parentId,
    headers: {
      traceparent: `00-${traceId}-${parentId}-01`,
      baggage: 'enduser.id=u-42,session.id=s-42',
      authorization: 'Bearer s3cret-token',
      cookie: 'sid=abc123',
      'x-api-key': 'k-123',
      'x-custom-header': 'visible-value',
    },
  };
}

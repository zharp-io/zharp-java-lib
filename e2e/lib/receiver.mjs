// A stand-in for Zharp's API when no local backend runs: it keeps what the SDK and the Gradle
// plugin sent, decoded, for the checks.
//   POST /api/v1/otlp/v1/logs | /v1/traces     OTLP/HTTP protobuf (gzipped)
//   POST /api/v1/applications/:id/artifacts    the Gradle plugin's source bundle (multipart)
import { createServer } from 'node:http';
import { gunzipSync } from 'node:zlib';

// ── a small protobuf reader: just what OTLP logs and traces need ────────────────────────────────
function fields(buf) {
  const out = [];
  let i = 0;
  const varint = () => {
    let shift = 0n;
    let v = 0n;
    for (;;) {
      const b = buf[i++];
      v |= BigInt(b & 0x7f) << shift;
      if (!(b & 0x80)) return v;
      shift += 7n;
    }
  };
  while (i < buf.length) {
    const tag = Number(varint());
    const no = tag >> 3;
    const wire = tag & 7;
    if (wire === 0) out.push([no, varint()]);
    else if (wire === 1) { out.push([no, buf.readBigUInt64LE(i)]); i += 8; }
    else if (wire === 2) { const len = Number(varint()); out.push([no, buf.subarray(i, i + len)]); i += len; }
    else if (wire === 5) { out.push([no, buf.readUInt32LE(i)]); i += 4; }
    else throw new Error(`wire type ${wire}`);
  }
  return out;
}
const all = (buf, no) => fields(buf).filter(([n]) => n === no).map(([, v]) => v);
const one = (buf, no) => all(buf, no)[0];
function anyValue(buf) {
  if (!buf) return null;
  for (const [n, v] of fields(buf)) {
    if (n === 1) return v.toString('utf8');
    if (n === 2) return Boolean(v);
    if (n === 3) return Number(v);
  }
  return null;
}
function attributes(buf) {
  const a = {};
  for (const kv of all(buf, 6)) a[one(kv, 1).toString('utf8')] = anyValue(one(kv, 2));
  return a;
}
const keyValues = (list) => Object.fromEntries(list.map((kv) => [one(kv, 1).toString('utf8'), anyValue(one(kv, 2))]));

export function decodeLogs(body) {
  const records = [];
  for (const rl of all(body, 1)) {
    const resource = keyValues(all(one(rl, 1) ?? Buffer.alloc(0), 1));
    for (const sl of all(rl, 2)) {
      const scope = one(sl, 1) ? one(one(sl, 1), 1)?.toString('utf8') : '';
      for (const lr of all(sl, 2)) {
        records.push({ resource, scope, severity: Number(one(lr, 2) ?? 0), severityText: one(lr, 3)?.toString('utf8'), body: anyValue(one(lr, 5)), attributes: attributes(lr) });
      }
    }
  }
  return records;
}

export function decodeSpans(body) {
  const spans = [];
  for (const rs of all(body, 1)) {
    const resource = keyValues(all(one(rs, 1) ?? Buffer.alloc(0), 1));
    for (const ss of all(rs, 2)) {
      for (const sp of all(ss, 2)) {
        const a = {};
        for (const kv of all(sp, 9)) a[one(kv, 1).toString('utf8')] = anyValue(one(kv, 2));
        spans.push({ resource, name: one(sp, 5)?.toString('utf8'), attributes: a });
      }
    }
  }
  return spans;
}

function multipart(body, type) {
  const boundary = /boundary=(?:"([^"]+)"|([^;\s]+))/i.exec(type || '');
  if (!boundary) return {};
  const marker = Buffer.from(`--${boundary[1] ?? boundary[2]}`);
  const parts = {};
  let at = body.indexOf(marker);
  while (at >= 0) {
    let start = at + marker.length;
    if (body[start] === 0x2d) break;
    if (body[start] === 0x0d) start += 2;
    const next = body.indexOf(marker, start);
    if (next < 0) break;
    const part = body.subarray(start, next - 2);
    const split = part.indexOf('\r\n\r\n');
    const name = /name="([^"]*)"/.exec(part.subarray(0, split).toString())?.[1] ?? '';
    parts[name] = part.subarray(split + 4);
    at = next;
  }
  return parts;
}

/** Starts the receiver; `got` fills as requests arrive. */
export function startReceiver(port) {
  const got = { logs: [], spans: [], artifacts: [], requests: [] };
  const server = createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      let raw = Buffer.concat(chunks);
      got.requests.push(`${req.method} ${req.url}`);
      try {
        if (req.headers['content-encoding'] === 'gzip') raw = gunzipSync(raw);
        if (req.url.endsWith('/v1/logs')) got.logs.push(...decodeLogs(raw).map((r) => ({ ...r, auth: req.headers.authorization })));
        else if (req.url.endsWith('/v1/traces')) got.spans.push(...decodeSpans(raw));
        else if (/\/applications\/\d+\/artifacts/.test(req.url)) {
          const p = multipart(raw, req.headers['content-type']);
          const file = p.file ? JSON.parse(gunzipSync(p.file).toString('utf8')) : {};
          got.artifacts.push({ kind: p.kind?.toString(), debugId: (p.debugId ?? p.buildId)?.toString(), files: Object.keys(file.files ?? {}) });
          res.writeHead(201, { 'content-type': 'application/json' });
          res.end('{"data":{}}');
          return;
        }
      } catch (error) {
        got.requests.push(`  decode failed: ${error.message}`);
      }
      res.writeHead(200, { 'content-type': 'application/x-protobuf' });
      res.end();
    });
  });
  return new Promise((resolve) => server.listen(port, '0.0.0.0', () => resolve({ got, close: () => server.close() })));
}

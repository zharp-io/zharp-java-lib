// The local Zharp API, for the end-to-end runs: throwaway apps and keys, and what Zharp stored.
import { readFileSync } from 'node:fs';

export const API = (process.env.ZHARP_E2E_API ?? 'http://localhost:3000') + '/api/v1';
const token = (process.env.ZHARP_E2E_TOKEN ?? readFileSync(process.env.ZHARP_E2E_TOKEN_FILE, 'utf8')).trim();
const headers = { Authorization: `Bearer ${token}`, 'x-workspace-id': process.env.ZHARP_E2E_WORKSPACE ?? '3', 'content-type': 'application/json' };

export async function api(path, init = {}) {
  const res = await fetch(`${API}/${path}`, { headers, ...init });
  const text = await res.text();
  try {
    return JSON.parse(text);
  } catch {
    return { status: res.status, text };
  }
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export async function createApp(name, framework) {
  // 'java' once the backend lists it (APPLICATION_PLATFORMS); 'otel' until then.
  for (const platform of ['java', 'otel']) {
    const res = await api('applications', { method: 'POST', body: JSON.stringify({ name, platform, framework }) });
    if (res.data?.ingestKey) return res.data;
    if (platform === 'otel') throw new Error(`Could not create an app: ${JSON.stringify(res).slice(0, 300)}`);
  }
}

export const deleteApp = (id) => api(`applications/${id}`, { method: 'DELETE' });

/** A workspace API key with artifacts:write, for source-bundle uploads. */
export async function createUploadKey(name) {
  const created = await api('api-keys', { method: 'POST', body: JSON.stringify({ name, scopes: ['artifacts:write'] }) });
  const id = created.data?.id;
  const rolled = await api(`api-keys/${id}/roll`, { method: 'POST' });
  return { id, key: rolled.data?.plainKey };
}

export const deleteKey = (id) => api(`api-keys/${id}`, { method: 'DELETE' });

export const traces = async (appId) => (await api(`applications/${appId}/traces?window=1h`)).data?.traces ?? [];
export const trace = async (traceId) => (await api(`applications/traces/${traceId}`)).data;
export async function issues(appId) {
  const r = (await api(`applications/${appId}/issues?window=1h`)).data;
  return Array.isArray(r) ? r : r?.issues ?? [];
}
export const issue = async (appId, issueId) => (await api(`applications/${appId}/issues/${issueId}`)).data;
export async function issueEvents(appId, issueId) {
  const r = (await api(`applications/${appId}/issues/${issueId}/events`)).data;
  return Array.isArray(r) ? r : r?.events ?? r?.items ?? [];
}
export const issueEvent = async (appId, issueId, eventId) => (await api(`applications/${appId}/issues/${issueId}/events/${eventId}`)).data;
export async function logs(appId) {
  const r = (await api(`applications/${appId}/logs?window=1h&limit=500`)).data;
  return Array.isArray(r) ? r : r?.logs ?? r?.entries ?? [];
}

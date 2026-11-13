/** Server-only Google Sheets archive. This module also runs under Node for offline tests. */
export const SHEET_SCHEMA = Object.freeze({
  location: Object.freeze({ title: '定位回報', headers: ['紀錄ID', '家庭ID', '操作', '回報者ID', '名稱', '定位時間', '緯度', '經度', '精度（公尺）', '電量（%）', '紀錄時間', '原始資料（JSON）'] }),
  member: Object.freeze({ title: '家庭成員', headers: ['紀錄ID', '家庭ID', '操作', '成員ID', '名稱', '分享狀態', '紀錄時間', '原始資料（JSON）'] }),
  calendar: Object.freeze({ title: '行事曆', headers: ['紀錄ID', '家庭ID', '操作', '活動ID', '標題', '開始時間', '結束時間', '時區', '提前提醒（分鐘）', '地點', '備註', '登錄者ID', '紀錄時間', '原始資料（JSON）'] }),
  settings: Object.freeze({ title: '回報設定', headers: ['紀錄ID', '家庭ID', '操作', '成員ID', '回報間隔（分鐘）', '提醒開關', '提醒位置', '行事曆提醒開關', '紀錄時間', '原始資料（JSON）'] }),
  family: Object.freeze({ title: '操作紀錄', headers: ['紀錄ID', '家庭ID', '資料類型', '操作', '使用者ID', '實體ID', '紀錄時間', '原始資料（JSON）'] }),
});

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const SHEET_ID = /^[A-Za-z0-9_-]{20,150}$/;
const MAX_CLAIM = 50;
const MAX_FAMILIES = 3;
const RUN_BUDGET_MS = 110_000;
const REQUEST_TIMEOUT_MS = 12_000;
// A timed-out client request can continue at Google for up to its 180s server limit.
// Start writes early in the five-minute lease, and retry ambiguous writes after that lease.
const LEASE_BUFFER_MS = 190_000;
const RUN_BUFFER_MS = 2_000;
const DATE_COLUMNS = Object.freeze({
  '定位回報': [5, 10], '家庭成員': [6], '行事曆': [5, 6, 12],
  '回報設定': [8], '操作紀錄': [6],
});
const encoder = new TextEncoder();

export class SyncError extends Error {
  constructor(code, status = 0) { super(code); this.name = 'SyncError'; this.code = code; this.status = status; }
}

function object(value) { return value !== null && typeof value === 'object' && !Array.isArray(value); }
function string(value) { return typeof value === 'string' ? value : ''; }
function text(value) {
  const result = value == null ? '' : String(value);
  if (result.length > 48_000) throw new SyncError('CELL_TOO_LARGE');
  return result;
}
function number(value) { return typeof value === 'number' && Number.isFinite(value) ? value : ''; }
function boolean(value) { return typeof value === 'boolean' ? value : ''; }

/** All formatted spreadsheet date columns represent Taipei time; JSON keeps original ISO values. */
export function sheetsDateSerial(value) {
  if (value == null || value === '') return '';
  if (typeof value !== 'string') throw new SyncError('INVALID_DATE');
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) throw new SyncError('INVALID_DATE');
  return timestamp / 86_400_000 + 25_569 + 8 / 24;
}

export function columnsFor(type) {
  const schema = SHEET_SCHEMA[type];
  if (!schema) throw new SyncError('UNSUPPORTED_ENTITY');
  return schema;
}

export function rowFor(record) {
  if (!object(record) || !UUID.test(string(record.event_id)) || !UUID.test(string(record.family_id))
      || !UUID.test(string(record.lease_id)) || !['INSERT', 'UPDATE', 'DELETE'].includes(record.operation)
      || !object(record.payload) || !object(record.payload.data)) throw new SyncError('INVALID_OUTBOX_RECORD');
  const data = record.payload.data;
  if (data.family_id != null && data.family_id !== record.family_id) throw new SyncError('CROSS_FAMILY_PAYLOAD');
  const raw = JSON.stringify(record.payload);
  if (raw.length > 48_000) throw new SyncError('PAYLOAD_TOO_LARGE');
  if (!record.occurred_at) throw new SyncError('INVALID_DATE');
  const date = sheetsDateSerial(record.occurred_at);
  const head = [record.event_id, record.family_id, record.operation];
  const name = record.payload.display_name ?? data.display_name ?? '';
  switch (record.entity_type) {
    case 'location': return [...head, text(data.user_id), text(name), sheetsDateSerial(data.recorded_at),
      number(data.latitude), number(data.longitude), number(data.accuracy_m), number(data.battery_level), date, raw];
    case 'member': return [...head, text(data.user_id), text(name), boolean(data.sharing), date, raw];
    case 'calendar': return [...head, text(data.id), text(data.title), sheetsDateSerial(data.starts_at),
      sheetsDateSerial(data.ends_at), text(data.timezone), number(data.reminder_minutes), text(data.location),
      text(data.description), text(data.created_by), date, raw];
    case 'settings': return [...head, text(data.user_id), number(data.interval_minutes), boolean(data.reminders_enabled),
      text(data.placement), boolean(data.calendar_reminders_enabled), date, raw];
    case 'family': return [record.event_id, record.family_id, record.entity_type, record.operation,
      text(record.user_id), text(record.entity_id), date, raw];
    default: throw new SyncError('UNSUPPORTED_ENTITY');
  }
}

export function parseFamilyMap(raw) {
  let parsed;
  try { parsed = typeof raw === 'string' ? JSON.parse(raw) : raw; }
  catch { throw new SyncError('INVALID_FAMILY_MAP'); }
  if (!object(parsed)) throw new SyncError('INVALID_FAMILY_MAP');
  const entries = Object.entries(parsed);
  if (entries.length > 200) throw new SyncError('FAMILY_MAP_TOO_LARGE');
  const mapping = new Map();
  const usedSheets = new Set();
  for (const [family, sheet] of entries) {
    if (!UUID.test(family) || typeof sheet !== 'string' || !SHEET_ID.test(sheet)) throw new SyncError('INVALID_FAMILY_MAPPING');
    if (usedSheets.has(sheet)) throw new SyncError('SPREADSHEET_SHARED_BETWEEN_FAMILIES');
    mapping.set(family.toLowerCase(), sheet);
    usedSheets.add(sheet);
  }
  return mapping;
}

export function readConfig(env) {
  let serviceAccount;
  try { serviceAccount = JSON.parse(env('GOOGLE_SERVICE_ACCOUNT_JSON') || ''); }
  catch { throw new SyncError('GOOGLE_CREDENTIALS_MISSING'); }
  if (!object(serviceAccount) || serviceAccount.type !== 'service_account'
      || !/^[A-Za-z0-9._-]+@[A-Za-z0-9.-]+\.iam\.gserviceaccount\.com$/.test(string(serviceAccount.client_email))
      || !string(serviceAccount.private_key).startsWith('-----BEGIN PRIVATE KEY-----')
      || serviceAccount.private_key.length > 32_000) throw new SyncError('INVALID_GOOGLE_CREDENTIALS');
  let url;
  try { url = new URL(env('SUPABASE_URL') || ''); }
  catch { throw new SyncError('SUPABASE_CONFIGURATION_MISSING'); }
  if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash
      || !['', '/'].includes(url.pathname)) throw new SyncError('INVALID_SUPABASE_URL');
  const roleKey = env('SUPABASE_SERVICE_ROLE_KEY') || '';
  if (roleKey.length < 20 || roleKey.length > 4096 || /[\r\n]/.test(roleKey)) throw new SyncError('SUPABASE_ROLE_KEY_MISSING');
  return { serviceAccount, supabaseUrl: url.origin, roleKey, familyMap: parseFamilyMap(env('GOOGLE_SHEETS_FAMILY_MAP') || '{}') };
}

/** Hash equal-length digests before comparing, including when source strings have different lengths. */
export async function secretMatches(provided, expected, cryptoImpl = globalThis.crypto) {
  if (typeof expected !== 'string' || expected.length < 32 || expected.length > 256
      || typeof provided !== 'string' || provided.length > 256 || provided.length === 0) return false;
  const [a, b] = await Promise.all([
    cryptoImpl.subtle.digest('SHA-256', encoder.encode(provided)),
    cryptoImpl.subtle.digest('SHA-256', encoder.encode(expected)),
  ]);
  const left = new Uint8Array(a), right = new Uint8Array(b);
  let mismatch = 0;
  for (let index = 0; index < left.length; index++) mismatch |= left[index] ^ right[index];
  return mismatch === 0;
}

/** Both response headers and response body are inside the same timeout. */
export async function requestJson(fetchImpl, url, init = {}, options = {}) {
  const timeoutMs = Math.max(1, Math.min(options.timeoutMs ?? REQUEST_TIMEOUT_MS,
    (options.deadline ?? Infinity) - (options.now ?? Date.now)()));
  if (options.deadline != null && options.deadline <= (options.now ?? Date.now)()) throw new SyncError('RUN_BUDGET_EXHAUSTED');
  const controller = new AbortController();
  let timer;
  const operation = (async () => {
    let response;
    try { response = await fetchImpl(url, { ...init, signal: controller.signal, redirect: 'error' }); }
    catch (error) { throw error instanceof SyncError ? error : new SyncError('UPSTREAM_UNREACHABLE'); }
    let body;
    try { body = await response.text(); }
    catch { throw new SyncError('UPSTREAM_BODY_UNREADABLE'); }
    if (body.length > (options.maxResponseChars ?? 4_000_000)) throw new SyncError('UPSTREAM_RESPONSE_TOO_LARGE');
    if (!response.ok) throw new SyncError(`UPSTREAM_HTTP_${response.status}`, response.status);
    if (!body.trim()) return null;
    try { return JSON.parse(body); }
    catch { throw new SyncError('UPSTREAM_INVALID_JSON'); }
  })();
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => { controller.abort(); reject(new SyncError('UPSTREAM_TIMEOUT')); }, timeoutMs);
  });
  try { return await Promise.race([operation, timeout]); }
  finally { clearTimeout(timer); }
}

function base64Url(bytes) {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
}

export function createGoogleTokenProvider(serviceAccount, options = {}) {
  const cryptoImpl = options.cryptoImpl ?? globalThis.crypto;
  const fetchImpl = options.fetchImpl ?? globalThis.fetch;
  const now = options.now ?? Date.now;
  let cached = null;
  let cacheUntil = 0;
  return async (deadline) => {
    if (cached && now() < cacheUntil) return cached;
    const issuedAt = Math.floor(now() / 1000);
    const header = base64Url(encoder.encode(JSON.stringify({ alg: 'RS256', typ: 'JWT' })));
    const payload = base64Url(encoder.encode(JSON.stringify({
      iss: serviceAccount.client_email, scope: 'https://www.googleapis.com/auth/spreadsheets',
      aud: 'https://oauth2.googleapis.com/token', iat: issuedAt, exp: issuedAt + 3600,
    })));
    const pem = serviceAccount.private_key.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, '');
    let key;
    try {
      const binary = atob(pem);
      const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
      key = await cryptoImpl.subtle.importKey('pkcs8', bytes,
        { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
    } catch { throw new SyncError('GOOGLE_KEY_IMPORT_FAILED'); }
    const signature = await cryptoImpl.subtle.sign('RSASSA-PKCS1-v1_5', key, encoder.encode(`${header}.${payload}`));
    const assertion = `${header}.${payload}.${base64Url(new Uint8Array(signature))}`;
    const token = await requestJson(fetchImpl, 'https://oauth2.googleapis.com/token', {
      method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion }).toString(),
    }, { deadline, now });
    if (!token || typeof token.access_token !== 'string' || token.access_token.length < 10
        || token.token_type?.toLowerCase() !== 'bearer' || !Number.isFinite(token.expires_in)) throw new SyncError('GOOGLE_TOKEN_INVALID');
    cached = token.access_token;
    cacheUntil = now() + Math.max(0, Math.min(3600, token.expires_in) - 60) * 1000;
    return cached;
  };
}

function columnName(count) {
  let label = '';
  while (count > 0) { count--; label = String.fromCharCode(65 + count % 26) + label; count = Math.floor(count / 26); }
  return label;
}
function a1(title, cells) { return `'${title.replaceAll("'", "''")}'!${cells}`; }

export function createSupabaseClient(config, options = {}) {
  const fetchImpl = options.fetchImpl ?? globalThis.fetch;
  const now = options.now ?? Date.now;
  const headers = { apikey: config.roleKey, 'Content-Type': 'application/json', Accept: 'application/json' };
  if (config.roleKey.split('.').length === 3) headers.Authorization = `Bearer ${config.roleKey}`;
  return async (name, body, deadline) => requestJson(fetchImpl, `${config.supabaseUrl}/rest/v1/rpc/${name}`,
    { method: 'POST', headers, body: JSON.stringify(body) }, { deadline, now });
}

export function createSheetsClient(options = {}) {
  const fetchImpl = options.fetchImpl ?? globalThis.fetch;
  const now = options.now ?? Date.now;
  const getToken = options.getToken;
  // The client is constructed once per runSync, so metadata is fresh per run.
  const sheetIdsBySpreadsheet = new Map();
  return {
    async inspect(spreadsheet, schema, deadline) {
      const query = new URLSearchParams({ majorDimension: 'ROWS', valueRenderOption: 'UNFORMATTED_VALUE' });
      query.append('ranges', a1(schema.title, `A1:${columnName(schema.headers.length)}1`));
      query.append('ranges', a1(schema.title, 'A2:A'));
      const token = await getToken(deadline);
      const result = await requestJson(fetchImpl, `https://sheets.googleapis.com/v4/spreadsheets/${spreadsheet}/values:batchGet?${query}`,
        { headers: { Authorization: `Bearer ${token}` } }, { deadline, now, maxResponseChars: 12_000_000 });
      const ranges = result?.valueRanges;
      const actual = ranges?.[0]?.values?.[0] ?? [];
      if (!Array.isArray(ranges) || ranges.length !== 2 || actual.length !== schema.headers.length
          || actual.some((value, index) => value !== schema.headers[index])) throw new SyncError('SHEET_HEADER_MISMATCH');
      const ids = new Set();
      for (const row of ranges[1]?.values ?? []) {
        if (Array.isArray(row) && typeof row[0] === 'string') ids.add(row[0]);
      }
      return ids;
    },
    async append(spreadsheet, schema, rows, deadline) {
      if (rows.length === 0) return;
      const token = await getToken(deadline);
      const range = encodeURIComponent(a1(schema.title, `A:${columnName(schema.headers.length)}`));
      const result = await requestJson(fetchImpl, `https://sheets.googleapis.com/v4/spreadsheets/${spreadsheet}/values/${range}:append?valueInputOption=RAW&insertDataOption=INSERT_ROWS`, {
        method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ majorDimension: 'ROWS', values: rows }),
      }, { deadline, now });
      if (!result?.updates || result.updates.updatedRows !== rows.length) throw new SyncError('APPEND_RESULT_INCOMPLETE');
    },
    async formatDates(spreadsheet, schema, deadline) {
      const token = await getToken(deadline);
      let sheetIds = sheetIdsBySpreadsheet.get(spreadsheet);
      if (!sheetIds) {
        const query = new URLSearchParams({ fields: 'sheets(properties(sheetId,title))' });
        const metadata = await requestJson(fetchImpl,
          `https://sheets.googleapis.com/v4/spreadsheets/${spreadsheet}?${query}`,
          { headers: { Authorization: `Bearer ${token}` } }, { deadline, now });
        if (!Array.isArray(metadata?.sheets)) throw new SyncError('SHEET_METADATA_INVALID');
        sheetIds = new Map();
        for (const sheet of metadata.sheets) {
          const properties = sheet?.properties;
          if (typeof properties?.title !== 'string' || !Number.isSafeInteger(properties.sheetId)
              || properties.sheetId < 0) throw new SyncError('SHEET_METADATA_INVALID');
          sheetIds.set(properties.title, properties.sheetId);
        }
        sheetIdsBySpreadsheet.set(spreadsheet, sheetIds);
      }
      const sheetId = sheetIds.get(schema.title);
      const dateColumns = DATE_COLUMNS[schema.title];
      if (sheetId === undefined || !dateColumns) throw new SyncError('SHEET_FORMAT_TARGET_MISSING');
      const requests = dateColumns.map((column) => ({ repeatCell: {
        // No endRowIndex: format all existing data rows, including rows appended
        // beyond the template's preformatted area. A1 remains untouched.
        range: { sheetId, startRowIndex: 1, startColumnIndex: column, endColumnIndex: column + 1 },
        cell: { userEnteredFormat: { numberFormat: { type: 'DATE_TIME', pattern: 'yyyy-mm-dd hh:mm:ss' } } },
        fields: 'userEnteredFormat.numberFormat',
      } }));
      await requestJson(fetchImpl, `https://sheets.googleapis.com/v4/spreadsheets/${spreadsheet}:batchUpdate`, {
        method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ requests }),
      }, { deadline, now });
    },
  };
}

/** Lease-aware runner; database leases serialize every family across invocations. */
export async function runSync(config, options = {}) {
  const now = options.now ?? Date.now;
  const deadline = now() + Math.min(options.budgetMs ?? RUN_BUDGET_MS, RUN_BUDGET_MS);
  const rpc = options.rpc ?? createSupabaseClient(config, { ...options, now });
  const getToken = options.getToken ?? createGoogleTokenProvider(config.serviceAccount, { ...options, now });
  const sheets = options.sheets ?? createSheetsClient({ ...options, now, getToken });
  const summary = { claimed: 0, written: 0, alreadyPresent: 0, delivered: 0, failed: 0, deferred: 0, ackFailures: 0, families: 0 };
  const seenFamilies = new Set();
  for (let batch = 0; batch < MAX_FAMILIES && summary.claimed < MAX_CLAIM && now() + RUN_BUFFER_MS < deadline; batch++) {
    const records = await rpc('sheet_outbox_claim', { p_limit: MAX_CLAIM - summary.claimed }, deadline);
    if (!Array.isArray(records)) throw new SyncError('CLAIM_RESPONSE_INVALID');
    if (records.length === 0) break;
    if (records.length > MAX_CLAIM - summary.claimed) throw new SyncError('CLAIM_LIMIT_EXCEEDED');
    summary.claimed += records.length;
    const family = records[0]?.family_id;
    // Defensive checks reject a broken/mismatched server contract before accessing Google.
    if (!UUID.test(string(family)) || records.some((record) => record.family_id !== family)) throw new SyncError('CLAIM_CROSS_FAMILY');
    if (seenFamilies.has(family)) { summary.deferred += records.length; break; }
    seenFamilies.add(family);
    summary.families++;
    const groups = new Map();
    const results = new Map();
    for (const record of records) {
      try {
        const schema = columnsFor(record.entity_type);
        const row = rowFor(record);
        const expires = Date.parse(record.lease_until);
        if (!Number.isFinite(expires) || expires - now() < LEASE_BUFFER_MS) throw new SyncError('LEASE_TOO_CLOSE_TO_EXPIRY');
        if (!groups.has(record.entity_type)) groups.set(record.entity_type, { schema, entries: [], expires });
        const group = groups.get(record.entity_type);
        group.expires = Math.min(group.expires, expires);
        group.entries.push({ record, row });
      } catch (error) { results.set(record, error instanceof SyncError ? error.code : 'INVALID_RECORD'); }
    }
    const spreadsheet = config.familyMap.get(family.toLowerCase());
    for (const group of groups.values()) {
      if (!spreadsheet) {
        group.entries.forEach(({ record }) => results.set(record, 'FAMILY_SPREADSHEET_NOT_CONFIGURED'));
        continue;
      }
      try {
        if (now() + RUN_BUFFER_MS >= deadline) throw new SyncError('RUN_BUDGET_EXHAUSTED');
        const ids = await sheets.inspect(spreadsheet, group.schema, Math.min(deadline, group.expires - LEASE_BUFFER_MS));
        const pending = [];
        for (const entry of group.entries) {
          if (ids.has(entry.record.event_id)) {
            summary.alreadyPresent++;
          } else {
            // Handles duplicate event IDs inside a malformed response without double-appending.
            ids.add(entry.record.event_id);
            pending.push(entry);
          }
        }
        if (pending.length) {
          if (group.expires - now() < LEASE_BUFFER_MS || now() + 1000 >= deadline) throw new SyncError('LEASE_TOO_CLOSE_TO_EXPIRY');
          await sheets.append(spreadsheet, group.schema, pending.map(({ row }) => row),
            Math.min(deadline, group.expires - LEASE_BUFFER_MS));
          summary.written += pending.length;
        }
        // Retry formatting even when every immutable event ID is already present.
        // Otherwise an append followed by a format failure could be acknowledged
        // on retry while displaying raw date serial numbers in the user's sheet.
        if (typeof sheets.formatDates !== 'function') throw new SyncError('SHEET_FORMAT_OPERATION_MISSING');
        await sheets.formatDates(spreadsheet, group.schema, Math.min(deadline, group.expires - LEASE_BUFFER_MS));
        group.entries.forEach(({ record }) => results.set(record, null));
      } catch (error) {
        const code = error instanceof SyncError ? error.code : 'SHEETS_OPERATION_FAILED';
        for (const { record } of group.entries) if (!results.has(record)) results.set(record, code);
      }
    }
    // Ack success only after a durable Google write, or a read finding that immutable event ID.
    // Concurrent acks bound network time while keeping the last-row lease-release invariant.
    for (let index = 0; index < records.length; index += 5) {
      await Promise.all(records.slice(index, index + 5).map(async (record) => {
        if (now() + 100 >= deadline) { summary.deferred++; return; }
        const error = results.has(record) ? results.get(record) : 'RUN_BUDGET_EXHAUSTED';
        try {
          await rpc('sheet_outbox_ack', { p_event_id: record.event_id, p_lease_id: record.lease_id, p_error: error }, deadline);
          if (error === null) summary.delivered++; else summary.failed++;
        } catch { summary.ackFailures++; }
      }));
    }
  }
  return summary;
}

function response(status, value) {
  return new Response(JSON.stringify(value), { status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' } });
}

/** No CORS/browser endpoint: only the trusted server scheduler knows x-sync-secret. */
export function createSyncHandler(options) {
  const env = options.env;
  let config;
  let getToken;
  return async (request) => {
    if (request.method !== 'POST') return response(405, { error: 'METHOD_NOT_ALLOWED' });
    if (!await secretMatches(request.headers.get('x-sync-secret'), env('SHEETS_SYNC_SECRET'), options.cryptoImpl))
      return response(401, { error: 'UNAUTHORIZED' });
    try {
      config ??= readConfig(env);
      getToken ??= createGoogleTokenProvider(config.serviceAccount, options);
      const result = await runSync(config, { ...options, getToken });
      return response(200, result);
    } catch (error) {
      // Deliberately never include upstream response text, headers, tokens, or credential JSON.
      const code = error instanceof SyncError ? error.code : 'SYNC_FAILED';
      options.log?.({ event: 'sheets_sync_failed', code });
      return response(503, { error: code });
    }
  };
}

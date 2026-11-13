import test from 'node:test';
import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
import {
  SHEET_SCHEMA, SyncError, parseFamilyMap, rowFor, sheetsDateSerial, secretMatches,
  requestJson, createGoogleTokenProvider, createSheetsClient, createSyncHandler, runSync,
} from './sync-core.mjs';

const FAMILY_A = 'a0000000-0000-4000-8000-000000000001';
const FAMILY_B = 'b0000000-0000-4000-8000-000000000002';
const USER = 'c0000000-0000-4000-8000-000000000003';
const LEASE = 'd0000000-0000-4000-8000-000000000004';
const SHEET_A = 'spreadsheet_family_A_0123456789';
const SHEET_B = 'spreadsheet_family_B_0123456789';
const NOW = Date.parse('2026-11-13T04:00:00Z');
const SECRET = '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef';

function record(index = 1, overrides = {}) {
  return {
    event_id: `e0000000-0000-4000-8000-${String(index).padStart(12, '0')}`,
    family_id: FAMILY_A, user_id: USER, entity_type: 'location', operation: 'INSERT', entity_id: USER,
    occurred_at: '2026-11-13T04:00:00Z', lease_id: LEASE,
    lease_until: new Date(NOW + 300_000).toISOString(),
    payload: { data: { family_id: FAMILY_A, user_id: USER, recorded_at: '2026-11-13T03:59:00Z',
      latitude: 25.03, longitude: 121.56, accuracy_m: 12, battery_level: 75 }, display_name: '本人' },
    ...overrides,
  };
}

function harness(batches, options = {}) {
  const acknowledgements = [];
  const writes = [];
  const formats = [];
  const durable = options.durable ?? new Map();
  const config = { familyMap: parseFamilyMap(options.mapping ?? { [FAMILY_A]: SHEET_A, [FAMILY_B]: SHEET_B }) };
  const rpc = async (name, body) => {
    if (name === 'sheet_outbox_claim') {
      assert.ok(body.p_limit >= 1 && body.p_limit <= 50);
      return batches.shift() ?? [];
    }
    assert.equal(name, 'sheet_outbox_ack');
    acknowledgements.push(body);
    if (options.failAck) throw new SyncError('ACK_FAILED');
    return null;
  };
  const sheets = {
    async inspect(sheet, schema) {
      if (options.inspectError) throw new SyncError(options.inspectError);
      return new Set((durable.get(`${sheet}/${schema.title}`) ?? []).map((row) => row[0]));
    },
    async append(sheet, schema, rows) {
      writes.push({ sheet, title: schema.title, rows });
      const key = `${sheet}/${schema.title}`;
      durable.set(key, [...(durable.get(key) ?? []), ...rows]);
      if (options.failAfterWrite) throw new SyncError('UPSTREAM_TIMEOUT');
    },
    async formatDates(sheet, schema) {
      formats.push({ sheet, title: schema.title });
      if (options.formatError) throw new SyncError(options.formatError);
    },
  };
  return { config, rpc, sheets, writes, formats, acknowledgements, durable,
    async run(extra = {}) { return runSync(config, { rpc, sheets, now: () => NOW, ...extra }); } };
}

test('family routing rejects shared spreadsheet and malformed identifiers', () => {
  assert.equal(parseFamilyMap({ [FAMILY_A]: SHEET_A }).get(FAMILY_A), SHEET_A);
  assert.throws(() => parseFamilyMap({ [FAMILY_A]: SHEET_A, [FAMILY_B]: SHEET_A }), /SPREADSHEET_SHARED_BETWEEN_FAMILIES/);
  assert.throws(() => parseFamilyMap({ [FAMILY_A]: 'https://attacker.invalid/path' }), /INVALID_FAMILY_MAPPING/);
  assert.throws(() => parseFamilyMap({ bad: SHEET_A }), /INVALID_FAMILY_MAPPING/);
});

test('dates are numeric serials displayed in Taipei and original ISO stays in JSON', () => {
  const value = record();
  const row = rowFor(value);
  assert.equal(typeof row[5], 'number');
  assert.equal(typeof row[10], 'number');
  assert.equal(sheetsDateSerial('1970-01-01T00:00:00Z'), 25569 + 8 / 24);
  assert.equal(JSON.parse(row[11]).data.recorded_at, value.payload.data.recorded_at);
  assert.equal(row[9], 75);
  assert.throws(() => sheetsDateSerial('garbage'), /INVALID_DATE/);
});

test('calendar formula-like text remains literal and row shapes match template headers', () => {
  const value = record(1, { entity_type: 'calendar', entity_id: USER,
    payload: { data: { id: USER, family_id: FAMILY_A, title: '=IMPORTXML("x")',
      starts_at: '2026-11-14T02:00:00Z', ends_at: '2026-11-14T03:00:00Z', timezone: 'Asia/Taipei',
      reminder_minutes: 30, location: '+1+1', description: '@hello', created_by: USER } } });
  const row = rowFor(value);
  assert.equal(row.length, SHEET_SCHEMA.calendar.headers.length);
  assert.equal(row[4], '=IMPORTXML("x")');
  assert.equal(row[9], '+1+1');
  assert.equal(row[10], '@hello');
  assert.equal(typeof row[5], 'number');
  for (const type of ['family', 'member', 'settings', 'location']) {
    assert.equal(rowFor(record(2, { entity_type: type })).length, SHEET_SCHEMA[type].headers.length);
  }
});

test('cross-family payload is refused before any Google write', async () => {
  const bad = record(1, { payload: { data: { family_id: FAMILY_B } } });
  const run = harness([[bad]]);
  const result = await run.run();
  assert.equal(result.failed, 1);
  assert.equal(run.writes.length, 0);
  assert.equal(run.acknowledgements[0].p_error, 'CROSS_FAMILY_PAYLOAD');
});

test('one batch appends several same-tab records together and success is acknowledged', async () => {
  const run = harness([[record(1), record(2), record(3)]]);
  const result = await run.run();
  assert.equal(result.written, 3);
  assert.equal(result.delivered, 3);
  assert.equal(run.writes.length, 1);
  assert.equal(run.writes[0].rows.length, 3);
  assert.ok(run.acknowledgements.every((ack) => ack.p_error === null && ack.p_lease_id === LEASE));
});

test('ambiguous append response retains task and retry finds durable event id without duplication', async () => {
  const durable = new Map();
  const first = harness([[record()]], { durable, failAfterWrite: true });
  const failed = await first.run();
  assert.equal(failed.failed, 1);
  assert.equal(first.acknowledgements[0].p_error, 'UPSTREAM_TIMEOUT');
  const second = harness([[record()]], { durable });
  const retried = await second.run();
  assert.equal(retried.alreadyPresent, 1);
  assert.equal(retried.delivered, 1);
  assert.equal(second.writes.length, 0);
  assert.equal(durable.get(`${SHEET_A}/定位回報`).length, 1);
});

test('failed ack retries safely using durable Google event id', async () => {
  const durable = new Map();
  const first = harness([[record()]], { durable, failAck: true });
  const result = await first.run();
  assert.equal(result.ackFailures, 1);
  const retry = harness([[record()]], { durable });
  assert.equal((await retry.run()).delivered, 1);
  assert.equal(retry.writes.length, 0);
});

test('format failure after durable append retains task; duplicate retry formats before ack without appending', async () => {
  const durable = new Map();
  const first = harness([[record()]], { durable, formatError: 'UPSTREAM_HTTP_403' });
  const failed = await first.run();
  assert.equal(failed.written, 1);
  assert.equal(failed.delivered, 0);
  assert.equal(failed.failed, 1);
  assert.equal(first.acknowledgements[0].p_error, 'UPSTREAM_HTTP_403');
  const second = harness([[record()]], { durable });
  const retried = await second.run();
  assert.equal(retried.alreadyPresent, 1);
  assert.equal(retried.delivered, 1);
  assert.equal(second.writes.length, 0);
  assert.equal(second.formats.length, 1);
  assert.equal(second.acknowledgements[0].p_error, null);
  assert.equal(durable.get(`${SHEET_A}/定位回報`).length, 1);
});

test('missing spreadsheet mapping for one family does not prevent another family syncing', async () => {
  const second = record(2, { family_id: FAMILY_B, payload: { data: { family_id: FAMILY_B, user_id: USER } } });
  const run = harness([[record()], [second]], { mapping: { [FAMILY_B]: SHEET_B } });
  const result = await run.run();
  assert.equal(result.failed, 1);
  assert.equal(result.delivered, 1);
  assert.equal(run.writes.length, 1);
  assert.equal(run.writes[0].sheet, SHEET_B);
});

test('near-expired leases fail without writing', async () => {
  const run = harness([[record(1, { lease_until: new Date(NOW + 185_000).toISOString() })]]);
  const result = await run.run();
  assert.equal(result.failed, 1);
  assert.equal(run.writes.length, 0);
  assert.equal(run.acknowledgements[0].p_error, 'LEASE_TOO_CLOSE_TO_EXPIRY');
});

test('batch cannot contain multiple families, even if mappings exist', async () => {
  const run = harness([[record(), record(2, { family_id: FAMILY_B })]]);
  await assert.rejects(run.run(), /CLAIM_CROSS_FAMILY/);
  assert.equal(run.writes.length, 0);
});

test('bad headers retain outbox task instead of altering user worksheet', async () => {
  const run = harness([[record()]]);
  const sheets = createSheetsClient({ getToken: async () => 'google-test-token', now: () => NOW,
    fetchImpl: async () => Response.json({ valueRanges: [{ values: [['unexpected header']] }, { values: [] }] }) });
  const result = await run.run({ sheets });
  assert.equal(result.failed, 1);
  assert.equal(run.acknowledgements[0].p_error, 'SHEET_HEADER_MISMATCH');
});

test('Sheets REST appends RAW values and an allowlisted fixed host/range', async () => {
  const seen = [];
  const sheets = createSheetsClient({ getToken: async () => 'google-test-token', now: () => NOW,
    fetchImpl: async (url, init) => {
      seen.push({ url: new URL(url), init });
      if (init.method === 'POST') return Response.json({ updates: { updatedRows: 1 } });
      return Response.json({ valueRanges: [{ values: [SHEET_SCHEMA.calendar.headers] }, { values: [['existing-id']] }] });
    } });
  const ids = await sheets.inspect(SHEET_A, SHEET_SCHEMA.calendar, NOW + 20_000);
  assert.ok(ids.has('existing-id'));
  await sheets.append(SHEET_A, SHEET_SCHEMA.calendar, [['=1+1']], NOW + 20_000);
  assert.equal(seen[1].url.hostname, 'sheets.googleapis.com');
  assert.equal(seen[1].url.searchParams.get('valueInputOption'), 'RAW');
  assert.equal(JSON.parse(seen[1].init.body).values[0][0], '=1+1');
  assert.equal(seen[1].init.redirect, 'error');
});

test('row beyond 201 is formatted across entire date columns and metadata is cached per spreadsheet', async () => {
  const storedRows = Array.from({ length: 201 }, (_, index) => rowFor(record(index + 1)));
  let metadataRequests = 0;
  const formatRequests = [];
  const run = harness([[record(202)]]);
  const sheets = createSheetsClient({ getToken: async () => 'google-test-token', now: () => NOW,
    fetchImpl: async (url, init) => {
      const target = new URL(url);
      if (target.pathname.endsWith('/values:batchGet')) return Response.json({ valueRanges: [
        { values: [SHEET_SCHEMA.location.headers] }, { values: storedRows.map((row) => [row[0]]) },
      ] });
      if (target.pathname.includes('/values/') && target.pathname.endsWith(':append')) {
        const rows = JSON.parse(init.body).values;
        storedRows.push(...rows);
        return Response.json({ updates: { updatedRows: rows.length } });
      }
      if (target.pathname.endsWith(':batchUpdate')) {
        formatRequests.push(JSON.parse(init.body).requests);
        return Response.json({ replies: [] });
      }
      metadataRequests++;
      assert.equal(target.searchParams.get('fields'), 'sheets(properties(sheetId,title))');
      return Response.json({ sheets: [
        { properties: { sheetId: 42, title: SHEET_SCHEMA.location.title } },
        { properties: { sheetId: 0, title: SHEET_SCHEMA.member.title } },
      ] });
    },
  });
  const result = await run.run({ sheets });
  assert.equal(result.delivered, 1);
  assert.equal(storedRows.length, 202);
  assert.equal(formatRequests.length, 1);
  assert.equal(formatRequests[0].length, 2);
  for (const request of formatRequests[0]) {
    assert.equal(request.repeatCell.range.sheetId, 42);
    assert.equal(request.repeatCell.range.startRowIndex, 1);
    assert.equal('endRowIndex' in request.repeatCell.range, false);
    assert.equal(request.repeatCell.fields, 'userEnteredFormat.numberFormat');
    assert.deepEqual(request.repeatCell.cell.userEnteredFormat.numberFormat,
      { type: 'DATE_TIME', pattern: 'yyyy-mm-dd hh:mm:ss' });
  }
  assert.deepEqual(formatRequests[0].map((request) => request.repeatCell.range.startColumnIndex), [5, 10]);
  await sheets.formatDates(SHEET_A, SHEET_SCHEMA.member, NOW + 20_000);
  assert.equal(metadataRequests, 1);
  assert.equal(formatRequests[1][0].repeatCell.range.sheetId, 0);
});

test('strong secret required; unauthorized requests read no credentials or records', async () => {
  assert.equal(await secretMatches(SECRET, SECRET, webcrypto), true);
  assert.equal(await secretMatches(`${SECRET}x`, SECRET, webcrypto), false);
  assert.equal(await secretMatches(null, SECRET, webcrypto), false);
  assert.equal(await secretMatches('weak', 'weak', webcrypto), false);
  let reads = 0, requests = 0;
  const handler = createSyncHandler({ cryptoImpl: webcrypto, env: (name) => {
    if (name !== 'SHEETS_SYNC_SECRET') reads++;
    return name === 'SHEETS_SYNC_SECRET' ? SECRET : undefined;
  }, fetchImpl: async () => { requests++; throw new Error('must not fetch'); } });
  const denied = await handler(new Request('https://example.invalid', { method: 'POST' }));
  assert.equal(denied.status, 401);
  assert.equal(reads, 0);
  assert.equal(requests, 0);
  assert.equal((await handler(new Request('https://example.invalid'))).status, 405);
});

test('HTTP timeout bounds both fetch and response-body processing', async () => {
  const started = Date.now();
  await assert.rejects(requestJson(async () => new Promise(() => {}), 'https://example.invalid', {}, { timeoutMs: 15 }), /UPSTREAM_TIMEOUT/);
  await assert.rejects(requestJson(async () => ({ ok: true, text: () => new Promise(() => {}) }),
    'https://example.invalid', {}, { timeoutMs: 15 }), /UPSTREAM_TIMEOUT/);
  assert.ok(Date.now() - started < 1000);
});

test('OAuth JWT signs RS256 with Sheets-only scope, fixed audience, 1h lifetime, and token cache', async () => {
  const pair = await webcrypto.subtle.generateKey({ name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048,
    publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' }, true, ['sign', 'verify']);
  const pkcs8 = await webcrypto.subtle.exportKey('pkcs8', pair.privateKey);
  const private_key = `-----BEGIN PRIVATE KEY-----\n${Buffer.from(pkcs8).toString('base64')}\n-----END PRIVATE KEY-----`;
  let calls = 0;
  const provider = createGoogleTokenProvider({ private_key, client_email: 'test@project.iam.gserviceaccount.com' }, {
    cryptoImpl: webcrypto, now: () => NOW,
    fetchImpl: async (url, init) => {
      calls++;
      assert.equal(url, 'https://oauth2.googleapis.com/token');
      const form = new URLSearchParams(init.body);
      assert.equal(form.get('grant_type'), 'urn:ietf:params:oauth:grant-type:jwt-bearer');
      const [header, payload, signature] = form.get('assertion').split('.');
      assert.equal(JSON.parse(Buffer.from(header, 'base64url')).alg, 'RS256');
      const claims = JSON.parse(Buffer.from(payload, 'base64url'));
      assert.equal(claims.scope, 'https://www.googleapis.com/auth/spreadsheets');
      assert.equal(claims.aud, 'https://oauth2.googleapis.com/token');
      assert.equal(claims.exp - claims.iat, 3600);
      assert.equal(await webcrypto.subtle.verify('RSASSA-PKCS1-v1_5', pair.publicKey,
        Buffer.from(signature, 'base64url'), new TextEncoder().encode(`${header}.${payload}`)), true);
      return Response.json({ access_token: 'fresh-access-token', token_type: 'Bearer', expires_in: 3600 });
    },
  });
  assert.equal(await provider(NOW + 20_000), 'fresh-access-token');
  assert.equal(await provider(NOW + 20_000), 'fresh-access-token');
  assert.equal(calls, 1);
});

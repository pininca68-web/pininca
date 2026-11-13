// Local PostgreSQL/WASM verification only. No Supabase account or live data is used.
import { readFile, writeFile, mkdir, access } from 'node:fs/promises';
import { constants } from 'node:fs';
import { createHash, timingSafeEqual } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));
const tooling = path.join(root, '.tooling', 'pglite');
const packageRoot = path.join(tooling, 'package');
const entry = path.join(packageRoot, 'dist', 'index.js');
const version = '0.3.14';

async function exists(file) {
  try { await access(file, constants.R_OK); return true; }
  catch { return false; }
}

async function fetchChecked(url) {
  const response = await fetch(url, { signal: AbortSignal.timeout(120_000) });
  if (!response.ok) throw new Error(`Download failed (${response.status}): ${url}`);
  return response;
}

async function downloadPglite() {
  await mkdir(tooling, { recursive: true });
  const metadataUrl = `https://registry.npmjs.org/@electric-sql/pglite/${version}`;
  const metadata = await (await fetchChecked(metadataUrl)).json();
  if (metadata.name !== '@electric-sql/pglite' || metadata.version !== version) {
    throw new Error('Unexpected npm package identity.');
  }
  const tarballUrl = new URL(metadata.dist?.tarball ?? '');
  const integrity = metadata.dist?.integrity ?? '';
  if (tarballUrl.protocol !== 'https:' || tarballUrl.hostname !== 'registry.npmjs.org'
      || !integrity.startsWith('sha512-')) {
    throw new Error('Expected an official HTTPS npm tarball and SHA512 integrity.');
  }
  console.log(`Downloading @electric-sql/pglite ${version} from the official npm registry...`);
  const archive = Buffer.from(await (await fetchChecked(tarballUrl)).arrayBuffer());
  const expected = Buffer.from(integrity.slice('sha512-'.length), 'base64');
  const actual = createHash('sha512').update(archive).digest();
  if (actual.length !== expected.length || !timingSafeEqual(actual, expected)) {
    throw new Error('PGlite package SHA512 mismatch. Nothing was extracted.');
  }
  const archiveFile = path.join(tooling, `pglite-${version}.tgz`);
  await writeFile(archiveFile, archive);
  const list = spawnSync('tar', ['-tzf', archiveFile], { encoding: 'utf8' });
  if (list.error || list.status !== 0) throw new Error(list.error?.message ?? list.stderr);
  for (const name of list.stdout.split(/\r?\n/).filter(Boolean)) {
    const normalized = name.replaceAll('\\', '/');
    if (!normalized.startsWith('package/') || normalized.split('/').includes('..')) {
      throw new Error(`Unsafe package archive path: ${name}`);
    }
  }
  const unpack = spawnSync('tar', ['-xzf', archiveFile, '-C', tooling], { encoding: 'utf8' });
  if (unpack.error || unpack.status !== 0) throw new Error(unpack.error?.message ?? unpack.stderr);
  await writeFile(path.join(tooling, 'verified-package.json'), JSON.stringify({
    name: metadata.name, version, tarball: tarballUrl.href, integrity,
    verifiedAt: new Date().toISOString(),
  }, null, 2) + '\n');
  if (!(await exists(entry))) throw new Error('Verified package does not contain the expected JS entry.');
  console.log('SHA512 verified; package extracted without npm install or lifecycle scripts.');
}

async function main() {
  if (!(await exists(entry))) {
    if (!process.argv.includes('--download')) {
      throw new Error('PGlite is missing. Run: node tools/test-backend.mjs --download');
    }
    await downloadPglite();
  }
  const metadata = JSON.parse(await readFile(path.join(packageRoot, 'package.json'), 'utf8'));
  if (metadata.name !== '@electric-sql/pglite' || metadata.version !== version) {
    throw new Error('Local PGlite package identity differs from the pinned version.');
  }
  const { PGlite } = await import(pathToFileURL(entry).href);
  const db = new PGlite();
  try {
    await db.exec(`
      create role anon nologin;
      create role authenticated nologin;
      create schema auth;
      create table auth.users (
        id uuid primary key,
        email text,
        raw_user_meta_data jsonb not null default '{}'::jsonb
      );
      create function auth.uid() returns uuid language sql stable as $$
        select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid;
      $$;
      grant usage on schema auth to anon, authenticated;
      grant execute on function auth.uid() to anon, authenticated;
    `);
    const schema = await readFile(path.join(root, 'backend', 'schema.sql'), 'utf8');
    const tests = await readFile(path.join(root, 'backend', 'security-tests.sql'), 'utf8');
    await db.exec(schema);
    console.log('PASS: schema.sql executed on a fresh local PostgreSQL engine.');
    const results = await db.exec(tests);
    const assertionCount = (tests.match(/select\s+pg_temp\.fl_assert\s*\(/gi) ?? []).length;
    const expectedErrorCount = (tests.match(/select\s+pg_temp\.fl_expect_error\s*\(/gi) ?? []).length;
    const remaining = await db.query('select count(*)::integer as count from auth.users');
    if (remaining.rows[0]?.count !== 0) throw new Error('Security test ROLLBACK did not remove test users.');
    const report = {
      engine: `@electric-sql/pglite ${version}`, status: 'passed',
      checkedAt: new Date().toISOString(), statementResults: results.length,
      assertionCount, expectedErrorCount, fixtureRollbackVerified: true,
      limitations: 'Local SQL/role/RLS verification; not live Supabase HTTP auth, concurrent connections, or Android device testing.',
    };
    const reportDir = path.join(root, 'output');
    await mkdir(reportDir, { recursive: true });
    await writeFile(path.join(reportDir, 'backend-test-result.json'), JSON.stringify(report, null, 2) + '\n');
    console.log(`PASS: ${assertionCount} SQL assertions and ${expectedErrorCount} expected-error checks; fixtures rolled back.`);
    console.log('Limit: no live Supabase HTTP authentication or simultaneous-connection test.');
  } finally {
    await db.close();
  }
}

main().catch(error => {
  console.error(`Backend verification failed: ${error.message}`);
  if (error.code) console.error(`SQL/system code: ${error.code}`);
  if (error.detail) console.error(`Detail: ${error.detail}`);
  process.exitCode = 1;
});

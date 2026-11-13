import { createSyncHandler } from './sync-core.mjs';

// Server-only. verify_jwt may be disabled because every request requires our
// independent strong scheduler secret before any credentials or rows are read.
Deno.serve(createSyncHandler({
  env: Deno.env.get.bind(Deno.env),
  fetchImpl: fetch,
  cryptoImpl: crypto,
  log: console.error.bind(console),
}));

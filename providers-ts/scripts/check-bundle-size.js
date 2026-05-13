#!/usr/bin/env node
/**
 * scripts/check-bundle-size.js
 *
 * Fails the build if any generated bundle exceeds MAX_BYTES (150 KB).
 * Run after rollup: `rollup -c && node scripts/check-bundle-size.js`
 */

import { readdirSync, statSync, mkdirSync } from 'fs';
import { join } from 'path';

const ROOT_DIR   = new URL('..', import.meta.url).pathname;
const DIST_DIR   = join(ROOT_DIR, 'dist');
const MAX_BYTES  = 150 * 1024; // 150 KB

mkdirSync(DIST_DIR, { recursive: true });

let failed = false;

for (const name of readdirSync(DIST_DIR)) {
  if (!name.endsWith('.js')) continue;
  const filePath = join(DIST_DIR, name);
  const size     = statSync(filePath).size;
  const kb       = (size / 1024).toFixed(1);
  if (size > MAX_BYTES) {
    console.error(`❌  Bundle too large: ${name}  (${kb} KB > 150 KB)`);
    console.error(`    Check for accidental polyfill chains or large transitive deps.`);
    failed = true;
  } else {
    console.log(`✅  ${name}  ${kb} KB`);
  }
}

if (failed) process.exit(1);

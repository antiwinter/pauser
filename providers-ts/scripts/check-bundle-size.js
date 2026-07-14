#!/usr/bin/env node
/**
 * scripts/check-bundle-size.js
 *
 * Fails the build if any generated bundle exceeds MAX_BYTES (150 KB).
 * Run after rollup: `rollup -c && node scripts/check-bundle-size.js`
 */

import { readdirSync, statSync, mkdirSync } from 'fs';
import { join } from 'path';
import { fileURLToPath } from 'url';

const ROOT_DIR   = fileURLToPath(new URL('..', import.meta.url));
const DIST_DIR   = join(ROOT_DIR, 'dist');
const MAX_BYTES  = 150 * 1024; // 150 KB

mkdirSync(DIST_DIR, { recursive: true });

let failed = false;

function walk(dir) {
  for (const name of readdirSync(dir)) {
    const filePath = join(dir, name);
    const st = statSync(filePath);
    if (st.isDirectory()) {
      walk(filePath);
    } else if (name === 'index.js') {
      const size = st.size;
      const kb   = (size / 1024).toFixed(1);
      if (size > MAX_BYTES) {
        console.error(`❌  Bundle too large: ${filePath.slice(DIST_DIR.length + 1)}  (${kb} KB > 150 KB)`);
        console.error(`    Check for accidental polyfill chains or large transitive deps.`);
        failed = true;
      } else {
        console.log(`✅  ${filePath.slice(DIST_DIR.length + 1)}  ${kb} KB`);
      }
    }
  }
}

walk(DIST_DIR);

if (failed) process.exit(1);

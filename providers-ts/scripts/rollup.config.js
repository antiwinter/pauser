import { readdirSync, existsSync, mkdirSync, copyFileSync, statSync } from 'fs';
import { dirname, join } from 'path';
import { fileURLToPath } from 'url';
import typescript from '@rollup/plugin-typescript';
import nodeResolve from '@rollup/plugin-node-resolve';
import terser from '@rollup/plugin-terser';
import quickjsCompat from './rollup-plugin-quickjs-compat.js';

const __dirname = dirname(fileURLToPath(import.meta.url));
const rootDir = join(__dirname, '..');
const providersRoot = join(rootDir, 'providers');

const providerNames = readdirSync(providersRoot, { withFileTypes: true })
  .filter((e) => e.isDirectory())
  .map((e) => e.name)
  .filter((name) => existsSync(join(providersRoot, name, 'index.ts')));

if (providerNames.length === 0) {
  throw new Error(`[rollup] No providers found under ${providersRoot} (expected */index.ts)`);
}

function makePlugins() {
  return [
    quickjsCompat(), // must be first — blocks forbidden imports before resolution
    nodeResolve({ preferBuiltins: false }),
    typescript({ tsconfig: join(rootDir, 'tsconfig.json') }),
    terser(),
  ];
}

/** Materialise release-shape assets for one provider into `dist/<name>/`:
 *  - `meta.json` — provider manifest read by `JsProviderLoader.readProviderMeta`
 *  - `*.jar`     — co-located JARs auto-injected by `JsProviderLoader.injectCoLocatedJars`
 *  Source-only files (`.ts`, `.env`, anything else) are NOT part of the release shape
 *  and stay out of dist. `dist/` is wiped by `scripts/clean-dist.js` before the build,
 *  so stale outputs from a previous run cannot survive here. */
function isReleaseAsset(name) {
  return name === 'meta.json' || name.endsWith('.jar');
}

function copyProviderAssets(name) {
  const srcDir = join(providersRoot, name);
  const dstDir = join(rootDir, 'dist', name);
  mkdirSync(dstDir, { recursive: true });
  for (const child of readdirSync(srcDir)) {
    const src = join(srcDir, child);
    const dst = join(dstDir, child);
    if (!existsSync(src) || !statSync(src).isFile()) continue;
    if (!isReleaseAsset(child)) continue;
    copyFileSync(src, dst);
  }
  return {
    name: 'copy-provider-assets',
    buildStart() { copyProviderAssets(name); },
  };
}

/** @type {import('rollup').RollupOptions[]} */
export default providerNames.map((name) => ({
  input: join(rootDir, 'providers', name, 'index.ts'),
  plugins: [...makePlugins(), copyProviderAssets(name)],
  output: {
    file: join(rootDir, 'dist', name, 'index.js'),
    format: 'iife',
    name: 'insomniaProvider', // the bundle assigns its `export default` to
                              // `globalThis.insomniaProvider`; host looks methods
                              // up via that single key.
    strict: false, // QuickJS bundle does not need the 'use strict' wrapper
  },
}));

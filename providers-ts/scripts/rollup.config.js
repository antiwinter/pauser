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

/** Materialise every sibling of `index.ts` (meta.json, *.jar, …) into dist/<name>/ so the
 *  Gradle asset merger picks the folder up whole. */
function copyProviderAssets(name) {
  const srcDir = join(providersRoot, name);
  const dstDir = join(rootDir, 'dist', name);
  mkdirSync(dstDir, { recursive: true });
  for (const child of readdirSync(srcDir)) {
    if (child === 'index.ts') continue;
    const src = join(srcDir, child);
    const dst = join(dstDir, child);
    if (!existsSync(src)) continue;
    // Only copy regular files. Subdirectories under a provider (e.g. catvod/shim-jar,
    // catvod/test) are not part of the asset bundle — those are build/test inputs.
    if (!statSync(src).isFile()) continue;
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

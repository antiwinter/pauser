import { readdirSync, existsSync } from 'fs';
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

/** @type {import('rollup').RollupOptions[]} */
export default providerNames.map((name) => ({
  input: join(rootDir, 'providers', name, 'index.ts'),
  plugins: makePlugins(),
  output: {
    file: join(rootDir, 'dist', `${name}.js`),
    format: 'iife',
    name: 'opentuneProvider', // sets globalThis.opentuneProvider in QuickJS
    strict: false, // QuickJS bundle does not need the 'use strict' wrapper
  },
}));

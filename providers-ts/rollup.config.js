import typescript from '@rollup/plugin-typescript';
import nodeResolve from '@rollup/plugin-node-resolve';
import terser from '@rollup/plugin-terser';
import quickjsCompat from './rollup-plugin-quickjs-compat.js';

/** @type {import('rollup').RollupOptions[]} */
export default [
  {
    input: 'emby/index.ts',
    plugins: [
      quickjsCompat(), // must be first — blocks forbidden imports before resolution
      nodeResolve({ preferBuiltins: false }),
      typescript({ tsconfig: './tsconfig.json' }),
      terser(),
    ],
    output: {
      file:   'dist/emby.js',
      format: 'iife',
      name:   'opentuneProvider', // sets globalThis.opentuneProvider in QuickJS
      strict: false,              // QuickJS bundle does not need the 'use strict' wrapper
    },
  },
];

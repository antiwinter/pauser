/**
 * rollup-plugin-quickjs-compat.js
 *
 * Rollup plugin that blocks imports of modules forbidden in the QuickJS environment.
 * QuickJS-ng has no `window`, no `node:*`, no bare `fs`/`crypto`/`https`, etc.
 * If a dependency tries to import these, the build fails with a clear error.
 */

const FORBIDDEN = [
  /^node:/,
  /^fs($|\/)/,
  /^path($|\/)/,
  /^crypto($|\/)/,
  /^https?($|\/)/,
  /^http($|\/)/,
  /^os($|\/)/,
  /^child_process($|\/)/,
  /^stream($|\/)/,
  /^buffer($|\/)/,
  /^util($|\/)/,
  /^events($|\/)/,
  /^assert($|\/)/,
  /^url($|\/)/,
  /^net($|\/)/,
  /^dns($|\/)/,
  /^tls($|\/)/,
  /^zlib($|\/)/,
  /^process($|\/)/,
  /^vm($|\/)/,
  /^readline($|\/)/,
  /^worker_threads($|\/)/,
];

export default function quickjsCompat() {
  return {
    name: 'quickjs-compat',
    resolveId(source, importer) {
      const forbidden = FORBIDDEN.find((re) => re.test(source));
      if (forbidden) {
        throw new Error(
          `[quickjs-compat] Forbidden import "${source}" in "${importer ?? '?'}"\n` +
          `  QuickJS does not support Node.js built-in modules.\n` +
          `  Pattern: ${forbidden}`,
        );
      }
      return null; // let other plugins handle it
    },
  };
}

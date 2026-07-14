import { readFile } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { assert, assertType } from '../validators.js';

const REQUIRED_METHODS = [
  'init',
  'test',
  'listEntry',
  'search',
  'updateEntryState',
];
// Methods that may or may not be present depending on the provider's surface.
// We log presence but do not fail on absence — providers add what they need.
const OPTIONAL_METHODS = [
  'getEntries',
  'getPlaybackSources',
  'resetSpiders',
  'getQr',
  'pollQr',
];

const __dirname = dirname(fileURLToPath(import.meta.url));
const providersTsRoot = join(__dirname, '..', '..');

/**
 * Category: Bridge
 * Verifies that the provider bundle exposes the required dynamic contract and that
 * its `meta.json` carries the static surface (providesArt, fieldSpec, version, …).
 *
 * @param {import('../reporter.js').Reporter} reporter
 * @param {import('../quickjs-runner.js').QuickJsProviderRunner} runner
 * @param {{ providerName: string }} ctx
 * @returns {Promise<{ providesArt: boolean, meta: object }>}
 */
export async function runBridgeChecks(reporter, runner, { providerName }) {
  reporter.beginCategory('Bridge', ['meta.json', 'insomniaProvider']);

  let providesArt = false;
  let meta;

  await reporter.step('meta.json is valid and has required fields', async () => {
    const path = join(providersTsRoot, 'providers', providerName, 'meta.json');
    meta = JSON.parse(await readFile(path, 'utf8'));
    assertType(meta.protocol, 'string', 'meta.protocol');
    assertType(meta.displayName, 'string', 'meta.displayName');
    assertType(meta.version, 'string', 'meta.version');
    assertType(meta.providesArt, 'boolean', 'meta.providesArt');
    assert(Array.isArray(meta.fieldSpec), 'meta.fieldSpec must be array');
    providesArt = meta.providesArt;
    return `protocol=${meta.protocol} version=${meta.version} fields=${meta.fieldSpec.length}`;
  });

  for (const method of REQUIRED_METHODS) {
    await reporter.step(`method "${method}" exists`, async () => {
      const present = await runner.hasMethod(method);
      assert(present, `missing method: ${method}`);
    });
  }

  for (const method of OPTIONAL_METHODS) {
    await reporter.step(`optional method "${method}"`, async () => {
      const present = await runner.hasMethod(method);
      return present ? 'present' : 'absent';
    });
  }

  reporter.endCategory();
  return { providesArt, meta };
}

import { assert, assertType } from '../validators.js';

const REQUIRED_METHODS = [
  'getFieldsSpec',
  'validateFields',
  'init',
  'listEntry',
  'search',
  'getDetail',
  'getPlaybackSpec',
  'updateEntryState',
];

/**
 * Category: Bridge
 * Verifies that the provider bundle exposes the required contract surface.
 *
 * @param {import('../reporter.js').Reporter} reporter
 * @param {import('../quickjs-runner.js').QuickJsProviderRunner} runner
 * @returns {Promise<{ providesArt: boolean }>}
 */
export async function runBridgeChecks(reporter, runner) {
  reporter.beginCategory('Bridge', ['insomniaProvider']);

  let providesArt = false;

  await reporter.step('providesArt field is present and boolean', async () => {
    const value = await runner.getProvidesArt();
    assertType(value, 'boolean', 'providesArt');
    providesArt = value;
    return `providesArt=${value}`;
  });

  for (const method of REQUIRED_METHODS) {
    await reporter.step(`method "${method}" exists`, async () => {
      const present = await runner.hasMethod(method);
      assert(present, `missing method: ${method}`);
    });
  }

  reporter.endCategory();
  return { providesArt };
}

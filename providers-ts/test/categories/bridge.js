import { assert, assertType } from '../validators.js';

const REQUIRED_METHODS = [
  'getFieldsSpec',
  'validateFields',
  'init',
  'listEntry',
  'search',
  'getDetail',
  'getPlaybackSpec',
  'onPlaybackReady',
  'onProgressTick',
  'onStop',
];

/**
 * Category: Bridge
 * Verifies that the provider bundle exposes the required contract surface.
 *
 * @param {import('../reporter.js').Reporter} reporter
 * @param {import('../quickjs-runner.js').QuickJsProviderRunner} runner
 * @returns {Promise<{ providesCover: boolean }>}
 */
export async function runBridgeChecks(reporter, runner) {
  reporter.beginCategory('Bridge', ['opentuneProvider']);

  let providesCover = false;

  await reporter.step('providesCover field is present and boolean', async () => {
    const value = await runner.getProvidesCover();
    assertType(value, 'boolean', 'providesCover');
    providesCover = value;
    return `providesCover=${value}`;
  });

  for (const method of REQUIRED_METHODS) {
    await reporter.step(`method "${method}" exists`, async () => {
      const present = await runner.hasMethod(method);
      assert(present, `missing method: ${method}`);
    });
  }

  reporter.endCategory();
  return { providesCover };
}

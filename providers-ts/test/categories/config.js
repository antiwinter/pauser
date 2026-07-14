import {
  assert,
  assertType,
  assertNonEmptyString,
  assertObject,
} from '../validators.js';

const VALID_FIELD_KINDS = new Set([
  'text', 'singleLine', 'password', 'proxySelector', 'qrCode',
]);

/**
 * Category: Config
 * Covers `meta.json`'s `fieldSpec` array and `test()` against env credentials.
 *
 * @param {import('../reporter.js').Reporter} reporter
 * @param {import('../quickjs-runner.js').QuickJsProviderRunner} runner
 * @param {{ meta: object }} ctx
 * @param {Record<string,string>} envValues  raw values loaded from .env
 * @returns {Promise<{ fields: import('../../utils/types.js').ServerFieldSpec[], credentials: Record<string,string> }>}
 */
export async function runConfigChecks(reporter, runner, ctx, envValues) {
  reporter.beginCategory('Config', ['meta.json.fieldSpec', 'test()']);

  const fields = await reporter.step('meta.fieldSpec returns a valid fields array', async () => {
    if (!ctx.meta) throw new Error('meta not loaded');
    const arr = ctx.meta.fieldSpec;
    assert(Array.isArray(arr), 'meta.fieldSpec');
    assert(arr.length > 0, 'meta.fieldSpec must have at least one field');
    for (const [i, field] of arr.entries()) validateFieldSpec(field, `fields[${i}]`);
    return arr;
  });

  await reporter.step('field ids are unique', async () => {
    if (!fields) throw new Error('fields not loaded');
    const ids = fields.map((f) => f.id);
    const dupes = ids.filter((id, i) => ids.indexOf(id) !== i);
    assert(dupes.length === 0, `duplicate field ids: ${dupes.join(', ')}`);
    return `${fields.length} field(s)`;
  });

  let testData = null;
  await reporter.step('test() accepts env credentials', async () => {
    const raw = await runner.callMethod('test', { values: envValues });
    const result = typeof raw === 'string' ? JSON.parse(raw) : raw;
    assertObject(result, 'test() result');
    assertType(result.success, 'boolean', 'test() result.success');
    if (!result.success) throw new Error(`test() failed: ${result.error ?? 'unknown provider error'}`);
    assertObject(result.fields, 'test() result.fields');
    testData = result;
    return `success=${result.success} fields=${Object.keys(result.fields).length}`;
  });

  reporter.endCategory();
  return { fields: fields ?? [], credentials: testData?.fields ?? {} };
}

function validateFieldSpec(field, path) {
  assertObject(field, path);
  assertNonEmptyString(field.id, `${path}.id`);
  assert(VALID_FIELD_KINDS.has(field.kind),
    `${path}.kind must be text|singleLine|password|proxySelector|qrCode, got "${field.kind}"`);
  if (field.labelKey != null)       assertType(field.labelKey,       'string',  `${path}.labelKey`);
  if (field.required != null)       assertType(field.required,       'boolean', `${path}.required`);
  if (field.sensitive != null)      assertType(field.sensitive,      'boolean', `${path}.sensitive`);
  if (field.order != null)          assertType(field.order,          'number',  `${path}.order`);
  if (field.placeholderKey != null) assertType(field.placeholderKey, 'string',  `${path}.placeholderKey`);
  if (field.identity != null)       assertType(field.identity,       'boolean', `${path}.identity`);
}

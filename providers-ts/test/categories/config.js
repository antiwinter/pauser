import {
  assert,
  assertType,
  assertNonEmptyString,
  assertObject,
  assertArray,
  parseJsonResult,
} from '../validators.js';

const VALID_FIELD_KINDS = new Set(['text', 'singleLine', 'password']);

/**
 * Category: Config
 * Covers getFieldsSpec and validateFields.
 *
 * @param {import('../reporter.js').Reporter} reporter
 * @param {import('../quickjs-runner.js').QuickJsProviderRunner} runner
 * @param {Record<string,string>} envValues  raw values loaded from .env
 * @returns {Promise<{ fields: import('../../utils/types.js').ServerFieldSpec[], credentials: Record<string,string> }>}
 */
export async function runConfigChecks(reporter, runner, envValues) {
  reporter.beginCategory('Config', ['getFieldsSpec', 'validateFields']);

  const fields = await reporter.step('getFieldsSpec returns a valid fields array', async () => {
    const result = parseJsonResult(await runner.callMethod('getFieldsSpec', {}), 'getFieldsSpec');
    assertArray(result, 'getFieldsSpec result');
    assert(result.length > 0, 'getFieldsSpec must return at least one field');
    for (const [i, field] of result.entries()) validateFieldSpec(field, `fields[${i}]`);
    return result;
  });

  await reporter.step('field ids are unique', async () => {
    if (!fields) throw new Error('fields not loaded');
    const ids = fields.map((f) => f.id);
    const dupes = ids.filter((id, i) => ids.indexOf(id) !== i);
    assert(dupes.length === 0, `duplicate field ids: ${dupes.join(', ')}`);
    return `${fields.length} field(s)`;
  });

  const validation = await reporter.step('validateFields accepts env credentials', async () => {
    const result = parseJsonResult(
      await runner.callMethod('validateFields', { values: envValues }),
      'validateFields',
    );
    assertObject(result, 'validateFields result');
    assertType(result.success, 'boolean', 'validateFields result.success');
    if (!result.success) throw new Error(`validation failed: ${result.error ?? 'unknown provider error'}`);
    assertNonEmptyString(result.hash, 'validateFields result.hash');
    assertNonEmptyString(result.name, 'validateFields result.name');
    assertObject(result.fields, 'validateFields result.fields');
    return `name="${result.name}" hash=${result.hash.slice(0, 8)}…`;
  });

  await reporter.step('validated fields cover all required field ids', async () => {
    if (!fields || !validation) throw new Error('fields or validation not loaded');
    const required = fields.filter((f) => f.required !== false).map((f) => f.id);
    const missing = required.filter((id) => !validation.fields[id]);
    assert(missing.length === 0, `validated fields missing required ids: ${missing.join(', ')}`);
    return `${Object.keys(validation.fields).length} key(s)`;
  });

  reporter.endCategory();
  return { fields: fields ?? [], credentials: validation?.fields ?? {} };
}

function validateFieldSpec(field, path) {
  assertObject(field, path);
  assertNonEmptyString(field.id, `${path}.id`);
  assertNonEmptyString(field.labelKey, `${path}.labelKey`);
  assert(VALID_FIELD_KINDS.has(field.kind), `${path}.kind must be text|singleLine|password, got "${field.kind}"`);
  if (field.required != null) assertType(field.required, 'boolean', `${path}.required`);
  if (field.sensitive != null) assertType(field.sensitive, 'boolean', `${path}.sensitive`);
  if (field.order != null) assertType(field.order, 'number', `${path}.order`);
  if (field.placeholderKey != null) assertType(field.placeholderKey, 'string', `${path}.placeholderKey`);
}

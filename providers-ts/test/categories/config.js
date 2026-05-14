import {
  assert,
  assertType,
  assertNonEmptyString,
  assertObject,
  assertArray,
  parseJsonResult,
} from '../validators.js';
import { NAError } from '../reporter.js';

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

  // Use a closure variable so the step can display a string detail while still
  // returning the full object for subsequent steps and the caller.
  let validationData = null;
  await reporter.step('validateFields accepts env credentials', async () => {
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
    validationData = { name: result.name, hash: result.hash, fields: result.fields };
    return `name="${result.name}" hash=${result.hash.slice(0, 8)}…`;
  });

  await reporter.step('validated fields is a non-empty string map', async () => {
    if (!validationData) throw new NAError('validation not loaded');
    const entries = Object.entries(validationData.fields);
    assert(entries.length > 0, 'validateFields returned an empty fields map');
    for (const [k, v] of entries) {
      assertType(k, 'string', 'fields key');
      assertType(v, 'string', `fields["${k}"]`);
    }
    return `${entries.length} credential key(s)`;
  });

  reporter.endCategory();
  return { fields: fields ?? [], credentials: validationData?.fields ?? {} };
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

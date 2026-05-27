import type { ProviderFieldSpec, ValidationResult } from '../../utils/types.js';
import { fetchConfig } from './config.js';

export function getFieldsSpec(): ProviderFieldSpec[] {
  return [
    {
      id:             'config_url',
      labelKey:       'catvod.field.config_url',
      kind:           'singleLine',
      required:       true,
      order:          0,
      placeholderKey: 'catvod.field.config_url.placeholder',
    },
  ];
}

export async function validateFields(
  values: Record<string, string>,
): Promise<ValidationResult> {
  try {
    const url = (values['config_url'] ?? '').trim();
    if (!url) throw new Error('Config URL is required');
    const config = await fetchConfig(url);
    if (!config.sites?.length) throw new Error('No sites found in config');
    const hash = await host.crypto.sha256({ input: url });
    return {
      success: true,
      hash,
      name:   `CatVod (${config.sites.length} sources)`,
      fields: { config_url: url },
    };
  } catch (e) {
    return { success: false, error: e instanceof Error ? e.message : String(e) };
  }
}

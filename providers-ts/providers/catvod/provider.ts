import type { ProviderFieldSpec } from '../../utils/types.js';

export function getFieldsSpec(): ProviderFieldSpec[] {
  return [
    {
      id:             'config_url',
      labelKey:       'catvod.field.config_url',
      kind:           'singleLine',
      required:       true,
      identity:       true,
      order:          0,
      placeholderKey: 'catvod.field.config_url.placeholder',
    },
    { id: 'name', labelKey: 'fld_endpoint_name', kind: 'singleLine', required: false, order: 100 },
  ];
}

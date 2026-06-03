/**
 * provider.ts — Emby stateless factory (getFieldsSpec, makeInstanceState).
 * Mirrors EmbyProvider.kt.
 */
import type { ProviderFieldSpec, PlatformInfo } from '../../utils/types.js';
import type { EmbyInstanceState } from './instance.js';

export function getFieldsSpec(): ProviderFieldSpec[] {
  return [
    { id: 'base_url',  labelKey: 'emby.field.base_url',  kind: 'singleLine', required: true,  identity: true,  order: 0 },
    { id: 'username',  labelKey: 'emby.field.username',  kind: 'singleLine', required: true,  identity: true,  order: 1 },
    { id: 'password',  labelKey: 'emby.field.password',  kind: 'password',   required: false, sensitive: true, order: 2 },
    { id: 'proxy',     labelKey: '',                     kind: 'proxySelector', order: 999999 },
    { id: 'name',      labelKey: 'fld_endpoint_name',    kind: 'singleLine', required: false, order: 100 },
  ];
}

export function makeInstanceState(
  values: Record<string, string>,
  deviceInfo: PlatformInfo,
  deviceName: string,
): EmbyInstanceState {
  return {
    rawCredentials: { ...values },
    deviceProfile: {},
    capabilities: deviceInfo,
  };
}

/**
 * provider.ts — Emby stateless factory (makeClientState).
 * Mirrors EmbyProvider.kt.
 */
import type { PlatformInfo } from '../../utils/types.js';
import type { EmbyClientState } from './client.js';
import { buildDeviceProfile } from './device-profile.js';

export function makeClientState(
  values: Record<string, string>,
  deviceInfo: PlatformInfo,
  deviceName: string,
): EmbyClientState {
  return {
    rawCredentials: { ...values },
    deviceProfile: buildDeviceProfile(deviceInfo, deviceName),
    capabilities: deviceInfo,
  };
}

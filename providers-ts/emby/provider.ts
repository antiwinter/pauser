/**
 * provider.ts — Emby stateless factory (getFieldsSpec, validateFields, bootstrap).
 * Mirrors EmbyProvider.kt.
 */
import { EmbyApi, setGlobalAuth } from './api.js';
import { normalizeBaseUrl } from './urls.js';
import { buildDeviceProfile } from './device-profile.js';
import type { ServerFieldSpec, ValidationResult, CodecCapabilities } from '../src/types.js';
import type { EmbyCredentials, EmbyInstanceState } from './instance.js';
import type { DeviceProfile } from './dto.js';

export function getFieldsSpec(): ServerFieldSpec[] {
  return [
    { id: 'base_url',  labelKey: 'emby.field.base_url',  kind: 'singleLine', required: true,  order: 0 },
    { id: 'username',  labelKey: 'emby.field.username',  kind: 'singleLine', required: true,  order: 1 },
    { id: 'password',  labelKey: 'emby.field.password',  kind: 'password',   required: false, sensitive: true, order: 2 },
  ];
}

export async function validateFields(values: Record<string, string>): Promise<ValidationResult> {
  try {
    const baseUrl  = normalizeBaseUrl(values['base_url'] ?? '');
    const username = (values['username'] ?? '').trim();
    const password = values['password'] ?? '';

    const unauthApi = new EmbyApi(baseUrl, '', '');
    const auth = await unauthApi.authenticateByName({ Username: username, Pw: password });
    const token  = auth.AccessToken;
    const userId = auth.User?.Id;
    if (!token)  throw new Error('No access token returned');
    if (!userId) throw new Error('No user id returned');

    const api = new EmbyApi(baseUrl, token, userId);
    const info = await api.getSystemInfo();

    const hashInput = `${baseUrl}${userId}`;
    const hash = await host.crypto.sha256({ input: hashInput });
    const displayName = info.ServerName ?? baseUrl;

    const fieldsJson = JSON.stringify({
      base_url:     baseUrl,
      user_id:      userId,
      access_token: token,
      server_id:    info.Id ?? null,
    });

    return { success: true, hash, displayName, fieldsJson };
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : String(e);
    return { success: false, error: msg };
  }
}

let globalDeviceProfile: DeviceProfile | null = null;
let globalCapabilities: CodecCapabilities | null = null;

export function bootstrap(caps: CodecCapabilities, deviceName: string, deviceId: string, clientVersion: string): void {
  globalCapabilities = caps;
  setGlobalAuth({ clientName: 'OpenTune', deviceName, deviceId, clientVersion });
  globalDeviceProfile = buildDeviceProfile(caps, deviceName);
}

export function makeInstanceState(values: Record<string, string>): EmbyInstanceState {
  const credentials: EmbyCredentials = {
    baseUrl:     values['base_url']     ?? '',
    userId:      values['user_id']      ?? '',
    accessToken: values['access_token'] ?? '',
    serverId:    values['server_id']    ?? null,
  };
  if (!credentials.baseUrl)     throw new Error('Missing base_url');
  if (!credentials.userId)      throw new Error('Missing user_id');
  if (!credentials.accessToken) throw new Error('Missing access_token');

  const caps = globalCapabilities ?? {
    videoMimes: ['video/avc'],
    audioMimes: ['audio/mp4a-latm'],
    subtitleFormats: ['srt'],
    maxVideoPixels: 1920 * 1080,
  };
  const profile = globalDeviceProfile ?? buildDeviceProfile(caps, 'Android');

  return { credentials, deviceProfile: profile, capabilities: caps };
}

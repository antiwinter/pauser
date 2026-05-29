/**
 * api.ts — wraps host.jar.reflect() for the Telegram shim-jar.
 * Uses asset key so JarLoader loads from app/assets/telegram-shim.jar.
 */
const ASSET_KEY = 'asset:telegram-shim.jar';
const BRIDGE_CLS = 'com.opentune.telegram.TelegramBridge';

let _loaded = false;

export async function shimReflect(method: string, args: unknown[]): Promise<string> {
  if (!_loaded) {
    await host.jar.loadAsset({ name: 'telegram-shim.jar' });
    _loaded = true;
  }
  return await host.jar.reflect({
    url: ASSET_KEY,
    cls: BRIDGE_CLS,
    method,
    args,
  });
}

export function resetLoaded(): void {
  _loaded = false;
}

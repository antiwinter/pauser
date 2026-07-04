import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';

const DEFAULT_PLATFORM_INFO = {
  deviceName: 'Insomnia Provider Test',
  deviceId: 'insomnia-provider-test',
  clientVersion: '0.0-test',
};

// Port forwarded via `adb forward tcp:7778 tcp:7920` (server default port)
const JAR_BRIDGE_PORT = 7778;
const JAR_BRIDGE_URL  = `http://localhost:${JAR_BRIDGE_PORT}/debug/jar`;

let _jarBridgeAvailable = null; // null = unchecked, true/false = result

async function probeJarBridge() {
  if (_jarBridgeAvailable !== null) return _jarBridgeAvailable;
  try {
    const r = await fetch(JAR_BRIDGE_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: 'ping', args: '{}' }),
      signal: AbortSignal.timeout(1000),
    });
    // Any response (even error) means the bridge is up
    _jarBridgeAvailable = r.status < 500 || r.status === 500;
  } catch {
    _jarBridgeAvailable = false;
  }
  return _jarBridgeAvailable;
}

export function setupAdbForward() {
  // Forward device port 7920 → localhost:7778 so the JAR bridge is reachable
  const result = spawnSync('adb', ['forward', `tcp:${JAR_BRIDGE_PORT}`, 'tcp:7920'], {
    encoding: 'utf8', stdio: 'pipe',
  });
  return result.status === 0;
}

export class HostApis {
  constructor({ platformInfo = DEFAULT_PLATFORM_INFO } = {}) {
    this.platformInfo = platformInfo;
    this._jarBridgeReady = null; // lazy probe per instance
  }

  async dispatch(namespace, name, argsJson) {
    const args = argsJson ? JSON.parse(argsJson) : null;
    switch (namespace) {
      case 'http':
        return JSON.stringify(await this.handleHttp(name, args));
      case 'crypto':
        return JSON.stringify(this.handleCrypto(name, args));
      case 'platform':
        return JSON.stringify(this.handlePlatform(name));
      case 'jar':
        return await this.handleJar(name, argsJson ?? '{}');
      default:
        throw new Error(`Unknown host namespace: ${namespace}`);
    }
  }

  async handleHttp(name, args) {
    if (name !== 'get' && name !== 'post') throw new Error(`Unknown http method: ${name}`);
    if (!args?.url) throw new Error(`http.${name}: missing url`);

    const headers = { ...(args.headers ?? {}) };
    const init = { method: name.toUpperCase(), headers };
    if (name === 'post') {
      if (args.contentType && !hasHeader(headers, 'content-type')) {
        headers['Content-Type'] = args.contentType;
      }
      init.body = args.body ?? '';
    }

    const response = await fetch(args.url, init);
    return {
      status: response.status,
      body: await response.text(),
      headers: Object.fromEntries(response.headers.entries()),
    };
  }

  // Tries the real Android JAR bridge first; falls back to stub if unavailable.
  async handleJar(name, argsJson) {
    if (this._jarBridgeReady === null) {
      this._jarBridgeReady = await probeJarBridge();
    }
    if (this._jarBridgeReady) {
      try {
        const r = await fetch(JAR_BRIDGE_URL, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ name, args: argsJson }),
          signal: AbortSignal.timeout(30_000),
        });
        const body = await r.json();
        if (body.error) throw new Error(`jar.${name}: ${body.error}`);
        return body.result !== undefined ? body.result : null;
      } catch (e) {
        // Bridge went away mid-session — fall through to stub
        _jarBridgeAvailable = false;
        this._jarBridgeReady = false;
      }
    }
    return JSON.stringify(this.handleJarStub(name, JSON.parse(argsJson)));
  }

  handleJarStub(name, args) {
    switch (name) {
      case 'load':   return true;
      case 'clear':  return true;
      case 'clearInstances': return true;
      case 'reflect': {
        const method = args?.method;
        if (method === 'newInstance') return 'stub';
        if (method === 'init')           return null;
        if (method === 'homeContent')    return JSON.stringify({ class: [] });
        if (method === 'categoryContent') return JSON.stringify({ list: [], total: 0 });
        if (method === 'detailContent')  return JSON.stringify({ list: [] });
        if (method === 'playerContent')  return JSON.stringify({ url: null });
        return null;
      }
      default:
        throw new Error(`host.jar.${name} is not available in the test harness`);
    }
  }

  handleCrypto(name, args) {
    if (name !== 'sha256') throw new Error(`Unknown crypto method: ${name}`);
    return createHash('sha256').update(String(args?.input ?? ''), 'utf8').digest('hex');
  }

  handlePlatform(name) {
    if (name !== 'getPlatformInfo') throw new Error(`Unknown platform method: ${name}`);
    return this.platformInfo;
  }
}

function hasHeader(headers, needle) {
  return Object.keys(headers).some((key) => key.toLowerCase() === needle);
}


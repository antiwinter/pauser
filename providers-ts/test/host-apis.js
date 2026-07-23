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
      case 'fs':
        return JSON.stringify(await this.handleFs(name, args));
      case 'jar':
        return await this.handleJar(name, argsJson ?? '{}');
      // Stubs for namespaces the bundle references but the harness doesn't model.
      // Real implementations live on the Android side; tests just no-op them.
      case 'timer':
        if (name === 'sleep') await new Promise((r) => setTimeout(r, args?.ms ?? 0));
        return null;
      case 'dns':
        return JSON.stringify({ ok: true });
      case 'relay':
        return JSON.stringify({ token: args?.token ?? '', baseUrl: `http://localhost/${args?.token ?? ''}` });
      case 'web':
        throw new Error(`host.web.${name} is not available in the test harness`);
      case 'notification':
        return JSON.stringify({ ok: true });
      default:
        throw new Error(`Unknown host namespace: ${namespace}`);
    }
  }

  // host.fs is a no-op stub in the test harness — providers exercise cache writes through the
  // real Android sandbox. Returning the absolute path keeps the contract symmetric with the
  // Kotlin implementation (which echoes `file.absolutePath` after a successful write).
  async handleFs(name, args) {
    if (name !== 'write' && name !== 'read' && name !== 'exists' && name !== 'delete') {
      throw new Error(`Unknown fs method: ${name}`);
    }
    if (!args?.path) throw new Error(`fs.${name}: missing path`);
    switch (name) {
      case 'write':  return args.path;
      case 'read':   return '';
      case 'exists': return false;
      case 'delete': return true;
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
      case 'load': {
        if (!args?.source || typeof args.source !== 'object') throw new Error('host.jar.load: missing source');
        const variants = Object.keys(args.source);
        if (variants.length !== 1) throw new Error(`host.jar.load: source must declare exactly one of url|path|buffer (got ${variants.join(',')})`);
        // Returns a JSON-encoded opaque handle, mirroring Kotlin. The dispatch layer
        // (HostApis.handleJar in Kotlin) wraps with JsonPrimitive(...).toString().
        const v = variants[0];
        if (v === 'url') return JSON.stringify(sha16(args.source.url));
        if (v === 'path') return JSON.stringify('path:' + sha16(args.source.path));
        return JSON.stringify('buf:' + sha16(args.source.buffer));
      }
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
      case 'loadClass': return true;
      case 'registerLoader': return true;
      case 'adoptParent': return true;
      default:
        throw new Error(`host.jar.${name} is not available in the test harness`);
    }
  }

  handleCrypto(name, args) {
    if (name !== 'checksum') throw new Error(`Unknown crypto method: ${name}`);
    const algo = (args?.algo ?? 'sha-256').toLowerCase().replace('-', '');
    if (algo !== 'md5' && algo !== 'sha1' && algo !== 'sha256' && algo !== 'sha512') {
      throw new Error(`host.crypto.checksum: unsupported algo '${args?.algo}'`);
    }
    const input = String(args?.input ?? '');
    const encoding = args?.encoding ?? 'utf8';
    let bytes;
    if (encoding === 'base64') bytes = Buffer.from(input, 'base64');
    else if (encoding === 'hex') bytes = Buffer.from(input, 'hex');
    else bytes = Buffer.from(input, 'utf8');
    return createHash(algo === 'md5' ? 'md5' : algo === 'sha1' ? 'sha1' : algo === 'sha512' ? 'sha512' : 'sha256').update(bytes).digest('hex');
  }

  handlePlatform(name) {
    if (name !== 'getPlatformInfo') throw new Error(`Unknown platform method: ${name}`);
    return this.platformInfo;
  }
}

function hasHeader(headers, needle) {
  return Object.keys(headers).some((key) => key.toLowerCase() === needle);
}

function sha16(s) {
  return createHash('sha256').update(String(s), 'utf8').digest('hex').slice(0, 16);
}


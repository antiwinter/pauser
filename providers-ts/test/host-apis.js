import { createHash } from 'node:crypto';

const DEFAULT_PLATFORM_INFO = {
  deviceName: 'OpenTune Provider Test',
  deviceId: 'opentune-provider-test',
  clientVersion: '0.0-test',
};

export class HostApis {
  constructor({ platformInfo = DEFAULT_PLATFORM_INFO } = {}) {
    this.platformInfo = platformInfo;
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

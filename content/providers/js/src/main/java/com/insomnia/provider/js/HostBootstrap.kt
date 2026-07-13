package com.insomnia.provider.js

/**
 * QuickJS bootstrap that runs before the provider bundle. Wires `globalThis.host.*`
 * proxies and `globalThis.console.*` so the bundle can dispatch into the host
 * without each provider rolling its own.
 *
 * Stays alive across engines (one QuickJsEngine per endpoint client). The bundle's
 * `export default {...}` is then assigned to `globalThis.insomniaProvider` by the
 * rollup iife wrapper.
 */
internal object HostBootstrap {
    const val JS = """
(function() {
  function ns(name) {
    return new Proxy({}, {
      get: function(_, prop) {
        return function(args) {
          return globalThis.__hostDispatch(name, prop, JSON.stringify(args === undefined ? null : args));
        };
      }
    });
  }
  globalThis.host = {
    http:   ns('http'),
    crypto: ns('crypto'),
    jar:    ns('jar'),
    fs:     ns('fs'),
    log:    ns('log'),
    timer:  ns('timer'),
    dns:    ns('dns'),
    relay:  ns('relay'),
  };
  globalThis.console = {
    log: function() {
      host.log.d({ msg: Array.prototype.join.call(arguments, ' ') });
    },
    warn: function() {
      host.log.w({ msg: Array.prototype.join.call(arguments, ' ') });
    },
    error: function() {
      host.log.e({ msg: Array.prototype.join.call(arguments, ' ') });
    },
  };
  Object.defineProperty(globalThis.host, 'proxyConfig', {
    get: function() { return globalThis.__proxyConfig || null; },
    enumerable: true,
  });
})();
"""
}
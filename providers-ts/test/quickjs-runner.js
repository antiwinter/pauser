import quickJsVariant from '@jitl/quickjs-ng-wasmfile-release-asyncify';
import {
  newQuickJSAsyncWASMModuleFromVariant,
  shouldInterruptAfterDeadline,
} from 'quickjs-emscripten-core';

const HOST_BOOTSTRAP_JS = `
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
    http:     ns('http'),
    crypto:   ns('crypto'),
    platform: ns('platform'),
  };
})();
`;

const HOST_DISPATCH_JS = `
globalThis.__hostDispatch = async function(ns, name, argsJson) {
  var resultJson = await globalThis.__hostDispatchRaw(ns, name, argsJson);
  return resultJson == null ? null : JSON.parse(resultJson);
};
`;

let quickJsModulePromise;

export class QuickJsProviderRunner {
  constructor({ bundle, hostApis, filename = '<bundle>', timeoutMs = 60_000 }) {
    this.bundle = bundle;
    this.hostApis = hostApis;
    this.filename = filename;
    this.timeoutMs = timeoutMs;
    this.vm = null;
  }

  async init() {
    const QuickJS = await getQuickJsModule();
    this.vm = QuickJS.newContext();
    this.vm.runtime.setMemoryLimit(32 * 1024 * 1024);
    this.vm.runtime.setMaxStackSize(512 * 1024);
    this.installHostDispatch();
    await this.evalImmediate(HOST_DISPATCH_JS, '<host-dispatch>');
    await this.evalImmediate(HOST_BOOTSTRAP_JS, '<host-bootstrap>');
    await this.evalImmediate(this.bundle, this.filename, { strict: true });
  }

  dispose() {
    this.vm?.dispose();
    this.vm = null;
  }

  async getProvidesCover() {
    return this.evalImmediate('globalThis.opentuneProvider.providesCover', '<providesCover>');
  }

  async hasMethod(name) {
    return this.evalImmediate(
      `typeof globalThis.opentuneProvider[${JSON.stringify(name)}] === "function"`,
      `<has:${name}>`,
    );
  }

  async callMethod(method, args = {}) {
    const argsJson = JSON.stringify(args);
    const code = `
(async function() {
  var provider = globalThis.opentuneProvider;
  if (provider == null) throw new Error('opentuneProvider not on globalThis');
  var fn = provider[${JSON.stringify(method)}];
  if (typeof fn !== 'function') throw new Error('method not found: ${method}');
  var value = await fn(${argsJson});
  return value == null || value === undefined
    ? null
    : typeof value === 'string'
      ? value
      : JSON.stringify(value);
})()
`;
    return this.evalPromiseToNative(code, `<call:${method}>`);
  }

  installHostDispatch() {
    const rawDispatch = this.vm.newAsyncifiedFunction('__hostDispatchRaw', async (nsHandle, nameHandle, argsHandle) => {
      const namespace = this.vm.getString(nsHandle);
      const name = this.vm.getString(nameHandle);
      const argsJson = this.vm.getString(argsHandle);
      const resultJson = await this.hostApis.dispatch(namespace, name, argsJson);
      return resultJson == null ? this.vm.null : this.vm.newString(resultJson);
    });
    this.vm.setProp(this.vm.global, '__hostDispatchRaw', rawDispatch);
    rawDispatch.dispose();
  }

  async evalImmediate(code, filename, options = {}) {
    this.vm.runtime.setInterruptHandler(shouldInterruptAfterDeadline(Date.now() + this.timeoutMs));
    let result;
    try {
      result = await this.vm.evalCodeAsync(code, filename, {
        type: 'global',
        strict: false,
        ...options,
      });
    } finally {
      this.vm.runtime.removeInterruptHandler();
    }
    const handle = this.vm.unwrapResult(result);
    try {
      return this.vm.dump(handle);
    } finally {
      handle.dispose();
    }
  }

  async evalPromiseToNative(code, filename) {
    this.vm.runtime.setInterruptHandler(shouldInterruptAfterDeadline(Date.now() + this.timeoutMs));
    let result;
    try {
      result = await this.vm.evalCodeAsync(code, filename, { type: 'global' });
    } finally {
      this.vm.runtime.removeInterruptHandler();
    }
    const promiseHandle = this.vm.unwrapResult(result);
    try {
      const resolvedResult = await this.settleQuickJsPromise(this.vm.resolvePromise(promiseHandle));
      const valueHandle = this.vm.unwrapResult(resolvedResult);
      try {
        return this.vm.dump(valueHandle);
      } finally {
        valueHandle.dispose();
      }
    } finally {
      promiseHandle.dispose();
    }
  }

  async settleQuickJsPromise(promise) {
    const started = Date.now();
    let settled = false;
    let value;
    let error;
    promise.then(
      (result) => {
        settled = true;
        value = result;
      },
      (reason) => {
        settled = true;
        error = reason;
      },
    );

    while (!settled) {
      const jobs = this.vm.runtime.executePendingJobs(64);
      this.vm.unwrapResult(jobs);
      if (Date.now() - started > this.timeoutMs) {
        throw new Error(`QuickJS promise timed out after ${this.timeoutMs}ms`);
      }
      await new Promise((resolve) => setTimeout(resolve, 0));
    }

    if (error) throw error;
    return value;
  }
}

function getQuickJsModule() {
  quickJsModulePromise ??= newQuickJSAsyncWASMModuleFromVariant(quickJsVariant);
  return quickJsModulePromise;
}

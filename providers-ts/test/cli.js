#!/usr/bin/env node
import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { readFile, readdir } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Command } from 'commander';
import { HostApis } from './host-apis.js';
import { QuickJsProviderRunner } from './quickjs-runner.js';
import {
  missingRequiredFields,
  promptForMissingFields,
  readEnvFile,
  writeEnvFile,
} from './env.js';
import { Reporter, SkipError } from './reporter.js';
import {
  checkBridge,
  loadFieldsSpec,
  runInstanceSmoke,
  validateProvider,
} from './contract.js';

const testDir = dirname(fileURLToPath(import.meta.url));
const rootDir = dirname(testDir);
const providersDir = join(rootDir, 'providers');
const distDir = join(rootDir, 'dist');

const program = new Command()
  .name('provider-test')
  .description('Run standardized OpenTune TypeScript provider checks in QuickJS.')
  .argument('[provider]', 'provider directory name under providers-ts/providers')
  .option('--no-prompt', 'do not prompt for missing credentials')
  .option('--fresh-auth', 'prompt again for provider field values before validation')
  .option('--hooks', 'call playback hooks that may report state to the provider service')
  .option('--json', 'print machine-readable JSON output')
  .option('--list', 'list implemented providers')
  .option('--no-color', 'disable colored terminal output')
  .parse();

const options = program.opts();
const provider = program.args[0];
const reporter = new Reporter({ json: options.json, color: options.color });

try {
  if (options.list) {
    const providers = await listProviders();
    if (options.json) {
      console.log(JSON.stringify({ providers }, null, 2));
    } else {
      console.log(providers.join('\n'));
    }
    process.exit(0);
  }

  if (!provider) {
    program.error('missing provider argument; use `npm run test -- <provider>`');
  }

  await run(provider, options, reporter);
  const summary = reporter.finish();
  process.exit(summary.fail > 0 ? 1 : 0);
} catch (error) {
  if (options.json) {
    console.log(JSON.stringify({ error: error.message ?? String(error) }, null, 2));
  } else {
    console.error(error?.stack ?? error);
  }
  process.exit(1);
}

async function run(providerName, opts, out) {
  const providerDir = join(providersDir, providerName);
  const entryPath = join(providerDir, 'index.ts');
  const envPath = join(providerDir, '.env');
  const bundlePath = join(distDir, `${providerName}.js`);

  assertProviderName(providerName);
  if (!existsSync(entryPath)) {
    throw new Error(`Provider "${providerName}" not found at ${entryPath}`);
  }

  out.setMeta({
    provider: providerName,
    bundlePath,
    envPath,
    quickjs: '@jitl/quickjs-ng-wasmfile-release-asyncify',
  });

  out.heading(`Provider: ${providerName}`);
  out.line(`Bundle: ${bundlePath}`);
  out.line(`Env: ${envPath}`);
  out.line('QuickJS: @jitl/quickjs-ng-wasmfile-release-asyncify');
  out.line();

  await out.step('build provider bundles', async () => {
    const result = spawnSync('npm', ['run', 'build'], {
      cwd: rootDir,
      encoding: 'utf8',
      stdio: opts.json ? 'pipe' : 'inherit',
    });
    if (result.status !== 0) {
      throw new Error(result.stderr || result.stdout || `npm run build failed with status ${result.status}`);
    }
  });

  const bundle = await readFile(bundlePath, 'utf8');
  const hostApis = new HostApis();
  const providerRunner = new QuickJsProviderRunner({
    bundle,
    hostApis,
    filename: bundlePath,
  });

  let fields;
  let envValues;
  let validation;
  try {
    await providerRunner.init();
    await checkBridge(out, providerRunner);
    fields = await loadFieldsSpec(out, providerRunner);
    envValues = await loadCredentials(out, fields, envPath, opts);

    if (!envValues) {
      out.finish();
      process.exit(0);
    }

    validation = await validateProvider(out, providerRunner, envValues);
    await writeEnvFile(envPath, { ...envValues, ...validation.fields });
  } finally {
    providerRunner.dispose();
  }

  const instanceRunner = new QuickJsProviderRunner({
    bundle,
    hostApis,
    filename: bundlePath,
  });
  try {
    await instanceRunner.init();
    await runInstanceSmoke(out, instanceRunner, validation.fields, opts);
  } finally {
    instanceRunner.dispose();
  }
}

async function loadCredentials(out, fields, envPath, opts) {
  const values = await out.step('load provider credentials', async () => {
    let values = opts.freshAuth ? {} : await readEnvFile(envPath);
    if (opts.freshAuth && opts.prompt) {
      values = await promptForMissingFields(fields, values);
    }

    const missing = missingRequiredFields(fields, values);
    if (missing.length > 0) {
      if (!opts.prompt) {
        throw new SkipError(`missing required env values: ${missing.join(', ')}`);
      }
      values = await promptForMissingFields(fields, values);
    }

    await writeEnvFile(envPath, values);
    return values;
  });

  if (values == null) return null;
  return values;
}

async function listProviders() {
  const entries = await readdir(providersDir, { withFileTypes: true });
  const providers = [];
  for (const entry of entries) {
    if (!entry.isDirectory()) continue;
    if (existsSync(join(providersDir, entry.name, 'index.ts'))) providers.push(entry.name);
  }
  return providers.sort();
}

function assertProviderName(providerName) {
  if (!/^[a-z0-9_-]+$/i.test(providerName)) {
    throw new Error(`Invalid provider name: ${providerName}`);
  }
}

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
import { Reporter, NAError } from './reporter.js';
import { ffprobeAvailable } from './validators.js';
import { runBridgeChecks } from './categories/bridge.js';
import { runConfigChecks } from './categories/config.js';
import { runCatalogChecks } from './categories/catalog.js';
import { runDetailChecks } from './categories/detail.js';
import { runPlaybackChecks } from './categories/playback.js';

const testDir = dirname(fileURLToPath(import.meta.url));
const rootDir = dirname(testDir);
const providersDir = join(rootDir, 'providers');
const distDir = join(rootDir, 'dist');
const quickJsRuntimeName = '@jitl/quickjs-ng-wasmfile-release-sync';

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
  .option('--no-ffprobe', 'disable ffprobe media validation checks')
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

  // Detect ffprobe once — used by catalog, detail and playback checks
  const ffprobe = opts.ffprobe !== false && ffprobeAvailable() != null;

  out.setMeta({
    provider: providerName,
    bundlePath,
    envPath,
    quickjs: quickJsRuntimeName,
    ffprobe,
  });

  out.heading(`Provider: ${providerName}`);
  out.line(`Bundle:   ${bundlePath}`);
  out.line(`Env:      ${envPath}`);
  out.line(`QuickJS:  ${quickJsRuntimeName}`);
  out.line(`ffprobe:  ${ffprobe ? 'enabled' : 'disabled'}`);
  out.line();

  // ── Build ──────────────────────────────────────────────────────────────────

  await out.step('build provider bundles', async () => {
    const command = packageManagerCommand();
    const result = spawnSync(command.bin, command.args, {
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

  // ── Phase 1: bridge + config (stateless runner) ────────────────────────────

  const configRunner = new QuickJsProviderRunner({ bundle, hostApis, filename: bundlePath });
  let credentials;
  let providesCover = false;

  try {
    await configRunner.init();

    const bridgeResult = await runBridgeChecks(out, configRunner);
    providesCover = bridgeResult.providesCover;

    const envValues = await loadCredentials(out, configRunner, envPath, opts);
    if (!envValues) {
      out.finish();
      process.exit(0);
    }

    const configResult = await runConfigChecks(out, configRunner, envValues);
    credentials = configResult.credentials;
    await writeEnvFile(envPath, { ...envValues, ...credentials });
  } finally {
    configRunner.dispose();
  }

  // ── Phase 2: instance checks (initialized runner) ─────────────────────────

  const instanceRunner = new QuickJsProviderRunner({ bundle, hostApis, filename: bundlePath });
  try {
    await instanceRunner.init();
    await instanceRunner.callMethod('init', {
      credentials,
      capabilities: defaultCapabilities(),
    });

    const catalogContext = await runCatalogChecks(out, instanceRunner, { providesCover, ffprobe });

    await runDetailChecks(out, instanceRunner, {
      firstItem: catalogContext.firstItem,
      ffprobe,
    });

    await runPlaybackChecks(out, instanceRunner, {
      playableItem: catalogContext.playableItem,
      hooks: opts.hooks ?? false,
      ffprobe,
    });
  } finally {
    instanceRunner.dispose();
  }
}

async function loadCredentials(out, runner, envPath, opts) {
  // Read fields directly (infrastructure step, not a test assertion) so we
  // know which fields are required before running the formal Config category.
  const rawFieldsJson = await runner.callMethod('getFieldsSpec', {});
  const rawFields = typeof rawFieldsJson === 'string' ? JSON.parse(rawFieldsJson) : rawFieldsJson;

  const values = await out.step('load provider credentials', async () => {
    let vals = opts.freshAuth ? {} : await readEnvFile(envPath);
    if (opts.freshAuth && opts.prompt) {
      vals = await promptForMissingFields(rawFields, vals);
    }

    const missing = missingRequiredFields(rawFields, vals);
    if (missing.length > 0) {
      if (!opts.prompt) {
        throw new NAError(`missing required env values: ${missing.join(', ')}`);
      }
      vals = await promptForMissingFields(rawFields, vals);
    }

    await writeEnvFile(envPath, vals);
    return vals;
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

function defaultCapabilities() {
  return {
    videoMime: ['video/mp4', 'video/webm', 'video/x-matroska'],
    audioMime: ['audio/mp4', 'audio/mpeg', 'audio/opus'],
    subtitleFormats: ['srt', 'vtt', 'ass'],
    maxPixels: 3840 * 2160,
  };
}

function packageManagerCommand() {
  const userAgent = process.env.npm_config_user_agent ?? '';
  if (userAgent.startsWith('yarn/')) return { bin: 'yarn', args: ['run', 'build'] };
  if (userAgent.startsWith('pnpm/')) return { bin: 'pnpm', args: ['run', 'build'] };
  return { bin: 'npm', args: ['run', 'build'] };
}

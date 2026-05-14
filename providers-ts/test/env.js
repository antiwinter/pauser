import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { createInterface } from 'node:readline/promises';
import { stdin as input, stdout as output } from 'node:process';
import dotenv from 'dotenv';

export async function readEnvFile(path) {
  try {
    return dotenv.parse(await readFile(path, 'utf8'));
  } catch (error) {
    if (error?.code === 'ENOENT') return {};
    throw error;
  }
}

export async function writeEnvFile(path, values) {
  await mkdir(dirname(path), { recursive: true });
  const body = Object.entries(values)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([key, value]) => `${key}=${quoteEnvValue(value)}`)
    .join('\n');
  await writeFile(path, `${body}\n`, { mode: 0o600 });
}

export async function promptForMissingFields(fields, values) {
  const next = { ...values };
  const missing = fields.filter((field) => next[field.id] == null);
  if (missing.length === 0) return next;

  const rl = createInterface({ input, output });
  try {
    for (const field of missing) {
      const label = field.labelKey ?? field.id;
      const suffix = field.required === false ? ' (optional)' : '';
      const secret = field.kind === 'password' || field.sensitive === true || isSensitiveKey(field.id);
      const answer = await question(rl, `${label}${suffix}: `, { secret });
      next[field.id] = answer;
    }
  } finally {
    rl.close();
  }
  return next;
}

export function missingRequiredFields(fields, values) {
  return fields
    .filter((field) => field.required !== false)
    .filter((field) => !values[field.id])
    .map((field) => field.id);
}

export function maskValues(values) {
  return Object.fromEntries(
    Object.entries(values).map(([key, value]) => [key, isSensitiveKey(key) ? '<redacted>' : value]),
  );
}

function quoteEnvValue(value) {
  const text = String(value ?? '');
  if (/^[A-Za-z0-9_./:@-]*$/.test(text)) return text;
  return JSON.stringify(text);
}

async function question(rl, prompt, { secret }) {
  if (!secret) return rl.question(prompt);

  const originalWrite = rl._writeToOutput;
  rl._writeToOutput = function writeMasked(text) {
    if (text === prompt) {
      originalWrite.call(this, text);
    } else {
      originalWrite.call(this, '*'.repeat(text.length));
    }
  };
  try {
    return await rl.question(prompt);
  } finally {
    rl._writeToOutput = originalWrite;
    output.write('\n');
  }
}

function isSensitiveKey(key) {
  return /password|pass|secret|token|api[_-]?key/i.test(key);
}

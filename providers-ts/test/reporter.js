import { Chalk } from 'chalk';

export class SkipError extends Error {
  constructor(message) {
    super(message);
    this.name = 'SkipError';
  }
}

export class Reporter {
  constructor({ json = false, color = process.stdout.isTTY && !process.env.NO_COLOR } = {}) {
    this.json = json;
    this.chalk = new Chalk({ level: color ? undefined : 0 });
    this.results = [];
    this.meta = {};
  }

  setMeta(meta) {
    this.meta = { ...this.meta, ...meta };
  }

  line(message = '') {
    if (!this.json) console.log(message);
  }

  heading(message) {
    if (!this.json) console.log(this.chalk.cyan(message));
  }

  async step(name, fn) {
    const started = performance.now();
    try {
      const detail = await fn();
      this.record({ name, status: 'pass', durationMs: performance.now() - started, detail });
      return detail;
    } catch (error) {
      const status = error instanceof SkipError ? 'skip' : 'fail';
      this.record({ name, status, durationMs: performance.now() - started, error });
      if (status === 'fail') throw error;
      return undefined;
    }
  }

  record(result) {
    this.results.push({
      name: result.name,
      status: result.status,
      durationMs: Math.round(result.durationMs),
      detail: result.detail,
      error: result.error ? formatError(result.error) : undefined,
    });

    if (this.json) return;

    const statusText = result.status === 'pass'
      ? this.chalk.green('PASS')
      : result.status === 'skip'
        ? this.chalk.yellow('SKIP')
        : this.chalk.red('FAIL');
    const duration = this.chalk.dim(`${Math.round(result.durationMs)}ms`);
    console.log(`${statusText} ${result.name} ${duration}`);
    if (typeof result.detail === 'string' || typeof result.detail === 'number') {
      console.log(`     ${this.chalk.dim(String(result.detail))}`);
    }
    if (result.error) {
      const colorize = result.status === 'skip' ? this.chalk.yellow : this.chalk.red;
      console.log(`     ${colorize(formatError(result.error))}`);
    }
  }

  finish() {
    const summary = this.summary();
    if (this.json) {
      console.log(JSON.stringify({ ...summary, meta: this.meta, results: this.results }, null, 2));
      return summary;
    }

    this.line();
    this.heading('Summary');
    this.line(
      `${this.chalk.green(`${summary.pass} passed`)}, ` +
      `${this.chalk.yellow(`${summary.skip} skipped`)}, ` +
      `${summary.fail > 0 ? this.chalk.red(`${summary.fail} failed`) : this.chalk.dim(`${summary.fail} failed`)}`,
    );
    return summary;
  }

  summary() {
    return this.results.reduce((acc, result) => {
      acc[result.status] += 1;
      return acc;
    }, { pass: 0, skip: 0, fail: 0 });
  }

}

function formatError(error) {
  const message = error?.message ?? String(error);
  return message.replace(/\b(password|access_token|token)=([^&\s]+)/gi, '$1=<redacted>');
}

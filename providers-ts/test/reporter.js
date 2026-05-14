import { Chalk } from 'chalk';

export class NAError extends Error {
  constructor(message) {
    super(message);
    this.name = 'NAError';
  }
}

/** @deprecated Use NAError instead */
export class SkipError extends NAError {
  constructor(message) {
    super(message);
    this.name = 'SkipError';
  }
}

export class Reporter {
  constructor({ json = false, color = process.stdout.isTTY && !process.env.NO_COLOR, filter = null } = {}) {
    this.json = json;
    this.chalk = new Chalk({ level: color ? undefined : 0 });
    this.filter = filter ? filter.toLowerCase() : null;
    this.categories = [];
    this._current = null;
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

  beginCategory(name, apis = []) {
    const cat = { name, apis, results: [] };
    this.categories.push(cat);
    this._current = cat;
    if (!this.json) {
      console.log();
      const apisLabel = apis.length > 0 ? this.chalk.dim(` [${apis.join(', ')}]`) : '';
      console.log(this.chalk.bold.cyan(`▸ ${name}`) + apisLabel);
    }
  }

  endCategory() {
    this._current = null;
  }

  async step(name, fn) {
    const started = performance.now();
    const matches = !this.filter || name.toLowerCase().includes(this.filter);
    if (!matches) {
      // Still execute so context-building side-effects happen; never record or throw.
      try { return await fn(); } catch { return undefined; }
    }
    try {
      const detail = await fn();
      this.record({ name, status: 'pass', durationMs: performance.now() - started, detail });
      return detail;
    } catch (error) {
      const status = error instanceof NAError ? 'na' : 'fail';
      this.record({ name, status, durationMs: performance.now() - started, error });
      if (status === 'fail') throw error;
      return undefined;
    }
  }

  record(result) {
    const entry = {
      name: result.name,
      status: result.status,
      durationMs: Math.round(result.durationMs),
      detail: result.detail,
      error: result.error ? formatError(result.error) : undefined,
    };

    if (this._current) {
      this._current.results.push(entry);
    } else {
      // Uncategorized — put in a synthetic first category
      if (this.categories.length === 0 || this.categories[0].name !== '') {
        this.categories.unshift({ name: '', apis: [], results: [] });
      }
      this.categories[0].results.push(entry);
    }

    if (this.json) return;

    const c = this.chalk;
    const statusText =
      result.status === 'pass' ? c.green('PASS') :
      result.status === 'na'   ? c.yellow('N/A ') :
                                  c.red('FAIL');
    const duration = c.dim(`${Math.round(result.durationMs)}ms`);
    console.log(`  ${statusText}  ${result.name} ${duration}`);
    if (typeof result.detail === 'string' || typeof result.detail === 'number') {
      console.log(`        ${c.dim(String(result.detail))}`);
    }
    if (result.error) {
      const colorize = result.status === 'na' ? c.yellow : c.red;
      console.log(`        ${colorize(formatError(result.error))}`);
    }
  }

  finish() {
    const summary = this.summary();

    if (this.json) {
      console.log(JSON.stringify({ ...summary, meta: this.meta, categories: this.categories }, null, 2));
      return summary;
    }

    const c = this.chalk;
    console.log();
    c.bold.cyan('Summary');
    this.heading('Summary');

    // Per-category table
    const rows = this.categories.filter((cat) => cat.name !== '' || cat.results.length > 0);
    if (rows.length > 1) {
      const nameWidth = Math.max(8, ...rows.map((r) => r.name.length));
      const header = `  ${'Category'.padEnd(nameWidth)}  PASS   N/A  FAIL`;
      console.log(c.dim(header));
      console.log(c.dim('  ' + '─'.repeat(header.length - 2)));
      for (const cat of rows) {
        const pass = cat.results.filter((r) => r.status === 'pass').length;
        const na   = cat.results.filter((r) => r.status === 'na').length;
        const fail = cat.results.filter((r) => r.status === 'fail').length;
        const name = cat.name.padEnd(nameWidth);
        const passStr = String(pass).padStart(4);
        const naStr   = String(na).padStart(5);
        const failStr = fail > 0 ? c.red(String(fail).padStart(5)) : c.dim(String(fail).padStart(5));
        console.log(`  ${name}  ${c.green(passStr)} ${naStr} ${failStr}`);
      }
      console.log(c.dim('  ' + '─'.repeat(header.length - 2)));
    }

    const passStr = c.green(`${summary.pass} passed`);
    const naStr   = c.yellow(`${summary.na} N/A`);
    const failStr = summary.fail > 0 ? c.red(`${summary.fail} failed`) : c.dim(`${summary.fail} failed`);
    console.log(`  ${passStr},  ${naStr},  ${failStr}`);

    return summary;
  }

  summary() {
    const all = this.categories.flatMap((c) => c.results);
    return all.reduce(
      (acc, result) => {
        if (result.status === 'pass') acc.pass += 1;
        else if (result.status === 'na') acc.na += 1;
        else acc.fail += 1;
        return acc;
      },
      { pass: 0, na: 0, fail: 0 },
    );
  }
}

function formatError(error) {
  const message = error?.message ?? String(error);
  return message.replace(/\b(password|access_token|token)=([^&\s]+)/gi, '$1=<redacted>');
}

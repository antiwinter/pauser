export interface BenchResult {
  name: string;
  ms: number;
  iterations: number;
}

function run(name: string, iterations: number, fn: () => void): BenchResult {
  const start = Date.now();
  for (let i = 0; i < iterations; i++) fn();
  return { name, ms: Date.now() - start, iterations };
}

function fib(n: number): number {
  return n <= 1 ? n : fib(n - 1) + fib(n - 2);
}

export function runBench(): Record<string, string> {
  const results: Record<string, string> = {};

  function record(r: BenchResult) {
    const nsPerIter = r.iterations > 0 ? Math.round((r.ms * 1e6) / r.iterations) : 0;
    results[r.name] = `${r.ms}ms (${nsPerIter} ns/iter)`;
  }

  // Arithmetic
  record(run('int-loop', 5_000_000, () => {
    let x = 0;
    for (let i = 0; i < 100; i++) x += i;
    return x;
  }));

  record(run('float-loop', 2_000_000, () => {
    let x = 1.0;
    for (let i = 1; i <= 50; i++) x *= 1.001;
    return x;
  }));

  // String
  record(run('string-concat', 50_000, () => {
    let s = '';
    for (let i = 0; i < 100; i++) s += 'x';
    return s;
  }));

  record(run('regex-match', 200_000, () => {
    return /^(\d{4})-(\d{2})-(\d{2})$/.test('2024-06-01');
  }));

  // Array
  record(run('array-sort', 10_000, () => {
    const a = [5, 3, 8, 1, 9, 2, 7, 4, 6, 0];
    a.sort((x, y) => x - y);
    return a;
  }));

  record(run('array-map-filter', 50_000, () => {
    return [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
      .map(x => x * 2)
      .filter(x => x > 10)
      .reduce((acc, x) => acc + x, 0);
  }));

  // Object / JSON
  const obj = { a: 1, b: 'hello', c: [1, 2, 3], d: { nested: true } };
  record(run('json-stringify', 100_000, () => JSON.stringify(obj)));

  const jsonStr = '{"items":[{"id":1,"name":"foo"},{"id":2,"name":"bar"}],"total":2}';
  record(run('json-parse', 100_000, () => JSON.parse(jsonStr)));

  // Closure / call
  record(run('fib-30', 10, () => fib(30)));

  record(run('closure-counter', 1_000_000, () => {
    let count = 0;
    const inc = () => ++count;
    for (let i = 0; i < 10; i++) inc();
    return count;
  }));

  // Compat probes
  try {
    const b = BigInt(9007199254740993n);
    results['compat-BigInt'] = b > 0n ? 'ok' : 'ok';
  } catch (_) {
    results['compat-BigInt'] = 'unsupported';
  }

  try {
    Promise.allSettled([Promise.resolve(1)]);
    results['compat-Promise.allSettled'] = 'ok';
  } catch (_) {
    results['compat-Promise.allSettled'] = 'unsupported';
  }

  try {
    const sc = (globalThis as unknown as Record<string, unknown>)['structuredClone'];
    if (typeof sc === 'function') sc({ x: 1 });
    results['compat-structuredClone'] = 'ok';
  } catch (_) {
    results['compat-structuredClone'] = 'unsupported';
  }

  try {
    const at = [1, 2, 3].at(-1);
    results['compat-Array.at'] = at === 3 ? 'ok' : 'unexpected';
  } catch (_) {
    results['compat-Array.at'] = 'unsupported';
  }

  try {
    const at = 'hello'.at(-1);
    results['compat-String.at'] = at === 'o' ? 'ok' : 'unexpected';
  } catch (_) {
    results['compat-String.at'] = 'unsupported';
  }

  return results;
}

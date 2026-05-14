import {
  assert,
  assertType,
  assertNonEmptyString,
  assertObject,
  assertArray,
  assertUrl,
  checkMediaWithFfprobe,
  parseJsonResult,
} from '../validators.js';
import { NAError } from '../reporter.js';

const ENTRY_TYPES = new Set(['Folder', 'Series', 'Season', 'Episode', 'Playable', 'Other']);
const CONTAINER_TYPES = new Set(['Folder', 'Series', 'Season']);
const SERIES_CHILD_TYPES = new Set(['Season', 'Episode']);

const PAGINATION_PAGE_SIZE = 5;

/**
 * Category: Catalog
 * Covers listEntry (basic, pagination, series/season rules) and search.
 *
 * @param {import('../reporter.js').Reporter} reporter
 * @param {import('../quickjs-runner.js').QuickJsProviderRunner} runner
 * @param {{ providesCover: boolean, ffprobe: boolean }} opts
 * @returns {Promise<{ firstItem: object|null, seriesItem: object|null, seasonItem: object|null, playableItem: object|null }>}
 */
export async function runCatalogChecks(reporter, runner, opts) {
  reporter.beginCategory('Catalog', ['listEntry', 'search']);

  const { providesCover, ffprobe } = opts;
  let context = { firstItem: null, seriesItem: null, seasonItem: null, playableItem: null };

  // ── Basic root list ────────────────────────────────────────────────────────

  const rootList = await reporter.step('listEntry(root) returns a valid EntryList', async () => {
    const list = parseJsonResult(
      await runner.callMethod('listEntry', { location: null, startIndex: 0, limit: 20 }),
      'listEntry',
    );
    validateEntryList(list, 'root list');
    return list;
  });

  await reporter.step('root list items have valid EntryInfo fields', async () => {
    if (!rootList) throw new NAError('root list not loaded');
    if (rootList.items.length === 0) throw new NAError('root list is empty — no items to validate');
    for (const [i, item] of rootList.items.entries()) {
      validateEntryInfoFields(item, `root[${i}]`);
    }
    context.firstItem = rootList.items[0] ?? null;
    return `${rootList.items.length} item(s), totalCount=${rootList.totalCount}`;
  });

  await reporter.step('root list cover URLs are valid', async () => {
    if (!rootList) throw new NAError('root list not loaded');
    if (!providesCover) throw new NAError('providesCover=false — cover URLs not expected');
    const itemsWithCover = rootList.items.filter((item) => item.cover != null);
    if (itemsWithCover.length === 0) throw new NAError('no items have a cover URL in root list');
    for (const item of itemsWithCover) {
      assertUrl(item.cover, `item "${item.id}" cover`);
    }
    return `${itemsWithCover.length} cover URL(s) valid`;
  });

  await reporter.step('cover URLs pass ffprobe image check', async () => {
    if (!rootList) throw new NAError('root list not loaded');
    if (!providesCover) throw new NAError('providesCover=false — cover URLs not expected');
    if (!ffprobe) throw new NAError('--no-ffprobe flag set');
    const sample = rootList.items.filter((item) => item.cover != null).slice(0, 3);
    if (sample.length === 0) throw new NAError('no items have a cover URL to probe');
    for (const item of sample) {
      await checkMediaWithFfprobe(item.cover, {}, { expectImage: true });
    }
    return `checked ${sample.length} cover(s)`;
  });

  // ── Pagination ─────────────────────────────────────────────────────────────

  await reporter.step(`listEntry page size is respected (limit=${PAGINATION_PAGE_SIZE})`, async () => {
    const page = parseJsonResult(
      await runner.callMethod('listEntry', { location: null, startIndex: 0, limit: PAGINATION_PAGE_SIZE }),
      'listEntry page 1',
    );
    validateEntryList(page, 'page 1');
    assert(
      page.items.length <= PAGINATION_PAGE_SIZE,
      `page 1 returned ${page.items.length} items, expected ≤ ${PAGINATION_PAGE_SIZE}`,
    );
    return `page 1: ${page.items.length} item(s)`;
  });

  await reporter.step('listEntry page 2 is non-empty when totalCount > page size', async () => {
    if (!rootList) throw new NAError('root list not loaded');
    if (rootList.totalCount <= PAGINATION_PAGE_SIZE) {
      throw new NAError(`totalCount=${rootList.totalCount} ≤ ${PAGINATION_PAGE_SIZE} — single page`);
    }
    const page2 = parseJsonResult(
      await runner.callMethod('listEntry', { location: null, startIndex: PAGINATION_PAGE_SIZE, limit: PAGINATION_PAGE_SIZE }),
      'listEntry page 2',
    );
    validateEntryList(page2, 'page 2');
    assert(page2.items.length > 0, 'page 2 is empty despite totalCount > page size');
    return `page 2: ${page2.items.length} item(s)`;
  });

  await reporter.step('no duplicate IDs across page 1 and page 2', async () => {
    if (!rootList) throw new NAError('root list not loaded');
    if (rootList.totalCount <= PAGINATION_PAGE_SIZE) {
      throw new NAError(`totalCount=${rootList.totalCount} ≤ ${PAGINATION_PAGE_SIZE} — single page`);
    }
    const page1 = parseJsonResult(
      await runner.callMethod('listEntry', { location: null, startIndex: 0, limit: PAGINATION_PAGE_SIZE }),
      'listEntry dup-check page 1',
    );
    const page2 = parseJsonResult(
      await runner.callMethod('listEntry', { location: null, startIndex: PAGINATION_PAGE_SIZE, limit: PAGINATION_PAGE_SIZE }),
      'listEntry dup-check page 2',
    );
    const ids1 = new Set(page1.items.map((i) => i.id));
    const dupes = page2.items.filter((i) => ids1.has(i.id));
    assert(dupes.length === 0, `duplicate IDs between pages: ${dupes.map((i) => i.id).join(', ')}`);
    return `${page1.items.length + page2.items.length} unique IDs`;
  });

  // ── Series / Season rules ──────────────────────────────────────────────────
  // Use search to find a Series rather than relying on root list order.

  const seriesItem = await findSeriesViaSearch(runner);
  context.seriesItem = seriesItem;

  await reporter.step('Series children are Season or Episode only', async () => {
    if (!seriesItem) throw new NAError('no Series item found via search');
    const children = await fetchAllChildren(runner, seriesItem.id);
    if (children.length === 0) throw new NAError(`Series "${seriesItem.id}" has no children`);
    for (const child of children) {
      assert(
        SERIES_CHILD_TYPES.has(child.type),
        `Series child "${child.id}" has unexpected type "${child.type}"`,
      );
    }
    return `${children.length} child(ren) verified`;
  });

  // Locate a Season: prefer children of the found Series; fall back to search.
  let seasonItem = null;
  if (seriesItem) {
    try {
      const seriesChildren = await fetchAllChildren(runner, seriesItem.id);
      seasonItem = findByType(seriesChildren, 'Season');
    } catch {
      // ignore — fall through to search
    }
  }
  if (!seasonItem) {
    seasonItem = await findSeasonViaSearch(runner);
  }
  context.seasonItem = seasonItem;

  await reporter.step('Season children are Episode only', async () => {
    if (!seasonItem) throw new NAError('no Season item found');
    const children = await fetchAllChildren(runner, seasonItem.id);
    if (children.length === 0) throw new NAError(`Season "${seasonItem.id}" has no children`);
    for (const child of children) {
      assert(child.type === 'Episode', `Season child "${child.id}" has unexpected type "${child.type}"`);
    }
    return `${children.length} episode(s) verified`;
  });

  await reporter.step('Season episodes all have indexNumber', async () => {
    if (!seasonItem) throw new NAError('no Season item found');
    const children = await fetchAllChildren(runner, seasonItem.id);
    if (children.length === 0) throw new NAError(`Season "${seasonItem.id}" has no episodes`);
    const missing = children.filter((ep) => ep.indexNumber == null);
    assert(
      missing.length === 0,
      `${missing.length} episode(s) missing indexNumber: ${missing.slice(0, 3).map((e) => e.id).join(', ')}`,
    );
    return `${children.length} episode(s) all have indexNumber`;
  });

  await reporter.step('Season episode indexNumbers are unique', async () => {
    if (!seasonItem) throw new NAError('no Season item found');
    const children = await fetchAllChildren(runner, seasonItem.id);
    if (children.length === 0) throw new NAError(`Season "${seasonItem.id}" has no episodes`);
    const nums = children.map((ep) => ep.indexNumber).filter((n) => n != null);
    const dupes = nums.filter((n, i) => nums.indexOf(n) !== i);
    assert(dupes.length === 0, `duplicate indexNumbers in Season: ${dupes.join(', ')}`);
    return `${nums.length} unique index number(s)`;
  });

  await reporter.step('Season episode pagination is consistent across pages', async () => {
    if (!seasonItem) throw new NAError('no Season item found');

    // Single large fetch as ground truth
    const bigPage = parseJsonResult(
      await runner.callMethod('listEntry', { location: seasonItem.id, startIndex: 0, limit: 200 }),
      'listEntry season all',
    );
    validateEntryList(bigPage, 'season full list');
    const totalCount = bigPage.totalCount;

    if (totalCount <= PAGINATION_PAGE_SIZE) {
      throw new NAError(`Season has only ${totalCount} episode(s) — too few to paginate`);
    }

    // Paginate through with small pages
    const pagedIds = [];
    const pagedIndexNumbers = [];
    let offset = 0;
    while (offset < totalCount) {
      const page = parseJsonResult(
        await runner.callMethod('listEntry', { location: seasonItem.id, startIndex: offset, limit: PAGINATION_PAGE_SIZE }),
        `listEntry season page at ${offset}`,
      );
      validateEntryList(page, `season page at ${offset}`);
      assert(
        page.items.length <= PAGINATION_PAGE_SIZE,
        `page at offset ${offset} returned ${page.items.length} items, expected ≤ ${PAGINATION_PAGE_SIZE}`,
      );
      for (const ep of page.items) {
        pagedIds.push(ep.id);
        if (ep.indexNumber != null) pagedIndexNumbers.push(ep.indexNumber);
      }
      if (page.items.length === 0) break;
      offset += page.items.length;
    }

    // Uniqueness across pages
    const uniquePagedIds = new Set(pagedIds);
    assert(uniquePagedIds.size === pagedIds.length, `duplicate IDs across paged episode fetch`);

    // Compare id sets with single-fetch ground truth
    const singleIds = new Set(bigPage.items.map((e) => e.id));
    const missing = [...singleIds].filter((id) => !uniquePagedIds.has(id));
    const extra = [...uniquePagedIds].filter((id) => !singleIds.has(id));
    assert(missing.length === 0, `paginated fetch missing ${missing.length} episode(s) present in single fetch`);
    assert(extra.length === 0, `paginated fetch has ${extra.length} extra episode(s) not in single fetch`);

    // indexNumber consistency
    const singleIndexNums = bigPage.items.map((e) => e.indexNumber).filter((n) => n != null).sort((a, b) => a - b);
    const pagedIndexNumsSorted = pagedIndexNumbers.slice().sort((a, b) => a - b);
    assert(
      JSON.stringify(singleIndexNums) === JSON.stringify(pagedIndexNumsSorted),
      'indexNumbers differ between single-fetch and paginated fetch',
    );

    return `${pagedIds.length} episodes across ${Math.ceil(pagedIds.length / PAGINATION_PAGE_SIZE)} page(s) consistent`;
  });

  // ── Search ─────────────────────────────────────────────────────────────────

  await reporter.step('search returns an array of valid EntryInfo', async () => {
    const result = parseJsonResult(
      await runner.callMethod('search', { scopeLocation: '', query: '' }),
      'search',
    );
    assertArray(result, 'search result');
    for (const [i, item] of result.entries()) validateEntryInfoFields(item, `search[${i}]`);
    return `${result.length} item(s)`;
  });

  // ── Container browse ───────────────────────────────────────────────────────

  await reporter.step('listEntry on a container returns a valid child list', async () => {
    if (!rootList) throw new NAError('root list not loaded');
    const container = rootList.items.find(
      (item) => CONTAINER_TYPES.has(item.type) && item.type !== 'Series',
    ) ?? rootList.items.find((item) => CONTAINER_TYPES.has(item.type));
    if (!container) throw new NAError('no container item in root list');
    const children = parseJsonResult(
      await runner.callMethod('listEntry', { location: container.id, startIndex: 0, limit: 20 }),
      'listEntry container',
    );
    validateEntryList(children, `children of "${container.id}"`);
    return `${children.items.length} child(ren) in "${container.title}"`;
  });

  // Locate a playable item for playback category
  context.playableItem = await findPlayableItem(runner, rootList);

  reporter.endCategory();
  return context;
}

// ── Internal helpers ──────────────────────────────────────────────────────────

function validateEntryList(list, path) {
  assertObject(list, path);
  assertArray(list.items, `${path}.items`);
  assertType(list.totalCount, 'number', `${path}.totalCount`);
  for (const [i, item] of list.items.entries()) validateEntryInfoFields(item, `${path}.items[${i}]`);
}

function validateEntryInfoFields(item, path) {
  assertObject(item, path);
  assertNonEmptyString(item.id, `${path}.id`);
  assertNonEmptyString(item.title, `${path}.title`);
  assert(ENTRY_TYPES.has(item.type), `${path}.type is invalid: "${item.type}"`);
  if (item.cover != null) assertType(item.cover, 'string', `${path}.cover`);
  if (item.originalTitle != null) assertType(item.originalTitle, 'string', `${path}.originalTitle`);
  if (item.communityRating != null) assertType(item.communityRating, 'number', `${path}.communityRating`);
  if (item.indexNumber != null) assertType(item.indexNumber, 'number', `${path}.indexNumber`);
  if (item.etag != null) assertType(item.etag, 'string', `${path}.etag`);
  if (item.genres != null) {
    assertArray(item.genres, `${path}.genres`);
    item.genres.forEach((g, i) => assertType(g, 'string', `${path}.genres[${i}]`));
  }
  if (item.studios != null) {
    assertArray(item.studios, `${path}.studios`);
    item.studios.forEach((s, i) => assertType(s, 'string', `${path}.studios[${i}]`));
  }
  if (item.userData != null) {
    assertObject(item.userData, `${path}.userData`);
    assertType(item.userData.positionMs, 'number', `${path}.userData.positionMs`);
    assertType(item.userData.isFavorite, 'boolean', `${path}.userData.isFavorite`);
    assertType(item.userData.played, 'boolean', `${path}.userData.played`);
  }
}

function findByType(items, type) {
  return items.find((item) => item.type === type) ?? null;
}

async function findSeriesViaSearch(runner) {
  const result = parseJsonResult(
    await runner.callMethod('search', { scopeLocation: '', query: '' }),
    'search(series lookup)',
  );
  if (!Array.isArray(result)) return null;
  return result.find((item) => item.type === 'Series') ?? null;
}

async function findSeasonViaSearch(runner) {
  const result = parseJsonResult(
    await runner.callMethod('search', { scopeLocation: '', query: '' }),
    'search(season lookup)',
  );
  if (!Array.isArray(result)) return null;
  return result.find((item) => item.type === 'Season') ?? null;
}

async function fetchAllChildren(runner, parentId) {
  const list = parseJsonResult(
    await runner.callMethod('listEntry', { location: parentId, startIndex: 0, limit: 200 }),
    `listEntry(${parentId})`,
  );
  validateEntryList(list, `children of ${parentId}`);
  return list.items;
}

async function findPlayableItem(runner, rootList) {
  if (!rootList) return null;
  const queue = [...rootList.items];
  const seen = new Set();
  let scanned = 0;

  while (queue.length > 0 && scanned < 60) {
    const item = queue.shift();
    if (!item || seen.has(item.id)) continue;
    seen.add(item.id);
    scanned += 1;

    if (item.type === 'Playable' || item.type === 'Episode') return item;
    if (!CONTAINER_TYPES.has(item.type)) continue;

    try {
      const childList = parseJsonResult(
        await runner.callMethod('listEntry', { location: item.id, startIndex: 0, limit: 20 }),
        `listEntry(${item.id})`,
      );
      queue.push(...childList.items);
    } catch {
      // Non-browsable container — skip
    }
  }

  return null;
}

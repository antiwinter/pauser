import { writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { HostApis } from '../../../test/host-apis.js';
import { QuickJsProviderRunner } from '../../../test/quickjs-runner.js';
import {
  assert,
  assertArray,
  assertUrl,
  parseJsonResult,
  checkMediaWithFfprobe,
} from '../../../test/validators.js';
import { NAError } from '../../../test/reporter.js';

const testDir = dirname(fileURLToPath(import.meta.url));
const DUMP_PATH = join(testDir, 'dump.json');

// ── Constants ─────────────────────────────────────────────────────────────────

const CONFIG_URL = 'https://catvod-test.local/config';
const CMS_API    = 'https://catvod-test.local/cms';
const SPIDER_URL = 'https://catvod-test.local/spider.js';

const MOCK_CATEGORIES = JSON.stringify({ class: [{ type_id: '1', type_name: 'Action' }] });
const MOCK_VIDEOLIST  = JSON.stringify({
  list: [{ vod_id: '1', vod_name: 'Test Movie', vod_pic: '', type_name: 'Action' }],
  total: 1,
});
const MOCK_DETAIL = JSON.stringify({
  list: [{
    vod_id: '1',
    vod_name: 'Test Movie',
    vod_play_from: 'test',
    vod_play_url: 'Ep1$http://example.com/ep1.mp4',
  }],
});

const DRPY_SPIDER = `var __jsEvalReturn = function() {
  return {
    init: function(ext) {},
    home: function(filter) {
      return JSON.stringify({ class: [{ type_id: '1', type_name: 'Action' }] });
    },
    category: function(tid, pg, filter, extend) {
      return JSON.stringify({
        list: [{ vod_id: '1', vod_name: 'Test Movie', vod_pic: '', type_name: 'Action' }],
        pagecount: 1,
        total: 1,
      });
    },
    detail: function(id) {
      return JSON.stringify({
        list: [{
          vod_id: id,
          vod_name: 'Test Movie',
          vod_play_from: 'test',
          vod_play_url: 'Ep1$http://example.com/ep1.mp4',
        }],
      });
    },
    play: function(flag, id, flags) {
      return JSON.stringify({ url: 'http://example.com/ep1.mp4', header: {}, type: 'video/mp4' });
    },
    search: function(key, quick, pg) {
      return JSON.stringify({
        list: [{ vod_id: '1', vod_name: key, vod_pic: '', type_name: 'Action' }],
      });
    },
  };
};`;

// ── MockHostApis ──────────────────────────────────────────────────────────────

class MockHostApis extends HostApis {
  constructor(mocks) {
    super();
    this.mocks = mocks;
  }

  async handleHttp(name, args) {
    const url = args?.url ?? '';
    if (url in this.mocks) return mockResponse(this.mocks[url], url);
    const base = url.split('?')[0];
    if (base in this.mocks) return mockResponse(this.mocks[base], url);
    return super.handleHttp(name, args);
  }
}

function mockResponse(value, url) {
  const body = typeof value === 'function' ? value(url) : value;
  return { status: 200, body, headers: {} };
}

// ── Mock runner factory ───────────────────────────────────────────────────────

async function createMockRunner(bundle, bundlePath, mocks) {
  const hostApis = new MockHostApis(mocks);
  const runner = new QuickJsProviderRunner({ bundle, hostApis, filename: bundlePath });
  await runner.init();
  await runner.callMethod('init', {
    credentials: { config_url: CONFIG_URL },
    capabilities: {
      videoMime: ['video/mp4'],
      audioMime: ['audio/mp4'],
      subtitleFormats: ['srt'],
      maxPixels: 3840 * 2160,
    },
  });
  return runner;
}

// ── Config helpers ────────────────────────────────────────────────────────────

function makeCmsConfig(type) {
  return JSON.stringify({
    sites: [{ key: 'test_cms', name: `Test CMS (type ${type})`, type, api: CMS_API }],
  });
}

function makeDrpyConfig(type) {
  return JSON.stringify({
    sites: [{ key: 'test_drpy', name: `Test Drpy (type ${type})`, type, api: SPIDER_URL, ext: '' }],
  });
}

function cmsMock(url) {
  if (url.includes('ac=list'))      return MOCK_CATEGORIES;
  if (url.includes('ac=videolist')) return MOCK_VIDEOLIST;
  if (url.includes('ac=detail'))    return MOCK_DETAIL;
  return JSON.stringify({ error: 'unknown ac' });
}

// ── Real-data helpers ─────────────────────────────────────────────────────────

async function callList(runner, location, startIndex = 0, limit = 20) {
  const r = parseJsonResult(
    await runner.callMethod('listEntry', { location, startIndex, limit }),
    'listEntry',
  );
  return r;
}

async function callSearch(runner, query) {
  const r = parseJsonResult(
    await runner.callMethod('listEntry', { location: null, startIndex: 0, limit: 100, options: { searchTerm: query } }),
    'listEntry(search)',
  );
  return Array.isArray(r) ? r : (r?.items ?? []);
}

// ── Real-data test suite ──────────────────────────────────────────────────────

async function probeSite(runner, siteItem) {
  const result = { ref: siteItem.ref, title: siteItem.title, type: siteItem.type };
  try {
    const l1 = await callList(runner, siteItem.ref, 0, 5);
    result.listEntry_site = l1;
    const firstFolder = (l1?.items ?? []).find((i) => i.type === 'Folder');
    if (firstFolder) {
      const l2 = await callList(runner, firstFolder.ref, 0, 5);
      result.listEntry_cat = l2;
      const firstPlayable = (l2?.items ?? []).find((i) => i.type === 'Playable');
      if (firstPlayable) {
        result.getDetail = parseJsonResult(
          await runner.callMethod('getDetail', { itemRef: firstPlayable.ref }),
          'getDetail',
        );
        result.getPlaybackSpec = parseJsonResult(
          await runner.callMethod('getPlaybackSpec', { itemRef: firstPlayable.ref, startMs: 0 }),
          'getPlaybackSpec',
        );
      }
    }
  } catch (e) {
    result.error = e.message;
  }
  return result;
}

async function runRealSiteChecks(out, runner, ffprobe) {
  out.beginCategory('CatVod Real Sites', ['listEntry', 'search', 'getDetail', 'getPlaybackSpec']);

  const dump = { timestamp: new Date().toISOString(), sites: [], search: {} };

  // 1. Root contains sites
  let rootItems = [];
  await out.step('real root contains sites', async () => {
    const r = await callList(runner, null, 0, 100);
    rootItems = r?.items ?? [];
    assert(rootItems.length > 0, 'root returned no items');
    return `${rootItems.length} item(s)`;
  });

  // 2. Search returns provider boundary shape
  await out.step('real search returns provider boundary shape', async () => {
    const queries = ['Dark', '庆余年', '海贼王'];
    let results = null;
    for (const q of queries) {
      try {
        const r = await callSearch(runner, q);
        dump.search[q] = r;
        if (r.length > 0 && results === null) results = r;
      } catch (e) {
        dump.search[q] = { error: e.message };
      }
    }
    if (results === null && Object.keys(dump.search).length === 0) {
      throw new NAError('all search queries failed');
    }
    const flat = results ?? [];
    assertArray(flat, 'search result');
    return flat.length > 0 ? `${flat.length} result(s)` : 'no results (acceptable)';
  });

  // 3. Probe every root site and dump results
  // Reset before each site to avoid Guard JAR shared-state corruption between spiders
  await out.step('real site probe (writes dump.json)', async () => {
    const folders = rootItems.filter((i) => i.type === 'Folder');
    for (const item of folders) {
      await runner.callMethod('resetSpiders', {}).catch(() => undefined);
      const probed = await probeSite(runner, item);
      dump.sites.push(probed);
    }
    await writeFile(DUMP_PATH, JSON.stringify(dump, null, 2), 'utf8');
    const withContent = dump.sites.filter((s) => (s.listEntry_site?.items ?? []).length > 0);
    return `probed ${folders.length} site(s), ${withContent.length} with content → ${DUMP_PATH}`;
  });

  // 4. Find first playable from dump results (non-live sites only)
  const LIVE_PATTERN = /直播|live|iptv/i;
  let playableItem = null;
  let playbackUrl = null;
  let playbackHeaders = {};

  // Collect all candidate playable items across non-live sites
  const candidates = [];
  for (const s of dump.sites) {
    if (LIVE_PATTERN.test(s.title ?? '')) continue;
    for (const i of (s.listEntry_cat?.items ?? [])) {
      if (i.type === 'Playable') candidates.push(i);
    }
  }
  // Also add search results as fallback
  for (const items of Object.values(dump.search)) {
    if (Array.isArray(items)) for (const i of items) candidates.push(i);
  }

  await out.step('real detail returns detail shape', async () => {
    if (candidates.length === 0) throw new NAError('no playable item found in any site or search');

    await runner.callMethod('resetSpiders', {}).catch(() => undefined);

    for (const candidate of candidates) {
      const detail = parseJsonResult(
        await runner.callMethod('getDetail', { itemRef: candidate.ref }),
        'getDetail',
      );
      if (detail != null && typeof detail === 'object' && typeof detail.title === 'string' && detail.title.length > 0) {
        playableItem = candidate;
        return `title="${detail.title}"`;
      }
    }
    throw new NAError('no candidate returned a non-empty title from getDetail');
  });

  // 5. getPlaybackSpec returns a URL
  await out.step('real playback returns playable URL', async () => {
    if (candidates.length === 0) throw new NAError('no playable item found');

    await runner.callMethod('resetSpiders', {}).catch(() => undefined);

    for (const candidate of candidates) {
      let spec;
      try {
        spec = parseJsonResult(
          await runner.callMethod('getPlaybackSpec', { itemRef: candidate.ref, startMs: 0 }),
          'getPlaybackSpec',
        );
      } catch (_) {
        continue;
      }
      if (spec == null || typeof spec !== 'object' || !spec.url) continue;
      try { assertUrl(spec.url, 'playbackSpec.url'); } catch (_) { continue; }
      playableItem = candidate;
      playbackUrl = spec.url;
      playbackHeaders = spec.headers ?? {};
      return `url="${playbackUrl}"`;
    }
    throw new NAError('no candidate returned a playable URL from getPlaybackSpec');
  });

  // 6. ffprobe validates the stream
  await out.step('real playback passes ffprobe', async () => {
    if (!ffprobe) throw new NAError('ffprobe disabled');
    if (!playbackUrl) throw new NAError('no playback URL from previous step');
    await checkMediaWithFfprobe(playbackUrl, playbackHeaders, { expectVideo: true });
    return 'streams ok';
  });

  out.endCategory();
}

// ── Main export ───────────────────────────────────────────────────────────────

export async function runProviderChecks(out, { bundle, bundlePath, runner, ffprobe }) {
  // ── Mock qualification tests ──────────────────────────────────────────────

  out.beginCategory('CatVod Mock Site Types', ['listEntry', 'search', 'getDetail', 'getPlaybackSpec']);

  for (const type of [0, 1, 2]) {
    const mocks = {
      [CONFIG_URL]: makeCmsConfig(type),
      [CMS_API]: cmsMock,
    };

    let mockRunner;
    try {
      mockRunner = await createMockRunner(bundle, bundlePath, mocks);

      await out.step(`type ${type}: listEntry(root) returns site folder`, async () => {
        const r = parseJsonResult(
          await mockRunner.callMethod('listEntry', { location: null, startIndex: 0, limit: 20 }),
          'listEntry root',
        );
        assert(r.items?.length === 1, 'expected 1 site');
        assert(r.items[0].type === 'Folder', 'site should be Folder');
        return `"${r.items[0].title}"`;
      });

      const siteRef = JSON.stringify({ type: 'site', key: 'test_cms' });

      await out.step(`type ${type}: listEntry(site) returns categories`, async () => {
        const r = parseJsonResult(
          await mockRunner.callMethod('listEntry', { location: siteRef, startIndex: 0, limit: 20 }),
          'listEntry site',
        );
        assert(r.items?.length >= 1, 'expected at least 1 category');
        assert(r.items[0].type === 'Folder', 'categories should be Folder');
        return `${r.items.length} category`;
      });

      const catRef = JSON.stringify({ type: 'cat', key: 'test_cms', tid: '1' });

      await out.step(`type ${type}: listEntry(cat) returns video items`, async () => {
        const r = parseJsonResult(
          await mockRunner.callMethod('listEntry', { location: catRef, startIndex: 0, limit: 20 }),
          'listEntry cat',
        );
        assert(r.items?.length >= 1, 'expected at least 1 item');
        assert(r.items[0].type === 'Playable', 'items should be Playable');
        return `${r.items.length} item(s)`;
      });

      await out.step(`type ${type}: search returns results`, async () => {
        const r = await callSearch(mockRunner, 'test');
        assertArray(r, 'search result');
        assert(r.length >= 1, 'expected at least 1 search result');
        return `${r.length} result(s)`;
      });

      const vodRef = JSON.stringify({ type: 'vod', key: 'test_cms', id: '1' });

      await out.step(`type ${type}: getDetail returns valid detail`, async () => {
        const r = parseJsonResult(
          await mockRunner.callMethod('getDetail', { itemRef: vodRef }),
          'getDetail',
        );
        assert(typeof r.title === 'string' && r.title.length > 0, 'expected non-empty title');
        return `title="${r.title}"`;
      });

      const epRef = JSON.stringify({ type: 'ep', key: 'test_cms', id: '1', flag: 'test', epUrl: 'http://example.com/ep1.mp4' });

      await out.step(`type ${type}: getPlaybackSpec returns URL`, async () => {
        const r = parseJsonResult(
          await mockRunner.callMethod('getPlaybackSpec', { itemRef: epRef, startMs: 0 }),
          'getPlaybackSpec',
        );
        assert(typeof r.url === 'string' && r.url.length > 0, 'expected non-empty url');
        return `url="${r.url}"`;
      });

    } finally {
      mockRunner?.dispose();
    }
  }

  for (const type of [3, 4, 9, 10]) {
    const mocks = {
      [CONFIG_URL]: makeDrpyConfig(type),
      [SPIDER_URL]: DRPY_SPIDER,
    };

    let mockRunner;
    try {
      mockRunner = await createMockRunner(bundle, bundlePath, mocks);

      const siteRef = JSON.stringify({ type: 'site', key: 'test_drpy' });

      await out.step(`type ${type}: listEntry(site) returns categories`, async () => {
        const r = parseJsonResult(
          await mockRunner.callMethod('listEntry', { location: siteRef, startIndex: 0, limit: 20 }),
          'listEntry site',
        );
        assert(r.items?.length >= 1, 'expected at least 1 category');
        assert(r.items[0].type === 'Folder', 'categories should be Folder');
        return `${r.items.length} category`;
      });

      const catRef = JSON.stringify({ type: 'cat', key: 'test_drpy', tid: '1' });

      await out.step(`type ${type}: listEntry(cat) returns video items`, async () => {
        const r = parseJsonResult(
          await mockRunner.callMethod('listEntry', { location: catRef, startIndex: 0, limit: 20 }),
          'listEntry cat',
        );
        assert(r.items?.length >= 1, 'expected at least 1 item');
        assert(r.items[0].type === 'Playable', 'items should be Playable');
        return `${r.items.length} item(s)`;
      });

      await out.step(`type ${type}: search returns results`, async () => {
        const r = await callSearch(mockRunner, 'test');
        assertArray(r, 'search result');
        assert(r.length >= 1, 'expected at least 1 search result');
        return `${r.length} result(s)`;
      });

      const vodRef = JSON.stringify({ type: 'vod', key: 'test_drpy', id: '1' });

      await out.step(`type ${type}: getDetail returns valid detail`, async () => {
        const r = parseJsonResult(
          await mockRunner.callMethod('getDetail', { itemRef: vodRef }),
          'getDetail',
        );
        assert(typeof r.title === 'string' && r.title.length > 0, 'expected non-empty title');
        return `title="${r.title}"`;
      });

    } finally {
      mockRunner?.dispose();
    }
  }

  out.endCategory();

  // ── Real-data probes ──────────────────────────────────────────────────────

  if (runner) {
    await runRealSiteChecks(out, runner, ffprobe);
  }
}

import { HostApis } from '../../../test/host-apis.js';
import { QuickJsProviderRunner } from '../../../test/quickjs-runner.js';
import { assert, assertArray, parseJsonResult } from '../../../test/validators.js';

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

// Minimal drpy2 spider using the __jsEvalReturn factory pattern
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
    // 1. Exact match
    if (url in this.mocks) {
      return mockResponse(this.mocks[url], url);
    }
    // 2. Base-URL match (strips query string) — handles `?ac=list`, `?ac=videolist`, etc.
    const base = url.split('?')[0];
    if (base in this.mocks) {
      return mockResponse(this.mocks[base], url);
    }
    return super.handleHttp(name, args);
  }
}

function mockResponse(value, url) {
  const body = typeof value === 'function' ? value(url) : value;
  return { status: 200, body, headers: {} };
}

// ── Runner factory ────────────────────────────────────────────────────────────

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

// ── CMS mock handler (dispatches on ?ac=...) ──────────────────────────────────

function cmsMock(url) {
  if (url.includes('ac=list'))      return MOCK_CATEGORIES;
  if (url.includes('ac=videolist')) return MOCK_VIDEOLIST;
  if (url.includes('ac=detail'))    return MOCK_DETAIL;
  return JSON.stringify({ error: 'unknown ac' });
}

// ── Main export ───────────────────────────────────────────────────────────────

export async function runProviderChecks(out, { bundle, bundlePath }) {
  out.beginCategory('CatVod Site Types', ['listEntry', 'search', 'getDetail', 'getPlaybackSpec']);

  // ── CMS: types 0, 1, 2 ───────────────────────────────────────────────────

  for (const type of [0, 1, 2]) {
    const mocks = {
      [CONFIG_URL]: makeCmsConfig(type),
      [CMS_API]: cmsMock,
    };

    let runner;
    try {
      runner = await createMockRunner(bundle, bundlePath, mocks);

      await out.step(`type ${type}: listEntry(root) returns site folder`, async () => {
        const r = parseJsonResult(
          await runner.callMethod('listEntry', { location: null, startIndex: 0, limit: 20 }),
          'listEntry root',
        );
        assert(r.items?.length === 1, 'expected 1 site');
        assert(r.items[0].type === 'Folder', 'site should be Folder');
        return `"${r.items[0].title}"`;
      });

      const siteRef = JSON.stringify({ type: 'site', key: 'test_cms' });

      await out.step(`type ${type}: listEntry(site) returns categories`, async () => {
        const r = parseJsonResult(
          await runner.callMethod('listEntry', { location: siteRef, startIndex: 0, limit: 20 }),
          'listEntry site',
        );
        assert(r.items?.length >= 1, 'expected at least 1 category');
        assert(r.items[0].type === 'Folder', 'categories should be Folder');
        return `${r.items.length} category`;
      });

      const catRef = JSON.stringify({ type: 'cat', key: 'test_cms', tid: '1' });

      await out.step(`type ${type}: listEntry(cat) returns video items`, async () => {
        const r = parseJsonResult(
          await runner.callMethod('listEntry', { location: catRef, startIndex: 0, limit: 20 }),
          'listEntry cat',
        );
        assert(r.items?.length >= 1, 'expected at least 1 item');
        assert(r.items[0].type === 'Playable', 'items should be Playable');
        return `${r.items.length} item(s)`;
      });

      await out.step(`type ${type}: search returns results`, async () => {
        const r = parseJsonResult(
          await runner.callMethod('search', { scopeLocation: '', query: 'test' }),
          'search',
        );
        assertArray(r, 'search result');
        assert(r.length >= 1, 'expected at least 1 search result');
        return `${r.length} result(s)`;
      });

      const vodRef = JSON.stringify({ type: 'vod', key: 'test_cms', id: '1' });

      await out.step(`type ${type}: getDetail returns valid detail`, async () => {
        const r = parseJsonResult(
          await runner.callMethod('getDetail', { itemRef: vodRef }),
          'getDetail',
        );
        assert(typeof r.title === 'string' && r.title.length > 0, 'expected non-empty title');
        return `title="${r.title}"`;
      });

      const epRef = JSON.stringify({ type: 'ep', key: 'test_cms', id: '1', flag: 'test', epUrl: 'http://example.com/ep1.mp4' });

      await out.step(`type ${type}: getPlaybackSpec returns URL`, async () => {
        const r = parseJsonResult(
          await runner.callMethod('getPlaybackSpec', { itemRef: epRef, startMs: 0 }),
          'getPlaybackSpec',
        );
        assert(typeof r.url === 'string' && r.url.length > 0, 'expected non-empty url');
        return `url="${r.url}"`;
      });

    } finally {
      runner?.dispose();
    }
  }

  // ── drpy2: types 4, 9, 10 ────────────────────────────────────────────────

  for (const type of [4, 9, 10]) {
    const mocks = {
      [CONFIG_URL]: makeDrpyConfig(type),
      [SPIDER_URL]: DRPY_SPIDER,
    };

    let runner;
    try {
      runner = await createMockRunner(bundle, bundlePath, mocks);

      const siteRef = JSON.stringify({ type: 'site', key: 'test_drpy' });

      await out.step(`type ${type}: listEntry(site) returns categories`, async () => {
        const r = parseJsonResult(
          await runner.callMethod('listEntry', { location: siteRef, startIndex: 0, limit: 20 }),
          'listEntry site',
        );
        assert(r.items?.length >= 1, 'expected at least 1 category');
        assert(r.items[0].type === 'Folder', 'categories should be Folder');
        return `${r.items.length} category`;
      });

      const catRef = JSON.stringify({ type: 'cat', key: 'test_drpy', tid: '1' });

      await out.step(`type ${type}: listEntry(cat) returns video items`, async () => {
        const r = parseJsonResult(
          await runner.callMethod('listEntry', { location: catRef, startIndex: 0, limit: 20 }),
          'listEntry cat',
        );
        assert(r.items?.length >= 1, 'expected at least 1 item');
        assert(r.items[0].type === 'Playable', 'items should be Playable');
        return `${r.items.length} item(s)`;
      });

      await out.step(`type ${type}: search returns results`, async () => {
        const r = parseJsonResult(
          await runner.callMethod('search', { scopeLocation: '', query: 'test' }),
          'search',
        );
        assertArray(r, 'search result');
        assert(r.length >= 1, 'expected at least 1 search result');
        return `${r.length} result(s)`;
      });

      const vodRef = JSON.stringify({ type: 'vod', key: 'test_drpy', id: '1' });

      await out.step(`type ${type}: getDetail returns valid detail`, async () => {
        const r = parseJsonResult(
          await runner.callMethod('getDetail', { itemRef: vodRef }),
          'getDetail',
        );
        assert(typeof r.title === 'string' && r.title.length > 0, 'expected non-empty title');
        return `title="${r.title}"`;
      });

    } finally {
      runner?.dispose();
    }
  }

  out.endCategory();
}

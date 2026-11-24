'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { core } = require('./helpers.js');

const ARTICLE_PAGE = core.detectPage('https://zhuanlan.zhihu.com/p/1010101');

function jsonResponse(status, body) {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => (typeof body === 'string' ? body : JSON.stringify(body))
  };
}

function emptyPage() {
  return { data: [], paging: { totals: 0, is_end: true } };
}

async function withPage(fetchImpl, gmImpl, run) {
  const previousDocument = globalThis.document;
  const previousWindow = globalThis.window;
  const hadGm = Object.prototype.hasOwnProperty.call(globalThis, 'GM_xmlhttpRequest');
  const previousGm = globalThis.GM_xmlhttpRequest;

  globalThis.document = {
    title: '',
    querySelector: () => null,
    getElementById: () => null
  };
  globalThis.window = { fetch: fetchImpl };
  if (gmImpl) {
    globalThis.GM_xmlhttpRequest = gmImpl;
  } else {
    delete globalThis.GM_xmlhttpRequest;
  }

  try {
    return await run();
  } finally {
    if (previousDocument === undefined) {
      delete globalThis.document;
    } else {
      globalThis.document = previousDocument;
    }
    if (previousWindow === undefined) {
      delete globalThis.window;
    } else {
      globalThis.window = previousWindow;
    }
    if (hadGm) {
      globalThis.GM_xmlhttpRequest = previousGm;
    } else {
      delete globalThis.GM_xmlhttpRequest;
    }
  }
}

test('retries a network-blocked Zhihu request once through GM_xmlhttpRequest', async () => {
  const gmCalls = [];
  const data = await withPage(
    () => Promise.reject(new TypeError('Failed to fetch')),
    (options) => {
      gmCalls.push(options.url);
      options.onload({
        status: 200,
        responseText: JSON.stringify({
          id: 1010101,
          title: 'GM title',
          content: '<p>GM body</p>',
          author: { name: 'GM Author', url_token: 'gm-author' },
          created: 1609459200,
          voteup_count: 1,
          comment_count: 2
        })
      });
    },
    () => core.loadSourceData(ARTICLE_PAGE, null, [])
  );

  assert.equal(data.title, 'GM title');
  assert.deepEqual(gmCalls, ['https://zhuanlan.zhihu.com/api/articles/1010101']);
});

test('returns unavailable comments instead of aborting when both comment channels are network-blocked', async () => {
  const result = await withPage(
    () => Promise.reject(new TypeError('Failed to fetch')),
    null,
    () => core.captureRootPages(ARTICLE_PAGE, { comment_count: 5 }, null)
  );

  assert.deepEqual(result.pages, []);
  assert.equal(result.legacy, false);
  assert.equal(result.unavailable.v5.code, 'NETWORK_ERROR');
  assert.equal(result.unavailable.legacy.code, 'NETWORK_ERROR');
});

test('returns unavailable comments when v5 is empty and the legacy request is network-blocked', async () => {
  const result = await withPage(
    (url) => {
      if (url.indexOf('comment_v5/articles/') !== -1) {
        return Promise.resolve(jsonResponse(200, emptyPage()));
      }
      return Promise.reject(new TypeError('Failed to fetch'));
    },
    null,
    () => core.captureRootPages(ARTICLE_PAGE, { comment_count: 5 }, null)
  );

  assert.equal(result.unavailable.v5, null);
  assert.equal(result.unavailable.legacy.code, 'NETWORK_ERROR');
});

test('still aborts when both comment channels answer successfully but return no roots', async () => {
  await assert.rejects(
    withPage(
      () => Promise.resolve(jsonResponse(200, emptyPage())),
      null,
      () => core.captureRootPages(ARTICLE_PAGE, { comment_count: 5 }, null)
    ),
    (error) => error.code === 'COMMENTS_UNAVAILABLE' && error.detail.expectedTotal === 5
  );
});

'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { core, fixtureText } = require('./helpers.js');

const ARTICLE_PAGE = core.detectPage('https://zhuanlan.zhihu.com/p/1010101');

function node(text, attrs, html) {
  return {
    textContent: text,
    innerHTML: html,
    getAttribute(name) {
      return attrs && Object.prototype.hasOwnProperty.call(attrs, name) ? attrs[name] : null;
    }
  };
}

function fakeDocument(stateText, selectors) {
  const map = selectors || {};
  return {
    title: 'Fallback title - 知乎',
    querySelector(selector) {
      if (selector === 'script#js-initialData' && stateText != null) {
        return { textContent: stateText };
      }
      return map[selector] || null;
    },
    getElementById(id) {
      return id === 'js-initialData' && stateText != null ? { textContent: stateText } : null;
    }
  };
}

function jsonResponse(status, body) {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => (typeof body === 'string' ? body : JSON.stringify(body))
  };
}

async function withPage(doc, fetchImpl, run) {
  const previousDocument = globalThis.document;
  const previousWindow = globalThis.window;
  globalThis.document = doc;
  globalThis.window = { fetch: fetchImpl };
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
  }
}

function domSelectors() {
  return {
    '.Post-RichText': node('', {}, '<p>DOM body</p>'),
    'h1.Post-Title': node('DOM title', {}, ''),
    '.AuthorInfo-name a': node('DOM Author', { href: '//www.zhihu.com/people/dom-author' }, ''),
    '.ContentItem-time': node('发布于 2021-01-01 08:00', {}, ''),
    '.VoteButton--up': node('赞同 41', {}, ''),
    '#comments h4': node('9 条评论', {}, '')
  };
}

const SIGNATURE_BODY = { error: { code: 10003, message: '请求参数异常，请升级客户端后重试。' } };

test('reads the embedded page state without issuing a request', async () => {
  const calls = [];
  const progress = [];
  const warnings = [];
  const data = await withPage(
    fakeDocument(fixtureText('article-initial-data.json')),
    (url) => {
      calls.push(url);
      throw new Error('fetch must not be called');
    },
    () => core.loadSourceData(ARTICLE_PAGE, (step) => progress.push(step), warnings)
  );

  assert.equal(data.title, 'Embedded article title');
  assert.deepEqual(calls, []);
  assert.deepEqual(progress, []);
  assert.deepEqual(warnings, []);
});

test('falls back to the column API when page state has no article', async () => {
  const calls = [];
  const progress = [];
  const warnings = [];
  const data = await withPage(
    fakeDocument(null),
    (url) => {
      calls.push(url);
      return Promise.resolve(
        jsonResponse(200, {
          id: 1010101,
          title: 'API title',
          content: '<p>API body</p>',
          author: { name: 'API Author', url_token: 'api-author' },
          created: 1609459200,
          voteup_count: 3,
          comment_count: 4
        })
      );
    },
    () => core.loadSourceData(ARTICLE_PAGE, (step) => progress.push(step), warnings)
  );

  assert.equal(data.title, 'API title');
  assert.match(data.content, /API body/);
  assert.deepEqual(calls, ['https://zhuanlan.zhihu.com/api/articles/1010101']);
  assert.deepEqual(progress, ['article:api']);
  assert.deepEqual(warnings, []);
});

test('reports a warning when the API is signature-gated and the DOM supplies the article', async () => {
  const warnings = [];
  const progress = [];
  const data = await withPage(
    fakeDocument(null, domSelectors()),
    () => Promise.resolve(jsonResponse(403, SIGNATURE_BODY)),
    () => core.loadSourceData(ARTICLE_PAGE, (step) => progress.push(step), warnings)
  );

  assert.equal(data.title, 'DOM title');
  assert.match(data.content, /DOM body/);
  assert.deepEqual(progress, ['article:api']);
  assert.equal(warnings.length, 1);
  assert.match(warnings[0], /ZHIHU_SIGNATURE_REQUIRED/);
  assert.match(warnings[0], /page DOM/);
});

test('rejects with the API error when page state, API, and DOM all fail', async () => {
  const warnings = [];
  await assert.rejects(
    withPage(
      fakeDocument(null, { '.Post-Title': node('Title', {}, '') }),
      () => Promise.resolve(jsonResponse(403, SIGNATURE_BODY)),
      () => core.loadSourceData(ARTICLE_PAGE, null, warnings)
    ),
    (error) => error.code === 'ZHIHU_SIGNATURE_REQUIRED' && error.detail.step === 'article:api'
  );
  assert.deepEqual(warnings, []);
});

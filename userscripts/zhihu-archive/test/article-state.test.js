'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { core, fixtureText } = require('./helpers.js');

const CAPTURED_AT = '2026-01-01T00:00:00.000Z';

function buildFromState(id) {
  const data = core.articleFromInitialDataJson(fixtureText('article-initial-data.json'), id);
  assert.ok(data, 'page state should contain the article');
  return core.buildPayload({
    page: core.detectPage('https://zhuanlan.zhihu.com/p/1010101'),
    data,
    comments: { total: 0, roots: [] },
    capturedAt: CAPTURED_AT,
    clientVersion: '1.0.7'
  });
}

test('reads the article entity from the page js-initialData state by id', () => {
  const payload = buildFromState('1010101');

  assert.equal(payload.article.title, 'Embedded article title');
  assert.deepEqual(payload.article.author, {
    name: 'Embedded Author',
    profileUrl: 'https://www.zhihu.com/people/embedded-author'
  });
  assert.equal(payload.article.publishedAt, '2021-01-01T00:00:00.000Z');
  assert.equal(payload.article.likeCount, 77);
  assert.equal(payload.article.commentCount, 9);
  assert.match(payload.article.html, /<strong>emphasis<\/strong>/);
  assert.match(payload.article.html, /<img src="https:\/\/pic1\.zhimg\.com\/embedded-article\.jpg">/);
  assert.deepEqual(payload.assets.items, [
    { url: 'https://pic1.zhimg.com/embedded-article.jpg', type: 'IMAGE' }
  ]);
});

test('accepts a numeric article id and falls back to the first usable entity', () => {
  assert.ok(core.articleFromInitialDataJson(fixtureText('article-initial-data.json'), 1010101));
  assert.ok(core.articleFromInitialDataJson(fixtureText('article-initial-data.json'), '9999999'));
});

test('rejects page state without an article body instead of archiving an excerpt', () => {
  const state = JSON.stringify({
    initialState: { entities: { articles: { 1: { id: 1, title: 'No body', excerpt: 'only excerpt' } } } }
  });
  assert.equal(core.articleFromInitialDataJson(state, '1'), null);
  assert.equal(core.articleFromInitialDataJson('not json', '1'), null);
});

function domNode(text, attrs, html) {
  return {
    textContent: text,
    innerHTML: html,
    getAttribute(name) {
      return attrs && Object.prototype.hasOwnProperty.call(attrs, name) ? attrs[name] : null;
    }
  };
}

function fakeDocument(selectors) {
  return {
    title: 'Fallback title - 知乎',
    querySelector(selector) {
      return selectors[selector] || null;
    },
    getElementById() {
      return null;
    }
  };
}

test('reads a rendered article from the DOM when page state and API are unavailable', () => {
  const doc = fakeDocument({
    '.Post-RichText': domNode('', {}, '<p>DOM body with <strong>emphasis</strong>.</p>'),
    'h1.Post-Title': domNode('DOM article title', {}, ''),
    '.AuthorInfo-name a': domNode('DOM Author', { href: '//www.zhihu.com/people/dom-author' }, ''),
    '.ContentItem-time': domNode('发布于 2021-01-01 08:00', {}, ''),
    '.VoteButton--up': domNode('赞同 41', {}, ''),
    '#comments h4': domNode('9 条评论', {}, '')
  });

  const data = core.articleFromDom(doc);
  assert.equal(data.title, 'DOM article title');
  assert.equal(data.content, '<p>DOM body with <strong>emphasis</strong>.</p>');
  assert.deepEqual(data.author, {
    name: 'DOM Author',
    url: 'https://www.zhihu.com/people/dom-author',
    url_token: null
  });
  assert.equal(data.created, Math.floor(new Date(2021, 0, 1, 8, 0, 0, 0).getTime() / 1000));
  assert.equal(data.voteup_count, 41);
  assert.equal(data.comment_count, 9);
});

test('scales a 万-suffixed count parsed from the DOM', () => {
  const doc = fakeDocument({
    '.Post-RichText': domNode('', {}, '<p>body</p>'),
    '.VoteButton--up': domNode('赞同 1.2 万', {}, '')
  });
  assert.equal(core.articleFromDom(doc).voteup_count, 12000);
});

test('returns null from the DOM reader when the article body is not rendered', () => {
  assert.equal(core.articleFromDom(fakeDocument({ '.Post-Title': domNode('Title', {}, '') })), null);
});

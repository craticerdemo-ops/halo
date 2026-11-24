'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { core, fixture, commentSelection } = require('./helpers.js');

// Key lists copied from the backend records in
// application/src/main/java/run/halo/app/zhihu/*.java
const EXPECTED = {
  payload: ['schemaVersion', 'source', 'article', 'comments', 'capture', 'assets'],
  source: ['url', 'type', 'id'],
  article: ['title', 'author', 'publishedAt', 'likeCount', 'commentCount', 'html'],
  author: ['name', 'profileUrl'],
  comments: ['total', 'roots'],
  comment: [
    'id',
    'author',
    'contentHtml',
    'likeCount',
    'createdAt',
    'ipLocation',
    'badge',
    'collapsed',
    'replyToAuthor',
    'replies'
  ],
  capture: ['capturedAt', 'clientVersion'],
  assets: ['items'],
  asset: ['url', 'type']
};

function keys(value) {
  return Object.keys(value).sort();
}

test('payload keys match the backend DTOs exactly, with no extra wire fields', () => {
  const payload = core.buildPayload({
    page: core.detectPage('https://zhuanlan.zhihu.com/p/1010101'),
    data: fixture('article.json'),
    comments: commentSelection(),
    capturedAt: '2026-01-01T00:00:00.000Z',
    clientVersion: '1.0.0'
  });

  assert.deepEqual(keys(payload), EXPECTED.payload.slice().sort());
  assert.deepEqual(keys(payload.source), EXPECTED.source.slice().sort());
  assert.deepEqual(keys(payload.article), EXPECTED.article.slice().sort());
  assert.deepEqual(keys(payload.article.author), EXPECTED.author.slice().sort());
  assert.deepEqual(keys(payload.comments), EXPECTED.comments.slice().sort());
  assert.deepEqual(keys(payload.capture), EXPECTED.capture.slice().sort());
  assert.deepEqual(keys(payload.assets), EXPECTED.assets.slice().sort());

  const root = payload.comments.roots.find((entry) => entry.replies.length > 0);
  assert.ok(root, 'at least one selected root should have replies');
  assert.deepEqual(keys(root), EXPECTED.comment.slice().sort());
  assert.deepEqual(keys(root.author), EXPECTED.author.slice().sort());
  assert.ok(root.replies.length > 0);
  assert.deepEqual(keys(root.replies[0]), EXPECTED.comment.slice().sort());

  for (const asset of payload.assets.items) {
    assert.deepEqual(keys(asset), EXPECTED.asset.slice().sort());
  }
});

test('serialized JSON contains only enumerable contract keys', () => {
  const payload = core.buildPayload({
    page: core.detectPage('https://zhuanlan.zhihu.com/p/1010101'),
    data: fixture('article.json'),
    comments: commentSelection(),
    capturedAt: '2026-01-01T00:00:00.000Z'
  });
  const parsed = JSON.parse(JSON.stringify(payload));

  assert.deepEqual(keys(parsed), EXPECTED.payload.slice().sort());
  assert.deepEqual(keys(parsed.comments.roots[0]), EXPECTED.comment.slice().sort());
  assert.equal(Object.prototype.propertyIsEnumerable.call(payload, 'warnings'), false);
});

test('uses the schema version and enum casing the backend expects', () => {
  assert.equal(core.buildPayload({
    page: core.detectPage('https://zhuanlan.zhihu.com/p/1010101'),
    data: fixture('article.json'),
    comments: { total: 0, roots: [] }
  }).schemaVersion, 1);

  const types = [
    ['https://zhuanlan.zhihu.com/p/1010101', 'ARTICLE'],
    ['https://www.zhihu.com/question/3030303/answer/2020202', 'ANSWER'],
    ['https://www.zhihu.com/pin/4040404', 'PIN'],
    ['https://www.zhihu.com/zvideo/5050505', 'VIDEO']
  ];
  for (const [url, expectedType] of types) {
    const page = core.detectPage(url);
    const payload = core.buildPayload({
      page,
      data: fixture(page.type.toLowerCase() + '.json'),
      comments: { total: 0, roots: [] }
    });
    assert.equal(payload.source.type, expectedType);
  }
});

test('comment timestamps are ISO-8601 strings and counts are non-negative numbers', () => {
  const payload = core.buildPayload({
    page: core.detectPage('https://zhuanlan.zhihu.com/p/1010101'),
    data: fixture('article.json'),
    comments: commentSelection()
  });
  for (const root of payload.comments.roots) {
    assert.equal(typeof root.likeCount, 'number');
    assert.ok(root.likeCount >= 0);
    assert.match(root.createdAt, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);
    for (const reply of root.replies) {
      assert.equal(typeof reply.likeCount, 'number');
      assert.match(reply.createdAt, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);
    }
  }
});

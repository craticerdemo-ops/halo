'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { core, fixture, articlePage, commentSelection } = require('./helpers.js');

const CAPTURED_AT = '2026-01-01T00:00:00.000Z';

function build(url, data, comments) {
  return core.buildPayload({
    page: core.detectPage(url),
    data,
    comments: comments || { total: 0, roots: [] },
    capturedAt: CAPTURED_AT,
    clientVersion: '1.0.0'
  });
}

test('extracts an article: title, author, publish time, counts, sanitized HTML, assets', () => {
  const payload = build('https://zhuanlan.zhihu.com/p/1010101', fixture('article.json'));

  assert.equal(payload.schemaVersion, 1);
  assert.deepEqual(payload.source, {
    url: 'https://zhuanlan.zhihu.com/p/1010101',
    type: 'ARTICLE',
    id: '1010101'
  });
  assert.equal(payload.article.title, 'Fixture article title');
  assert.deepEqual(payload.article.author, {
    name: 'Fixture Author',
    profileUrl: 'https://www.zhihu.com/people/fixture-author'
  });
  assert.equal(payload.article.publishedAt, '2021-01-01T00:00:00.000Z');
  assert.equal(payload.article.likeCount, 4242);
  assert.equal(payload.article.commentCount, 31);
  assert.equal(payload.capture.capturedAt, CAPTURED_AT);
  assert.equal(payload.capture.clientVersion, '1.0.0');

  assert.match(payload.article.html, /<strong>bold<\/strong>/);
  assert.match(payload.article.html, /<img src="https:\/\/pic1\.zhimg\.com\/fixture-article\.jpg">/);
  assert.doesNotMatch(payload.article.html, /<script/i);
  assert.doesNotMatch(payload.article.html, /<style/i);
  assert.doesNotMatch(payload.article.html, /onerror/i);
  assert.doesNotMatch(payload.article.html, /window\.evil/);

  assert.deepEqual(payload.assets.items, [
    { url: 'https://pic1.zhimg.com/fixture-article.jpg', type: 'IMAGE' }
  ]);
});

test('extracts an answer using the question title and the answer publish time', () => {
  const payload = build(
    'https://www.zhihu.com/question/3030303/answer/2020202',
    fixture('answer.json')
  );

  assert.equal(payload.source.type, 'ANSWER');
  assert.equal(payload.source.id, '2020202');
  assert.equal(payload.article.title, 'Fixture question title');
  assert.equal(payload.article.author.name, 'Fixture Answerer');
  assert.equal(payload.article.author.profileUrl, 'https://www.zhihu.com/people/fixture-answerer');
  assert.equal(payload.article.publishedAt, '2021-02-01T00:00:00.000Z');
  assert.equal(payload.article.likeCount, 515);
  assert.equal(payload.article.commentCount, 12);
  assert.match(payload.article.html, /<a href="https:\/\/www\.zhihu\.com\/people\/fixture-author">link<\/a>/);
  assert.match(payload.article.html, /<img src="https:\/\/pic2\.zhimg\.com\/fixture-answer\.jpg"[^>]*>/);
  assert.doesNotMatch(payload.article.html, /data:image/);
  assert.deepEqual(payload.assets.items, [
    { url: 'https://pic2.zhimg.com/fixture-answer.jpg', type: 'IMAGE' }
  ]);
});

test('extracts a pin from its content segments and derives a title from the text', () => {
  const payload = build('https://www.zhihu.com/pin/4040404', fixture('pin.json'));

  assert.equal(payload.source.type, 'PIN');
  assert.equal(payload.article.title, 'Fixture pin text line one. Line two.');
  assert.equal(payload.article.publishedAt, '2021-07-01T00:00:00.000Z');
  assert.equal(payload.article.likeCount, 77);
  assert.equal(payload.article.commentCount, 5);
  assert.match(payload.article.html, /Fixture pin text line one\.<br>Line two\./);
  assert.match(payload.article.html, /<img src="https:\/\/pic3\.zhimg\.com\/fixture-pin\.jpg"[^>]*>/);
  assert.deepEqual(payload.assets.items, [
    { url: 'https://pic3.zhimg.com/fixture-pin.jpg', type: 'IMAGE' }
  ]);
});

test('extracts a video as poster plus source link, never video bytes', () => {
  const payload = build('https://www.zhihu.com/zvideo/5050505', fixture('video.json'));

  assert.equal(payload.source.type, 'VIDEO');
  assert.equal(payload.article.title, 'Fixture video title');
  assert.equal(payload.article.publishedAt, '2021-12-01T00:00:00.000Z');
  assert.equal(payload.article.likeCount, 1234);
  assert.equal(payload.article.commentCount, 9);
  assert.match(payload.article.html, /class="zhihu-archive-video"/);
  assert.match(payload.article.html, /data-video-id="5050505"/);
  assert.match(payload.article.html, /<img src="https:\/\/pic4\.zhimg\.com\/fixture-video-poster\.jpg"/);
  assert.match(payload.article.html, /https:\/\/vdn\.vzuu\.com\/fixture\/fixture-video-hd\.mp4/);
  assert.doesNotMatch(payload.article.html, /<video/i);
  assert.doesNotMatch(payload.article.html, /fixture-video-sd\.mp4/);
  assert.deepEqual(payload.assets.items, [
    { url: 'https://pic4.zhimg.com/fixture-video-poster.jpg', type: 'POSTER' },
    { url: 'https://vdn.vzuu.com/fixture/fixture-video-hd.mp4', type: 'VIDEO' }
  ]);
});

test('strips javascript: URLs and inline handlers from raw source HTML', () => {
  const payload = build('https://zhuanlan.zhihu.com/p/1010101', {
    title: 'Sanitizer fixture',
    content:
      '<p onclick="steal()">safe</p><a href="javascript:alert(1)">bad</a>' +
      '<img src="https://pic1.zhimg.com/x.jpg" onload="steal()">',
    created: 1609459200,
    voteup_count: 1,
    comment_count: 0,
    author: { name: 'Sanitizer Author', url_token: 'sanitizer-author' }
  });

  assert.doesNotMatch(payload.article.html, /onclick|onload|javascript:/i);
  assert.match(payload.article.html, /<p>safe<\/p>/);
});

test('strips noscript fallback images from raw source HTML', () => {
  const payload = build('https://zhuanlan.zhihu.com/p/1010101', {
    title: 'Noscript fixture',
    content:
      '<figure><noscript><img src="https://pic1.zhimg.com/fallback.jpg"></noscript>' +
      '<img src="https://pic1.zhimg.com/fallback.jpg"></figure>',
    created: 1609459200,
    voteup_count: 1,
    comment_count: 0,
    author: { name: 'Noscript Author', url_token: 'noscript-author' }
  });

  assert.doesNotMatch(payload.article.html, /<noscript/i);
  assert.equal((payload.article.html.match(/<img /g) || []).length, 1);
  assert.match(payload.article.html, /<figure><img src="https:\/\/pic1\.zhimg\.com\/fallback\.jpg"><\/figure>/);
});

test('fails loudly with the failing step when the source shape is unexpected', () => {
  const page = core.detectPage('https://zhuanlan.zhihu.com/p/1010101');

  assert.throws(
    () =>
      core.buildPayload({
        page,
        data: { content: '<p>x</p>', author: { name: 'A' } },
        comments: { total: 0, roots: [] }
      }),
    (error) => error.code === 'UNEXPECTED_SHAPE' && error.detail.step === 'article:title'
  );

  assert.throws(
    () =>
      core.buildPayload({
        page,
        data: { title: 'T', author: { name: 'A' } },
        comments: { total: 0, roots: [] }
      }),
    (error) => error.code === 'UNEXPECTED_SHAPE' && error.detail.step === 'article:html'
  );

  assert.throws(
    () =>
      core.buildPayload({
        page,
        data: { title: 'T', content: '<p>x</p>' },
        comments: { total: 0, roots: [] }
      }),
    (error) => error.code === 'UNEXPECTED_SHAPE' && error.detail.step === 'article:author'
  );
});

test('refuses unsupported pages and oversized article HTML instead of producing partial data', () => {
  assert.throws(
    () =>
      core.buildPayload({
        page: core.detectPage('https://www.zhihu.com/people/fixture-author'),
        data: {},
        comments: { total: 0, roots: [] }
      }),
    (error) => error.code === 'UNSUPPORTED_PAGE'
  );

  assert.throws(
    () =>
      build('https://zhuanlan.zhihu.com/p/1010101', {
        title: 'Huge',
        content: '<p>' + 'x'.repeat(2000000) + '</p>',
        author: { name: 'A' }
      }),
    (error) =>
      error.code === 'LIMIT_EXCEEDED' &&
      error.detail.step === 'article:html' &&
      error.detail.limit === 2000000
  );
});

test('truncates an oversized title and records a non-enumerable warning', () => {
  const payload = build('https://zhuanlan.zhihu.com/p/1010101', {
    title: 'T'.repeat(600),
    content: '<p>body</p>',
    author: { name: 'A' }
  });

  assert.equal(payload.article.title.length, 500);
  assert.equal(payload.article.title.endsWith('…'), true);
  assert.equal(payload.warnings.some((warning) => /Title exceeded 500/.test(warning)), true);
  assert.equal(Object.keys(payload).includes('warnings'), false);
  assert.equal(JSON.stringify(payload).includes('warnings'), false);
});

test('builds the payload with selected comments attached', () => {
  const payload = build(
    'https://zhuanlan.zhihu.com/p/1010101',
    fixture('article.json'),
    commentSelection()
  );

  assert.equal(payload.comments.total, 30);
  assert.equal(payload.comments.roots.length, 10);
  assert.equal(payload.comments.roots[0].id, '9021');
  assert.equal(payload.comments.roots[0].replies.length, 0);
});

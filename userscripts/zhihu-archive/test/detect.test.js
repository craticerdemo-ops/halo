'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { core } = require('./helpers.js');

test('detects an article and maps its API and comment endpoints', () => {
  const page = core.detectPage('https://zhuanlan.zhihu.com/p/1010101');
  assert.equal(page.supported, true);
  assert.equal(page.type, 'ARTICLE');
  assert.equal(page.id, '1010101');
  assert.equal(page.url, 'https://zhuanlan.zhihu.com/p/1010101');
  assert.equal(page.apiUrl, 'https://www.zhihu.com/api/v4/articles/1010101');
  assert.equal(page.commentResource, 'articles');
  assert.equal(page.reason, null);
});

test('detects an answer and uses the answer id rather than the question id', () => {
  const page = core.detectPage('https://www.zhihu.com/question/3030303/answer/2020202?utm_source=fixture#comments');
  assert.equal(page.supported, true);
  assert.equal(page.type, 'ANSWER');
  assert.equal(page.id, '2020202');
  assert.equal(page.url, 'https://www.zhihu.com/question/3030303/answer/2020202?utm_source=fixture');
  assert.equal(
    page.apiUrl,
    'https://www.zhihu.com/api/v4/answers/2020202?include=content,excerpt,created_time,updated_time,voteup_count,comment_count,author,question'
  );
  assert.equal(page.commentResource, 'answers');
});

test('detects a pin', () => {
  const page = core.detectPage('https://www.zhihu.com/pin/4040404');
  assert.equal(page.supported, true);
  assert.equal(page.type, 'PIN');
  assert.equal(page.id, '4040404');
  assert.equal(page.apiUrl, 'https://www.zhihu.com/api/v4/pins/4040404');
  assert.equal(page.commentResource, 'pins');
});

test('detects a video and does not invent a comment endpoint', () => {
  const page = core.detectPage('https://www.zhihu.com/zvideo/5050505');
  assert.equal(page.supported, true);
  assert.equal(page.type, 'VIDEO');
  assert.equal(page.id, '5050505');
  assert.equal(page.apiUrl, 'https://www.zhihu.com/api/v4/zvideos/5050505');
  assert.equal(page.commentResource, null);
});

test('rejects unsupported hosts, schemes, credentials, paths, and malformed URLs', () => {
  const cases = [
    'http://zhuanlan.zhihu.com/p/1010101',
    'https://evil.example.com/p/1010101',
    'https://zhuanlan.zhihu.com.evil.example.com/p/1010101',
    'https://user:pass@zhuanlan.zhihu.com/p/1010101',
    'https://zhuanlan.zhihu.com/p/not-a-number',
    'https://zhuanlan.zhihu.com/p/1010101/extra',
    'https://www.zhihu.com/question/3030303',
    'https://www.zhihu.com/people/fixture-author',
    'https://www.zhihu.com/pin/',
    'not a url'
  ];

  for (const url of cases) {
    const page = core.detectPage(url);
    assert.equal(page.supported, false, `expected unsupported for ${url}`);
    assert.equal(page.type, null);
    assert.equal(page.id, null);
    assert.match(page.reason, /Unsupported page|HTTPS|parsed|credentials/);
  }
});

test('rejects a non-Zhihu subdomain that merely contains the word zhihu', () => {
  const page = core.detectPage('https://zhihu.com.example.net/p/1010101');
  assert.equal(page.supported, false);
});

test('accepts only numeric, non-zero-leading ids', () => {
  assert.equal(core.detectPage('https://zhuanlan.zhihu.com/p/0001').supported, false);
  assert.equal(core.detectPage('https://www.zhihu.com/pin/0').supported, false);
  assert.equal(core.detectPage('https://www.zhihu.com/zvideo/abc').supported, false);
});

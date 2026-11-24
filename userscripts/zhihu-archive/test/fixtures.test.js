'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { core, fixture, fixtureText } = require('./helpers.js');

test('every fabricated page fixture has a canonical URL that the userscript supports', () => {
  const pages = [
    ['article-page.html', 'ARTICLE'],
    ['answer-page.html', 'ANSWER'],
    ['pin-page.html', 'PIN'],
    ['video-page.html', 'VIDEO']
  ];

  for (const [file, expectedType] of pages) {
    const html = fixtureText(file);
    const match = /<link rel="canonical" href="([^"]+)"/.exec(html);
    assert.ok(match, `${file} should declare a canonical URL`);
    const page = core.detectPage(match[1]);
    assert.equal(page.supported, true, `${file} canonical URL should be supported`);
    assert.equal(page.type, expectedType);
  }
});

test('the comment page fixture points at the article that owns the comment fixtures', () => {
  const html = fixtureText('comments-page.html');
  const match = /<link rel="canonical" href="([^"]+)"/.exec(html);
  const page = core.detectPage(match[1]);
  assert.equal(page.type, 'ARTICLE');
  assert.equal(page.id, '1010101');
  assert.match(html, /data-comment-id="9001"/);
});

test('source fixtures are fabricated and contain the documented API fields', () => {
  const article = fixture('article.json');
  assert.equal(article.id, 1010101);
  assert.equal(article.author.name, 'Fixture Author');
  assert.equal(typeof article.content, 'string');
  assert.match(article.content, /fixture-article\.jpg/);

  const answer = fixture('answer.json');
  assert.equal(answer.id, 2020202);
  assert.equal(answer.question.id, 3030303);

  const pin = fixture('pin.json');
  assert.equal(pin.id, 4040404);
  assert.ok(Array.isArray(pin.content));

  const video = fixture('video.json');
  assert.equal(video.id, 5050505);
  assert.equal(typeof video.video.playlist.hd.url, 'string');
  assert.equal(video.video.playlist.sd.url.includes('.mp4'), true);

  for (const page of [article, answer, pin, video]) {
    assert.match(JSON.stringify(page), /Fixture/);
  }
});

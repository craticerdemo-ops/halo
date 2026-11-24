'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

require('../zhihu-archive.user.js');

const core = globalThis.__haloZhihuArchive;
assert.ok(core, 'userscript should expose globalThis.__haloZhihuArchive under Node');

const FIXTURES = path.join(__dirname, 'fixtures');

function fixture(name) {
  return JSON.parse(fs.readFileSync(path.join(FIXTURES, name), 'utf8'));
}

function fixtureText(name) {
  return fs.readFileSync(path.join(FIXTURES, name), 'utf8');
}

function articlePage() {
  return core.detectPage('https://zhuanlan.zhihu.com/p/1010101');
}

function commentSelection(options = {}) {
  const rootPages = [
    fixture('comments-roots-page-1.json'),
    fixture('comments-roots-page-2.json'),
    fixture('comments-roots-page-3.json'),
    fixture('comments-roots-page-4.json')
  ];
  const repliesByRoot = options.repliesByRoot || {
    9001: [fixture('comments-replies-9001.json')],
    9002: [fixture('comments-replies-9002-nested.json')]
  };
  return core.selectComments({ rootPages, repliesByRoot, total: 30 });
}

module.exports = {
  core,
  fixture,
  fixtureText,
  articlePage,
  commentSelection,
  FIXTURES
};

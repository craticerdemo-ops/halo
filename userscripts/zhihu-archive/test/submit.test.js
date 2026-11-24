'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { core, fixture } = require('./helpers.js');

const SETTINGS = {
  haloBaseUrl: 'https://blog.example.com/',
  pat: 'pat-fixture-not-a-real-token',
  policyName: 'zhihu-archive-policy',
  visibility: 'PUBLIC'
};

function validPayload() {
  return core.buildPayload({
    page: core.detectPage('https://zhuanlan.zhihu.com/p/1010101'),
    data: fixture('article.json'),
    comments: { total: 0, roots: [] },
    capturedAt: '2026-01-01T00:00:00.000Z'
  });
}

function clone(payload) {
  return JSON.parse(JSON.stringify(payload));
}

function response(status, body) {
  return {
    status,
    responseText: typeof body === 'string' ? body : JSON.stringify(body)
  };
}

test('posts the exact envelope to the console endpoint with bearer auth and no retry', async () => {
  const calls = [];
  const result = await core.submitPayload(validPayload(), SETTINGS, {
    request: async (options) => {
      calls.push(options);
      return response(200, {
        metadata: { name: 'zhihu-archive-fixture' },
        status: { permalink: '/archives/zhihu-archive-fixture' }
      });
    }
  });

  assert.equal(calls.length, 1);
  assert.equal(calls[0].method, 'POST');
  assert.equal(calls[0].url, 'https://blog.example.com/apis/api.console.halo.run/v1alpha1/zhihu-archives');
  assert.equal(calls[0].headers.Authorization, 'Bearer pat-fixture-not-a-real-token');
  assert.equal(calls[0].headers['Content-Type'], 'application/json');
  const body = JSON.parse(calls[0].data);
  assert.deepEqual(Object.keys(body).sort(), ['payload', 'policyName', 'visible']);
  assert.equal(body.policyName, 'zhihu-archive-policy');
  assert.equal(body.visible, 'PUBLIC');
  assert.equal(body.payload.schemaVersion, 1);
  assert.equal(result.name, 'zhihu-archive-fixture');
  assert.equal(result.permalink, '/archives/zhihu-archive-fixture');
  assert.equal(result.page.metadata.name, 'zhihu-archive-fixture');
  assert.equal(JSON.stringify(calls[0]).includes('Cookie'), false);
});

test('maps Halo 401, 403, and 400 responses to explicit PAT/payload errors without retrying', async () => {
  const cases = [
    [401, { message: 'unauthorized' }, 'PAT_UNAUTHORIZED'],
    [403, { message: 'forbidden' }, 'PAT_FORBIDDEN'],
    [400, { message: 'title must not be blank' }, 'PAYLOAD_INVALID'],
    [422, { message: 'invalid' }, 'PAYLOAD_INVALID'],
    [404, { message: 'not found' }, 'ENDPOINT_NOT_FOUND'],
    [500, { message: 'boom' }, 'SERVER_ERROR']
  ];

  for (const [status, body, expectedCode] of cases) {
    let calls = 0;
    await assert.rejects(
      core.submitPayload(validPayload(), SETTINGS, {
        request: async () => {
          calls++;
          return response(status, body);
        }
      }),
      (error) => error.code === expectedCode && error.detail.step === 'submit'
    );
    assert.equal(calls, 1);
  }
});

test('includes the attempted URL and response snippet when the endpoint is missing', async () => {
  await assert.rejects(
    core.submitPayload(validPayload(), SETTINGS, {
      request: async () => response(404, '<html><body>404 page not found</body></html>')
    }),
    (error) => {
      assert.equal(error.code, 'ENDPOINT_NOT_FOUND');
      assert.match(
        error.message,
        /POST https:\/\/blog\.example\.com\/apis\/api\.console\.halo\.run\/v1alpha1\/zhihu-archives/
      );
      assert.match(error.message, /404 page not found/);
      return true;
    }
  );
});

test('maps a rejected network request to NETWORK_ERROR and never retries', async () => {
  let calls = 0;
  await assert.rejects(
    core.submitPayload(validPayload(), SETTINGS, {
      request: async () => {
        calls++;
        throw new Error('socket hang up');
      }
    }),
    (error) => error.code === 'NETWORK_ERROR' && /No data was submitted/.test(error.message)
  );
  assert.equal(calls, 1);
});

test('refuses a payload that exceeds a backend field limit before calling Halo', () => {
  const payload = clone(validPayload());
  payload.article.html = 'x'.repeat(2000001);
  let called = false;

  assert.throws(
    () =>
      core.submitPayload(payload, SETTINGS, {
        request: async () => {
          called = true;
          return response(200, {});
        }
      }),
    (error) => error.code === 'LIMIT_EXCEEDED' && error.detail.step === 'submit:article.html'
  );
  assert.equal(called, false);
});

test('refuses a serialized body larger than the Halo codec limit before calling Halo', () => {
  const payload = clone(validPayload());
  payload.article.html = 'a'.repeat(2000000);
  payload.article.title = 'Large but valid';
  payload.comments = {
    total: 1,
    roots: [
      {
        id: '1',
        author: { name: 'Fixture', profileUrl: null },
        contentHtml: 'c'.repeat(20000),
        likeCount: 1,
        createdAt: null,
        ipLocation: null,
        badge: null,
        collapsed: null,
        replyToAuthor: null,
        replies: Array.from({ length: 500 }, (_, index) => ({
          id: String(index),
          author: { name: 'Reply', profileUrl: null },
          contentHtml: 'r'.repeat(20000),
          likeCount: 0,
          createdAt: null,
          ipLocation: null,
          badge: null,
          collapsed: null,
          replyToAuthor: null,
          replies: []
        }))
      }
    ]
  };
  let called = false;

  assert.throws(
    () =>
      core.submitPayload(payload, SETTINGS, {
        request: async () => {
          called = true;
          return response(200, {});
        }
      }),
    (error) =>
      error.code === 'LIMIT_EXCEEDED' &&
      error.detail.step === 'submit:body-size' &&
      error.detail.limit === 10 * 1024 * 1024
  );
  assert.equal(called, false);
});

test('refuses a schema version mismatch instead of adapting unknown shapes', () => {
  const payload = clone(validPayload());
  payload.schemaVersion = 2;

  assert.throws(
    () => core.submitPayload(payload, SETTINGS, { request: async () => response(200, {}) }),
    (error) => error.code === 'SCHEMA_MISMATCH'
  );
});

test('requires base URL, PAT, and policy name without writing the PAT anywhere', () => {
  const variants = [
    {},
    { haloBaseUrl: 'https://blog.example.com', pat: '', policyName: 'p' },
    { haloBaseUrl: '', pat: 'secret', policyName: 'p' },
    { haloBaseUrl: 'ftp://blog.example.com', pat: 'secret', policyName: 'p' },
    { haloBaseUrl: 'https://blog.example.com', pat: 'secret', policyName: '' },
    { haloBaseUrl: 'https://blog.example.com', pat: 'secret', policyName: 'p', visibility: 'INTERNAL' }
  ];
  for (const settings of variants) {
    assert.throws(
      () => core.submitPayload(validPayload(), settings, { request: async () => response(200, {}) }),
      (error) => error.code === 'SETTINGS_INVALID'
    );
  }
});

test('rejects a non-JSON or nameless success response', async () => {
  await assert.rejects(
    core.submitPayload(validPayload(), SETTINGS, {
      request: async () => ({ status: 200, responseText: 'not json' })
    }),
    (error) => error.code === 'UNEXPECTED_SHAPE' && error.detail.step === 'submit:response'
  );

  await assert.rejects(
    core.submitPayload(validPayload(), SETTINGS, {
      request: async () => response(200, { metadata: {}, status: { permalink: '/x' } })
    }),
    (error) => error.code === 'UNEXPECTED_SHAPE' && error.detail.step === 'submit:response.metadata.name'
  );
});

test('mapError handles Zhihu login-wall, signature, and risk-control codes', () => {
  assert.equal(
    core.mapError({ context: 'capture', status: 403, body: { error: { code: 10003 } } }).code,
    'ZHIHU_SIGNATURE_REQUIRED'
  );
  assert.equal(
    core.mapError({ context: 'capture', status: 403, body: { error: { code: 40353 } } }).code,
    'ZHIHU_LOGIN_REQUIRED'
  );
  assert.equal(
    core.mapError({ context: 'capture', status: 403, body: { error: { code: 40362 } } }).code,
    'ZHIHU_RISK_CONTROL'
  );
  assert.equal(core.mapError({ context: 'capture', status: 401, body: { message: 'login' } }).code, 'ZHIHU_LOGIN_REQUIRED');
  assert.equal(core.mapError({ context: 'capture', status: 429, body: {} }).code, 'ZHIHU_RATE_LIMITED');
  assert.equal(core.mapError({ context: 'capture', status: 403, body: {} }).code, 'ZHIHU_FORBIDDEN');
  assert.equal(core.mapError({ networkError: 'offline' }).code, 'NETWORK_ERROR');
  assert.equal(core.mapError({ context: 'submit', status: 401, body: {} }).code, 'PAT_UNAUTHORIZED');
  assert.equal(core.mapError({ context: 'submit', status: 400, body: { message: 'bad' } }).code, 'PAYLOAD_INVALID');
  assert.equal(core.mapError({ context: 'submit', status: 403, body: {} }).code, 'PAT_FORBIDDEN');
});

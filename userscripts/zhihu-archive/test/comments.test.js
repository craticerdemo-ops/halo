'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { core, fixture, commentSelection } = require('./helpers.js');

const ROOT_PAGES = [
  fixture('comments-roots-page-1.json'),
  fixture('comments-roots-page-2.json'),
  fixture('comments-roots-page-3.json'),
  fixture('comments-roots-page-4.json')
];

test('selects the top 10 roots by like count across at most three pages', () => {
  const selection = commentSelection();
  const ids = selection.comments.roots.map((root) => root.id);

  assert.deepEqual(ids, ['9021', '9013', '9014', '9001', '9002', '9003', '9015', '9004', '9016', '9005']);
  assert.equal(selection.meta.rootPagesUsed, 3);
  assert.equal(selection.meta.candidates, 23);
  assert.equal(selection.meta.selectedRoots, 10);
  assert.equal(selection.meta.omittedRoots, 13);
  assert.equal(selection.meta.truncatedRoots, true);
  assert.equal(ids.includes('9100'), false, 'the fourth page must be ignored');
});

test('breaks equal like counts by ascending id', () => {
  const selection = commentSelection();
  const tied = selection.comments.roots.filter((root) => root.likeCount === 100).map((root) => root.id);
  assert.deepEqual(tied, ['9001', '9002', '9003']);
});

test('reads the comment total from paging when no explicit total is supplied', () => {
  const selection = core.selectComments({ rootPages: ROOT_PAGES, repliesByRoot: {} });
  assert.equal(selection.comments.total, 30);
});

test('stops root pagination once enough candidates are loaded', () => {
  const synthetic = (pageNumber) => ({
    data: Array.from({ length: 20 }, (_, index) => ({
      id: pageNumber * 1000 + index,
      content: '<p>root</p>',
      like_count: 1000 - index,
      author: { name: 'Author ' + pageNumber + '-' + index }
    })),
    paging: { is_end: false, totals: 60 }
  });

  const selection = core.selectComments({
    rootPages: [synthetic(1), synthetic(2), synthetic(3)],
    repliesByRoot: {}
  });

  assert.equal(selection.meta.rootPagesUsed, 2);
  assert.equal(selection.meta.candidates, 40);
  assert.equal(selection.comments.roots.length, 10);
});

test('caps replies at 50 per root and reports truncation', () => {
  const selection = commentSelection();
  const root = selection.comments.roots.find((entry) => entry.id === '9001');

  assert.equal(root.replies.length, 50);
  assert.equal(root.replies[0].id, '8001');
  assert.equal(root.replies[49].id, '8050');
  assert.equal(selection.meta.replies['9001'].kept, 50);
  assert.equal(selection.meta.truncatedReplies, true);
  assert.equal(selection.meta.warnings.some((warning) => /9001.*truncated at 50/.test(warning)), true);
});

test('follows child pagination and keeps only the first 50 replies overall', () => {
  const makeReply = (id) => ({
    id,
    content: '<p>reply</p>',
    like_count: 1,
    author: { name: 'Reply Author ' + id }
  });
  const pageOne = {
    data: Array.from({ length: 30 }, (_, index) => makeReply(7000 + index)),
    paging: { is_end: false, next: 'https://www.zhihu.com/api/v4/comment_v5/comment/9001/child_comment?order_by=score&offset=30' }
  };
  const pageTwo = {
    data: Array.from({ length: 30 }, (_, index) => makeReply(7030 + index)),
    paging: { is_end: true }
  };

  const selection = core.selectComments({
    rootPages: [fixture('comments-roots-page-1.json')],
    repliesByRoot: { 9001: [pageOne, pageTwo] }
  });
  const root = selection.comments.roots.find((entry) => entry.id === '9001');

  assert.equal(root.replies.length, 50);
  assert.equal(root.replies[0].id, '7000');
  assert.equal(root.replies[49].id, '7049');
});

test('flattens reply-to-reply and records replyToAuthor', () => {
  const selection = commentSelection();
  const root = selection.comments.roots.find((entry) => entry.id === '9002');
  const replies = root.replies.map((reply) => ({ id: reply.id, replyToAuthor: reply.replyToAuthor }));

  assert.deepEqual(replies, [
    { id: '8101', replyToAuthor: null },
    { id: '8102', replyToAuthor: 'Commenter 8101' },
    { id: '8103', replyToAuthor: 'Commenter 8102' },
    { id: '8104', replyToAuthor: null }
  ]);
  assert.equal(root.replies.length, 4, 'a nested reply must be flattened rather than nested');
});

test('preserves IP location, badge, like count, timestamp, and collapsed state', () => {
  const selection = commentSelection();
  const root = selection.comments.roots.find((entry) => entry.id === '9001');
  const first = root.replies[0];
  const collapsed = root.replies.find((entry) => entry.id === '8010');

  assert.equal(first.author.name, 'Commenter 8001');
  assert.equal(first.author.profileUrl, 'https://www.zhihu.com/people/commenter-8001');
  assert.equal(first.ipLocation, 'IP 属地：Replyland');
  assert.equal(first.badge, 'Reply badge 1');
  assert.equal(first.likeCount, 7);
  assert.equal(first.createdAt, '2022-01-12T15:06:41.000Z');
  assert.equal(collapsed.collapsed, true);
  assert.equal(first.collapsed, false);
});

test('rejects a comment entry without HTML or author instead of silently dropping it', () => {
  const rootPage = {
    data: [{ id: 1, content: '', author: { name: 'A' } }],
    paging: { is_end: true, totals: 1 }
  };
  assert.throws(
    () => core.selectComments({ rootPages: [rootPage], repliesByRoot: {} }),
    (error) => error.code === 'UNEXPECTED_SHAPE' && error.detail.step === 'comments:root'
  );

  const authorless = {
    data: [{ id: 1, content: '<p>x</p>' }],
    paging: { is_end: true, totals: 1 }
  };
  assert.throws(
    () => core.selectComments({ rootPages: [authorless], repliesByRoot: {} }),
    (error) => error.code === 'UNEXPECTED_SHAPE'
  );
});

test('normalizes legacy v4 root comments (author.member, vote_count, address_text, reply_to_author)', () => {
  const selection = core.selectComments({
    rootPages: [fixture('comments-legacy-roots.json')],
    repliesByRoot: {},
    total: 123
  });

  assert.equal(selection.comments.total, 123);
  assert.deepEqual(
    selection.comments.roots.map((root) => root.id),
    ['5001', '5002', '5003']
  );

  const [first, second, third] = selection.comments.roots;
  assert.equal(first.author.name, 'Legacy 5001');
  assert.equal(first.author.profileUrl, 'https://www.zhihu.com/people/legacy-5001');
  assert.equal(first.likeCount, 12);
  assert.equal(first.createdAt, '2023-11-14T22:13:21.000Z');
  assert.equal(first.ipLocation, 'Shanghai');
  assert.equal(first.childCommentCount, 2);
  assert.equal(second.replyToAuthor, 'Someone Else');
  assert.equal(second.collapsed, true);
  assert.equal(third.childCommentCount, 0);
});

test('flattens legacy v4 child_comments and keeps legacy reply attribution', () => {
  const selection = core.selectComments({
    rootPages: [fixture('comments-legacy-roots.json')],
    repliesByRoot: { 5001: [fixture('comments-legacy-replies-5001.json')] }
  });

  const root = selection.comments.roots.find((entry) => entry.id === '5001');
  assert.deepEqual(
    root.replies.map((reply) => reply.id),
    ['6001', '6002', '6003']
  );
  assert.equal(root.replies[0].author.name, 'Replier 6001');
  assert.equal(root.replies[0].replyToAuthor, 'Legacy 5001');
  assert.equal(root.replies[1].replyToAuthor, 'Replier 6001');
  assert.equal(root.replies[2].author.name, 'Replier 6003');
  assert.equal(root.replies[2].replyToAuthor, 'Replier 6002');
});

test('builds legacy v4 root and child comment URLs without order on child comments', () => {
  const rootUrls = core.legacyRootCommentUrls('answers', '2102047954286781679', 20);
  assert.equal(
    rootUrls[0],
    'https://www.zhihu.com/api/v4/answers/2102047954286781679/comments?order=normal&limit=20&offset=20&status=open'
  );
  assert.equal(
    rootUrls[1],
    'https://api.zhihu.com/answers/2102047954286781679/comments?order=normal&limit=20&offset=20&status=open'
  );
  assert.equal(
    core.legacyRootCommentUrls('articles', '1010101', 0)[0],
    'https://www.zhihu.com/api/v4/articles/1010101/comments?order=normal&limit=20&offset=0&status=open'
  );

  const childUrls = core.legacyChildCommentUrls('5001', 20);
  assert.equal(childUrls[0], 'https://api.zhihu.com/comments/5001/child_comments?limit=20&offset=20');
  assert.equal(
    childUrls[1],
    'https://www.zhihu.com/api/v4/comments/5001/child_comments?limit=20&offset=20'
  );
  assert.equal(childUrls[0].includes('order='), false, 'legacy child_comments must not send order');
});

test('decides when an empty v5 root page must be retried against the legacy API', () => {
  assert.equal(core.countCommentEntries([{ data: [{ id: 1 }] }, { data: [{ id: 2 }] }]), 2);
  assert.equal(core.countCommentEntries([]), 0);

  assert.equal(core.shouldUseLegacyComments([], 123), true);
  assert.equal(core.shouldUseLegacyComments([{ data: [] }], 123), true);
  assert.equal(core.shouldUseLegacyComments([{ data: [{ id: 1 }] }], 123), false);
  assert.equal(core.shouldUseLegacyComments([], 0), false);
  assert.equal(core.shouldUseLegacyComments([], null), false);
});

// ==UserScript==
// @name         Halo Zhihu Archive
// @namespace    https://github.com/halo-dev/halo
// @version      1.0.5
// @description  Capture a Zhihu article, answer, pin, or video in your logged-in browser and submit a versioned archive payload to Halo.
// @author       Halo
// @match        https://zhuanlan.zhihu.com/p/*
// @match        https://www.zhihu.com/question/*/answer/*
// @match        https://www.zhihu.com/pin/*
// @match        https://www.zhihu.com/zvideo/*
// @grant        GM_getValue
// @grant        GM_setValue
// @grant        GM_xmlhttpRequest
// @grant        unsafeWindow
// @connect      *
// @run-at       document-idle
// ==/UserScript==

(function () {
  'use strict';

  var SCRIPT_VERSION = '1.0.5';
  var SCHEMA_VERSION = 1;
  var SETTINGS_PREFIX = 'haloZhihuArchive';
  var PANEL_ID = 'halo-zhihu-archive-panel';

  // Copied verbatim from application/src/main/java/run/halo/app/zhihu/ZhihuArchiveLimits.java
  var LIMITS = {
    MAX_ARTICLE_HTML_LENGTH: 2000000,
    MAX_COMMENT_HTML_LENGTH: 20000,
    MAX_ROOT_COMMENTS: 200,
    MAX_REPLIES_PER_ROOT: 500,
    MAX_ASSETS: 1000,
    MAX_TITLE_LENGTH: 500,
    MAX_AUTHOR_NAME_LENGTH: 200,
    MAX_URL_LENGTH: 2048,
    MAX_POLICY_NAME_LENGTH: 253,
    RENDER_ROOT_COMMENTS: 10,
    RENDER_REPLIES_PER_ROOT: 50
  };

  // Halo's WebFlux codec limit (application.yaml: spring.codec.max-in-memory-size: 10MB).
  var MAX_REQUEST_BYTES = 10 * 1024 * 1024;
  var ROOT_PAGE_SIZE = 20;
  var MAX_ROOT_PAGES = 3;
  var ENOUGH_ROOT_CANDIDATES = 30;
  var REPLY_PAGE_SIZE = 50;
  var REPLY_CAP = LIMITS.RENDER_REPLIES_PER_ROOT;
  var MAX_REPLY_PAGES_PER_ROOT = 10;
  // The legacy v4 comment list caps `limit` at 20 server-side and ignores `order=by_vote`
  // (only `normal` = oldest first and `reverse` = newest first are honoured).
  var LEGACY_PAGE_SIZE = 20;
  var MAX_LEGACY_ROOT_PAGES = 3;
  var COMMENT_RESOURCE = {
    ARTICLE: 'articles',
    ANSWER: 'answers',
    PIN: 'pins',
    VIDEO: null
  };

  // ---------------------------------------------------------------------------
  // Errors and error mapping
  // ---------------------------------------------------------------------------

  function UserscriptError(code, message, detail) {
    this.name = 'UserscriptError';
    this.code = code;
    this.message = message;
    this.detail = detail || {};
    if (typeof Error.captureStackTrace === 'function') {
      Error.captureStackTrace(this, UserscriptError);
    } else {
      this.stack = new Error(message).stack;
    }
  }
  UserscriptError.prototype = Object.create(Error.prototype);
  UserscriptError.prototype.constructor = UserscriptError;

  function parseBody(body) {
    if (body == null) {
      return null;
    }
    if (typeof body === 'object') {
      return body;
    }
    if (typeof body !== 'string') {
      return null;
    }
    var text = body.trim();
    if (!text) {
      return null;
    }
    try {
      return JSON.parse(text);
    } catch (e) {
      return { message: text };
    }
  }

  function bodyCode(parsed) {
    if (!parsed || typeof parsed !== 'object') {
      return null;
    }
    if (parsed.error && typeof parsed.error === 'object' && parsed.error.code != null) {
      return Number(parsed.error.code);
    }
    if (parsed.error_code != null) {
      return Number(parsed.error_code);
    }
    if (parsed.code != null && !isNaN(Number(parsed.code))) {
      return Number(parsed.code);
    }
    return null;
  }

  function bodyMessage(parsed) {
    if (!parsed) {
      return '';
    }
    if (typeof parsed === 'string') {
      return parsed;
    }
    var candidates = [
      parsed.message,
      parsed.msg,
      parsed.error && parsed.error.message,
      parsed.error && parsed.error.msg,
      parsed.detail
    ];
    for (var i = 0; i < candidates.length; i++) {
      if (typeof candidates[i] === 'string' && candidates[i].trim()) {
        return candidates[i].trim();
      }
    }
    return '';
  }

  /**
   * Maps a capture or submission failure to a stable, user-facing error object.
   * Never retries and never infers success.
   */
  function snippetOf(value) {
    var text = String(value == null ? '' : value)
      .replace(/<[^>]*>/g, ' ')
      .replace(/\s+/g, ' ')
      .trim();
    if (!text) {
      return '';
    }
    return text.length > 160 ? text.slice(0, 160) + '…' : text;
  }

  function requestContext(input, url, upstreamMessage) {
    var parts = [];
    if (url) {
      parts.push('Request: ' + (input.method || 'POST') + ' ' + url);
    }
    var snippet = upstreamMessage || snippetOf(input.body);
    if (snippet) {
      parts.push('Response: ' + snippet);
    }
    return parts.length ? ' (' + parts.join('; ') + ')' : '';
  }

  function mapError(input) {
    input = input || {};
    var status = Number(input.status || 0);
    var parsed = parseBody(input.body);
    var code = bodyCode(parsed);
    var message = bodyMessage(parsed);
    var step = input.step || (input.context === 'submit' ? 'submit' : 'capture');
    var context = input.context || (step === 'submit' ? 'submit' : 'capture');
    var diagnostics = requestContext(input, input.url, message);

    if (input.networkError || status === 0) {
      return {
        code: 'NETWORK_ERROR',
        message:
          'Network request failed (' +
          step +
          '). Check your connection and that the Halo base URL is reachable. No data was submitted.' +
          (input.url ? ' Request: ' + (input.method || 'POST') + ' ' + input.url : ''),
        detail: { step: step, status: status, cause: input.networkError || null, url: input.url || null }
      };
    }

    // Zhihu risk-control / auth codes take precedence wherever they appear.
    if (code === 10003) {
      return {
        code: 'ZHIHU_SIGNATURE_REQUIRED',
        message:
          'Zhihu rejected the request with code 10003: this API requires a signed request (x-zse-96). ' +
          'Reload the Zhihu page and retry; if it persists, capture from a normal logged-in browser tab. No data was submitted.',
        detail: { step: step, status: status, zhihuCode: code, upstreamMessage: message }
      };
    }
    if (code === 40353) {
      return {
        code: 'ZHIHU_LOGIN_REQUIRED',
        message:
          'Zhihu returned code 40353 (login wall). Sign in to Zhihu in this browser tab, reload the page, then retry. No data was submitted.',
        detail: { step: step, status: status, zhihuCode: code, upstreamMessage: message }
      };
    }
    if (code === 40362) {
      return {
        code: 'ZHIHU_RISK_CONTROL',
        message:
          'Zhihu returned code 40362 (risk control). Stop probing, wait a while, and retry later. No data was submitted.',
        detail: { step: step, status: status, zhihuCode: code, upstreamMessage: message }
      };
    }

    if (context === 'submit') {
      if (status === 401) {
        return {
          code: 'PAT_UNAUTHORIZED',
          message:
            'Halo rejected the Personal Access Token (401). Create a new PAT with system:zhihu-archives:manage and save it again. No partial data was stored.' +
            diagnostics,
          detail: { step: step, status: status, upstreamMessage: message }
        };
      }
      if (status === 403) {
        return {
          code: 'PAT_FORBIDDEN',
          message:
            'Halo rejected the request (403). The PAT is missing the system:zhihu-archives:manage permission. No partial data was stored.' +
            diagnostics,
          detail: { step: step, status: status, upstreamMessage: message }
        };
      }
      if (status === 400 || status === 422) {
        return {
          code: 'PAYLOAD_INVALID',
          message:
            'Halo rejected the payload (' +
            status +
            '): ' +
            (message || 'validation failed') +
            '. This usually means the userscript schema version does not match the Halo server. Nothing was stored.' +
            diagnostics,
          detail: { step: step, status: status, upstreamMessage: message }
        };
      }
      if (status === 404) {
        return {
          code: 'ENDPOINT_NOT_FOUND',
          message:
            'Halo returned 404 for the archive endpoint. Check that the Halo base URL is correct and that the server supports the Zhihu archive feature.' +
            diagnostics,
          detail: { step: step, status: status, upstreamMessage: message }
        };
      }
      if (status >= 500) {
        return {
          code: 'SERVER_ERROR',
          message:
            'Halo returned a server error (' + status + '). Nothing was stored; retry after checking the Halo logs.' +
            diagnostics,
          detail: { step: step, status: status, upstreamMessage: message }
        };
      }
    } else {
      if (status === 401) {
        return {
          code: 'ZHIHU_LOGIN_REQUIRED',
          message:
            'Zhihu returned 401 (login wall). Sign in to Zhihu, reload the page, then retry. No data was submitted.',
          detail: { step: step, status: status, upstreamMessage: message }
        };
      }
      if (status === 429) {
        return {
          code: 'ZHIHU_RATE_LIMITED',
          message:
            'Zhihu rate-limited the capture (429). Wait before retrying; the userscript does not retry automatically.',
          detail: { step: step, status: status, upstreamMessage: message }
        };
      }
      if (status === 403) {
        return {
          code: 'ZHIHU_FORBIDDEN',
          message:
            'Zhihu refused the request (403). This is usually a login wall, a missing signature, or risk control. ' +
            (message ? 'Upstream said: ' + message + '. ' : '') +
            'No data was submitted.',
          detail: { step: step, status: status, upstreamMessage: message }
        };
      }
      if (status >= 400) {
        return {
          code: 'ZHIHU_REQUEST_FAILED',
          message:
            'Zhihu request failed (' + status + ') during ' + step + '. No data was submitted.',
          detail: { step: step, status: status, upstreamMessage: message }
        };
      }
    }

    return {
      code: 'UNKNOWN_ERROR',
      message:
        'Unexpected failure during ' + step + (message ? ': ' + message : '') + '. No data was submitted.',
      detail: { step: step, status: status, upstreamMessage: message }
    };
  }

  function toError(mapped) {
    return new UserscriptError(mapped.code, mapped.message, mapped.detail);
  }

  // ---------------------------------------------------------------------------
  // URL detection
  // ---------------------------------------------------------------------------

  function digits(value) {
    return typeof value === 'string' && /^[1-9][0-9]*$/.test(value);
  }

  function unsupported(reason) {
    return { supported: false, type: null, id: null, url: null, apiUrl: null, commentResource: null, reason: reason };
  }

  /**
   * Detects a supported Zhihu page from its URL.
   */
  function detectPage(inputUrl) {
    var parsed;
    try {
      parsed = new URL(String(inputUrl || ''));
    } catch (e) {
      return unsupported('The current URL could not be parsed.');
    }
    if (parsed.protocol !== 'https:') {
      return unsupported('Only HTTPS Zhihu URLs are supported.');
    }
    if (parsed.username || parsed.password) {
      return unsupported('URLs containing credentials are not supported.');
    }
    var host = parsed.hostname.toLowerCase();
    var parts = parsed.pathname.split('/').filter(function (part) {
      return part.length > 0;
    });
    var type = null;
    var id = null;

    if (host === 'zhuanlan.zhihu.com' && parts.length === 2 && parts[0] === 'p' && digits(parts[1])) {
      type = 'ARTICLE';
      id = parts[1];
    } else if (
      host === 'www.zhihu.com' &&
      parts.length === 4 &&
      parts[0] === 'question' &&
      digits(parts[1]) &&
      parts[2] === 'answer' &&
      digits(parts[3])
    ) {
      type = 'ANSWER';
      id = parts[3];
    } else if (host === 'www.zhihu.com' && parts.length === 2 && parts[0] === 'pin' && digits(parts[1])) {
      type = 'PIN';
      id = parts[1];
    } else if (host === 'www.zhihu.com' && parts.length === 2 && parts[0] === 'zvideo' && digits(parts[1])) {
      type = 'VIDEO';
      id = parts[1];
    }

    if (!type) {
      return unsupported(
        'Unsupported page. Open a Zhihu article (/p/), answer (/question/.../answer/...), pin (/pin/), or video (/zvideo/).'
      );
    }

    var canonical = new URL(parsed.href);
    canonical.hash = '';
    var apiPaths = {
      ARTICLE: '/api/v4/articles/',
      ANSWER: '/api/v4/answers/',
      PIN: '/api/v4/pins/',
      VIDEO: '/api/v4/zvideos/'
    };
    var apiIncludes = {
      ANSWER: '?include=content,excerpt,created_time,updated_time,voteup_count,comment_count,author,question'
    };
    return {
      supported: true,
      type: type,
      id: id,
      url: canonical.href,
      apiUrl: 'https://www.zhihu.com' + apiPaths[type] + id + (apiIncludes[type] || ''),
      commentResource: COMMENT_RESOURCE[type],
      reason: null
    };
  }

  // ---------------------------------------------------------------------------
  // HTML helpers (defence in depth; Halo sanitizes again server-side)
  // ---------------------------------------------------------------------------

  function normalizeImageTags(html) {
    return html.replace(/<img\b([^>]*)>/gi, function (match, attrs) {
      var real = attribute(attrs, 'data-original') || attribute(attrs, 'data-actualsrc');
      if (!real) {
        return match;
      }
      var rest = attrs
        .replace(/\s(?:data-original|data-actualsrc)\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)/gi, '')
        .replace(/\bsrc\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)/i, '')
        .trim();
      return '<img src="' + escapeHtml(real) + '"' + (rest ? ' ' + rest : '') + '>';
    });
  }

  function sanitizeHtml(html) {
    if (typeof html !== 'string') {
      return '';
    }
    var out = html;
    out = out.replace(/<!--[\s\S]*?-->/g, '');
    out = out.replace(
      /<\s*(script|style|iframe|object|embed|form|link|meta|base|noscript)\b[^>]*>[\s\S]*?<\s*\/\s*\1\s*>/gi,
      ''
    );
    out = out.replace(/<\s*(script|style|iframe|object|embed|form|link|meta|base|noscript)\b[^>]*\/?>/gi, '');
    out = out.replace(/\son[a-z]+\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)/gi, '');
    out = out.replace(/\s(?:href|src|xlink:href)\s*=\s*(["'])\s*javascript:[^"']*\1/gi, '');
    out = out.replace(/\s(?:href|src|xlink:href)\s*=\s*javascript:[^\s>]*/gi, '');
    return normalizeImageTags(out).trim();
  }

  function escapeHtml(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function plainText(html) {
    return String(html == null ? '' : html)
      .replace(/<[^>]*>/g, ' ')
      .replace(/&nbsp;/gi, ' ')
      .replace(/&amp;/gi, '&')
      .replace(/&lt;/gi, '<')
      .replace(/&gt;/gi, '>')
      .replace(/&quot;/gi, '"')
      .replace(/&#39;/gi, "'")
      .replace(/\s+/g, ' ')
      .trim();
  }

  function truncateText(value, max) {
    var text = String(value == null ? '' : value);
    if (text.length <= max) {
      return text;
    }
    return text.slice(0, Math.max(0, max - 1)) + '…';
  }

  function truncateHtml(value, max) {
    var text = String(value == null ? '' : value);
    if (text.length <= max) {
      return text;
    }
    var cut = text.slice(0, Math.max(0, max - 1));
    var lastLt = cut.lastIndexOf('<');
    var lastGt = cut.lastIndexOf('>');
    if (lastLt > lastGt) {
      cut = cut.slice(0, lastLt);
    }
    return cut + '…';
  }

  function attribute(attrs, name) {
    var re = new RegExp('\\b' + name + '\\s*=\\s*(?:"([^"]*)"|\'([^\']*)\'|([^\\s>]+))', 'i');
    var match = re.exec(attrs || '');
    if (!match) {
      return null;
    }
    return match[1] != null ? match[1] : match[2] != null ? match[2] : match[3];
  }

  function normalizeUrl(value) {
    if (!value) {
      return null;
    }
    var url = String(value).trim();
    if (!url || /^data:/i.test(url) || /^blob:/i.test(url)) {
      return null;
    }
    if (url.indexOf('//') === 0) {
      return 'https:' + url;
    }
    return url;
  }

  function assetTypeForUrl(url) {
    if (/\/equation(?:[/?]|$)/i.test(url)) {
      return 'EQUATION';
    }
    return 'IMAGE';
  }

  function collectAssets(html, extraAssets, warnings) {
    var byUrl = Object.create(null);
    var order = [];
    var truncated = false;
    function add(rawUrl, type) {
      var url = normalizeUrl(rawUrl);
      if (!url) {
        return;
      }
      if (!/^https?:\/\//i.test(url)) {
        warnings.push('Skipped a non-HTTP asset URL.');
        return;
      }
      if (url.length > LIMITS.MAX_URL_LENGTH) {
        warnings.push('Skipped an asset URL longer than ' + LIMITS.MAX_URL_LENGTH + ' characters.');
        return;
      }
      if (byUrl[url]) {
        // A more specific type (for example POSTER supplied for a video) wins over IMAGE.
        if (type !== 'IMAGE') {
          byUrl[url].type = type;
        }
        return;
      }
      if (order.length >= LIMITS.MAX_ASSETS) {
        truncated = true;
        return;
      }
      byUrl[url] = { url: url, type: type };
      order.push(url);
    }

    var imgTag = /<img\b([^>]*)>/gi;
    var match;
    while ((match = imgTag.exec(html || '')) !== null) {
      var attrs = match[1];
      var src = attribute(attrs, 'data-original') || attribute(attrs, 'data-actualsrc') || attribute(attrs, 'src');
      add(src, assetTypeForUrl(normalizeUrl(src) || ''));
    }
    (extraAssets || []).forEach(function (asset) {
      add(asset.url, asset.type);
    });

    if (truncated) {
      warnings.push('Asset list was truncated at the backend limit of ' + LIMITS.MAX_ASSETS + ' entries.');
    }
    return order.map(function (url) {
      return byUrl[url];
    });
  }

  // ---------------------------------------------------------------------------
  // Time / numeric helpers
  // ---------------------------------------------------------------------------

  function toIsoTime(value) {
    if (value == null || value === '') {
      return null;
    }
    var date;
    if (typeof value === 'number') {
      date = new Date(value < 1e12 ? value * 1000 : value);
    } else if (typeof value === 'string' && /^[0-9]+$/.test(value.trim())) {
      var numeric = Number(value.trim());
      date = new Date(numeric < 1e12 ? numeric * 1000 : numeric);
    } else {
      date = new Date(value);
    }
    if (isNaN(date.getTime())) {
      return null;
    }
    return date.toISOString();
  }

  function toCount(value) {
    var numeric = Number(value);
    if (!isFinite(numeric) || numeric < 0) {
      return 0;
    }
    return Math.floor(numeric);
  }

  function compareIds(left, right) {
    var a = String(left == null ? '' : left);
    var b = String(right == null ? '' : right);
    if (/^[0-9]+$/.test(a) && /^[0-9]+$/.test(b)) {
      var na = Number(a);
      var nb = Number(b);
      if (na !== nb) {
        return na < nb ? -1 : 1;
      }
    }
    return a < b ? -1 : a > b ? 1 : 0;
  }

  // ---------------------------------------------------------------------------
  // Author / comment normalization
  // ---------------------------------------------------------------------------

  function extractAuthor(rawAuthor, step) {
    var author = rawAuthor;
    if (!author && step) {
      throw new UserscriptError('UNEXPECTED_SHAPE', 'Zhihu response is missing the author for ' + step + '.', {
        step: step
      });
    }
    if (!author || typeof author !== 'object') {
      throw new UserscriptError('UNEXPECTED_SHAPE', 'Zhihu returned an unexpected author shape for ' + step + '.', {
        step: step
      });
    }
    if (author.member && typeof author.member === 'object') {
      author = author.member;
    }
    var name = author.name || author.nickname || author.display_name;
    if (!name || !String(name).trim()) {
      throw new UserscriptError('UNEXPECTED_SHAPE', 'Zhihu returned an author without a display name for ' + step + '.', {
        step: step
      });
    }
    var profileUrl = normalizeUrl(author.url || author.profile_url || author.profileUrl);
    if (!profileUrl && author.url_token) {
      profileUrl = 'https://www.zhihu.com/people/' + encodeURIComponent(String(author.url_token));
    }
    if (profileUrl && !/^https?:\/\//i.test(profileUrl)) {
      profileUrl = null;
    }
    return { name: String(name).trim(), profileUrl: profileUrl };
  }

  function extractBadge(raw) {
    if (!raw) {
      return null;
    }
    if (typeof raw.badge === 'string' && raw.badge.trim()) {
      return raw.badge.trim();
    }
    if (raw.badge && typeof raw.badge === 'object' && typeof raw.badge.text === 'string') {
      return raw.badge.text.trim() || null;
    }
    if (Array.isArray(raw.comment_tag)) {
      var labels = [];
      raw.comment_tag.forEach(function (tag) {
        if (!tag) {
          return;
        }
        var text = typeof tag === 'string' ? tag : tag.text || tag.name;
        if (text && String(text).trim()) {
          labels.push(String(text).trim());
        }
      });
      if (labels.length) {
        return labels.join(' / ');
      }
    }
    if (raw.author && typeof raw.author === 'object') {
      var authorBadge = extractBadge(raw.author);
      if (authorBadge) {
        return authorBadge;
      }
      if (raw.author.member && typeof raw.author.member === 'object') {
        return extractBadge(raw.author.member);
      }
    }
    return null;
  }

  function normalizeComment(raw, step) {
    if (!raw || typeof raw !== 'object') {
      throw new UserscriptError('UNEXPECTED_SHAPE', 'Zhihu returned an unexpected comment entry for ' + step + '.', {
        step: step
      });
    }
    var rawHtml = raw.content_html || raw.contentHtml || raw.content || raw.rich_text || raw.text;
    var html = sanitizeHtml(rawHtml);
    if (!html) {
      throw new UserscriptError('UNEXPECTED_SHAPE', 'Zhihu returned a comment without HTML content for ' + step + '.', {
        step: step
      });
    }
    var id = raw.id != null ? String(raw.id) : raw.comment_id != null ? String(raw.comment_id) : '';
    var replyAuthor = raw.reply_to_author || raw.reply_author;
    var replyTo =
      (replyAuthor && replyAuthor.name) ||
      (replyAuthor && replyAuthor.member && replyAuthor.member.name) ||
      (raw.replyToAuthor && typeof raw.replyToAuthor === 'string' ? raw.replyToAuthor : null) ||
      (replyAuthor && typeof replyAuthor === 'string' ? replyAuthor : null);

    return {
      id: id,
      author: extractAuthor(raw.author, step),
      contentHtml: html,
      likeCount: toCount(
        raw.like_count != null ? raw.like_count : raw.voteup_count != null ? raw.voteup_count : raw.vote_count
      ),
      createdAt: toIsoTime(
        raw.created_time != null
          ? raw.created_time
          : raw.created != null
            ? raw.created
            : raw.created_at
      ),
      ipLocation: raw.ip_location || raw.ipLocation || raw.ip_info || raw.address_text || null,
      badge: extractBadge(raw),
      collapsed:
        raw.collapsed != null
          ? Boolean(raw.collapsed)
          : raw.is_collapsed != null
            ? Boolean(raw.is_collapsed)
            : raw.is_delete === true
              ? true
              : null,
      replyToAuthor: replyTo ? String(replyTo) : null,
      childCommentCount:
        raw.child_comment_count != null
          ? toCount(raw.child_comment_count)
          : raw.childCommentCount != null
            ? toCount(raw.childCommentCount)
            : raw.reply_count != null
              ? toCount(raw.reply_count)
              : raw.replies_count != null
                ? toCount(raw.replies_count)
                : null,
      replies: []
    };
  }

  function flattenReply(raw, parentAuthor, out, step) {
    var normalized = normalizeComment(raw, step);
    if (!normalized.replyToAuthor && parentAuthor) {
      normalized.replyToAuthor = parentAuthor;
    }
    out.push(normalized);
    var nested = Array.isArray(raw.replies) ? raw.replies : Array.isArray(raw.child_comments) ? raw.child_comments : [];
    nested.forEach(function (child) {
      flattenReply(child, normalized.author.name, out, step);
    });
  }

  function repliesFromPages(rootId, pages, warnings) {
    var flat = [];
    var pageCount = Math.min(Array.isArray(pages) ? pages.length : 0, MAX_REPLY_PAGES_PER_ROOT);
    for (var p = 0; p < pageCount && flat.length < REPLY_CAP; p++) {
      var page = pages[p] || {};
      var data = Array.isArray(page.data) ? page.data : [];
      if (!data.length) {
        break;
      }
      for (var i = 0; i < data.length && flat.length < REPLY_CAP; i++) {
        flattenReply(data[i], null, flat, 'replies:' + rootId);
      }
      if (flat.length >= REPLY_CAP) {
        break;
      }
      if (page.paging && page.paging.is_end) {
        break;
      }
    }
    if (flat.length > REPLY_CAP) {
      flat = flat.slice(0, REPLY_CAP);
    }
    if (flat.length === REPLY_CAP) {
      var possibleMore =
        pageCount > 0 && !(pages[pageCount - 1] && pages[pageCount - 1].paging && pages[pageCount - 1].paging.is_end);
      if (possibleMore) {
        warnings.push('Replies for root comment ' + rootId + ' were truncated at ' + REPLY_CAP + '.');
      }
    }
    return flat.slice(0, REPLY_CAP);
  }

  // ---------------------------------------------------------------------------
  // Comment selection (pure: no network)
  // ---------------------------------------------------------------------------

  function firstPagingTotal(pages) {
    for (var i = 0; i < pages.length; i++) {
      if (pages[i] && pages[i].paging && pages[i].paging.totals != null) {
        return toCount(pages[i].paging.totals);
      }
    }
    return null;
  }

  /**
   * Selects top 10 root comments by like count (tie-break by ascending id) from at most
   * three root pages, and flattens up to 50 replies per selected root from the supplied
   * child pages. Purely local; the caller performs the HTTP requests.
   */
  function selectComments(input) {
    input = input || {};
    var rootPages = Array.isArray(input.rootPages) ? input.rootPages : [];
    var repliesByRoot = input.repliesByRoot || {};
    var warnings = [];

    var candidates = [];
    var pagesUsed = 0;
    for (var p = 0; p < rootPages.length && pagesUsed < MAX_ROOT_PAGES; p++) {
      var page = rootPages[p] || {};
      var data = Array.isArray(page.data) ? page.data : [];
      if (!data.length) {
        break;
      }
      pagesUsed++;
      data.forEach(function (raw) {
        candidates.push(normalizeComment(raw, 'comments:root'));
      });
      if (candidates.length >= ENOUGH_ROOT_CANDIDATES) {
        break;
      }
      if (page.paging && page.paging.is_end) {
        break;
      }
    }

    var ranked = candidates
      .map(function (comment, index) {
        return { comment: comment, index: index };
      })
      .sort(function (left, right) {
        if (left.comment.likeCount !== right.comment.likeCount) {
          return right.comment.likeCount - left.comment.likeCount;
        }
        var byId = compareIds(left.comment.id, right.comment.id);
        return byId !== 0 ? byId : left.index - right.index;
      })
      .map(function (entry) {
        return entry.comment;
      });

    var selected = ranked.slice(0, LIMITS.RENDER_ROOT_COMMENTS);
    var replyMeta = {};
    selected.forEach(function (root) {
      var pages = repliesByRoot[root.id] || [];
      if (!Array.isArray(pages)) {
        pages = pages && Array.isArray(pages.data) ? [pages] : [];
      }
      root.replies = repliesFromPages(root.id, pages, warnings);
      replyMeta[root.id] = {
        pages: Array.isArray(pages) ? pages.length : 0,
        kept: root.replies.length,
        truncated: warnings.some(function (warning) {
          return warning.indexOf('root comment ' + root.id) !== -1;
        })
      };
    });

    var totalFromPages = firstPagingTotal(rootPages);
    var total = input.total != null ? toCount(input.total) : totalFromPages != null ? totalFromPages : candidates.length;

    return {
      comments: { total: total, roots: selected },
      meta: {
        rootPagesUsed: pagesUsed,
        candidates: candidates.length,
        selectedRoots: selected.length,
        omittedRoots: Math.max(0, candidates.length - selected.length),
        truncatedRoots: candidates.length > selected.length,
        truncatedReplies: selected.some(function (root) {
          return replyMeta[root.id].truncated;
        }),
        replies: replyMeta,
        warnings: warnings
      }
    };
  }

  // ---------------------------------------------------------------------------
  // Payload building (extraction from fixtures / live API responses)
  // ---------------------------------------------------------------------------

  function requireString(value, step) {
    if (value == null || !String(value).trim()) {
      throw new UserscriptError('UNEXPECTED_SHAPE', 'Zhihu response is missing ' + step + '.', { step: step });
    }
    return String(value).trim();
  }

  function requireHtml(value, step) {
    var html = sanitizeHtml(value);
    if (!html) {
      throw new UserscriptError('UNEXPECTED_SHAPE', 'Zhihu response is missing HTML content for ' + step + '.', {
        step: step
      });
    }
    return html;
  }

  function buildPinHtml(data, step) {
    var explicit = data.content_html || data.contentHtml;
    if (typeof explicit === 'string' && explicit.trim()) {
      return sanitizeHtml(explicit);
    }
    if (typeof data.content === 'string' && data.content.trim()) {
      return sanitizeHtml(data.content);
    }
    if (!Array.isArray(data.content)) {
      return '';
    }
    var parts = [];
    data.content.forEach(function (segment) {
      if (!segment) {
        return;
      }
      var type = segment.type || segment.content_type;
      if (type === 'text' || type === 'TEXT') {
        parts.push(escapeHtml(segment.content || segment.text || '').replace(/\n/g, '<br>'));
      } else if (type === 'image' || type === 'IMAGE') {
        var imageUrl = normalizeUrl(segment.original_url || segment.url);
        if (imageUrl) {
          parts.push('<img src="' + escapeHtml(imageUrl) + '" alt="">');
        }
      } else if (type === 'link' || type === 'LINK') {
        var href = normalizeUrl(segment.url || segment.href);
        if (href) {
          parts.push(
            '<a href="' + escapeHtml(href) + '" target="_blank" rel="noopener noreferrer">' +
              escapeHtml(segment.title || segment.content || href) +
              '</a>'
          );
        }
      }
    });
    return sanitizeHtml(parts.join(''));
  }

  function buildVideoHtml(data, page, warnings) {
    var video = data.video && typeof data.video === 'object' ? data.video : {};
    var poster =
      normalizeUrl(
        video.cover_url ||
          video.thumbnail ||
          data.cover_url ||
          data.cover ||
          data.thumbnail ||
          data.thumbnail_url ||
          data.poster
      ) || null;
    var playlist = video.playlist && typeof video.playlist === 'object' ? video.playlist : {};
    var source =
      normalizeUrl(
        (playlist.hd && playlist.hd.url) ||
          (playlist.sd && playlist.sd.url) ||
          (Array.isArray(video.video_urls) && video.video_urls[0] && video.video_urls[0].url) ||
          video.url ||
          video.video_url ||
          data.video_url ||
          data.play_url
      ) || null;
    var description = plainText(data.description || data.excerpt || '');
    var parts = [];
    if (description) {
      parts.push('<p>' + escapeHtml(description) + '</p>');
    }
    parts.push('<figure class="zhihu-archive-video" data-video-id="' + escapeHtml(page.id) + '">');
    if (poster) {
      parts.push('<img src="' + escapeHtml(poster) + '" alt="">');
    }
    if (source) {
      parts.push(
        '<figcaption><a href="' +
          escapeHtml(source) +
          '" target="_blank" rel="noopener noreferrer">Watch the source video on Zhihu</a></figcaption>'
      );
    } else {
      warnings.push('Video source URL was not present in the Zhihu response; only the poster was captured.');
    }
    parts.push('</figure>');
    return sanitizeHtml(parts.join(''));
  }

  function trimToLimit(value, limit, label, warnings) {
    var text = String(value == null ? '' : value);
    if (text.length <= limit) {
      return text;
    }
    warnings.push(label + ' exceeded ' + limit + ' characters and was truncated.');
    return truncateText(text, limit);
  }

  /**
   * Builds the exact backend payload from a detected page, the matching Zhihu API
   * response, and an already-selected comment object.
   */
  function buildPayload(input) {
    input = input || {};
    var page = input.page;
    var data = input.data;
    if (!page || !page.supported) {
      throw new UserscriptError('UNSUPPORTED_PAGE', 'Cannot build a payload for an unsupported page.', {
        step: 'detect'
      });
    }
    if (!data || typeof data !== 'object') {
      throw new UserscriptError('UNEXPECTED_SHAPE', 'Zhihu source response was empty or not an object.', {
        step: page.type.toLowerCase() + ':response'
      });
    }
    var warnings = Array.isArray(input.warnings) ? input.warnings.slice() : [];
    var step = page.type.toLowerCase();
    var articleHtml;
    var title;
    var publishedAt;
    var likeCount;
    var commentCount;
    var assets = [];

    if (page.type === 'ARTICLE') {
      title = requireString(data.title, step + ':title');
      articleHtml = requireHtml(data.content || data.content_html || data.rich_text || data.excerpt, step + ':html');
      publishedAt = toIsoTime(
        data.created != null ? data.created : data.created_time != null ? data.created_time : data.published_at
      );
      likeCount = toCount(data.voteup_count != null ? data.voteup_count : data.like_count);
      commentCount = toCount(data.comment_count);
    } else if (page.type === 'ANSWER') {
      var question = data.question && typeof data.question === 'object' ? data.question : {};
      title = requireString(question.title || data.question_title || data.title, step + ':question.title');
      articleHtml = requireHtml(data.content || data.content_html || data.rich_text || data.excerpt, step + ':html');
      publishedAt = toIsoTime(
        data.created_time != null ? data.created_time : data.created != null ? data.created : data.published_at
      );
      likeCount = toCount(data.voteup_count != null ? data.voteup_count : data.like_count);
      commentCount = toCount(data.comment_count);
    } else if (page.type === 'PIN') {
      articleHtml = requireHtml(buildPinHtml(data, step), step + ':html');
      title =
        (data.title && String(data.title).trim()) ||
        truncateText(plainText(articleHtml), 120) ||
        '知乎想法 ' + page.id;
      publishedAt = toIsoTime(
        data.created != null ? data.created : data.created_time != null ? data.created_time : data.created_at
      );
      likeCount = toCount(data.like_count != null ? data.like_count : data.voteup_count);
      commentCount = toCount(data.comment_count);
    } else if (page.type === 'VIDEO') {
      title = requireString(data.title, step + ':title');
      articleHtml = requireHtml(buildVideoHtml(data, page, warnings), step + ':html');
      publishedAt = toIsoTime(
        data.created_at != null ? data.created_at : data.created != null ? data.created : data.created_time
      );
      likeCount = toCount(data.voteup_count != null ? data.voteup_count : data.like_count);
      commentCount = toCount(data.comment_count);
      var video = data.video && typeof data.video === 'object' ? data.video : {};
      var playlist = video.playlist && typeof video.playlist === 'object' ? video.playlist : {};
      assets.push({
        url:
          normalizeUrl(
            (playlist.hd && playlist.hd.url) ||
              (playlist.sd && playlist.sd.url) ||
              video.url ||
              video.video_url ||
              data.video_url
          ) || '',
        type: 'VIDEO'
      });
      assets.push({
        url:
          normalizeUrl(
            video.cover_url || video.thumbnail || data.cover_url || data.cover || data.thumbnail || data.thumbnail_url
          ) || '',
        type: 'POSTER'
      });
    } else {
      throw new UserscriptError('UNSUPPORTED_PAGE', 'Unknown source type: ' + page.type, { step: 'detect' });
    }

    var author = extractAuthor(data.author, step + ':author');
    if (author.name.length > LIMITS.MAX_AUTHOR_NAME_LENGTH) {
      warnings.push('Author name exceeded ' + LIMITS.MAX_AUTHOR_NAME_LENGTH + ' characters and was truncated.');
      author.name = truncateText(author.name, LIMITS.MAX_AUTHOR_NAME_LENGTH);
    }
    if (author.profileUrl && author.profileUrl.length > LIMITS.MAX_URL_LENGTH) {
      warnings.push('Author profile URL exceeded ' + LIMITS.MAX_URL_LENGTH + ' characters and was dropped.');
      author.profileUrl = null;
    }

    if (!page.url || page.url.length > LIMITS.MAX_URL_LENGTH) {
      throw new UserscriptError('LIMIT_EXCEEDED', 'Source URL exceeds the backend limit of ' + LIMITS.MAX_URL_LENGTH + '.', {
        step: 'source:url'
      });
    }
    if (page.id.length > 128) {
      throw new UserscriptError('LIMIT_EXCEEDED', 'Source id exceeds the backend limit of 128 characters.', {
        step: 'source:id'
      });
    }
    if (title.length > LIMITS.MAX_TITLE_LENGTH) {
      warnings.push('Title exceeded ' + LIMITS.MAX_TITLE_LENGTH + ' characters and was truncated.');
      title = truncateText(title, LIMITS.MAX_TITLE_LENGTH);
    }
    if (articleHtml.length > LIMITS.MAX_ARTICLE_HTML_LENGTH) {
      throw new UserscriptError(
        'LIMIT_EXCEEDED',
        'Article HTML is ' +
          articleHtml.length +
          ' characters; the backend accepts at most ' +
          LIMITS.MAX_ARTICLE_HTML_LENGTH +
          '. Nothing was submitted.',
        { step: 'article:html', length: articleHtml.length, limit: LIMITS.MAX_ARTICLE_HTML_LENGTH }
      );
    }

    var selected = input.comments;
    if (selected && selected.comments && selected.comments.roots) {
      selected = selected.comments;
    }
    var roots = selected && Array.isArray(selected.roots) ? selected.roots : [];
    var totalComments =
      selected && selected.total != null ? toCount(selected.total) : commentCount;

    roots = roots.slice(0, LIMITS.RENDER_ROOT_COMMENTS).map(function (root) {
      var normalized = normalizeBuiltComment(root, warnings, 'comments:root');
      var replies = (Array.isArray(root.replies) ? root.replies : [])
        .slice(0, REPLY_CAP)
        .map(function (reply) {
          return normalizeBuiltComment(reply, warnings, 'comments:reply');
        });
      normalized.replies = replies;
      return normalized;
    });

    assets = collectAssets(articleHtml, assets.filter(function (asset) {
      return asset.url;
    }), warnings);

    var payload = {
      schemaVersion: SCHEMA_VERSION,
      source: { url: page.url, type: page.type, id: page.id },
      article: {
        title: title,
        author: author,
        publishedAt: publishedAt,
        likeCount: likeCount,
        commentCount: commentCount,
        html: articleHtml
      },
      comments: { total: totalComments, roots: roots },
      capture: {
        capturedAt: toIsoTime(input.capturedAt || new Date().toISOString()),
        clientVersion: truncateText(input.clientVersion || SCRIPT_VERSION, 64)
      },
      assets: { items: assets }
    };
    // Non-enumerable so it never changes the wire contract or key-parity checks.
    Object.defineProperty(payload, 'warnings', { value: warnings, enumerable: false });
    return payload;
  }

  function normalizeBuiltComment(comment, warnings, step) {
    if (!comment || typeof comment !== 'object') {
      throw new UserscriptError('UNEXPECTED_SHAPE', 'Selected comment is not an object: ' + step, { step: step });
    }
    var normalized = JSON.parse(JSON.stringify(comment));
    normalized.author = extractAuthor(normalized.author, step);
    normalized.author.name = trimToLimit(
      normalized.author.name,
      LIMITS.MAX_AUTHOR_NAME_LENGTH,
      'Comment author name',
      warnings
    );
    if (normalized.author.profileUrl && normalized.author.profileUrl.length > LIMITS.MAX_URL_LENGTH) {
      warnings.push('A comment author profile URL exceeded ' + LIMITS.MAX_URL_LENGTH + ' characters and was dropped.');
      normalized.author.profileUrl = null;
    }
    normalized.id = normalized.id == null ? '' : trimToLimit(normalized.id, 128, 'Comment id', warnings);
    normalized.contentHtml = truncateHtml(
      sanitizeHtml(normalized.contentHtml),
      LIMITS.MAX_COMMENT_HTML_LENGTH
    );
    if (!normalized.contentHtml) {
      throw new UserscriptError('UNEXPECTED_SHAPE', 'Selected comment has no HTML content: ' + step, { step: step });
    }
    if (normalized.contentHtml.length >= LIMITS.MAX_COMMENT_HTML_LENGTH) {
      warnings.push('A comment body reached the ' + LIMITS.MAX_COMMENT_HTML_LENGTH + ' character backend limit.');
    }
    normalized.likeCount = toCount(normalized.likeCount);
    normalized.createdAt = toIsoTime(normalized.createdAt);
    normalized.ipLocation = normalized.ipLocation
      ? trimToLimit(normalized.ipLocation, 200, 'Comment IP location', warnings)
      : null;
    normalized.badge = normalized.badge ? trimToLimit(normalized.badge, 200, 'Comment badge', warnings) : null;
    normalized.collapsed = normalized.collapsed == null ? null : Boolean(normalized.collapsed);
    normalized.replyToAuthor = normalized.replyToAuthor
      ? trimToLimit(normalized.replyToAuthor, 200, 'Comment reply-to author', warnings)
      : null;
    delete normalized.warnings;
    return {
      id: normalized.id,
      author: normalized.author,
      contentHtml: normalized.contentHtml,
      likeCount: normalized.likeCount,
      createdAt: normalized.createdAt,
      ipLocation: normalized.ipLocation,
      badge: normalized.badge,
      collapsed: normalized.collapsed,
      replyToAuthor: normalized.replyToAuthor,
      replies: Array.isArray(normalized.replies) ? normalized.replies : []
    };
  }

  // ---------------------------------------------------------------------------
  // Submission
  // ---------------------------------------------------------------------------

  function utf8Length(value) {
    if (typeof TextEncoder !== 'undefined') {
      return new TextEncoder().encode(value).length;
    }
    return Buffer.byteLength(value, 'utf8');
  }

  function normalizeSettings(settings) {
    settings = settings || {};
    var base = String(settings.haloBaseUrl || '').trim().replace(/\/+$/, '');
    var pat = String(settings.pat || '');
    var policyName = String(settings.policyName || '').trim();
    var visibility = String(settings.visibility || 'PUBLIC').trim().toUpperCase();
    if (!/^https?:\/\/[^\s]+$/i.test(base)) {
      throw new UserscriptError('SETTINGS_INVALID', 'Halo base URL must start with http:// or https://.', {
        step: 'settings:baseUrl'
      });
    }
    if (!pat.trim()) {
      throw new UserscriptError('SETTINGS_INVALID', 'A Personal Access Token is required.', { step: 'settings:pat' });
    }
    if (!policyName) {
      throw new UserscriptError('SETTINGS_INVALID', 'An attachment policy name is required.', {
        step: 'settings:policyName'
      });
    }
    if (policyName.length > LIMITS.MAX_POLICY_NAME_LENGTH) {
      throw new UserscriptError(
        'SETTINGS_INVALID',
        'Attachment policy name exceeds ' + LIMITS.MAX_POLICY_NAME_LENGTH + ' characters.',
        { step: 'settings:policyName' }
      );
    }
    if (visibility !== 'PUBLIC' && visibility !== 'PRIVATE') {
      throw new UserscriptError('SETTINGS_INVALID', 'Snapshot visibility must be PUBLIC or PRIVATE.', {
        step: 'settings:visibility'
      });
    }
    return { haloBaseUrl: base, pat: pat, policyName: policyName, visibility: visibility };
  }

  function gmRequest(options) {
    return new Promise(function (resolve, reject) {
      if (typeof GM_xmlhttpRequest !== 'function') {
        reject(
          new UserscriptError('GM_UNAVAILABLE', 'GM_xmlhttpRequest is not available in this userscript manager.', {
            step: 'submit'
          })
        );
        return;
      }
      GM_xmlhttpRequest({
        method: options.method,
        url: options.url,
        headers: options.headers,
        data: options.data,
        timeout: options.timeout || 60000,
        onload: function (response) {
          resolve({ status: response.status, responseText: response.responseText, statusText: response.statusText });
        },
        onerror: function () {
          reject(toError(mapError({ context: 'submit', step: 'submit', networkError: 'network error' })));
        },
        ontimeout: function () {
          reject(toError(mapError({ context: 'submit', step: 'submit', networkError: 'timeout' })));
        }
      });
    });
  }

  function validatePayloadForSubmission(payload) {
    if (!payload || typeof payload !== 'object') {
      throw new UserscriptError('PAYLOAD_INVALID', 'Payload is missing.', { step: 'submit:payload' });
    }
    if (payload.schemaVersion !== SCHEMA_VERSION) {
      throw new UserscriptError(
        'SCHEMA_MISMATCH',
        'This userscript produces payload schema version ' +
          SCHEMA_VERSION +
          ', but the payload has version ' +
          payload.schemaVersion +
          '. Update or reinstall the userscript.',
        { step: 'submit:schemaVersion', expected: SCHEMA_VERSION, actual: payload.schemaVersion }
      );
    }
    if (!payload.source || !payload.article || !payload.comments || !payload.capture) {
      throw new UserscriptError('PAYLOAD_INVALID', 'Payload is missing a required top-level object.', {
        step: 'submit:payload'
      });
    }
    if (['ARTICLE', 'ANSWER', 'PIN', 'VIDEO'].indexOf(payload.source.type) === -1) {
      throw new UserscriptError('PAYLOAD_INVALID', 'Unknown source type: ' + payload.source.type, {
        step: 'submit:source.type'
      });
    }
    if (!payload.source.url || payload.source.url.length > LIMITS.MAX_URL_LENGTH) {
      throw new UserscriptError('LIMIT_EXCEEDED', 'Source URL exceeds the backend URL limit.', { step: 'submit:source.url' });
    }
    if (!payload.source.id || payload.source.id.length > 128) {
      throw new UserscriptError('LIMIT_EXCEEDED', 'Source id exceeds the backend limit.', { step: 'submit:source.id' });
    }
    if (!payload.article.title || payload.article.title.length > LIMITS.MAX_TITLE_LENGTH) {
      throw new UserscriptError('LIMIT_EXCEEDED', 'Title exceeds the backend limit of ' + LIMITS.MAX_TITLE_LENGTH + '.', {
        step: 'submit:article.title'
      });
    }
    if (!payload.article.html || payload.article.html.length > LIMITS.MAX_ARTICLE_HTML_LENGTH) {
      throw new UserscriptError(
        'LIMIT_EXCEEDED',
        'Article HTML exceeds the backend limit of ' + LIMITS.MAX_ARTICLE_HTML_LENGTH + ' characters.',
        { step: 'submit:article.html' }
      );
    }
    var author = payload.article.author || {};
    if (!author.name || author.name.length > LIMITS.MAX_AUTHOR_NAME_LENGTH) {
      throw new UserscriptError('LIMIT_EXCEEDED', 'Author name exceeds the backend limit.', { step: 'submit:article.author' });
    }
    if (author.profileUrl && author.profileUrl.length > LIMITS.MAX_URL_LENGTH) {
      throw new UserscriptError('LIMIT_EXCEEDED', 'Author profile URL exceeds the backend URL limit.', {
        step: 'submit:article.author.profileUrl'
      });
    }
    var roots = payload.comments.roots;
    if (!Array.isArray(roots) || roots.length > LIMITS.MAX_ROOT_COMMENTS) {
      throw new UserscriptError('LIMIT_EXCEEDED', 'Root comment count exceeds the backend limit.', {
        step: 'submit:comments.roots'
      });
    }
    roots.forEach(function (root, index) {
      validateCommentForSubmission(root, 'submit:comments.roots[' + index + ']');
    });
    var items = payload.assets && Array.isArray(payload.assets.items) ? payload.assets.items : [];
    if (items.length > LIMITS.MAX_ASSETS) {
      throw new UserscriptError('LIMIT_EXCEEDED', 'Asset count exceeds the backend limit of ' + LIMITS.MAX_ASSETS + '.', {
        step: 'submit:assets.items'
      });
    }
    items.forEach(function (asset, index) {
      if (!asset || !asset.url || asset.url.length > LIMITS.MAX_URL_LENGTH) {
        throw new UserscriptError('LIMIT_EXCEEDED', 'Asset URL exceeds the backend URL limit.', {
          step: 'submit:assets.items[' + index + ']'
        });
      }
      if (['IMAGE', 'AVATAR', 'EQUATION', 'POSTER', 'VIDEO'].indexOf(asset.type) === -1) {
        throw new UserscriptError('PAYLOAD_INVALID', 'Unknown asset type: ' + asset.type, {
          step: 'submit:assets.items[' + index + '].type'
        });
      }
    });
  }

  function validateCommentForSubmission(comment, step, depth) {
    depth = depth || 0;
    if (!comment || typeof comment !== 'object') {
      throw new UserscriptError('PAYLOAD_INVALID', 'Comment entry is not an object.', { step: step });
    }
    if (depth > 3) {
      throw new UserscriptError('PAYLOAD_INVALID', 'Comment nesting is deeper than the contract allows.', {
        step: step
      });
    }
    if (!comment.author || !comment.author.name || comment.author.name.length > LIMITS.MAX_AUTHOR_NAME_LENGTH) {
      throw new UserscriptError('LIMIT_EXCEEDED', 'Comment author name exceeds the backend limit.', {
        step: step + '.author'
      });
    }
    if (comment.author.profileUrl && comment.author.profileUrl.length > LIMITS.MAX_URL_LENGTH) {
      throw new UserscriptError('LIMIT_EXCEEDED', 'Comment author profile URL exceeds the backend URL limit.', {
        step: step + '.author.profileUrl'
      });
    }
    if (comment.id != null && String(comment.id).length > 128) {
      throw new UserscriptError('LIMIT_EXCEEDED', 'Comment id exceeds the backend limit.', { step: step + '.id' });
    }
    if (!comment.contentHtml || comment.contentHtml.length > LIMITS.MAX_COMMENT_HTML_LENGTH) {
      throw new UserscriptError('LIMIT_EXCEEDED', 'Comment HTML exceeds the backend limit.', {
        step: step + '.contentHtml'
      });
    }
    ['ipLocation', 'badge', 'replyToAuthor'].forEach(function (field) {
      if (comment[field] != null && String(comment[field]).length > 200) {
        throw new UserscriptError('LIMIT_EXCEEDED', 'Comment ' + field + ' exceeds the backend limit of 200.', {
          step: step + '.' + field
        });
      }
    });
    if (comment.replies != null && !Array.isArray(comment.replies)) {
      throw new UserscriptError('PAYLOAD_INVALID', 'Comment replies must be an array.', { step: step + '.replies' });
    }
    if (Array.isArray(comment.replies)) {
      if (comment.replies.length > LIMITS.MAX_REPLIES_PER_ROOT) {
        throw new UserscriptError('LIMIT_EXCEEDED', 'Reply count exceeds the backend limit.', {
          step: step + '.replies'
        });
      }
      comment.replies.forEach(function (reply, index) {
        validateCommentForSubmission(reply, step + '.replies[' + index + ']', depth + 1);
      });
    }
  }

  /**
   * Validates limits, pre-flights the serialized body size, then performs a single
   * non-retried POST to Halo. Rejects with a mapped UserscriptError on any failure.
   */
  function submitPayload(payload, settings, deps) {
    var config = normalizeSettings(settings);
    validatePayloadForSubmission(payload);
    var body = JSON.stringify({
      payload: payload,
      policyName: config.policyName,
      visible: config.visibility
    });
    var bytes = utf8Length(body);
    if (bytes > MAX_REQUEST_BYTES) {
      throw new UserscriptError(
        'LIMIT_EXCEEDED',
        'Serialized payload is ' +
          bytes +
          ' bytes; Halo accepts at most ' +
          MAX_REQUEST_BYTES +
          ' bytes per request. Reduce comments or article size. Nothing was submitted.',
        { step: 'submit:body-size', bytes: bytes, limit: MAX_REQUEST_BYTES }
      );
    }

    var request = (deps && deps.request) || gmRequest;
    var url = config.haloBaseUrl + '/apis/api.console.halo.run/v1alpha1/zhihu-archives';
    var requestOptions = {
      method: 'POST',
      url: url,
      headers: {
        Authorization: 'Bearer ' + config.pat,
        'Content-Type': 'application/json',
        Accept: 'application/json'
      },
      data: body
    };
    return Promise.resolve()
      .then(function () {
        return request(requestOptions);
      })
      .catch(function (error) {
        if (error && error.name === 'UserscriptError') {
          throw error;
        }
        throw toError(
          mapError({
            context: 'submit',
            step: 'submit',
            networkError: error && error.message ? error.message : String(error)
          })
        );
      })
      .then(function (response) {
      var status = Number(response && response.status);
      var text = response ? response.responseText : '';
      if (!(status >= 200 && status < 300)) {
        throw toError(
          mapError({
            context: 'submit',
            step: 'submit',
            status: status,
            body: text,
            url: requestOptions.url,
            method: requestOptions.method
          })
        );
      }
      var parsed;
      try {
        parsed = JSON.parse(text);
      } catch (e) {
        throw new UserscriptError('UNEXPECTED_SHAPE', 'Halo returned a non-JSON success response.', {
          step: 'submit:response'
        });
      }
      var name = parsed && parsed.metadata && parsed.metadata.name;
      var permalink = parsed && parsed.status && parsed.status.permalink;
      if (!name) {
        throw new UserscriptError('UNEXPECTED_SHAPE', 'Halo response is missing metadata.name.', {
          step: 'submit:response.metadata.name'
        });
      }
      return { name: name, permalink: permalink || null, page: parsed };
    });
  }

  // ---------------------------------------------------------------------------
  // Capture orchestration (page context; no retries, no partial payloads)
  // ---------------------------------------------------------------------------

  function pageFetch() {
    var view = typeof unsafeWindow !== 'undefined' ? unsafeWindow : typeof window !== 'undefined' ? window : null;
    if (view && typeof view.fetch === 'function') {
      return view.fetch.bind(view);
    }
    if (typeof fetch === 'function') {
      return fetch;
    }
    throw new UserscriptError('FETCH_UNAVAILABLE', 'This browser does not expose fetch().', { step: 'capture' });
  }

  function safeZhihuNext(next) {
    if (!next) {
      return null;
    }
    try {
      var parsed = new URL(String(next), 'https://www.zhihu.com');
      if (parsed.protocol !== 'https:') {
        return null;
      }
      var host = parsed.hostname.toLowerCase();
      if (host !== 'www.zhihu.com' && host !== 'zhihu.com' && host !== 'api.zhihu.com') {
        return null;
      }
      return parsed.href;
    } catch (e) {
      return null;
    }
  }

  function legacyRootCommentUrls(resource, id, offset) {
    if (!resource) {
      return [];
    }
    var suffix = '/comments?order=normal&limit=' + LEGACY_PAGE_SIZE + '&offset=' + offset + '&status=open';
    return [
      'https://www.zhihu.com/api/v4/' + resource + '/' + id + suffix,
      'https://api.zhihu.com/' + resource + '/' + id + suffix
    ];
  }

  function legacyChildCommentUrls(rootId, offset) {
    var suffix = '?limit=' + LEGACY_PAGE_SIZE + '&offset=' + offset;
    var encoded = encodeURIComponent(rootId);
    return [
      'https://api.zhihu.com/comments/' + encoded + '/child_comments' + suffix,
      'https://www.zhihu.com/api/v4/comments/' + encoded + '/child_comments' + suffix
    ];
  }

  function countCommentEntries(pages) {
    return (Array.isArray(pages) ? pages : []).reduce(function (sum, page) {
      return sum + (page && Array.isArray(page.data) ? page.data.length : 0);
    }, 0);
  }

  function shouldUseLegacyComments(pages, expectedTotal) {
    if (countCommentEntries(pages) > 0) {
      return false;
    }
    return expectedTotal != null && toCount(expectedTotal) > 0;
  }

  function canFallbackToLegacy(error) {
    if (!error) {
      return true;
    }
    return error.code !== 'NETWORK_ERROR' && error.code !== 'ZHIHU_RISK_CONTROL';
  }

  async function fetchZhihuJsonFromAny(urls, step, onProgress) {
    var lastError = null;
    for (var i = 0; i < urls.length; i++) {
      try {
        return await fetchZhihuJson(urls[i], step, onProgress);
      } catch (error) {
        lastError = error;
        if (!canFallbackToLegacy(error) || i === urls.length - 1) {
          throw error;
        }
      }
    }
    throw lastError || new UserscriptError('UNEXPECTED_SHAPE', 'No candidate URL was available for ' + step + '.', {
      step: step
    });
  }

  async function fetchZhihuJson(url, step, onProgress) {
    if (onProgress) {
      onProgress(step);
    }
    var response;
    try {
      response = await pageFetch()(url, {
        method: 'GET',
        credentials: 'include',
        headers: { Accept: 'application/json' }
      });
    } catch (e) {
      throw toError(mapError({ context: 'capture', step: step, networkError: e && e.message }));
    }
    var text = await response.text();
    var parsed = parseBody(text);
    if (typeof text === 'string' && text.trim().charAt(0) === '<') {
      throw new UserscriptError(
        'ZHIHU_LOGIN_REQUIRED',
        'Zhihu returned an HTML page instead of JSON at ' + step + '. This is usually a login wall or a risk-control interstitial. Sign in, reload, and retry. No data was submitted.',
        { step: step, status: response.status }
      );
    }
    if (!response.ok) {
      throw toError(mapError({ context: 'capture', step: step, status: response.status, body: parsed }));
    }
    var upstreamCode =
      parsed && parsed.code != null
        ? parsed.code
        : parsed && parsed.error_code != null
          ? parsed.error_code
          : parsed && parsed.error && parsed.error.code;
    if (
      (parsed && parsed.error) ||
      (upstreamCode != null && [10003, 40353, 40362].indexOf(Number(upstreamCode)) !== -1)
    ) {
      throw toError(mapError({ context: 'capture', step: step, status: response.status, body: parsed }));
    }
    if (!parsed || typeof parsed !== 'object') {
      throw new UserscriptError('UNEXPECTED_SHAPE', 'Zhihu returned an unexpected response at ' + step + '.', {
        step: step
      });
    }
    return parsed;
  }

  async function captureV5RootPages(page, onProgress) {
    var pages = [];
    var offset = 0;
    for (var i = 0; i < MAX_ROOT_PAGES; i++) {
      var url =
        'https://www.zhihu.com/api/v4/comment_v5/' +
        page.commentResource +
        '/' +
        page.id +
        '/root_comment?order_by=score&limit=' +
        ROOT_PAGE_SIZE +
        '&offset=' +
        offset;
      var pageJson = await fetchZhihuJson(url, 'comments:roots:' + (i + 1), onProgress);
      pages.push(pageJson);
      var data = Array.isArray(pageJson.data) ? pageJson.data : [];
      if (!data.length || (pageJson.paging && pageJson.paging.is_end)) {
        break;
      }
      offset += ROOT_PAGE_SIZE;
      if (countCommentEntries(pages) >= ENOUGH_ROOT_CANDIDATES) {
        break;
      }
    }
    return pages;
  }

  async function captureLegacyRootPages(page, onProgress) {
    var pages = [];
    var offset = 0;
    for (var i = 0; i < MAX_LEGACY_ROOT_PAGES; i++) {
      var json = await fetchZhihuJsonFromAny(
        legacyRootCommentUrls(page.commentResource, page.id, offset),
        'comments:legacy-roots:' + (i + 1),
        onProgress
      );
      pages.push(json);
      var data = Array.isArray(json.data) ? json.data : [];
      if (!data.length || (json.paging && json.paging.is_end)) {
        break;
      }
      offset += LEGACY_PAGE_SIZE;
      if (countCommentEntries(pages) >= ENOUGH_ROOT_CANDIDATES) {
        break;
      }
    }
    return pages;
  }

  async function captureRootPages(page, sourceData, onProgress) {
    if (!page.commentResource) {
      return { pages: [], legacy: false };
    }
    var expected = sourceData && sourceData.comment_count != null ? toCount(sourceData.comment_count) : null;
    var pages = [];
    var v5Error = null;
    try {
      pages = await captureV5RootPages(page, onProgress);
    } catch (error) {
      if (!canFallbackToLegacy(error)) {
        throw error;
      }
      v5Error = error;
    }
    if (expected == null && v5Error) {
      throw v5Error;
    }
    if (!shouldUseLegacyComments(pages, expected)) {
      return { pages: pages, legacy: false };
    }
    var legacyPages = [];
    var legacyError = null;
    try {
      legacyPages = await captureLegacyRootPages(page, onProgress);
    } catch (error) {
      if (!canFallbackToLegacy(error)) {
        throw error;
      }
      legacyError = error;
    }
    if (countCommentEntries(legacyPages) > 0) {
      return { pages: legacyPages, legacy: true };
    }
    var details = [];
    if (v5Error) {
      details.push('comment_v5 failed: ' + v5Error.message);
    }
    if (legacyError) {
      details.push('legacy v4 failed: ' + legacyError.message);
    }
    throw new UserscriptError(
      'COMMENTS_UNAVAILABLE',
      'Zhihu reports ' +
        expected +
        ' comments, but neither comment_v5 nor the legacy v4 API returned root comments. Nothing was submitted.' +
        (details.length ? ' ' + details.join(' ') : ''),
      { step: 'comments:roots', expectedTotal: expected }
    );
  }

  async function captureV5Replies(rootId, onProgress) {
    var pages = [];
    var next =
      'https://www.zhihu.com/api/v4/comment_v5/comment/' +
      encodeURIComponent(rootId) +
      '/child_comment?order_by=score&limit=' +
      REPLY_PAGE_SIZE +
      '&offset=0';
    var collected = 0;
    for (var i = 0; i < MAX_REPLY_PAGES_PER_ROOT && collected < REPLY_CAP; i++) {
      var page = await fetchZhihuJson(next, 'comments:replies:' + rootId + ':' + (i + 1), onProgress);
      pages.push(page);
      var data = Array.isArray(page.data) ? page.data : [];
      collected += data.length;
      if (collected >= REPLY_CAP || !data.length || (page.paging && page.paging.is_end)) {
        break;
      }
      var nextUrl = safeZhihuNext(page.paging && page.paging.next);
      if (!nextUrl) {
        break;
      }
      next = nextUrl;
    }
    return pages;
  }

  async function captureLegacyReplies(rootId, onProgress) {
    var pages = [];
    var offset = 0;
    var collected = 0;
    for (var i = 0; i < MAX_REPLY_PAGES_PER_ROOT && collected < REPLY_CAP; i++) {
      var json = await fetchZhihuJsonFromAny(
        legacyChildCommentUrls(rootId, offset),
        'comments:legacy-replies:' + rootId + ':' + (i + 1),
        onProgress
      );
      pages.push(json);
      var data = Array.isArray(json.data) ? json.data : [];
      collected += data.length;
      if (collected >= REPLY_CAP || !data.length || (json.paging && json.paging.is_end)) {
        break;
      }
      offset += LEGACY_PAGE_SIZE;
    }
    return pages;
  }

  async function captureReplies(root, skipV5, warnings, onProgress) {
    if (!root || !root.id) {
      return [];
    }
    var expected = root.childCommentCount != null ? root.childCommentCount : null;
    if (expected === 0) {
      return [];
    }
    var pages = [];
    var v5Error = null;
    if (!skipV5) {
      try {
        pages = await captureV5Replies(root.id, onProgress);
      } catch (error) {
        if (!canFallbackToLegacy(error)) {
          throw error;
        }
        v5Error = error;
      }
    }
    if (countCommentEntries(pages) > 0) {
      return pages;
    }
    var attemptLegacy = skipV5 || v5Error != null || (expected != null && expected > 0);
    if (!attemptLegacy) {
      return [];
    }
    var legacyPages = [];
    var legacyError = null;
    try {
      legacyPages = await captureLegacyReplies(root.id, onProgress);
    } catch (error) {
      if (!canFallbackToLegacy(error)) {
        throw error;
      }
      legacyError = error;
    }
    if (countCommentEntries(legacyPages) > 0) {
      return legacyPages;
    }
    if (expected != null && expected > 0) {
      var details = ['child_comment_count=' + expected];
      if (v5Error) {
        details.push('comment_v5 failed: ' + v5Error.message);
      }
      if (legacyError) {
        details.push('legacy v4 failed: ' + legacyError.message);
      }
      warnings.push(
        'Root comment ' + root.id + ' reports replies but none were returned (' + details.join('; ') + ').'
      );
    }
    return [];
  }

  async function capture(page, settings, onProgress) {
    onProgress('source');
    var sourceData = await fetchZhihuJson(page.apiUrl, page.type.toLowerCase() + ':api', onProgress);
    var warnings = [];
    var rootResult = { pages: [], legacy: false };
    if (page.commentResource) {
      rootResult = await captureRootPages(page, sourceData, onProgress);
      if (rootResult.legacy) {
        warnings.push('The comment_v5 root API returned no data; the legacy v4 comment API was used.');
      }
    }
    var preliminary = selectComments({
      rootPages: rootResult.pages,
      repliesByRoot: {},
      total: sourceData.comment_count
    });
    var repliesByRoot = {};
    for (var i = 0; i < preliminary.comments.roots.length; i++) {
      var root = preliminary.comments.roots[i];
      repliesByRoot[root.id] = await captureReplies(root, rootResult.legacy, warnings, onProgress);
    }
    var selected = selectComments({
      rootPages: rootResult.pages,
      repliesByRoot: repliesByRoot,
      total: sourceData.comment_count
    });
    selected.meta.warnings.forEach(function (warning) {
      warnings.push(warning);
    });
    onProgress('payload');
    var payload = buildPayload({
      page: page,
      data: sourceData,
      comments: selected,
      capturedAt: new Date().toISOString(),
      clientVersion: SCRIPT_VERSION,
      warnings: warnings
    });
    onProgress('submit');
    var created = await submitPayload(payload, settings);
    return { created: created, warnings: payload.warnings.slice() };
  }

  // ---------------------------------------------------------------------------
  // Settings (GM storage only; the PAT never touches the DOM or console)
  // ---------------------------------------------------------------------------

  var SETTING_KEYS = {
    baseUrl: SETTINGS_PREFIX + '.baseUrl',
    pat: SETTINGS_PREFIX + '.pat',
    policyName: SETTINGS_PREFIX + '.policyName',
    visibility: SETTINGS_PREFIX + '.visibility'
  };

  function readSettings() {
    var read = typeof GM_getValue === 'function' ? GM_getValue : null;
    if (!read) {
      return { haloBaseUrl: '', pat: '', policyName: '', visibility: 'PUBLIC' };
    }
    return {
      haloBaseUrl: String(read(SETTING_KEYS.baseUrl, '') || ''),
      pat: String(read(SETTING_KEYS.pat, '') || ''),
      policyName: String(read(SETTING_KEYS.policyName, '') || ''),
      visibility: String(read(SETTING_KEYS.visibility, 'PUBLIC') || 'PUBLIC')
    };
  }

  function writeSettings(settings) {
    var write = typeof GM_setValue === 'function' ? GM_setValue : null;
    if (!write) {
      throw new UserscriptError('GM_UNAVAILABLE', 'GM_setValue is not available in this userscript manager.', {
        step: 'settings'
      });
    }
    write(SETTING_KEYS.baseUrl, String(settings.haloBaseUrl || '').trim().replace(/\/+$/, ''));
    write(SETTING_KEYS.pat, String(settings.pat || ''));
    write(SETTING_KEYS.policyName, String(settings.policyName || '').trim());
    write(SETTING_KEYS.visibility, String(settings.visibility || 'PUBLIC').trim().toUpperCase());
  }

  // ---------------------------------------------------------------------------
  // Floating status panel
  // ---------------------------------------------------------------------------

  function el(tag, props, children) {
    var node = document.createElement(tag);
    if (props) {
      Object.keys(props).forEach(function (key) {
        var value = props[key];
        if (key === 'text') {
          node.textContent = value;
        } else if (value == null || value === false) {
          return;
        } else if (value === true) {
          node.setAttribute(key, '');
        } else if (key === 'style') {
          node.setAttribute('style', value);
        } else if (key === 'className') {
          node.className = value;
        } else if (key === 'dataset') {
          Object.keys(value).forEach(function (dataKey) {
            node.dataset[dataKey] = value[dataKey];
          });
        } else if (key === 'onclick') {
          node.addEventListener('click', value);
        } else {
          node.setAttribute(key, value);
        }
      });
    }
    (children || []).forEach(function (child) {
      if (child == null) {
        return;
      }
      node.appendChild(typeof child === 'string' ? document.createTextNode(child) : child);
    });
    return node;
  }

  function formatError(error) {
    if (!error) {
      return 'Unknown error.';
    }
    var code = error.code ? '[' + error.code + '] ' : '';
    var step = error.detail && error.detail.step ? ' (step: ' + error.detail.step + ')' : '';
    return code + (error.message || String(error)) + step;
  }

  function absoluteUrl(base, maybeRelative) {
    if (!maybeRelative) {
      return null;
    }
    try {
      var resolved = new URL(String(maybeRelative), base);
      if (resolved.protocol !== 'http:' && resolved.protocol !== 'https:') {
        return null;
      }
      return resolved.href;
    } catch (e) {
      return null;
    }
  }

  function mountPanel() {
    if (typeof document === 'undefined' || document.getElementById(PANEL_ID)) {
      return;
    }
    var page = detectPage(window.location.href);
    var settings = readSettings();
    var status = el('div', { text: 'Idle.' });
    var errorText = el('div', { className: 'hza-error', text: '' });
    var progress = el('div', { className: 'hza-progress', text: '' });
    var result = el('div', { className: 'hza-result', text: '' });
    var warningsText = el('div', { className: 'hza-warning', text: '' });
    var captureButton = el('button', {
      type: 'button',
      text: 'Capture & send',
      disabled: page.supported ? null : 'disabled'
    });
    var settingsBox = el('div', { className: 'hza-settings', style: 'display:none;' });

    var baseInput = el('input', { type: 'url', placeholder: 'https://blog.example.com', autocomplete: 'off' });
    var patInput = el('input', {
      type: 'password',
      placeholder: 'Personal Access Token (leave blank to keep the saved token)',
      autocomplete: 'off'
    });
    var policyInput = el('input', {
      type: 'text',
      placeholder: 'Attachment policy name',
      autocomplete: 'off'
    });
    var visibilitySelect = el('select', {}, [
      el('option', { value: 'PUBLIC', text: 'PUBLIC — visible on the public index page (default)' }),
      el('option', { value: 'PRIVATE', text: 'PRIVATE — only the owner and administrators' })
    ]);
    baseInput.value = settings.haloBaseUrl;
    policyInput.value = settings.policyName;
    visibilitySelect.value = settings.visibility || 'PUBLIC';

    var saveButton = el('button', { type: 'button', text: 'Save' });
    var savedText = el('span', { className: 'hza-saved', text: '' });
    saveButton.addEventListener('click', function () {
      try {
        var next = {
          haloBaseUrl: baseInput.value,
          pat: patInput.value || settings.pat,
          policyName: policyInput.value,
          visibility: visibilitySelect.value
        };
        writeSettings(next);
        settings = next;
        patInput.value = '';
        savedText.textContent = 'Saved locally in userscript storage.';
        errorText.textContent = '';
      } catch (error) {
        errorText.textContent = formatError(error);
      }
    });

    settingsBox.appendChild(el('label', { text: 'Halo base URL' }));
    settingsBox.appendChild(baseInput);
    settingsBox.appendChild(el('label', { text: 'Personal Access Token (password field)' }));
    settingsBox.appendChild(patInput);
    settingsBox.appendChild(el('label', { text: 'Attachment policy name' }));
    settingsBox.appendChild(policyInput);
    settingsBox.appendChild(el('label', { text: 'Snapshot visibility' }));
    settingsBox.appendChild(visibilitySelect);
    var saveRow = el('div', { className: 'hza-row' }, [saveButton, savedText]);
    settingsBox.appendChild(saveRow);

    var toggleButton = el('button', { type: 'button', text: 'Settings' });
    toggleButton.addEventListener('click', function () {
      settingsBox.style.display = settingsBox.style.display === 'none' ? 'block' : 'none';
    });

    captureButton.addEventListener('click', async function () {
      captureButton.disabled = true;
      result.textContent = '';
      warningsText.textContent = '';
      errorText.textContent = '';
      progress.textContent = '';
      status.textContent = 'Starting…';
      try {
        var outcome = await capture(page, settings, function (step) {
          progress.textContent = 'Step: ' + step;
        });
        var created = outcome.created;
        status.textContent = 'Snapshot created.';
        var link = absoluteUrl(settings.haloBaseUrl, created.permalink);
        if (link) {
          var anchor = el('a', {
            href: link,
            target: '_blank',
            rel: 'noopener noreferrer',
            text: created.permalink || created.name
          });
          result.textContent = 'Snapshot ' + created.name + ': ';
          result.appendChild(anchor);
        } else {
          result.textContent = 'Snapshot ' + created.name + ' created (no permalink returned).';
        }
        if (outcome.warnings && outcome.warnings.length) {
          warningsText.textContent = 'Warnings: ' + outcome.warnings.join(' ');
        }
        progress.textContent = '';
      } catch (error) {
        status.textContent = 'Capture failed. Nothing was submitted.';
        errorText.textContent = formatError(error);
      } finally {
        captureButton.disabled = !page.supported;
      }
    });

    var typeLabel = page.supported
      ? 'Detected: ' + page.type + ' (' + page.id + ')'
      : 'Detected: unsupported page';
    var header = el('div', { className: 'hza-header' }, [
      el('strong', { text: 'Halo Zhihu Archive ' + SCRIPT_VERSION }),
      el('span', { text: typeLabel })
    ]);
    var explanation = page.supported
      ? null
      : el('div', { className: 'hza-error', text: page.reason });
    var buttonRow = el('div', { className: 'hza-row' }, [captureButton, toggleButton]);

    var panel = el(
      'div',
      {
        id: PANEL_ID,
        style: [
          'position:fixed',
          'top:16px',
          'right:16px',
          'width:320px',
          'max-width:calc(100vw - 32px)',
          'z-index:2147483647',
          'background:#fff',
          'color:#1f2329',
          'border:1px solid #d0d7de',
          'border-radius:8px',
          'box-shadow:0 8px 24px rgba(0,0,0,.18)',
          'padding:12px',
          'font:13px/1.45 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif'
        ].join(';')
      },
      [header, explanation, settingsBox, buttonRow, status, progress, errorText, result, warningsText]
    );

    var style = document.createElement('style');
    style.textContent =
      '#' +
      PANEL_ID +
      ' .hza-row{display:flex;gap:8px;align-items:center;margin:8px 0;flex-wrap:wrap}' +
      '#' +
      PANEL_ID +
      ' button{padding:4px 10px;border:1px solid #d0d7de;border-radius:6px;background:#f6f8fa;cursor:pointer}' +
      '#' +
      PANEL_ID +
      ' button:disabled{opacity:.55;cursor:not-allowed}' +
      '#' +
      PANEL_ID +
      ' input{width:100%;box-sizing:border-box;margin:2px 0 8px;padding:4px 6px;border:1px solid #d0d7de;border-radius:6px}' +
      '#' +
      PANEL_ID +
      ' label{display:block;font-weight:600;margin-top:4px}' +
      '#' +
      PANEL_ID +
      ' .hza-error{color:#b42318;word-break:break-word;margin-top:6px}' +
      '#' +
      PANEL_ID +
      ' .hza-progress{color:#57606a;margin-top:4px}' +
      '#' +
      PANEL_ID +
      ' .hza-result{word-break:break-word;margin-top:6px}' +
      '#' +
      PANEL_ID +
      ' .hza-warning{color:#9a6700;word-break:break-word;margin-top:6px}' +
      '#' +
      PANEL_ID +
      ' .hza-saved{color:#1a7f37}' +
      '#' +
      PANEL_ID +
      ' .hza-header{display:flex;flex-direction:column;gap:2px}';
    document.head.appendChild(style);
    document.body.appendChild(panel);
  }

  // ---------------------------------------------------------------------------
  // Exports
  // ---------------------------------------------------------------------------

  var core = {
    detectPage: detectPage,
    buildPayload: buildPayload,
    selectComments: selectComments,
    submitPayload: submitPayload,
    mapError: mapError,
    legacyRootCommentUrls: legacyRootCommentUrls,
    legacyChildCommentUrls: legacyChildCommentUrls,
    countCommentEntries: countCommentEntries,
    shouldUseLegacyComments: shouldUseLegacyComments
  };

  var isNode = typeof process !== 'undefined' && process.versions && process.versions.node;
  var isTestHarness =
    isNode ||
    (typeof globalThis !== 'undefined' && globalThis.__HALO_ZHIHU_ARCHIVE_TEST__ === true);
  if (isTestHarness) {
    globalThis.__haloZhihuArchive = core;
  }

  if (typeof document !== 'undefined' && typeof window !== 'undefined' && document.readyState !== 'loading') {
    mountPanel();
  } else if (typeof document !== 'undefined' && typeof window !== 'undefined') {
    document.addEventListener('DOMContentLoaded', mountPanel);
  }
})();

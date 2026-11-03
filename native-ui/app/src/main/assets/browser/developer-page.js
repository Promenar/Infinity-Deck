// 浏览器开发者工具的页面端；仅返回数据，由宿主标记不可信来源。
(function (op, args) {
  'use strict';
  function failure(code, message) { return { ok: false, error: code, message: String(message).slice(0, 500) }; }
  function safeUrl(raw) {
    try {
      var url = new URL(raw, location.href);
      if (url.protocol !== 'http:' && url.protocol !== 'https:') return '[non-http URL]';
      url.username = ''; url.password = ''; url.search = ''; url.hash = '';
      return url.href.slice(0, 2000);
    } catch (_) { return '[invalid URL]'; }
  }
  if (op === 'eval') {
    try {
      // 间接 eval 在页面全局作用域执行，返回最后一个表达式的完成值。
      var result = (0, eval)(args.code);
      if (result && typeof result.then === 'function') return failure('ASYNC_UNSUPPORTED', '请用同步表达式读取结果，异步操作可另次读取状态。');
      var text = JSON.stringify(result, function (_, value) { return typeof value === 'bigint' ? String(value) : value; });
      if (text === undefined) text = 'null';
      return { ok: true, result: text.slice(0, 16000), truncated: text.length > 16000 };
    } catch (error) { return failure('EVAL_FAILED', error.message || error); }
  }
  if (op === 'dom') {
    var matches;
    try { matches = document.querySelectorAll(args.selector); }
    catch (_) { return failure('INVALID_SELECTOR', 'CSS 选择器无效。'); }
    var items = [], remaining = Math.min(16000, args.maxChars || 12000), truncated = false;
    var maximum = Math.min(50, args.limit || 20);
    function bounded(text) {
      text = String(text || '');
      var part = text.slice(0, remaining);
      remaining -= part.length;
      if (part.length < text.length) truncated = true;
      return part;
    }
    for (var i = 0; i < matches.length && i < maximum && remaining > 0; i++) {
      var node = matches[i].cloneNode(true);
      // DOM 检查沿用敏感表单值不离开页面的规则；仅处理克隆副本。
      var fields = [node].concat(Array.prototype.slice.call(node.querySelectorAll('input,textarea')));
      fields.forEach(function (field) {
        if (!field.matches || !field.matches('input,textarea')) return;
        var hints = [field.getAttribute('type'), field.getAttribute('autocomplete'), field.getAttribute('name'), field.id, field.getAttribute('placeholder'), field.getAttribute('aria-label')].join(' ');
        var pattern = new RegExp(args.sensitivePattern || 'password|one-time-code|cc-', 'i');
        if (pattern.test(hints) || /password|one-time-code|cc-|transaction-/i.test(hints)) {
          field.setAttribute('value', '[redacted]');
          if (field.tagName.toLowerCase() === 'textarea') field.textContent = '[redacted]';
        }
      });
      if (args.textOnly) {
        var attributes = {};
        Array.prototype.forEach.call(node.attributes, function (attribute) {
          if (remaining > 0) attributes[bounded(attribute.name)] = bounded(attribute.value);
        });
        items.push({ tag: bounded(node.tagName.toLowerCase()), attributes: attributes, text: bounded(node.textContent) });
      } else items.push({ html: bounded(node.outerHTML) });
    }
    return { ok: true, items: items, total: matches.length, truncated: truncated || items.length < matches.length };
  }
  if (op === 'network') {
    try {
      var all = performance.getEntriesByType('navigation').concat(performance.getEntriesByType('resource'));
      var entries = all.slice(-300).map(function (entry) {
        return {
          url: safeUrl(entry.name), method: null, resourceType: String(entry.initiatorType || entry.entryType || '').slice(0, 40) || null,
          statusCode: entry.responseStatus > 0 ? entry.responseStatus : null,
          durationMs: Number.isFinite(entry.duration) && entry.duration >= 0 ? entry.duration : null,
          timestampMs: Math.round((performance.timeOrigin || 0) + entry.startTime), source: 'resource_timing'
        };
      });
      return { ok: true, entries: entries };
    } catch (_) { return { ok: true, entries: [], unavailable: true }; }
  }
  return failure('UNKNOWN_OPERATION', '未知的页面开发者操作。');
})

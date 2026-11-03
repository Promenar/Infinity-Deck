// 直接用 Node 验证页面工具的数据边界，不依赖 Android 或 Gradle。
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const { runInNewContext } = require('node:vm');
const assert = require('node:assert/strict');
const source = readFileSync(join(__dirname, '../../main/assets/browser/developer-page.js'), 'utf8');
const run = (op, args, extra = {}) => runInNewContext(`(${source})(${JSON.stringify(op)}, ${JSON.stringify(args)})`, {
  document: { title: '示例', querySelectorAll() { throw new Error('bad selector'); } },
  performance: { getEntriesByType: () => [], timeOrigin: 1000 }, URL, location: { href: 'https://example.com/' }, ...extra,
});
const plain = value => JSON.parse(JSON.stringify(value));
assert.equal(run('eval', { code: 'document.title' }).result, '"示例"');
assert.equal(run('eval', { code: 'undefined' }).result, 'null');
assert.equal(run('eval', { code: '123n' }).result, '"123"');
assert.equal(run('eval', { code: 'throw new Error("error")' }).ok, false);
assert.equal(run('eval', { code: 'Promise.resolve(1)' }).error, 'ASYNC_UNSUPPORTED');
assert.equal(run('eval', { code: '"x".repeat(50000)' }).truncated, true);
assert.ok(run('eval', { code: '"x".repeat(50000)' }).result.length <= 16000);
assert.equal(run('dom', { selector: '[', limit: 20, maxChars: 1000 }).error, 'INVALID_SELECTOR');
const element = {
  outerHTML: '<p>' + 'x'.repeat(5000) + '</p>',
  cloneNode() { return { ...this, querySelectorAll: () => [], matches: () => false }; },
};
const dom = run('dom', { selector: 'p', limit: 1, maxChars: 100 }, { document: { querySelectorAll: () => [element, element] } });
assert.equal(dom.items.length, 1);
assert.equal(dom.truncated, true);
assert.ok(dom.items[0].html.length <= 100);
const network = plain(run('network', {}, { performance: { timeOrigin: 1000, getEntriesByType: type => type === 'resource' ? [
  { name: 'https://user:pass@example.com/a?Authorization=secret#secret', initiatorType: 'fetch', responseStatus: 200, duration: 23.5, startTime: 1 },
  { name: 'https://example.com/b', initiatorType: 'img', responseStatus: 0, duration: 0, startTime: 2 },
] : [] } }));
assert.equal(network.entries[0].url, 'https://example.com/a');
assert.equal(network.entries[0].method, null);
assert.equal(network.entries[1].statusCode, null);
assert.equal(network.entries[0].durationMs, 23.5);
console.log('页面开发者脚本：15 项断言通过');
// 克隆副本中的 aria-label 敏感字段与 textarea 文本都必须脱敏。
for (const tag of ['INPUT', 'TEXTAREA']) {
  function field() {
    const values = { 'aria-label': '密码', value: 'example-secret' };
    return {
      tagName: tag, id: '', textContent: tag === 'TEXTAREA' ? 'example-secret' : '',
      getAttribute: key => values[key] || null,
      setAttribute(key, value) { values[key] = value; },
      querySelectorAll: () => [], matches: () => true,
      get outerHTML() { return `<${tag} value="${values.value}">${this.textContent}</${tag}>`; },
      cloneNode() { return field(); },
    };
  }
  const redacted = run('dom', { selector: 'input,textarea', sensitivePattern: '密码', limit: 20, maxChars: 1000 }, {
    document: { querySelectorAll: () => [field()] },
  });
  assert.equal(redacted.items[0].html.includes('example-secret'), false);
  assert.equal(redacted.items[0].html.includes('[redacted]'), true);
}
console.log('DOM 敏感字段：4 项断言通过');

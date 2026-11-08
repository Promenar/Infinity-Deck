// Infinity Deck Agent 浏览器的页面脚本：生成可访问性快照并按 ref 操作元素。
// 宿主每次调用都重新求值本函数，页面状态只保存在以随机键挂到 window 的不可枚举对象上。
(function (key, op, args) {
  'use strict';
  var state = window[key];
  if (!state || !state.refs) {
    state = { refs: new Map(), ids: new WeakMap(), seq: 0 };
    try { Object.defineProperty(window, key, { value: state, enumerable: false }); } catch (e) { return { ok: false, error: 'SCRIPT_ERROR', message: 'state' }; }
  }
  var sensitiveHint = new RegExp(args.sensitivePattern || '$^', 'i');
  var INTERACTIVE = { link: 1, button: 1, textbox: 1, searchbox: 1, checkbox: 1, radio: 1, combobox: 1, listbox: 1, 'switch': 1,
    tab: 1, menuitem: 1, menuitemcheckbox: 1, menuitemradio: 1, option: 1, slider: 1, spinbutton: 1, treeitem: 1 };
  var INPUT_ROLES = { button: 'button', submit: 'button', reset: 'button', image: 'button', checkbox: 'checkbox', radio: 'radio',
    range: 'slider', search: 'searchbox', number: 'spinbutton', file: 'button', color: 'button' };
  // 这些 input 类型没有可输入的文本；search、number 等仍可输入。
  var NON_TEXT_INPUTS = { button: 1, submit: 1, reset: 1, image: 1, checkbox: 1, radio: 1, range: 1, file: 1, color: 1, hidden: 1 };
  var SKIP = { SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, TEMPLATE: 1, HEAD: 1, META: 1, LINK: 1 };

  function clean(s, n) {
    s = String(s || '').replace(/\s+/g, ' ').trim();
    return s.length > n ? s.slice(0, n - 1) + '…' : s;
  }
  function roleOf(el) {
    var explicit = el.getAttribute('role');
    if (explicit) return explicit.trim().split(/\s+/)[0].toLowerCase();
    var tag = el.tagName.toLowerCase();
    if (tag === 'a' || tag === 'area') return el.hasAttribute('href') ? 'link' : null;
    if (tag === 'button' || tag === 'summary') return 'button';
    if (tag === 'input') {
      var type = (el.getAttribute('type') || 'text').toLowerCase();
      return type === 'hidden' ? null : (INPUT_ROLES[type] || 'textbox');
    }
    if (tag === 'textarea') return 'textbox';
    if (tag === 'select') return el.multiple || el.size > 1 ? 'listbox' : 'combobox';
    if (/^h[1-6]$/.test(tag)) return 'heading';
    if (el.isContentEditable && !(el.parentElement && el.parentElement.isContentEditable)) return 'textbox';
    return null;
  }
  function isEditable(el) {
    var tag = el.tagName.toLowerCase();
    if (tag === 'textarea') return !el.readOnly && !el.disabled;
    if (tag === 'input') {
      var type = (el.getAttribute('type') || 'text').toLowerCase();
      return !NON_TEXT_INPUTS[type] && !el.readOnly && !el.disabled;
    }
    return el.isContentEditable;
  }
  function visible(el) {
    var rect = el.getBoundingClientRect();
    if (rect.width <= 0 && rect.height <= 0) return false;
    var style = getComputedStyle(el);
    return style.visibility !== 'hidden' && style.display !== 'none';
  }
  function nameOf(el) {
    var aria = el.getAttribute('aria-label');
    if (aria && aria.trim()) return aria;
    var by = el.getAttribute('aria-labelledby');
    if (by) {
      var joined = by.split(/\s+/).map(function (id) { var n = document.getElementById(id); return n ? (n.innerText || n.textContent) : ''; }).join(' ');
      if (joined.trim()) return joined;
    }
    if (el.labels && el.labels.length) {
      var label = Array.prototype.map.call(el.labels, function (l) { return l.innerText || l.textContent; }).join(' ');
      if (label.trim()) return label;
    }
    var tag = el.tagName.toLowerCase();
    if (tag === 'input') {
      var type = (el.type || '').toLowerCase();
      if (type === 'button' || type === 'submit' || type === 'reset') return el.value || type;
      if (type === 'image') return el.alt || 'image';
    }
    if (tag !== 'input' && tag !== 'textarea' && tag !== 'select') {
      var inner = el.innerText || el.textContent || '';
      if (inner.trim()) return inner;
      var img = el.querySelector && el.querySelector('img[alt]');
      if (img && img.alt.trim()) return img.alt;
    }
    return el.getAttribute('placeholder') || el.getAttribute('title') || el.getAttribute('alt') || el.getAttribute('name') || '';
  }
  function sensitive(el) {
    var type = (el.getAttribute('type') || '').toLowerCase();
    var auto = (el.getAttribute('autocomplete') || '').toLowerCase();
    if (type === 'password' || /(^|\s)(current-password|new-password|one-time-code|cc-\S+|transaction-\S+)/.test(auto)) return true;
    var hints = [el.getAttribute('name'), el.id, el.getAttribute('placeholder'), el.getAttribute('aria-label')].join(' ');
    return sensitiveHint.test(hints);
  }
  function refOf(el) {
    var id = state.ids.get(el);
    if (!id) { id = 'e' + (++state.seq); state.ids.set(el, id); }
    state.refs.set(id, el);
    return id;
  }
  function describe(el, ref) {
    var role = roleOf(el) || 'button';
    var tag = el.tagName.toLowerCase();
    var editable = isEditable(el);
    var item = { ref: ref, role: role, name: clean(nameOf(el), 100), tag: tag, editable: editable, states: [] };
    if (tag === 'input') item.inputType = (el.getAttribute('type') || 'text').toLowerCase();
    var auto = el.getAttribute('autocomplete');
    if (auto) item.autocomplete = auto;
    var fieldName = el.getAttribute('name') || el.id;
    if (fieldName) item.fieldName = clean(fieldName, 60);
    var placeholder = el.getAttribute('placeholder');
    if (placeholder) item.placeholder = clean(placeholder, 60);
    if (role === 'heading') item.level = Number(tag.charAt(1)) || Number(el.getAttribute('aria-level')) || undefined;
    if (role === 'link' && el.href) item.href = String(el.href);
    if (editable || tag === 'select') {
      var value = tag === 'select' ? (el.selectedOptions && el.selectedOptions[0] ? el.selectedOptions[0].text : '')
        : (el.isContentEditable && tag !== 'input' && tag !== 'textarea' ? el.innerText : el.value);
      // 敏感字段的取值不离开页面。
      if (value) item.value = sensitive(el) ? '••' : clean(value, 200);
    }
    if (el.disabled || el.getAttribute('aria-disabled') === 'true') item.states.push('disabled');
    if (el.checked === true || el.getAttribute('aria-checked') === 'true') item.states.push('checked');
    if (el.getAttribute('aria-expanded') === 'true') item.states.push('expanded');
    if (el.getAttribute('aria-expanded') === 'false') item.states.push('collapsed');
    if (el.getAttribute('aria-selected') === 'true') item.states.push('selected');
    if (el.required) item.states.push('required');
    if (el.readOnly) item.states.push('readonly');
    if (document.activeElement === el) item.states.push('focused');
    return item;
  }
  function clickable(el) {
    if (el.hasAttribute('onclick')) return true;
    var tabindex = el.getAttribute('tabindex');
    return tabindex !== null && Number(tabindex) >= 0;
  }
  function snapshot() {
    var elements = [];
    var headings = [];
    var truncated = false;
    var visited = 0;
    function walk(node) {
      if (visited++ > 30000) { truncated = true; return; }
      for (var child = node.firstElementChild; child; child = child.nextElementSibling) {
        if (SKIP[child.tagName] || child.getAttribute('aria-hidden') === 'true' || child.hasAttribute('inert') || child.id === key + '-overlay') continue;
        var role = roleOf(child);
        if (role === 'heading') {
          if (headings.length < 40 && visible(child)) {
            var text = clean(child.innerText || child.textContent, 120);
            if (text) headings.push({ level: Number(child.tagName.charAt(1)) || Number(child.getAttribute('aria-level')) || 2, text: text });
          }
        } else if ((role && INTERACTIVE[role]) || (!role && clickable(child))) {
          if (visible(child)) {
            if (elements.length < 400) elements.push(describe(child, refOf(child))); else truncated = true;
          }
        }
        if (child.shadowRoot) walk(child.shadowRoot);
        walk(child);
      }
    }
    state.refs.forEach(function (el, ref) { if (!el.isConnected) state.refs.delete(ref); });
    if (document.body) walk(document.body);
    var result = { url: location.href, title: document.title || '', elements: elements, headings: headings,
      elementsTruncated: truncated, loading: document.readyState !== 'complete' };
    if (args.includeText) {
      var root = mainRoot();
      var all = String(root.innerText || root.textContent || '').replace(/[ \t]+\n/g, '\n').replace(/\n{3,}/g, '\n\n').trim();
      var offset = Math.max(0, Math.min(args.textOffset || 0, all.length));
      result.text = all.slice(offset, offset + 6000);
      result.textOffset = offset;
      result.textTotal = all.length;
    }
    return result;
  }
  function mainRoot() {
    var best = null;
    var bestLength = 0;
    var candidates = document.querySelectorAll('article, main, [role="main"], #content, .content, .post, .article');
    for (var i = 0; i < candidates.length; i++) {
      var length = (candidates[i].innerText || '').length;
      if (length > bestLength) { best = candidates[i]; bestLength = length; }
    }
    return best && bestLength > 200 ? best : (document.body || document.documentElement);
  }
  function element(ref) {
    var el = state.refs.get(ref);
    return el && el.isConnected ? el : null;
  }
  // 用文档坐标定位，页面滚动时轮廓随元素移动；keep 用于交给用户填写期间持续标出字段。
  function highlight(el, label, color, keep) {
    var old = document.getElementById(key + '-overlay');
    if (old) old.remove();
    if (!el) return;
    var rect = el.getBoundingClientRect();
    var box = document.createElement('div');
    box.id = key + '-overlay';
    box.setAttribute('aria-hidden', 'true');
    box.style.cssText = 'position:absolute;pointer-events:none;z-index:2147483647;box-sizing:border-box;border:2px solid ' + color +
      ';border-radius:6px;left:' + (rect.left + window.scrollX - 3) + 'px;top:' + (rect.top + window.scrollY - 3) + 'px;width:' +
      (rect.width + 6) + 'px;height:' + (rect.height + 6) + 'px;';
    var badge = document.createElement('span');
    badge.textContent = label;
    badge.style.cssText = 'position:absolute;left:-2px;top:-20px;padding:1px 6px;border-radius:6px;white-space:nowrap;font:600 11px/16px system-ui,sans-serif;color:#fff;background:' + color + ';';
    box.appendChild(badge);
    document.documentElement.appendChild(box);
    if (!keep) setTimeout(function () { if (box.isConnected) box.remove(); }, 1800);
  }
  function setNativeValue(el, text) {
    var proto = el.tagName.toLowerCase() === 'textarea' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
    setter.call(el, text);
  }
  function key_(el, type) {
    return el.dispatchEvent(new KeyboardEvent(type, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true }));
  }

  try {
    if (op === 'snapshot') return snapshot();
    if (op === 'describe') {
      var target = element(args.ref);
      return target ? describe(target, args.ref) : null;
    }
    if (op === 'clearHighlight') { highlight(null); return { ok: true }; }
    var el = element(args.ref);
    if (!el) return { ok: false, error: 'STALE_REF', message: 'No element ' + args.ref + ' exists on the current page.' };
    if (op === 'highlight') {
      el.scrollIntoView({ block: 'center', inline: 'center' });
      highlight(el, args.label || args.ref, args.color, args.keep === true);
      return { ok: true };
    }
    if (op === 'click') {
      if (el.disabled || el.getAttribute('aria-disabled') === 'true') return { ok: false, error: 'DISABLED', message: 'Element ' + args.ref + ' is disabled.' };
      var rect = el.getBoundingClientRect();
      var init = { bubbles: true, cancelable: true, composed: true, view: window, clientX: rect.left + rect.width / 2, clientY: rect.top + rect.height / 2, button: 0 };
      if (typeof el.focus === 'function') el.focus({ preventScroll: true });
      el.dispatchEvent(new PointerEvent('pointerdown', init));
      el.dispatchEvent(new MouseEvent('mousedown', init));
      el.dispatchEvent(new PointerEvent('pointerup', init));
      el.dispatchEvent(new MouseEvent('mouseup', init));
      el.click();
      return { ok: true };
    }
    if (op === 'type') {
      if (!isEditable(el)) return { ok: false, error: 'NOT_EDITABLE', message: 'Element ' + args.ref + ' is not a text field.' };
      if (sensitive(el)) return { ok: false, error: 'SENSITIVE_FIELD', message: 'Only the user can fill this field.' };
      el.focus({ preventScroll: true });
      var tag = el.tagName.toLowerCase();
      if (tag === 'input' || tag === 'textarea') {
        setNativeValue(el, args.text);
        el.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: args.text }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
      } else {
        document.execCommand('selectAll', false);
        document.execCommand('insertText', false, args.text);
      }
      if (args.submit) {
        var proceed = key_(el, 'keydown');
        key_(el, 'keypress');
        key_(el, 'keyup');
        if (proceed && el.form) {
          if (typeof el.form.requestSubmit === 'function') el.form.requestSubmit(); else el.form.submit();
        }
      }
      return { ok: true };
    }
    return { ok: false, error: 'SCRIPT_ERROR', message: 'Unknown operation.' };
  } catch (e) {
    return { ok: false, error: 'SCRIPT_ERROR', message: String(e && e.message || e).slice(0, 200) };
  }
})

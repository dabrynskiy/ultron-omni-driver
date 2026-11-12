// Read-only collector. Called by evaluateJavascript with the remaining native traversal budget.
(function (maxNodes, maxDepth) {
  'use strict';
  var count = 0;
  var truncated = false;
  var errors = [];
  var deadline = Date.now() + 1000;
  function text(value) { return value == null ? null : String(value).slice(0, 4096); }
  function collect(element, depth, selector, context) {
    if (count >= maxNodes || depth > maxDepth || Date.now() >= deadline) {
      truncated = true;
      return null;
    }
    count++;
    var tag = String(element.localName || '#shadow-root');
    var sensitive = tag === 'input' && (String(element.type).toLowerCase() === 'password' ||
      /(^|\s)(current-password|new-password)(\s|$)/i.test(element.getAttribute('autocomplete') || ''));
    var ownText = '';
    for (var t = element.firstChild; t; t = t.nextSibling) {
      if (Date.now() >= deadline) { truncated = true; break; }
      if (t.nodeType === 3) ownText = (ownText + t.textContent).slice(0, 4096);
    }
    var rect = element.getBoundingClientRect ? element.getBoundingClientRect() : null;
    var style = element.ownerDocument && element.ownerDocument.defaultView && element.nodeType === 1
      ? element.ownerDocument.defaultView.getComputedStyle(element) : null;
    var result = {
      type: 'DomNode', source: 'web', tagName: tag, namespaceURI: element.namespaceURI || null,
      compatMode: element.ownerDocument ? element.ownerDocument.compatMode : null,
      selector: selector, context: context,
      sensitive: sensitive,
      text: sensitive ? '[REDACTED]' : text(ownText.trim()),
      enabled: !element.disabled,
      displayed: !!(rect && rect.width && rect.height && style && style.display !== 'none' && style.visibility !== 'hidden'),
      attributes: {}
    };
    if (rect) result.dom_bounds = {l: rect.left, t: rect.top, r: rect.right, b: rect.bottom};
    ['id', 'name', 'type', 'role', 'aria-label', 'placeholder'].forEach(function (key) {
      var value = element.getAttribute && element.getAttribute(key);
      if (value !== null && value !== undefined) result.attributes[key] = sensitive && key !== 'id' && key !== 'type'
        ? '[REDACTED]' : text(value);
    });
    if (tag === 'input' || tag === 'textarea' || tag === 'select') {
      result.value = sensitive ? '[REDACTED]' : text(element.value);
    }
    if (tag === 'input' && (element.type === 'checkbox' || element.type === 'radio')) result.is_checked = !!element.checked;
    var children = [];
    var indices = Object.create(null);
    for (var child = element.firstElementChild; child; child = child.nextElementSibling) {
      if (Date.now() >= deadline) { truncated = true; break; }
      var childTag = String(child.localName);
      indices[childTag] = (indices[childTag] || 0) + 1;
      // Executable/style contents are not UI. Never serialize their source or attributes.
      if (['script', 'style', 'noscript', 'template'].indexOf(childTag) !== -1) continue;
      var part = CSS.escape(childTag) + ':nth-of-type(' + indices[childTag] + ')';
      var value = collect(child, depth + 1, selector ? selector + ' > ' + part : part, context);
      if (value) children.push(value); else break;
    }
    if (element.shadowRoot) {
      var shadow = collect(element.shadowRoot, depth + 1, '', context.concat([{type: 'shadow', selector: selector}]));
      if (shadow) children.push(shadow);
    }
    if (tag === 'iframe' || tag === 'frame') {
      try {
        if (!element.contentDocument || !element.contentDocument.documentElement) throw new Error('unavailable');
        var frame = collect(element.contentDocument.documentElement, depth + 1, 'html',
          context.concat([{type: 'frame', selector: selector}]));
        if (frame) children.push(frame);
      } catch (_) {
        result.dom_status = 'unavailable';
        errors.push('An embedded frame DOM is inaccessible');
      }
    }
    if (children.length) result.children = children;
    return result;
  }
  if (!document.documentElement) return {roots: [], errors: ['WebView document is not ready'], truncated: false};
  var root = collect(document.documentElement, 0, 'html', []);
  return {roots: root ? [root] : [], nodeCount: count, truncated: truncated, errors: errors};
})

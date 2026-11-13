/* Amounts (/app): the switch in the top bar that hides every amount of money behind a blur, as a bank's app
   does. `fmtEUR` and its siblings (core.js) wrap each amount in two private-use marks where they format it.
   This script turns the marked text a page renders into `.money` spans (blurred while `<html data-amounts>`
   is `hidden`), masks the marked titles and aria-labels, and drops the marks where a span can't go, so no
   mark is left in the page. Nothing matches a currency format against the page, so a new locale or format
   can't slip an amount past it: a new amount only has to come from a `fmtEUR*` helper (or `moneyMark`). */

const AMOUNTS_KEY = 'hideAmounts';
// Home's own switch, before the top bar had one: keep what people chose there.
const AMOUNTS_HOME_KEY = 'homeHideAmounts';
const MONEY_RE = /(.*?)/gs;
const MONEY_MARKED = /[]/;
const MONEY_MASKED_ATTRS = ['title', 'aria-label'];
// Attributes a title-like mask doesn't suit: they stay readable, minus the marks.
const MONEY_PLAIN_ATTRS = ['placeholder', 'value', 'label'];
const MONEY_ATTR_SELECTOR = [...MONEY_MASKED_ATTRS, ...MONEY_PLAIN_ATTRS].map(a => `[${a}]`).join(',');
// A span can't go inside these, so an amount there stays plain.
const MONEY_PLAIN_TAGS = new Set(['OPTION', 'TEXTAREA', 'TITLE', 'SCRIPT', 'STYLE']);
// What the page asked for an element's attribute, and what was written in its place: element -> { attr: { raw, applied } }.
const moneyAttrs = new WeakMap();
// Hidden until the person shows them, and then as they left it (this browser only).
let amountsHidden = true;

function readAmountsHidden() {
  try { return (localStorage.getItem(AMOUNTS_KEY) ?? localStorage.getItem(AMOUNTS_HOME_KEY)) !== '0'; } catch { return true; }
}

const maskMoney = raw => plainMoney(raw.replace(MONEY_RE, amountsHidden ? '•••' : '$1'));

// A text node's marked amounts become `.money` spans.
function convertMoneyText(node) {
  const text = node.nodeValue;
  if (!text || !MONEY_MARKED.test(text)) return;
  const parent = node.parentNode;
  if (!parent || parent.nodeType !== Node.ELEMENT_NODE || MONEY_PLAIN_TAGS.has(parent.nodeName) || parent.namespaceURI !== 'http://www.w3.org/1999/xhtml') {
    node.nodeValue = plainMoney(text);
    return;
  }
  const parts = document.createDocumentFragment();
  let last = 0;
  for (const m of text.matchAll(MONEY_RE)) {
    if (m.index > last) parts.append(plainMoney(text.slice(last, m.index)));
    const span = document.createElement('span');
    span.className = 'money';
    span.textContent = plainMoney(m[1]);
    parts.append(span);
    last = m.index + m[0].length;
  }
  if (last < text.length) parts.append(plainMoney(text.slice(last)));
  parent.replaceChild(parts, node);
}

function convertMoneyAttr(el, attr) {
  const value = el.getAttribute(attr);
  if (value === null) return;
  const known = moneyAttrs.get(el)?.[attr];
  if (known && value === known.applied) return; // our own write
  if (!MONEY_MARKED.test(value)) {
    // The page wrote a new value since: the one kept for it is stale.
    if (known) delete moneyAttrs.get(el)[attr];
    return;
  }
  if (MONEY_PLAIN_ATTRS.includes(attr)) return el.setAttribute(attr, plainMoney(value));
  const applied = maskMoney(value);
  moneyAttrs.set(el, { ...moneyAttrs.get(el), [attr]: { raw: value, applied } });
  el.setAttribute(attr, applied);
}

// Everything under `root` (a node the page just added).
function convertMoney(root) {
  if (root.nodeType === Node.TEXT_NODE) return convertMoneyText(root);
  if (root.nodeType !== Node.ELEMENT_NODE || root.classList.contains('money')) return;
  const marked = [];
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  for (let node = walker.nextNode(); node; node = walker.nextNode()) if (MONEY_MARKED.test(node.nodeValue)) marked.push(node);
  marked.forEach(convertMoneyText);
  for (const el of [root, ...root.querySelectorAll(MONEY_ATTR_SELECTOR)]) {
    for (const attr of [...MONEY_MASKED_ATTRS, ...MONEY_PLAIN_ATTRS]) convertMoneyAttr(el, attr);
  }
}

// The state shown and written: the page's attribute, the masks on titles, the switch's own label.
function applyAmounts() {
  document.documentElement.dataset.amounts = amountsHidden ? 'hidden' : 'shown';
  const button = $('#btn-amounts');
  if (button) {
    const label = amountsHidden ? STR.showAmounts : STR.hideAmounts;
    button.title = label;
    button.setAttribute('aria-label', label);
  }
  $$(MONEY_ATTR_SELECTOR).forEach(el => {
    const kept = moneyAttrs.get(el);
    if (!kept) return;
    for (const [attr, entry] of Object.entries(kept)) {
      entry.applied = maskMoney(entry.raw);
      el.setAttribute(attr, entry.applied);
    }
  });
}

function toggleAmounts() {
  amountsHidden = !amountsHidden;
  try { localStorage.setItem(AMOUNTS_KEY, amountsHidden ? '1' : '0'); } catch { /* storage blocked: the choice lasts this visit */ }
  applyAmounts();
}

// Called once from init(), before the first render.
function startAmounts() {
  amountsHidden = readAmountsHidden();
  applyAmounts();
  $('#btn-amounts')?.addEventListener('click', toggleAmounts);
  // Microtasks, so a render's amounts are spans before the browser paints them.
  new MutationObserver(records => {
    for (const record of records) {
      if (record.type === 'childList') record.addedNodes.forEach(node => { if (node.isConnected) convertMoney(node); });
      else if (record.type === 'characterData') { if (record.target.isConnected) convertMoneyText(record.target); }
      else if (record.target.isConnected) convertMoneyAttr(record.target, record.attributeName);
    }
  }).observe(document.documentElement, {
    childList: true, subtree: true, characterData: true,
    attributes: true, attributeFilter: [...MONEY_MASKED_ATTRS, ...MONEY_PLAIN_ATTRS],
  });
  convertMoney(document.body);
}

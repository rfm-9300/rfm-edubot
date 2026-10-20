/* The backoffice's foundation (/backoffice): the operator's session token and the page's `state`, api() and
   signOut(), formatting and catalog labels, and the drawer. DOM shortcuts, escapeHTML(), toast() and
   confirmDialog() come from /admin/common.js, which /app loads too.
   The page's own scripts, from this one to app.js in index.html's order, are classic scripts sharing one
   global scope, so each can call what another declares. None runs anything at its top level that needs
   another script: they only declare, and app.js calls init() once all of them have loaded. */

let token = localStorage.getItem('adminToken') || '';
let state = { tenants: [], stats: {}, search: '', filter: 'all', whatsAppSignup: { enabled: false } };
let currentView = 'tenants';

// Backoffice copy comes from the shared i18n catalogs (admin/catalog.*.js). Locale follows the
// browser / the operator's saved choice (super-admin is not tenant-scoped). `T` is a live proxy.
const T = I18N.section('backoffice');

function signOut() {
  localStorage.removeItem('adminToken');
  token = '';
  lockVault();
  renderLogin();
}

function api(path, options = {}) {
  return apiFetch(path, options, token, signOut);
}

const slugify = s => (s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
const fmtDate = iso => iso ? new Date(iso).toLocaleString(I18N.locale(), { dateStyle: 'short', timeStyle: 'short' }) : '—';
const fmtDay = d => d.toLocaleDateString(I18N.locale(), { dateStyle: 'short' });
const fmtTime = d => d.toLocaleTimeString(I18N.locale(), { timeStyle: 'short' });
// Time under the date: tenant rows already run two lines (name over slug), and the column stays narrow.
const dateCell = iso => (iso ? `${escapeHTML(fmtDay(new Date(iso)))}<div class="sub">${escapeHTML(fmtTime(new Date(iso)))}</div>` : '—');
// A cell's column heading, shown above its value when a .tbl--stack table turns its rows into cards on phones.
const dataLabel = text => `data-label="${escapeHTML(text)}"`;
// Channel platforms and dashboard roles reuse the /app catalog labels; an unknown value shows as sent.
const labelOr = (key, raw) => { const label = I18N.t(key); return label === key ? raw : label; };
const platformLabel = platform => labelOr(`app.channel_${platform}`, platform);
const roleLabel = role => labelOr(`app.accountRole${role}`, role);
const warnNoticeHtml = text => `<div class="notice notice--warn" role="status"><div class="notice__text"><span>${escapeHTML(text)}</span></div></div>`;

const loadErrorHtml = e => `<div class="empty"><p class="empty__title">${escapeHTML(T.loadError)}</p><p class="empty__desc">${escapeHTML(e.message)}</p></div>`;

let drawerOpener = null;

function closeDrawer() {
  const root = $('#drawer');
  if (root.hidden) return;
  root.hidden = true;
  if (drawerOpener?.isConnected) drawerOpener.focus();
  drawerOpener = null;
}

// `back` ({ label, open }) is the tenant record this drawer was opened from: the eyebrow links back to it,
// and a save reopens it with fresh data instead of closing. `onSave` may return false to stay open, or a
// function that opens the next drawer. Without `onSave` there is no footer. Without `back` the eyebrow
// reads `eyebrow`, or "Tenant".
function openDrawer({ title, body, onSave, saveLabel = T.save, autofocus = true, back = null, eyebrow = null }) {
  const root = $('#drawer');
  if (root.hidden) drawerOpener = document.activeElement;
  $('#drawer-title').textContent = title;
  const eyebrowEl = $('#drawer-eyebrow');
  if (back) {
    eyebrowEl.innerHTML = `<button class="drawer__back" type="button" aria-label="${escapeHTML(T.record.backTo({ name: back.label }))}">${escapeHTML(back.label)}</button>`;
    $('.drawer__back', eyebrowEl).addEventListener('click', () => back.open());
  } else {
    eyebrowEl.textContent = eyebrow || T.drawerEyebrow;
  }
  const host = $('#drawer-body');
  host.innerHTML = '';
  host.appendChild(body);
  if (onSave) {
    const foot = document.createElement('div');
    foot.className = 'drawer__foot';
    foot.innerHTML = `<button class="btn btn--ghost" type="button" data-close>${escapeHTML(T.cancel)}</button><button class="btn btn--accent" type="button" id="drawer-save">${escapeHTML(saveLabel)}</button>`;
    host.appendChild(foot);
    $('#drawer-save').addEventListener('click', async () => {
      const next = await onSave();
      if (next === false) return;
      if (typeof next === 'function') next();
      else if (back) back.open();
      else closeDrawer();
    });
  }
  $$('[data-close]', root).forEach(b => { b.onclick = closeDrawer; });
  root.hidden = false;
  // On a touch screen a focused field opens the keyboard over the drawer, so focus goes to × instead.
  const intoField = autofocus && !matchMedia('(pointer: coarse)').matches;
  setTimeout(() => (intoField ? host.querySelector('input,select,textarea') : $('.drawer__head [data-close]', root))?.focus(), 50);
}

/* The drawer every form and record opens in (#drawer): focus kept inside while open and given back on
   close, Escape, and the trail of drawers opened from one another that gives the head its way back.
   Settings → Appearance can have a feature's records and forms open as a page instead of at the side: the
   same drawer, laid over the page area with the menu and the top bar left usable (wiki/dashboard-ui.md). */

// Drawer content rendered after openDrawer() needs its own close buttons wired.
function bindDrawerClose(body) {
  $$('[data-close]', body).forEach(b => { b.onclick = () => closeDrawer({ dismissed: true }); });
}

// How a feature's records and forms open, chosen in Settings → Appearance and kept per browser, like the folded
// sidebar: 'side' is the drawer sliding in from the right (the default), 'page' lays it over the page area.
const DRAWER_MODE_KEY = 'ui:drawerMode';
let drawerModeNow = '';
function drawerMode() {
  if (!drawerModeNow) {
    try { drawerModeNow = localStorage.getItem(DRAWER_MODE_KEY) === 'page' ? 'page' : 'side'; } catch { drawerModeNow = 'side'; }
  }
  return drawerModeNow;
}
function setDrawerMode(mode) {
  drawerModeNow = mode === 'page' ? 'page' : 'side';
  try { localStorage.setItem(DRAWER_MODE_KEY, drawerModeNow); } catch { /* storage blocked: the choice lasts this visit */ }
}

// A page-style drawer starts under the top bar, which sticks to the window and wraps on narrow screens, so where
// it starts is measured, on opening and while the window resizes or scrolls. The menu's width is the CSS variable.
function placeDrawerPage() {
  const bar = $('.topbar');
  if (bar) $('#drawer').style.setProperty('--drawer-page-top', `${Math.max(0, Math.round(bar.getBoundingClientRect().bottom))}px`);
}
function watchDrawerPage(on) {
  const act = on ? 'addEventListener' : 'removeEventListener';
  window[act]('resize', placeDrawerPage);
  window[act]('scroll', placeDrawerPage, { passive: true });
}
function drawerShowsPage() {
  const root = $('#drawer');
  return !!root && !root.hidden && root.classList.contains('drawer--page');
}
// A page-style drawer stands in for the page, so leaving by the menu or the search box closes it. Phones keep the
// full-screen drawer, which already covers the page.
function closePageDrawer() {
  if (drawerShowsPage()) closeDrawer({ dismissed: true, restoreFocus: false });
}

let drawerPrevFocus = null;
let drawerKeyHandler = null;
let drawerGen = 0;
// Drawers opened from another drawer (a client record, an invoice…) stack their opener here as
// { key, label, open }. The head shows the way back to the last one, and a drawer that closes itself
// after an action (save, mark paid, convert…) reopens it with fresh data. Closing by hand (×, scrim,
// Escape) clears the stack and goes back to the page.
let drawerTrail = [];
const trailTop = () => drawerTrail[drawerTrail.length - 1] || null;
function openFrom(back, open) {
  if (back) drawerTrail.push(back);
  open();
}
// A link to the record the trail already goes back to would just loop.
const linksBackTo = key => trailTop()?.key === key;

function drawerFocusables(panel) {
  return [...panel.querySelectorAll('a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])')]
    .filter(el => !el.hidden && el.offsetParent !== null);
}

function closeDrawer(opts = {}) {
  const root = $('#drawer');
  if (root) {
    root.hidden = true;
    watchDrawerPage(false);
    if (drawerKeyHandler) {
      root.removeEventListener('keydown', drawerKeyHandler);
      drawerKeyHandler = null;
    }
  }
  if (opts.dismissed) drawerTrail = [];
  const back = drawerTrail.pop();
  if (back) {
    // The caller may open another drawer right after closing this one; only come back if it didn't.
    const gen = drawerGen;
    setTimeout(() => { if (drawerGen === gen && $('#drawer')?.hidden) back.open(); }, 0);
    return;
  }
  const prev = drawerPrevFocus;
  drawerPrevFocus = null;
  if (opts.restoreFocus !== false && prev && typeof prev.focus === 'function') prev.focus();
}

function renderDrawerEyebrow(text) {
  const el = $('#drawer .drawer__eyebrow');
  if (!el) return;
  const back = trailTop();
  if (!back) { el.textContent = text || STR.dashboardWord; return; }
  el.innerHTML = `<button class="drawer__back" type="button" aria-label="${escapeHTML(STR.clientBackAria({ name: back.label }))}">${escapeHTML(back.label)}</button>`;
  $('.drawer__back', el).addEventListener('click', () => { drawerTrail.pop(); back.open(); });
}

// `opts.page: true` marks a feature's record, or its main form: with "Full page" chosen it fills the page area
// (except on phones, where every drawer is full screen). `page: false` keeps a drawer at the side whatever is
// chosen (the account, the bell, the company switcher). Left out, a drawer follows what is on screen: a quick
// action opened from a page-style record stays in the page instead of jumping to the side and back.
function openDrawer(title, body, wide = false, opts = {}) {
  const root = $('#drawer');
  const panel = $('.drawer__panel', root);
  const page = drawerMode() === 'page' && window.matchMedia('(min-width: 561px)').matches && (opts.page ?? drawerShowsPage());
  if (drawerKeyHandler) root.removeEventListener('keydown', drawerKeyHandler);
  if (root.hidden) drawerPrevFocus = document.activeElement;
  drawerGen += 1;
  panel.classList.toggle('drawer__panel--wide', wide);
  root.classList.toggle('drawer--page', page);
  // A quick action that only followed the page along (no `page` option) keeps its form to a comfortable width.
  root.classList.toggle('drawer--page-quick', page && opts.page === undefined);
  // A page-style drawer leaves the menu and the top bar usable, so it doesn't hold focus or hide the page from readers.
  panel.setAttribute('aria-modal', String(!page));
  if (page) placeDrawerPage();
  watchDrawerPage(page);
  renderDrawerEyebrow(opts.eyebrow);
  $('#drawer-title').textContent = title;
  $('#drawer-body').innerHTML = '';
  // A record's tags sit above its body, so they stay while the body redraws itself.
  if (opts.tags) $('#drawer-body').appendChild(tagStrip(opts.tags.kind, opts.tags.id));
  $('#drawer-body').appendChild(body);
  root.hidden = false;
  $$('[data-close]', root).forEach(b => { b.onclick = () => closeDrawer({ dismissed: true }); });
  drawerKeyHandler = e => {
    if (e.key === 'Escape') { e.preventDefault(); closeDrawer({ dismissed: true }); return; }
    if (e.key !== 'Tab' || page) return;
    const list = drawerFocusables(panel);
    if (!list.length) return;
    const first = list[0];
    const last = list[list.length - 1];
    if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
    else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
  };
  root.addEventListener('keydown', drawerKeyHandler);
  requestAnimationFrame(() => {
    const preferred = body.querySelector('input, select, textarea, button');
    (preferred || drawerFocusables(panel)[0])?.focus();
  });
  return drawerGen;
}

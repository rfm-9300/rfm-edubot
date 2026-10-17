/* The drawer every form and record opens in (#drawer): focus kept inside while open and given back on
   close, Escape, and the trail of drawers opened from one another that gives the head its way back. */

// Drawer content rendered after openDrawer() needs its own close buttons wired.
function bindDrawerClose(body) {
  $$('[data-close]', body).forEach(b => { b.onclick = () => closeDrawer({ dismissed: true }); });
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
  if (prev && typeof prev.focus === 'function') prev.focus();
}

function renderDrawerEyebrow(text) {
  const el = $('#drawer .drawer__eyebrow');
  if (!el) return;
  const back = trailTop();
  if (!back) { el.textContent = text || STR.dashboardWord; return; }
  el.innerHTML = `<button class="drawer__back" type="button" aria-label="${escapeHTML(STR.clientBackAria({ name: back.label }))}">${escapeHTML(back.label)}</button>`;
  $('.drawer__back', el).addEventListener('click', () => { drawerTrail.pop(); back.open(); });
}

function openDrawer(title, body, wide = false, opts = {}) {
  const root = $('#drawer');
  const panel = $('.drawer__panel', root);
  if (drawerKeyHandler) root.removeEventListener('keydown', drawerKeyHandler);
  if (root.hidden) drawerPrevFocus = document.activeElement;
  drawerGen += 1;
  panel.classList.toggle('drawer__panel--wide', wide);
  renderDrawerEyebrow(opts.eyebrow);
  $('#drawer-title').textContent = title;
  $('#drawer-body').innerHTML = '';
  $('#drawer-body').appendChild(body);
  root.hidden = false;
  $$('[data-close]', root).forEach(b => { b.onclick = () => closeDrawer({ dismissed: true }); });
  drawerKeyHandler = e => {
    if (e.key === 'Escape') { e.preventDefault(); closeDrawer({ dismissed: true }); return; }
    if (e.key !== 'Tab') return;
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

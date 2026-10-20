/* Passwords (/backoffice → Passwords): the team's password vault. Everything is encrypted in this browser with a
   random vault key that only the team's master password unwraps (PBKDF2-SHA-256, then AES-256-GCM). The server
   keeps sealed data it can't read, and lets only a browser that unlocked the vault read or change the entries: each
   such call carries a token derived from the vault key. The vault locks after 10 minutes without activity, on Log
   out, and when the page closes; nothing of it is kept in the browser's storage. */

let vaultState = { meta: null, session: null, entries: [], search: '', timer: 0, watching: false };

const VAULT_ITERATIONS = 600000;
const VAULT_IDLE_MS = 10 * 60 * 1000;
const VAULT_MIN_PASSWORD = 12;
const VAULT_GENERATED_LENGTH = 20;
// Read back without mistakes: no 0/O, nor 1/l/I.
const VAULT_CHARSET = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!#$%&*+-=?@_';
const VAULT_LOCK_ICON = '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><rect x="4.5" y="10.5" width="15" height="10" rx="2"/><path d="M8 10.5V7.5a4 4 0 0 1 8 0v3"/></svg>';
// What password managers in the browser are asked to leave alone: these fields hold the vault's own secrets.
const VAULT_FIELD_ATTRS = 'autocomplete="off" data-1p-ignore data-lpignore="true" data-bwignore spellcheck="false"';

const vaultRandom = n => crypto.getRandomValues(new Uint8Array(n));

function vaultB64(bytes) {
  let text = '';
  for (let i = 0; i < bytes.length; i += 0x8000) text += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(text);
}

function vaultUnb64(text) {
  const raw = atob(text);
  const bytes = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
  return bytes;
}

// The key a master password derives, which seals and opens the vault key.
async function vaultPasswordKey(password, kdf) {
  const base = await crypto.subtle.importKey('raw', new TextEncoder().encode(password), 'PBKDF2', false, ['deriveKey']);
  return crypto.subtle.deriveKey(
    { name: 'PBKDF2', hash: kdf.hash, salt: vaultUnb64(kdf.salt), iterations: kdf.iterations },
    base,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt'],
  );
}

async function vaultSeal(key, bytes) {
  const iv = vaultRandom(12);
  const data = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, bytes));
  return { iv: vaultB64(iv), data: vaultB64(data) };
}

// Throws on a wrong key or changed data: AES-GCM checks both.
async function vaultOpen(key, sealed) {
  return new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: vaultUnb64(sealed.iv) }, key, vaultUnb64(sealed.data)));
}

// Two independent values from the vault key: the AES key that seals the entries and the token the server checks.
async function vaultSessionFrom(vaultKey) {
  const base = await crypto.subtle.importKey('raw', vaultKey, 'HKDF', false, ['deriveKey', 'deriveBits']);
  const params = info => ({ name: 'HKDF', hash: 'SHA-256', salt: new Uint8Array(0), info: new TextEncoder().encode(info) });
  const entryKey = await crypto.subtle.deriveKey(params('thebots-vault-entries-v1'), base, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
  const token = new Uint8Array(await crypto.subtle.deriveBits(params('thebots-vault-auth-v1'), base, 256));
  return { vaultKey, entryKey, token: vaultB64(token) };
}

const vaultNewKdf = () => ({ name: 'PBKDF2', hash: 'SHA-256', iterations: VAULT_ITERATIONS, salt: vaultB64(vaultRandom(16)) });

// A password from VAULT_CHARSET, each character equally likely (bytes past the last whole round are skipped).
function vaultGenerate(length = VAULT_GENERATED_LENGTH) {
  const limit = 256 - (256 % VAULT_CHARSET.length);
  let out = '';
  while (out.length < length) {
    for (const b of vaultRandom(length * 2)) {
      if (b < limit && out.length < length) out += VAULT_CHARSET[b % VAULT_CHARSET.length];
    }
  }
  return out;
}

function vaultApi(path, options = {}) {
  return api(path, { ...options, headers: { ...(options.headers || {}), 'X-Vault-Auth': vaultState.session?.token || '' } });
}

function vaultErrorText(err) {
  return T.vault.errors[err.code] || T.error({ msg: err.message });
}

// A site to link to: http(s) only, so a stored javascript: address can't run.
function vaultSite(url) {
  const raw = (url || '').trim();
  if (!raw) return null;
  try {
    const parsed = new URL(/^[a-z][a-z0-9+.-]*:/i.test(raw) ? raw : `https://${raw}`);
    return ['http:', 'https:'].includes(parsed.protocol) ? { href: parsed.href, host: parsed.host } : null;
  } catch {
    return null;
  }
}

function vaultTouch() {
  if (!vaultState.session) return;
  clearTimeout(vaultState.timer);
  vaultState.timer = setTimeout(() => lockVault(T.vault.lockedIdle), VAULT_IDLE_MS);
}

function vaultStartIdle() {
  if (!vaultState.watching) {
    vaultState.watching = true;
    document.addEventListener('pointerdown', vaultTouch, true);
    document.addEventListener('keydown', vaultTouch, true);
  }
  vaultTouch();
}

// Forgets the keys and the opened entries, and closes a password drawer.
function lockVault(message = '') {
  if (!vaultState.session) return;
  vaultState.session.vaultKey.fill(0);
  vaultState.session = null;
  vaultState.entries = [];
  vaultState.search = '';
  clearTimeout(vaultState.timer);
  if ($('#drawer-body [data-vault-drawer]')) closeDrawer();
  if (message) toast(message);
  if (currentView === 'vault' && token) renderVault();
}

async function renderVault() {
  const V = T.vault;
  if (!window.isSecureContext || !window.crypto?.subtle) {
    $('#view').innerHTML = `<div class="panel"><div class="empty"><p class="empty__title">${escapeHTML(V.insecureTitle)}</p><p class="empty__desc">${escapeHTML(V.insecureDesc)}</p></div></div>`;
    return;
  }
  if (vaultState.session) {
    vaultPaintList();
    return;
  }
  try {
    vaultState.meta = await api('/admin/api/vault');
  } catch (e) {
    if (currentView === 'vault') $('#view').innerHTML = loadErrorHtml(e);
    return;
  }
  if (currentView !== 'vault') return;
  if (vaultState.meta.exists) vaultPaintUnlock();
  else vaultPaintSetup();
}

function vaultCardHtml({ title, desc, body }) {
  const V = T.vault;
  return `<div class="auth auth--view"><div class="auth__card">
      <div class="auth__mark" aria-hidden="true">${VAULT_LOCK_ICON}</div>
      <p class="auth__eyebrow">${escapeHTML(V.nav)}</p>
      <h1 class="auth__title">${escapeHTML(title)}</h1>
      <p class="auth__desc">${escapeHTML(desc)}</p>
      ${body}
    </div></div>`;
}

const vaultHintBad = (hint, text) => {
  hint.textContent = text;
  hint.className = 'hint hint--bad';
  hint.setAttribute('role', 'alert');
};

// The first visit: someone chooses the team's master password, and the vault is created with it.
function vaultPaintSetup() {
  const V = T.vault;
  $('#view').innerHTML = vaultCardHtml({
    title: V.setupTitle,
    desc: V.setupDesc,
    body: `${warnNoticeHtml(V.setupWarning)}
      <form class="form vault__form" novalidate>
        <div class="form__row"><label class="lbl" for="vault-new">${escapeHTML(V.masterLabel)}</label><input class="inp" id="vault-new" type="password" ${VAULT_FIELD_ATTRS} /></div>
        <div class="form__row"><label class="lbl" for="vault-repeat">${escapeHTML(V.repeatLabel)}</label><input class="inp" id="vault-repeat" type="password" ${VAULT_FIELD_ATTRS} /></div>
        <p class="hint" data-vault-hint>${escapeHTML(V.masterHint({ n: VAULT_MIN_PASSWORD }))}</p>
        <button class="btn btn--primary" type="submit">${escapeHTML(V.create)}</button>
      </form>`,
  });
  const form = $('.vault__form');
  const hint = $('[data-vault-hint]', form);
  $('#vault-new', form).focus();
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const password = $('#vault-new', form).value;
    if (password.length < VAULT_MIN_PASSWORD) { vaultHintBad(hint, V.tooShort({ n: VAULT_MIN_PASSWORD })); $('#vault-new', form).focus(); return; }
    if ($('#vault-repeat', form).value !== password) { vaultHintBad(hint, V.mismatch); $('#vault-repeat', form).focus(); return; }
    const button = $('button[type=submit]', form);
    button.disabled = true;
    button.textContent = V.creating;
    try {
      const kdf = vaultNewKdf();
      const vaultKey = vaultRandom(32);
      const wrappedKey = await vaultSeal(await vaultPasswordKey(password, kdf), vaultKey);
      const session = await vaultSessionFrom(vaultKey);
      vaultState.meta = await api('/admin/api/vault', { method: 'POST', body: JSON.stringify({ kdf, wrappedKey, authToken: session.token }) });
      vaultState.session = session;
      vaultState.entries = [];
    } catch (err) {
      toast(vaultErrorText(err));
      button.disabled = false;
      button.textContent = V.create;
      if (err.code === 'vault_exists') renderVault();
      return;
    }
    vaultStartIdle();
    toast(V.created);
    if (currentView === 'vault') vaultPaintList();
  });
}

function vaultPaintUnlock() {
  const V = T.vault;
  $('#view').innerHTML = vaultCardHtml({
    title: V.unlockTitle,
    desc: V.unlockDesc,
    body: `<form class="form vault__form" novalidate>
        <div class="form__row"><label class="lbl" for="vault-master">${escapeHTML(V.masterLabel)}</label><input class="inp" id="vault-master" type="password" ${VAULT_FIELD_ATTRS} /></div>
        <p class="hint" data-vault-hint hidden></p>
        <button class="btn btn--primary" type="submit">${escapeHTML(V.unlock)}</button>
      </form>
      <p class="hint vault__lost">${escapeHTML(V.lostHint)}</p>`,
  });
  const form = $('.vault__form');
  const input = $('#vault-master', form);
  const hint = $('[data-vault-hint]', form);
  if (!matchMedia('(pointer: coarse)').matches) input.focus();
  form.addEventListener('submit', async e => {
    e.preventDefault();
    if (!input.value) { input.focus(); return; }
    const button = $('button[type=submit]', form);
    button.disabled = true;
    button.textContent = V.unlocking;
    const meta = vaultState.meta;
    let vaultKey = null;
    try {
      vaultKey = await vaultOpen(await vaultPasswordKey(input.value, meta.kdf), meta.wrappedKey);
    } catch {
      vaultKey = null;
    }
    if (!vaultKey) {
      hint.hidden = false;
      vaultHintBad(hint, V.wrongPassword);
      button.disabled = false;
      button.textContent = V.unlock;
      input.select();
      return;
    }
    vaultState.session = await vaultSessionFrom(vaultKey);
    try {
      await vaultLoadEntries();
    } catch (err) {
      vaultState.session = null;
      toast(vaultErrorText(err));
      button.disabled = false;
      button.textContent = V.unlock;
      return;
    }
    vaultStartIdle();
    if (currentView === 'vault') vaultPaintList();
  });
}

async function vaultLoadEntries() {
  const raw = await vaultApi('/admin/api/vault/entries');
  const key = vaultState.session.entryKey;
  const opened = await Promise.all(raw.map(async r => {
    try {
      const fields = JSON.parse(new TextDecoder().decode(await vaultOpen(key, r)));
      return {
        id: r.id,
        title: String(fields.title || ''),
        username: String(fields.username || ''),
        password: String(fields.password || ''),
        url: String(fields.url || ''),
        notes: String(fields.notes || ''),
        createdAt: r.createdAt,
        createdBy: r.createdBy,
        updatedAt: r.updatedAt,
        updatedBy: r.updatedBy,
      };
    } catch {
      return null;
    }
  }));
  vaultState.entries = opened.filter(Boolean);
  const unreadable = opened.length - vaultState.entries.length;
  if (unreadable) toast(T.vault.unreadable({ n: unreadable }));
}

// A refusal of the token means the vault changed under this page (recreated by hand): lock it and say so.
function vaultRefused(err) {
  if (err.code !== 'vault_auth_failed' && err.code !== 'no_vault') return false;
  lockVault(vaultErrorText(err));
  return true;
}

function vaultPaintList() {
  const V = T.vault;
  const q = vaultState.search.trim().toLowerCase();
  const rows = vaultState.entries
    .filter(e => !q || [e.title, e.username, e.url, e.notes].join(' ').toLowerCase().includes(q))
    .sort((a, b) => a.title.localeCompare(b.title, I18N.locale()));
  const empty = vaultState.entries.length
    ? `<div class="empty"><p class="empty__title">${escapeHTML(V.noMatch({ q: vaultState.search.trim() }))}</p></div>`
    : `<div class="empty"><p class="empty__title">${escapeHTML(V.emptyTitle)}</p><p class="empty__desc">${escapeHTML(V.emptyDesc)}</p></div>`;
  $('#view').innerHTML = `
    <div class="view__hero"><div><h1 class="view__title">${escapeHTML(V.title)}</h1><p class="view__desc">${escapeHTML(V.desc)}</p></div>
      <div class="actions">
        <button class="btn btn--ghost" type="button" data-vault-lock>${escapeHTML(V.lock)}</button>
        <button class="btn btn--primary" type="button" data-vault-new><span class="btn__plus" aria-hidden="true">+</span><span class="btn__label">${escapeHTML(V.newEntry)}</span></button>
      </div>
    </div>
    <div class="panel">
      <div class="panel__head"><h2 class="panel__title">${escapeHTML(V.listTitle)} <span class="tag">${vaultState.entries.length}</span></h2>
        <div class="panel__tools"><input class="inp panel__search" type="search" data-vault-q value="${escapeHTML(vaultState.search)}" placeholder="${escapeHTML(V.searchPlaceholder)}" aria-label="${escapeHTML(V.searchPlaceholder)}" autocomplete="off" spellcheck="false" /></div>
      </div>
      <div class="tbl-wrap"><table class="tbl tbl--stack tbl--flow"><thead><tr>
        <th>${escapeHTML(V.thName)}</th><th>${escapeHTML(V.thUsername)}</th><th>${escapeHTML(V.thUpdated)}</th><th class="right">${escapeHTML(T.thActions)}</th>
      </tr></thead><tbody data-vault-rows>${rows.length ? rows.map(vaultRowHtml).join('') : `<tr><td colspan="4">${empty}</td></tr>`}</tbody></table></div>
    </div>
    <div class="view__foot"><button class="btn btn--sm btn--ghost" type="button" data-vault-master>${escapeHTML(V.changeMaster)}</button></div>`;
  const view = $('#view');
  $('[data-vault-lock]', view).addEventListener('click', () => lockVault(V.locked));
  $('[data-vault-new]', view).addEventListener('click', () => vaultEntryDrawer());
  $('[data-vault-master]', view).addEventListener('click', () => vaultMasterDrawer());
  const search = $('[data-vault-q]', view);
  search.addEventListener('input', () => {
    vaultState.search = search.value;
    const caret = search.selectionStart;
    vaultPaintList();
    const again = $('[data-vault-q]');
    again.focus();
    again.setSelectionRange(caret, caret);
  });
  $$('[data-vault-open]', view).forEach(b => b.addEventListener('click', () => vaultEntryDrawer(vaultState.entries.find(e => e.id === b.dataset.vaultOpen))));
  $$('[data-vault-copy]', view).forEach(b => b.addEventListener('click', () => {
    const entry = vaultState.entries.find(e => e.id === b.dataset.id);
    if (entry) vaultCopy(entry[b.dataset.vaultCopy], b.dataset.vaultCopy === 'password' ? V.copiedPassword : V.copiedUsername);
  }));
}

function vaultRowHtml(e) {
  const V = T.vault;
  const site = vaultSite(e.url);
  return `<tr>
      <td class="name"><button class="tbl__open" type="button" data-vault-open="${escapeHTML(e.id)}">${escapeHTML(e.title)}</button>${site ? `<div class="sub"><a class="tbl__link" href="${escapeHTML(site.href)}" target="_blank" rel="noopener noreferrer">${escapeHTML(site.host)}</a></div>` : ''}</td>
      <td class="mono" ${dataLabel(V.thUsername)}>${e.username ? escapeHTML(e.username) : '—'}</td>
      <td class="muted" ${dataLabel(V.thUpdated)}>${dateCell(e.updatedAt)}</td>
      <td class="right"><div class="actions">
        ${e.username ? `<button class="btn btn--sm btn--ghost" type="button" data-vault-copy="username" data-id="${escapeHTML(e.id)}">${escapeHTML(V.copyUsername)}</button>` : ''}
        ${e.password ? `<button class="btn btn--sm" type="button" data-vault-copy="password" data-id="${escapeHTML(e.id)}">${escapeHTML(V.copyPassword)}</button>` : ''}
      </div></td>
    </tr>`;
}

async function vaultCopy(text, done) {
  try {
    await navigator.clipboard.writeText(text);
    toast(done);
  } catch {
    toast(T.vault.copyFailed);
  }
}

// A saved login, new or changed: everything in it is sealed in this browser before it leaves.
function vaultEntryDrawer(entry = null) {
  const V = T.vault;
  const body = document.createElement('div');
  body.dataset.vaultDrawer = '';
  const value = field => escapeHTML(entry?.[field] || '');
  body.innerHTML = `
    <form class="form" novalidate>
      <div class="form__row"><label class="lbl" for="vault-title">${escapeHTML(V.nameLabel)} <span class="req">*</span></label><input class="inp" id="vault-title" maxlength="200" value="${value('title')}" placeholder="${escapeHTML(V.namePlaceholder)}" autocomplete="off" /></div>
      <div class="form__row"><label class="lbl" for="vault-username">${escapeHTML(V.usernameLabel)} <span class="opt">${escapeHTML(V.optional)}</span></label>
        <div class="inp-row"><input class="inp inp--mono" id="vault-username" maxlength="500" value="${value('username')}" ${VAULT_FIELD_ATTRS} /><button class="btn btn--sm btn--ghost" type="button" data-copy="vault-username">${escapeHTML(V.copy)}</button></div></div>
      <div class="form__row"><label class="lbl" for="vault-password">${escapeHTML(V.passwordLabel)}</label>
        <div class="inp-row"><input class="inp inp--mono" id="vault-password" type="password" maxlength="1000" value="${value('password')}" ${VAULT_FIELD_ATTRS} />
          <button class="btn btn--sm btn--ghost" type="button" data-reveal aria-pressed="false">${escapeHTML(V.show)}</button>
          <button class="btn btn--sm btn--ghost" type="button" data-generate>${escapeHTML(V.generate)}</button>
          <button class="btn btn--sm btn--ghost" type="button" data-copy="vault-password">${escapeHTML(V.copy)}</button></div></div>
      <div class="form__row"><label class="lbl" for="vault-url">${escapeHTML(V.urlLabel)} <span class="opt">${escapeHTML(V.optional)}</span></label><input class="inp" id="vault-url" maxlength="2000" inputmode="url" value="${value('url')}" placeholder="https://" autocomplete="off" spellcheck="false" /></div>
      <div class="form__row"><label class="lbl" for="vault-notes">${escapeHTML(V.notesLabel)} <span class="opt">${escapeHTML(V.optional)}</span></label><textarea class="txt" id="vault-notes" rows="4" maxlength="10000">${value('notes')}</textarea></div>
    </form>
    ${entry ? `<p class="hint drawer__meta">${escapeHTML(vaultMetaLine(entry))}</p>
      <div class="actions drawer__actions"><button class="btn btn--sm btn--danger" type="button" data-vault-delete>${escapeHTML(V.deleteEntry)}</button></div>` : ''}`;
  const password = $('#vault-password', body);
  const reveal = $('[data-reveal]', body);
  const show = on => {
    password.type = on ? 'text' : 'password';
    reveal.textContent = on ? V.hide : V.show;
    reveal.setAttribute('aria-pressed', String(on));
  };
  reveal.addEventListener('click', () => show(password.type === 'password'));
  $('[data-generate]', body).addEventListener('click', () => {
    password.value = vaultGenerate();
    show(true);
    password.focus();
  });
  $$('[data-copy]', body).forEach(b => b.addEventListener('click', () => {
    const field = $(`#${b.dataset.copy}`, body);
    if (field.value) vaultCopy(field.value, b.dataset.copy === 'vault-password' ? V.copiedPassword : V.copiedUsername);
  }));
  $('[data-vault-delete]', body)?.addEventListener('click', async () => {
    if (!await confirmDialog({ title: V.deleteTitle, body: V.deleteBody({ name: entry.title }), okLabel: V.deleteEntry })) return;
    try {
      await vaultApi(`/admin/api/vault/entries/${encodeURIComponent(entry.id)}`, { method: 'DELETE' });
    } catch (e) {
      if (!vaultRefused(e)) toast(vaultErrorText(e));
      return;
    }
    vaultState.entries = vaultState.entries.filter(e => e.id !== entry.id);
    toast(V.deleted({ name: entry.title }));
    closeDrawer();
    if (currentView === 'vault') vaultPaintList();
  });
  openDrawer({
    title: entry ? entry.title : V.newEntry,
    eyebrow: V.nav,
    body,
    saveLabel: V.save,
    onSave: async () => {
      if (!vaultState.session) return true;
      const fields = {
        title: $('#vault-title', body).value.trim(),
        username: $('#vault-username', body).value.trim(),
        password: password.value,
        url: $('#vault-url', body).value.trim(),
        notes: $('#vault-notes', body).value.trim(),
      };
      if (!fields.title) {
        $('#vault-title', body).setAttribute('aria-invalid', 'true');
        $('#vault-title', body).focus();
        toast(V.nameRequired);
        return false;
      }
      const sealed = await vaultSeal(vaultState.session.entryKey, new TextEncoder().encode(JSON.stringify({ v: 1, ...fields })));
      let saved;
      try {
        saved = entry
          ? await vaultApi(`/admin/api/vault/entries/${encodeURIComponent(entry.id)}`, { method: 'PUT', body: JSON.stringify({ ...sealed, expectedUpdatedAt: entry.updatedAt }) })
          : await vaultApi('/admin/api/vault/entries', { method: 'POST', body: JSON.stringify(sealed) });
      } catch (e) {
        if (vaultRefused(e)) return true;
        toast(vaultErrorText(e));
        // Someone saved this login meanwhile: show theirs, so nobody's change is lost without a look.
        if (e.code === 'stale_entry' || e.code === 'entry_not_found') {
          await vaultLoadEntries().catch(() => {});
          if (currentView === 'vault') vaultPaintList();
          const fresh = vaultState.entries.find(x => x.id === entry?.id);
          return fresh ? () => vaultEntryDrawer(fresh) : true;
        }
        return false;
      }
      const opened = { ...fields, id: saved.id, createdAt: saved.createdAt, createdBy: saved.createdBy, updatedAt: saved.updatedAt, updatedBy: saved.updatedBy };
      vaultState.entries = entry ? vaultState.entries.map(x => (x.id === entry.id ? opened : x)) : [...vaultState.entries, opened];
      toast(V.saved({ name: fields.title }));
      if (currentView === 'vault') vaultPaintList();
      return true;
    },
  });
}

function vaultMetaLine(entry) {
  const V = T.vault;
  const who = email => email || V.passwordSession;
  const created = V.createdLine({ date: fmtDate(entry.createdAt), by: who(entry.createdBy) });
  return entry.updatedAt !== entry.createdAt ? `${created} · ${V.changedLine({ date: fmtDate(entry.updatedAt), by: who(entry.updatedBy) })}` : created;
}

// A new master password: the current one proves it's this team's member asking, then the same vault key is
// sealed again with the new one. The entries don't change.
function vaultMasterDrawer() {
  const V = T.vault;
  const body = document.createElement('div');
  body.dataset.vaultDrawer = '';
  body.innerHTML = `
    ${warnNoticeHtml(V.changeWarning)}
    <form class="form" novalidate>
      <div class="form__row"><label class="lbl" for="vault-current">${escapeHTML(V.currentLabel)}</label><input class="inp" id="vault-current" type="password" ${VAULT_FIELD_ATTRS} /></div>
      <div class="form__row"><label class="lbl" for="vault-next">${escapeHTML(V.newLabel)}</label><input class="inp" id="vault-next" type="password" ${VAULT_FIELD_ATTRS} /></div>
      <div class="form__row"><label class="lbl" for="vault-next-repeat">${escapeHTML(V.repeatLabel)}</label><input class="inp" id="vault-next-repeat" type="password" ${VAULT_FIELD_ATTRS} /></div>
      <p class="hint" data-vault-hint>${escapeHTML(V.masterHint({ n: VAULT_MIN_PASSWORD }))}</p>
    </form>`;
  const hint = $('[data-vault-hint]', body);
  openDrawer({
    title: V.changeMaster,
    eyebrow: V.nav,
    body,
    saveLabel: V.changeSave,
    onSave: async () => {
      if (!vaultState.session) return true;
      const current = $('#vault-current', body).value;
      const next = $('#vault-next', body).value;
      if (next.length < VAULT_MIN_PASSWORD) { vaultHintBad(hint, V.tooShort({ n: VAULT_MIN_PASSWORD })); $('#vault-next', body).focus(); return false; }
      if ($('#vault-next-repeat', body).value !== next) { vaultHintBad(hint, V.mismatch); $('#vault-next-repeat', body).focus(); return false; }
      try {
        // The vault as it is now, in case someone else changed the master password since this page unlocked it.
        const meta = await api('/admin/api/vault');
        let opened = null;
        try {
          opened = await vaultOpen(await vaultPasswordKey(current, meta.kdf), meta.wrappedKey);
        } catch {
          opened = null;
        }
        const same = opened && opened.length === vaultState.session.vaultKey.length && opened.every((b, i) => b === vaultState.session.vaultKey[i]);
        if (!same) { vaultHintBad(hint, V.currentWrong); $('#vault-current', body).focus(); return false; }
        const kdf = vaultNewKdf();
        const wrappedKey = await vaultSeal(await vaultPasswordKey(next, kdf), vaultState.session.vaultKey);
        vaultState.meta = await vaultApi('/admin/api/vault/key', { method: 'PUT', body: JSON.stringify({ kdf, wrappedKey }) });
      } catch (e) {
        if (vaultRefused(e)) return true;
        toast(vaultErrorText(e));
        return false;
      }
      toast(V.changed);
      return true;
    },
  });
}

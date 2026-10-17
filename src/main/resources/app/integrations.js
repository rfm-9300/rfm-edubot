/* Connecting the company's accounts from Settings → Channels: Instagram (an OAuth popup), Gmail (Google's
   consent in a popup or a redirect, and whether it reads the inbox) and WhatsApp (Meta's Embedded Signup).
   Also the OAuth popup's side: back on /app, it hands its outcome to the dashboard that opened it. */

let fbSdkPromise = null;

async function connectInstagram() {
  let res;
  try { res = await api('/app/api/instagram/connect'); }
  catch (e) { toast(e.message === 'unauthorized' ? STR.sessionExpired : STR.igUnavailable); return; }
  const popup = window.open(res.authorizeUrl, 'ig-oauth', 'width=600,height=750');
  if (!popup) { toast(STR.igAllowPopups); return; }
  const onMsg = async ev => {
    if (ev.origin !== window.location.origin || ev.data?.type !== 'ig-oauth') return;
    window.removeEventListener('message', onMsg);
    if (ev.data.status === 'connected') {
      toast(STR.igConnected);
      state.me = await api('/app/api/me');
      if (state.active === 'settings') render();
    } else {
      toast(STR.igFailed(ev.data.reason));
    }
  };
  window.addEventListener('message', onMsg);
}

async function disconnectInstagram(ig) {
  if (!ig) return;
  const ok = await confirmDialog({
    title: STR.igDisconnectConfirmTitle,
    body: STR.igDisconnectConfirmBody({ account: ig.displayName ? '@' + ig.displayName : ig.externalId }),
    okLabel: STR.igDisconnectAction,
  });
  if (!ok) return;
  try {
    await api(`/app/api/channels/INSTAGRAM/${encodeURIComponent(ig.externalId)}`, { method: 'DELETE' });
    state.me = await api('/app/api/me');
    toast(STR.igDisconnected);
    render();
  } catch { toast(STR.igDisconnectFailed); }
}

// ── Email (Google) ──────────────────────────────────────────────────────────────
const googleAccounts = () => (state.integrations?.connections || []).filter(c => c.provider === 'google');
// Reconnecting an account that reads its inbox asks for reading again, so it doesn't stay paused.
const readsInbox = account => !!(account?.inboxSync && state.integrations?.google?.inbox);

async function refreshGoogle() {
  const [integrations, email] = await Promise.all([
    hasModule('settings') ? api('/app/api/integrations').catch(() => state.integrations) : Promise.resolve(state.integrations),
    api('/app/api/email').catch(() => state.email),
  ]);
  state.integrations = integrations;
  state.email = email;
}

// Whether the company can email documents, and from which account; Settings refreshes it.
async function emailStatus() {
  if (!state.email) state.email = await api('/app/api/email').catch(() => null);
  return state.email;
}

// Hidden while the platform has no Google OAuth client.
function googleRowsHtml() {
  const info = state.integrations;
  if (!info?.google?.configured) return '';
  const canConnect = !!info.google.canConnect;
  const accounts = googleAccounts();
  const name = `<td class="name">${escapeHTML(GOOGLE.rowName)}</td>`;
  if (!accounts.length) {
    const action = canConnect
      ? `<button class="btn btn--sm" type="button" data-google-connect>${escapeHTML(GOOGLE.connect)}</button>`
      : `<span class="muted">${escapeHTML(GOOGLE.adminsOnly)}</span>`;
    return `<tr>${name}<td class="mono">—</td><td><span class="muted">${escapeHTML(STR.notConnected)}</span></td><td class="right">${action}</td></tr>`;
  }
  return accounts.map((c, i) => {
    const status = c.status === 'ACTIVE'
      ? escapeHTML(GOOGLE.status.ACTIVE)
      : `<span class="pill pill--${c.status === 'REVOKED' ? 'bad' : 'warn'}">${escapeHTML(googleTextOr(`status.${c.status}`, c.status))}</span>`;
    const isDefault = accounts.length > 1 && c.isDefault ? ` <span class="pill pill--info">${escapeHTML(GOOGLE.isDefault)}</span>` : '';
    const inbox = info.google.inbox && c.inboxSync && c.status === 'ACTIVE'
      ? ` <span class="pill ${c.inboxError ? 'pill--warn' : 'pill--accent'}">${escapeHTML(c.inboxError ? GOOGLE.inbox.rowPaused : GOOGLE.inbox.rowOn)}</span>`
      : '';
    const actions = [
      canConnect && c.status !== 'ACTIVE' ? `<button class="btn btn--sm btn--primary" type="button" data-google-connect="${escapeHTML(c.accountEmail)}">${escapeHTML(GOOGLE.reconnect)}</button>` : '',
      info.canManage ? `<button class="btn btn--sm" type="button" data-google-account="${escapeHTML(c.id)}">${escapeHTML(GOOGLE.manage)}</button>` : '',
      canConnect && i === accounts.length - 1 ? `<button class="btn btn--sm btn--ghost" type="button" data-google-connect>${escapeHTML(GOOGLE.addAccount)}</button>` : '',
    ].filter(Boolean).join(' ');
    return `<tr>${name}<td class="mono">${escapeHTML(c.accountEmail)}</td><td>${status}${isDefault}${inbox}</td><td class="right">${actions}</td></tr>`;
  }).join('');
}

// Google's pages send Cross-Origin-Opener-Policy, which can cut the popup off from this window on its
// way back; the popup then answers over this same-origin channel. The key marks a popup being open.
const GOOGLE_OAUTH_CHANNEL = 'google-oauth';
const GOOGLE_POPUP_KEY = 'googleOAuthPopup';
let googleChannel = null;

// `account` reconnects that account: Google's screen opens on it. `inbox` also asks to read its mail.
async function connectGoogle(account, { inbox = false } = {}) {
  // Opened before any await: browsers only allow a popup straight from the click.
  const popup = window.open('', 'google-oauth', 'width=520,height=680');
  const query = new URLSearchParams();
  if (account) query.set('account', account);
  if (inbox) query.set('inbox', '1');
  let res;
  try { res = await api(`/app/api/integrations/google/connect${query.toString() ? `?${query}` : ''}`); }
  catch (e) {
    popup?.close();
    if (e.message !== 'unauthorized') toast(googleTextOr(`connectErrors.${e.code || ''}`, GOOGLE.connectFailed));
    return;
  }
  // A blocked popup: the whole page goes to Google and comes back to /app/?google=… (takeGoogleRedirect).
  if (!popup) {
    try { localStorage.removeItem(GOOGLE_POPUP_KEY); } catch (_) {}
    window.location.assign(res.authorizeUrl);
    return;
  }
  try { localStorage.setItem(GOOGLE_POPUP_KEY, String(Date.now())); } catch (_) {}
  popup.location.href = res.authorizeUrl;
  listenForGoogle();
}

function listenForGoogle() {
  stopListeningForGoogle();
  window.addEventListener('message', onGoogleMessage);
  if ('BroadcastChannel' in window) {
    googleChannel = new BroadcastChannel(GOOGLE_OAUTH_CHANNEL);
    googleChannel.onmessage = ev => onGoogleResult(ev.data);
  }
}

function stopListeningForGoogle() {
  window.removeEventListener('message', onGoogleMessage);
  googleChannel?.close();
  googleChannel = null;
}

function onGoogleMessage(ev) {
  if (ev.origin === window.location.origin) onGoogleResult(ev.data);
}

function onGoogleResult(data) {
  if (data?.type !== 'google-oauth') return;
  stopListeningForGoogle();
  googleOutcome(data.status, data.reason);
}

// Whether this window is the consent popup a dashboard opened (set by connectGoogle), read once.
function takeGooglePopupMark() {
  try {
    const at = Number(localStorage.getItem(GOOGLE_POPUP_KEY));
    localStorage.removeItem(GOOGLE_POPUP_KEY);
    return at > 0 && Date.now() - at < 30 * 60 * 1000;
  } catch (_) { return false; }
}

async function googleOutcome(status, reason) {
  if (status === 'connected' || status === 'inbox') {
    toast(status === 'inbox' ? GOOGLE.inbox.turnedOn : GOOGLE.connected);
  } else {
    toast(googleTextOr(`reasons.${reason || ''}`, GOOGLE.connectFailed));
    // Reading was refused but sending was allowed, so the account did connect.
    if (reason !== 'missing_inbox_scope') return;
  }
  await refreshGoogle();
  if (state.active === 'settings') render();
  if (state.active === 'email') await window.EmailUI?.refresh();
}

// Google sent the whole page back (no popup): read the outcome once and drop it from the address.
function takeGoogleRedirect() {
  const params = new URLSearchParams(window.location.search);
  const status = params.get('google');
  if (!status) return null;
  const reason = params.get('reason');
  params.delete('google');
  params.delete('reason');
  const query = params.toString();
  history.replaceState(null, '', `${window.location.pathname}${query ? `?${query}` : ''}#settings`);
  return { status, reason };
}

// Only while the platform lets companies read inboxes. Its buttons act at once, apart from Save: turning
// reading on may need Google's consent, and that popup has to open straight from the click.
function emailInboxPanelHtml(account) {
  const google = state.integrations?.google;
  if (!google?.inbox) return '';
  const I = GOOGLE.inbox;
  const on = !!account.inboxSync;
  const active = account.status === 'ACTIVE';
  const failing = on && active && !!account.inboxError;
  const pill = !on ? `<span class="pill">${escapeHTML(I.off)}</span>`
    : `<span class="pill ${active && !failing ? 'pill--ok' : 'pill--warn'}">${escapeHTML(active && !failing ? I.on : I.paused)}</span>`;
  const detail = !on ? `<p class="view__desc">${escapeHTML(I.offDetail)}</p>`
    : !active ? `<p class="view__desc">${escapeHTML(I.pausedReconnect)}</p>`
      : failing ? `<p class="hint hint--warn">${escapeHTML(googleTextOr(`inbox.errors.${account.inboxError}`, I.errors.generic))}</p>`
        : `<p class="view__desc">${escapeHTML(account.inboxSyncedAt ? googleText('inbox.syncedAt', { when: relTime(account.inboxSyncedAt) }) : I.firstSync)}</p>`;
  const actions = !on
    ? (google.canConnect && active ? `<button class="btn btn--sm btn--primary" type="button" data-ga-inbox="on">${escapeHTML(I.turnOn)}</button>` : google.canConnect ? '' : `<span class="muted">${escapeHTML(I.adminsOnly)}</span>`)
    : [
      failing && account.inboxError === 'missing_scope' && google.canConnect ? `<button class="btn btn--sm btn--primary" type="button" data-ga-inbox="consent">${escapeHTML(I.allowReading)}</button>` : '',
      `<button class="btn btn--sm btn--ghost" type="button" data-ga-inbox="off">${escapeHTML(I.turnOff)}</button>`,
    ].filter(Boolean).join('');
  return `<section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(I.title)}</h2>${pill}</header>
    <div class="panel__body form">${detail}<p class="hint">${escapeHTML(hasModule('email') ? I.hintEmailPage : I.hint)}</p>${actions ? `<div class="actions">${actions}</div>` : ''}</div></section>`;
}

function openEmailAccount(id) {
  const account = googleAccounts().find(c => c.id === id);
  if (!account) return;
  const accounts = googleAccounts();
  const canConnect = !!state.integrations?.google?.canConnect;
  const company = state.documentTemplate?.companyName || state.me?.tenant?.name || '';
  const revoked = account.status === 'REVOKED';
  const optional = `<span class="opt">${escapeHTML(STR.optional)}</span>`;
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    ${account.status !== 'ACTIVE' ? `<div class="notice notice--warn">
      <div class="notice__text"><strong>${escapeHTML(revoked ? GOOGLE.revokedTitle : GOOGLE.reconnectTitle)}</strong><span>${escapeHTML(revoked ? GOOGLE.revokedDesc : GOOGLE.reconnectDesc)}</span></div>
      ${canConnect ? `<div class="notice__actions"><button class="btn btn--sm btn--primary" type="button" data-ga-reconnect>${escapeHTML(GOOGLE.reconnect)}</button></div>` : ''}
    </div>` : ''}
    <dl class="dash-facts">
      <div><dt>${escapeHTML(GOOGLE.factAccount)}</dt><dd>${escapeHTML(account.accountEmail)}</dd></div>
      ${account.connectedBy ? `<div><dt>${escapeHTML(GOOGLE.factConnectedBy)}</dt><dd>${escapeHTML(account.connectedBy)}</dd></div>` : ''}
      ${account.dailyLimit ? `<div><dt>${escapeHTML(GOOGLE.factSentToday)}</dt><dd data-ga-sent>${escapeHTML(googleText('sentToday', { sent: account.sentToday || 0, limit: account.dailyLimit }))}</dd></div>` : ''}
    </dl>
    ${emailInboxPanelHtml(account)}
    <div class="form__row"><label class="lbl" for="ga-name">${escapeHTML(GOOGLE.senderName)} ${optional}</label>
      <input class="inp" id="ga-name" maxlength="80" autocomplete="off" placeholder="${escapeHTML(company)}" value="${escapeHTML(account.senderName || '')}" />
      <p class="hint">${escapeHTML(googleText('senderNameHint', { company }))}</p></div>
    <div class="form__row"><label class="lbl" for="ga-reply">${escapeHTML(GOOGLE.replyTo)} ${optional}</label>
      <input class="inp" id="ga-reply" type="email" maxlength="254" autocomplete="off" placeholder="${escapeHTML(account.accountEmail)}" value="${escapeHTML(account.replyTo || '')}" />
      <p class="hint">${escapeHTML(GOOGLE.replyToHint)}</p></div>
    <div class="form__row"><label class="lbl" for="ga-signature">${escapeHTML(GOOGLE.signature)} ${optional}</label>
      <textarea class="txt" id="ga-signature" rows="4" maxlength="2000">${escapeHTML(account.signature || '')}</textarea>
      <p class="hint">${escapeHTML(GOOGLE.signatureHint)}</p></div>
    ${accounts.length > 1 ? (account.isDefault
      ? `<p class="hint">${escapeHTML(GOOGLE.defaultHint)}</p>`
      : `<label class="form__check"><input type="checkbox" id="ga-default" /> ${escapeHTML(GOOGLE.makeDefault)}</label>`) : ''}
    <div class="actions">
      <button class="btn btn--primary" type="submit">${escapeHTML(GOOGLE.save)}</button>
      ${account.canSend ? `<button class="btn btn--ghost" type="button" data-ga-test>${escapeHTML(GOOGLE.test)}</button>` : ''}
      <button class="btn btn--ghost" type="button" data-ga-disconnect>${escapeHTML(GOOGLE.disconnect)}</button>
    </div>`;
  const path = `/app/api/integrations/${encodeURIComponent(account.id)}`;
  $('[data-ga-reconnect]', form)?.addEventListener('click', () => {
    closeDrawer({ dismissed: true });
    connectGoogle(account.accountEmail, { inbox: readsInbox(account) });
  });
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    const payload = { senderName: $('#ga-name', form).value.trim(), replyTo: $('#ga-reply', form).value.trim(), signature: $('#ga-signature', form).value.trim() };
    if ($('#ga-default', form)?.checked) payload.isDefault = true;
    try {
      await api(path, { method: 'PATCH', body: JSON.stringify(payload) });
      await refreshGoogle();
      closeDrawer();
      toast(GOOGLE.saved);
      if (state.active === 'settings') render();
    } catch (err) {
      btn.disabled = false;
      if (err.message !== 'unauthorized') toast(googleTextOr(`errors.${err.code || ''}`, GOOGLE.saveFailed));
    }
  });
  $('[data-ga-test]', form)?.addEventListener('click', async e => {
    const btn = e.currentTarget;
    btn.disabled = true;
    try {
      const sent = await api(`${path}/test-email`, { method: 'POST' });
      toast(googleText('testSent', { to: sent.to }));
      await refreshGoogle();
      const fresh = googleAccounts().find(c => c.id === account.id);
      const counter = $('[data-ga-sent]', form);
      if (fresh?.dailyLimit && counter) counter.textContent = googleText('sentToday', { sent: fresh.sentToday || 0, limit: fresh.dailyLimit });
    } catch (err) {
      if (err.message === 'unauthorized') return;
      toast(googleTextOr(`sendErrors.${err.code || ''}`, GOOGLE.testFailed));
      // A refused grant flips the account to needing a reconnect on the server.
      await refreshGoogle();
      if (state.active === 'settings') render();
      const fresh = googleAccounts().find(c => c.id === account.id);
      if (fresh && fresh.status !== account.status && form.isConnected) openEmailAccount(fresh.id);
    } finally { btn.disabled = false; }
  });
  $$('[data-ga-inbox]', form).forEach(b => b.addEventListener('click', async () => {
    const mode = b.dataset.gaInbox;
    if (mode === 'consent' || (mode === 'on' && !account.canRead)) {
      closeDrawer({ dismissed: true });
      connectGoogle(account.accountEmail, { inbox: true });
      return;
    }
    b.disabled = true;
    try {
      await api(path, { method: 'PATCH', body: JSON.stringify({ inboxSync: mode === 'on' }) });
      toast(mode === 'on' ? GOOGLE.inbox.turnedOn : GOOGLE.inbox.turnedOff);
    } catch (err) {
      if (err.message === 'unauthorized') return;
      toast(googleTextOr(`inbox.patchErrors.${err.code || ''}`, GOOGLE.inbox.failed));
    }
    // A refused change (Google took reading back meanwhile) redraws the panel with what the server knows.
    await refreshGoogle();
    if (state.active === 'settings') render();
    if (form.isConnected && googleAccounts().some(c => c.id === account.id)) openEmailAccount(account.id);
  }));
  $('[data-ga-disconnect]', form).addEventListener('click', () => disconnectGoogle(account));
  openDrawer(account.accountEmail, form, false, { eyebrow: GOOGLE.eyebrow });
}

async function disconnectGoogle(account) {
  const ok = await confirmDialog({
    title: googleText('disconnectTitle', { account: account.accountEmail }),
    body: GOOGLE.disconnectBody,
    okLabel: GOOGLE.disconnect,
  });
  if (!ok) return;
  try {
    await api(`/app/api/integrations/${encodeURIComponent(account.id)}`, { method: 'DELETE' });
    await refreshGoogle();
    closeDrawer({ dismissed: true });
    toast(GOOGLE.disconnected);
    if (state.active === 'settings') render();
  } catch (err) {
    if (err.message !== 'unauthorized') toast(GOOGLE.disconnectFailed);
  }
}

function loadFacebookSdk(appId, graphVersion) {
  if (window.FB) {
    window.FB.init({ appId, version: graphVersion, cookie: true, xfbml: false });
    return Promise.resolve(window.FB);
  }
  if (fbSdkPromise) return fbSdkPromise;
  fbSdkPromise = new Promise((resolve, reject) => {
    window.fbAsyncInit = () => {
      window.FB.init({ appId, version: graphVersion, cookie: true, xfbml: false });
      resolve(window.FB);
    };
    const script = document.createElement('script');
    script.id = 'facebook-jssdk';
    script.src = 'https://connect.facebook.net/en_US/sdk.js';
    script.async = true;
    script.defer = true;
    script.onerror = () => reject(new Error('facebook_sdk_load_failed'));
    document.body.appendChild(script);
  });
  return fbSdkPromise;
}

function waitForWhatsAppSignupMessage() {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      window.removeEventListener('message', onMessage);
      reject(new Error('signup_message_timeout'));
    }, 5 * 60 * 1000);
    const onMessage = ev => {
      if (!['https://www.facebook.com', 'https://web.facebook.com'].includes(ev.origin)) return;
      const data = typeof ev.data === 'string' ? (() => { try { return JSON.parse(ev.data); } catch (_) { return null; } })() : ev.data;
      if (data?.type !== 'WA_EMBEDDED_SIGNUP') return;
      if (data.event && data.event !== 'FINISH') {
        clearTimeout(timer);
        window.removeEventListener('message', onMessage);
        reject(new Error(data.event === 'CANCEL' ? 'signup_cancelled' : 'signup_not_finished'));
        return;
      }
      const wabaId = data.data?.waba_id || data.data?.wabaId;
      const phoneNumberId = data.data?.phone_number_id || data.data?.phoneNumberId;
      if (!wabaId || !phoneNumberId) return;
      clearTimeout(timer);
      window.removeEventListener('message', onMessage);
      resolve({ wabaId, phoneNumberId });
    };
    window.addEventListener('message', onMessage);
  });
}

function facebookLoginForBusiness(FB, configId) {
  return new Promise((resolve, reject) => {
    FB.login(response => {
      const code = response?.authResponse?.code;
      if (!code) {
        reject(new Error(response?.status === 'not_authorized' ? 'not_authorized' : 'missing_code'));
        return;
      }
      resolve(code);
    }, {
      config_id: configId,
      response_type: 'code',
      override_default_response_type: true,
      extras: { setup: {} },
    });
  });
}

async function connectWhatsApp() {
  const cfg = state.whatsAppSignup;
  if (!cfg?.enabled) { toast(STR.waNotConfigured); return; }
  try {
    const FB = await loadFacebookSdk(cfg.appId, cfg.graphVersion || 'v21.0');
    toast(STR.waOpenPopup);
    const sessionPromise = waitForWhatsAppSignupMessage();
    const codePromise = facebookLoginForBusiness(FB, cfg.configId);
    const [session, code] = await Promise.all([sessionPromise, codePromise]);
    await api('/app/api/whatsapp/connect', {
      method: 'POST',
      body: JSON.stringify({ code, wabaId: session.wabaId, phoneNumberId: session.phoneNumberId }),
    });
    toast(STR.waConnected);
    state.me = await api('/app/api/me');
    if (state.active === 'settings') render();
  } catch (e) {
    const messages = {
      signup_cancelled: STR.waCancelled,
      missing_code: STR.waMissingCode,
      signup_message_timeout: STR.waTimeout,
      facebook_sdk_load_failed: STR.waSdkFailed,
      unauthorized: STR.sessionExpired,
    };
    toast(messages[e.message] || STR.waFailed({ msg: e.message }));
  }
}

// When the OAuth popup lands back on /app/?ig=... or /app/?google=..., relay the outcome to the
// opener (or, for Google, over GOOGLE_OAUTH_CHANNEL) and close. Returns true if this load was an
// OAuth popup (so the normal app boot is skipped).
function handleOAuthPopup() {
  const params = new URLSearchParams(window.location.search);
  const ig = params.get('ig');
  const google = params.get('google');
  if (!ig && !google) return false;
  const message = ig
    ? { type: 'ig-oauth', status: ig, reason: params.get('reason'), tenant: params.get('tenant') }
    : { type: 'google-oauth', status: google, reason: params.get('reason') };
  if (window.opener) {
    window.opener.postMessage(message, window.location.origin);
    window.close();
    return true;
  }
  if (!google || !('BroadcastChannel' in window) || !takeGooglePopupMark()) return false;
  const channel = new BroadcastChannel(GOOGLE_OAUTH_CHANNEL);
  channel.postMessage(message);
  channel.close();
  window.close();
  // Cut off from its opener, the popup may no longer count as opened by a script and stay open: it shows the app.
  setTimeout(() => { if (!window.closed) init(true); }, 500);
  return true;
}

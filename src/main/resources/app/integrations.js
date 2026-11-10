/* Connecting the company's accounts from Settings → Channels: Instagram (an OAuth popup), the company's own
   email address (forwarding to it, sending from it), Gmail (Google's consent in a popup or a redirect, and
   whether it reads the inbox; not offered while companies get their own address) and WhatsApp (Meta's Embedded Signup).
   Also the OAuth popup's side: back on /app, it hands its outcome to the dashboard that opened it. Meta's SDK
   and Instagram's consent popup are in /admin/common.js, shared with the backoffice. */

async function connectInstagram() {
  let res;
  try { res = await api('/app/api/instagram/connect'); }
  catch (e) { toast(e.message === 'unauthorized' ? STR.sessionExpired : STR.igUnavailable); return; }
  const outcome = await instagramConsent(res.authorizeUrl);
  if (!outcome) { toast(STR.igAllowPopups); return; }
  if (outcome.status !== 'connected') { toast(STR.igFailed(outcome.reason)); return; }
  toast(STR.igConnected);
  state.me = await api('/app/api/me');
  if (state.active === 'settings') render();
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

// Hidden while the platform has no Google OAuth client. While companies get their own address, Gmail isn't
// offered any more: only an account a company already connected is listed, so it can still be managed.
function googleRowsHtml() {
  const info = state.integrations;
  if (!info?.google?.configured) return '';
  const accounts = googleAccounts();
  if (mailboxOn() && !accounts.length) return '';
  const canConnect = !!info.google.canConnect && !mailboxOn();
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

// ── Email (the company's own address) ───────────────────────────────────────────
// The company forwards its mail to an address on the platform's domain (Amazon SES receives it), and its
// email goes out from that address. Anyone who manages settings sets it up: nothing is read until the
// company turns forwarding on in its own mailbox.
const mailboxAccount = () => (state.integrations?.connections || []).find(c => c.provider === 'mailbox') || null;
const mailboxOn = () => !!state.integrations?.mailbox?.configured;
// The forwarding steps each provider's tab lists (`app.integrations.mailbox.step.<provider><n>`).
const MAILBOX_STEPS = { gmail: 4, outlook: 3, other: 3 };
let mailboxProvider = 'gmail';

function mailboxRowHtml() {
  if (!mailboxOn()) return '';
  const info = state.integrations;
  const account = mailboxAccount();
  const name = `<td class="name">${escapeHTML(MAILBOX.rowName)}</td>`;
  if (!account) {
    const action = info.canManage
      ? `<button class="btn btn--sm btn--primary" type="button" data-mailbox-setup>${escapeHTML(MAILBOX.setUp)}</button>`
      : `<span class="muted">${escapeHTML(MAILBOX.adminsOnly)}</span>`;
    return `<tr>${name}<td class="mono">—</td><td><span class="muted">${escapeHTML(MAILBOX.notSetUp)}</span></td><td class="right">${action}</td></tr>`;
  }
  const status = account.confirmation
    ? `<span class="pill pill--info">${escapeHTML(MAILBOX.statusCode)}</span>`
    : account.receivedAt ? escapeHTML(MAILBOX.statusReceiving) : `<span class="pill pill--warn">${escapeHTML(MAILBOX.statusWaiting)}</span>`;
  const action = info.canManage ? `<button class="btn btn--sm" type="button" data-mailbox-open>${escapeHTML(MAILBOX.manage)}</button>` : '';
  return `<tr>${name}<td class="mono">${escapeHTML(account.accountEmail)}</td><td>${status}</td><td class="right">${action}</td></tr>`;
}

// Gives the company its address (or opens the one it has) and shows how to forward to it.
async function setupMailbox() {
  let account;
  try { account = await api('/app/api/integrations/mailbox', { method: 'POST' }); }
  catch (err) {
    if (err.message !== 'unauthorized') toast(MAILBOX.setUpFailed);
    return;
  }
  await refreshGoogle();
  if (state.active === 'settings') render();
  if (state.active === 'email') await window.EmailUI?.refresh();
  toast(MAILBOX.created);
  openMailbox(account.id);
}

function mailboxStepsHtml(provider) {
  const count = MAILBOX_STEPS[provider] || 0;
  const steps = Array.from({ length: count }, (_, i) => `<li>${escapeHTML(mailboxText(`step.${provider}${i + 1}`))}</li>`).join('');
  return `<ol class="plain-list plain-list--steps">${steps}</ol>
    <p class="hint">${escapeHTML(mailboxText(`note.${provider}`))}</p>
    <p class="hint">${escapeHTML(MAILBOX.byHand)} ${escapeHTML(MAILBOX.ownReplies)}</p>`;
}

// What Gmail asked, with the code to type and its own confirmation page. Only Google's links reach here.
function mailboxConfirmationHtml(confirmation, actions = true) {
  if (!confirmation) return '';
  const desc = !confirmation.code ? MAILBOX.confirmLink
    : confirmation.account ? mailboxText('confirmCodeFor', { account: confirmation.account, code: confirmation.code })
      : mailboxText('confirmCode', { code: confirmation.code });
  const open = confirmation.link
    ? `<a class="btn btn--sm btn--primary" href="${escapeHTML(confirmation.link)}" target="_blank" rel="noopener noreferrer">${escapeHTML(MAILBOX.confirmOpen)}</a>`
    : '';
  const done = actions ? `<button class="btn btn--sm btn--ghost" type="button" data-mb-confirm-done>${escapeHTML(MAILBOX.confirmDone)}</button>` : '';
  return `<div class="notice notice--info" role="status">
    <div class="notice__text"><strong>${escapeHTML(MAILBOX.confirmTitle)}</strong><span>${escapeHTML(desc)}</span></div>
    ${open || done ? `<div class="notice__actions">${open}${done}</div>` : ''}
  </div>`;
}

function openMailbox(id) {
  const account = mailboxAccount();
  if (!account || (id && account.id !== id)) return;
  const company = state.documentTemplate?.companyName || state.me?.tenant?.name || '';
  const optional = `<span class="opt">${escapeHTML(STR.optional)}</span>`;
  const chips = Object.keys(MAILBOX_STEPS).map(p => `<button type="button" class="chip${p === mailboxProvider ? ' is-on' : ''}" data-mb-provider="${p}" aria-pressed="${p === mailboxProvider}">${escapeHTML(mailboxText(`provider.${p}`))}</button>`).join('');
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    ${mailboxConfirmationHtml(account.confirmation)}
    <div class="form__row"><label class="lbl" for="mb-address">${escapeHTML(MAILBOX.addressLabel)}</label>
      <div class="inp-row"><input class="inp mono" id="mb-address" readonly value="${escapeHTML(account.accountEmail)}" />
        <button class="btn btn--sm" type="button" data-mb-copy>${escapeHTML(MAILBOX.copy)}</button></div></div>
    <dl class="dash-facts">
      <div><dt>${escapeHTML(MAILBOX.factLastEmail)}</dt><dd>${escapeHTML(account.receivedAt ? relTime(account.receivedAt) : MAILBOX.factNoneYet)}</dd></div>
      ${account.forwarders?.length ? `<div><dt>${escapeHTML(MAILBOX.factForwardedFrom)}</dt><dd>${escapeHTML(account.forwarders.join(', '))}</dd></div>` : ''}
      ${account.dailyLimit ? `<div><dt>${escapeHTML(GOOGLE.factSentToday)}</dt><dd data-mb-sent>${escapeHTML(googleText('sentToday', { sent: account.sentToday || 0, limit: account.dailyLimit }))}</dd></div>` : ''}
    </dl>
    <section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(MAILBOX.forwardTitle)}</h2>
      <div class="panel__tools panel__tools--chips" role="group" aria-label="${escapeHTML(MAILBOX.providersAria)}">${chips}</div></header>
      <div class="panel__body" data-mb-steps>${mailboxStepsHtml(mailboxProvider)}</div></section>
    <div class="form__row"><label class="lbl" for="mb-name">${escapeHTML(GOOGLE.senderName)} ${optional}</label>
      <input class="inp" id="mb-name" maxlength="80" autocomplete="off" placeholder="${escapeHTML(company)}" value="${escapeHTML(account.senderName || '')}" />
      <p class="hint">${escapeHTML(googleText('senderNameHint', { company }))}</p></div>
    <div class="form__row"><label class="lbl" for="mb-reply">${escapeHTML(GOOGLE.replyTo)} ${optional}</label>
      <input class="inp" id="mb-reply" type="email" maxlength="254" autocomplete="off" placeholder="${escapeHTML(account.accountEmail)}" value="${escapeHTML(account.replyTo || '')}" />
      <p class="hint">${escapeHTML(MAILBOX.replyToHint)}</p></div>
    <div class="form__row"><label class="lbl" for="mb-signature">${escapeHTML(GOOGLE.signature)} ${optional}</label>
      <textarea class="txt" id="mb-signature" rows="4" maxlength="2000">${escapeHTML(account.signature || '')}</textarea>
      <p class="hint">${escapeHTML(GOOGLE.signatureHint)}</p></div>
    <div class="actions">
      <button class="btn btn--primary" type="submit">${escapeHTML(GOOGLE.save)}</button>
      ${account.canSend ? `<button class="btn btn--ghost" type="button" data-mb-test>${escapeHTML(GOOGLE.test)}</button>` : ''}
      <button class="btn btn--ghost" type="button" data-mb-remove>${escapeHTML(MAILBOX.remove)}</button>
    </div>`;
  const path = `/app/api/integrations/${encodeURIComponent(account.id)}`;
  $$('[data-mb-provider]', form).forEach(chip => chip.addEventListener('click', () => {
    mailboxProvider = chip.dataset.mbProvider;
    $$('[data-mb-provider]', form).forEach(c => { const on = c === chip; c.classList.toggle('is-on', on); c.setAttribute('aria-pressed', String(on)); });
    $('[data-mb-steps]', form).innerHTML = mailboxStepsHtml(mailboxProvider);
  }));
  $('[data-mb-copy]', form).addEventListener('click', async () => {
    const input = $('#mb-address', form);
    try { await navigator.clipboard.writeText(account.accountEmail); toast(MAILBOX.copied); }
    catch { input.focus(); input.select(); toast(MAILBOX.copyFailed); }
  });
  $('[data-mb-confirm-done]', form)?.addEventListener('click', async e => {
    e.currentTarget.disabled = true;
    try {
      await api(`${path}/confirmation`, { method: 'DELETE' });
      toast(MAILBOX.confirmHidden);
      await refreshGoogle();
      if (state.active === 'settings') render();
      if (state.active === 'email') await window.EmailUI?.refresh();
      if (form.isConnected) openMailbox(account.id);
    } catch (err) {
      e.currentTarget.disabled = false;
      if (err.message !== 'unauthorized') toast(GOOGLE.saveFailed);
    }
  });
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    const payload = { senderName: $('#mb-name', form).value.trim(), replyTo: $('#mb-reply', form).value.trim(), signature: $('#mb-signature', form).value.trim() };
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
  $('[data-mb-test]', form)?.addEventListener('click', async e => {
    const btn = e.currentTarget;
    btn.disabled = true;
    try {
      const sent = await api(`${path}/test-email`, { method: 'POST' });
      toast(googleText('testSent', { to: sent.to }));
      await refreshGoogle();
      const fresh = mailboxAccount();
      const counter = $('[data-mb-sent]', form);
      if (fresh?.dailyLimit && counter) counter.textContent = googleText('sentToday', { sent: fresh.sentToday || 0, limit: fresh.dailyLimit });
    } catch (err) {
      if (err.message !== 'unauthorized') toast(googleTextOr(`sendErrors.${err.code || ''}`, GOOGLE.testFailed));
    } finally { btn.disabled = false; }
  });
  $('[data-mb-remove]', form).addEventListener('click', () => removeMailbox(account));
  openDrawer(account.accountEmail, form, false, { eyebrow: MAILBOX.eyebrow });
}

async function removeMailbox(account) {
  const ok = await confirmDialog({
    title: mailboxText('removeTitle', { address: account.accountEmail }),
    body: MAILBOX.removeBody,
    okLabel: MAILBOX.remove,
  });
  if (!ok) return;
  try {
    await api(`/app/api/integrations/${encodeURIComponent(account.id)}`, { method: 'DELETE' });
    await refreshGoogle();
    closeDrawer({ dismissed: true });
    toast(MAILBOX.removed);
    if (state.active === 'settings') render();
  } catch (err) {
    if (err.message !== 'unauthorized') toast(MAILBOX.removeFailed);
  }
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

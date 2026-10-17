/* Getting in: the sign-in screen (Google or email and password) and the account drawer, where the
   signed-in user manages both ways of signing in. Google's sign-in itself is in /admin/common.js. */

let authConfigLoad = null;

function loadAuthConfig() {
  authConfigLoad = authConfigLoad || fetch('/app/auth/config')
    .then(r => (r.ok ? r.json() : {}))
    .catch(() => { authConfigLoad = null; return {}; });
  return authConfigLoad;
}

function googlePopupErrorText(err) {
  const code = String(err?.code || '');
  if (code === 'auth/popup-closed-by-user' || code === 'auth/cancelled-popup-request') return STR.loginGoogleCancelled;
  if (code === 'auth/popup-blocked') return STR.loginGooglePopupBlocked;
  return STR.loginGoogleFailed;
}

async function startDashboardSession(newToken) {
  token = newToken;
  localStorage.setItem('dashboardToken', token);
  await bootAuthed();
}

async function renderLogin() {
  stopInboxPolling();
  stopNotifications();
  $('#nav').innerHTML = '';
  $('#btn-account').hidden = true;
  renderCompanySwitch();
  if (!$('#drawer')?.hidden) closeDrawer({ dismissed: true });
  const google = (await loadAuthConfig()).google || null;
  if (google) prepareGoogleSignIn(google).catch(() => {});
  $('#view').innerHTML = `<div class="auth"><div class="auth__card"><div class="auth__mark">AI</div><p class="auth__eyebrow">${escapeHTML(STR.loginEyebrow)}</p><h1 class="auth__title">${escapeHTML(STR.loginTitle)}</h1><p class="auth__desc">${escapeHTML(STR.loginDesc)}</p>
    ${google ? `<button class="btn btn--primary" type="button" id="login-google">${escapeHTML(STR.loginGoogle)}</button>
      <p class="hint hint--warn" id="login-google-error" role="alert" hidden></p>
      <p class="auth__desc">${escapeHTML(STR.loginOr)}</p>` : ''}
    <form class="form" id="login-form"><div class="form__row"><label class="lbl" for="email">${escapeHTML(STR.loginEmail)}</label><input class="inp" id="email" type="email" autocomplete="email" required /></div><div class="form__row"><label class="lbl" for="password">${escapeHTML(STR.loginPassword)}</label><input class="inp" id="password" type="password" autocomplete="current-password" required /></div><button class="btn ${google ? 'btn--ghost' : 'btn--primary'}" type="submit">${escapeHTML(STR.loginSubmit)}</button></form></div></div>`;
  $('#login-google')?.addEventListener('click', async e => {
    const button = e.currentTarget;
    const note = $('#login-google-error');
    note.hidden = true;
    button.disabled = true;
    try {
      const idToken = await googleIdToken();
      const res = await fetch('/app/auth/google', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ idToken }) });
      const body = await res.json().catch(() => ({}));
      if (res.ok) return await startDashboardSession(body.token);
      const refusal = { no_account: STR.loginGoogleNoAccount, other_google_account: STR.loginGoogleOtherAccount, account_inactive: STR.loginInactive }[body.error];
      if (refusal) { note.textContent = refusal; note.hidden = false; } else toast(STR.loginGoogleFailed);
    } catch (err) {
      toast(googlePopupErrorText(err));
    } finally {
      button.disabled = false;
    }
  });
  $('#login-form').addEventListener('submit', async e => {
    e.preventDefault();
    try { const res = await api('/app/auth/login', { method: 'POST', body: JSON.stringify({ email: $('#email').value, password: $('#password').value }) }); await startDashboardSession(res.token); }
    catch { toast(STR.loginInvalid); }
  });
}

// ── Account: how the signed-in user gets in ─────────────────────────────────────
// Two sign-in methods, Google and password. The server confirms every change with the current
// password or a fresh Google sign-in, so an open session alone can't take the account over.
const accountTrail = () => ({ key: 'account', label: STR.accountTitle, open: () => openAccount() });

function accountInitials(email = '') {
  const parts = String(email).split('@')[0].split(/[._+-]+/).filter(Boolean);
  return ((parts[0]?.[0] || '') + (parts[1]?.[0] || '')).toUpperCase() || '?';
}

function accountErrorText(err) {
  const code = String(err?.code || '');
  if (code.startsWith('auth/') || code === 'google_unavailable') return googlePopupErrorText(err);
  const key = `accountErr_${code}`;
  if (code && STR[key] !== `app.${key}`) return STR[key];
  return ['invalid_token', 'stale_sign_in', 'email_not_verified', 'not_google_sign_in'].includes(code) ? STR.loginGoogleFailed : STR.accountFailed;
}

async function openAccount() {
  let account;
  try { account = await api('/app/api/account'); }
  catch (err) { if (err.message !== 'unauthorized') toast(STR.accountLoadFailed); return; }
  state.account = account;
  if (account.googleAvailable) loadAuthConfig().then(cfg => cfg.google && prepareGoogleSignIn(cfg.google)).catch(() => {});
  const linked = !!account.googleEmail;
  const pill = (on, onLabel, offLabel) => `<span class="pill ${on ? 'pill--ok' : ''}">${escapeHTML(on ? onLabel : offLabel)}</span>`;
  const rows = [
    {
      key: 'google', title: STR.accountGoogle, tone: linked ? 'ok' : 'neutral', pill: pill(linked, STR.accountLinked, STR.accountNotLinked),
      detail: linked ? account.googleEmail : account.googleAvailable ? STR.accountGoogleOffDetail : STR.accountGoogleUnavailable,
      disabled: !linked && !account.googleAvailable,
    },
    {
      key: 'password', title: STR.accountPassword, tone: account.passwordEnabled ? 'ok' : 'neutral', pill: pill(account.passwordEnabled, STR.accountOn, STR.accountOff),
      detail: account.passwordEnabled ? STR.accountPasswordOnDetail : STR.accountPasswordOffDetail,
    },
  ];
  const hint = !linked ? (account.googleAvailable ? STR.accountHintLink : '')
    : account.passwordEnabled ? STR.accountHintBoth : STR.accountHintGoogleOnly;
  const body = document.createElement('div');
  body.className = 'record';
  body.innerHTML = `<section class="record-card"><div class="record-card__head">
      <span class="record-card__avatar" aria-hidden="true">${escapeHTML(accountInitials(account.email))}</span>
      <div class="record-card__who">
        <div class="record-card__lines"><span class="record-card__line">${escapeHTML(account.email)}</span></div>
        <p class="record-card__since">${escapeHTML([STR[`accountRole${account.role}`], state.me?.tenant?.name].filter(Boolean).join(' · '))}</p>
      </div>
    </div></section>
    <section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(STR.accountMethods)}</h2></header>
      <ul class="worklist">${rows.map(r => `<li><button class="worklist__item" type="button" data-account-open="${r.key}" data-tone="${r.tone}"${r.disabled ? ' disabled' : ''}>
        <span class="worklist__dot" aria-hidden="true"></span>
        <span class="worklist__main"><span class="worklist__title">${escapeHTML(r.title)}</span><span class="worklist__detail">${escapeHTML(r.detail)}</span></span>
        <span class="worklist__side"><span class="worklist__meta">${r.pill}</span></span>
      </button></li>`).join('')}</ul>
    </section>
    ${hint ? `<p class="hint">${escapeHTML(hint)}</p>` : ''}`;
  openDrawer(STR.accountTitle, body, false, { eyebrow: state.me?.tenant?.name });
  $$('[data-account-open]', body).forEach(b => b.addEventListener('click', () => {
    openFrom(accountTrail(), () => (b.dataset.accountOpen === 'google' ? openAccountGoogle() : openAccountPassword()));
  }));
}

function accountPasswordField(id, label, autocomplete, hint = '') {
  return `<div class="form__row form__row--full"><label class="lbl" for="${id}">${escapeHTML(label)}</label>
    <input class="inp" id="${id}" type="password" autocomplete="${autocomplete}" maxlength="128" required />${hint ? `<p class="hint">${escapeHTML(hint)}</p>` : ''}</div>`;
}

function accountActions(submitLabel, tone = 'btn--primary') {
  return `<div class="actions"><button class="btn ${tone}" type="submit">${escapeHTML(submitLabel)}</button><button class="btn btn--ghost" type="button" data-account-cancel>${escapeHTML(STR.cancel)}</button></div>`;
}

// Submits an account change. `run` must open the Google popup (if any) before its first await, while
// the click still counts. On success the account drawer comes back with the new state.
function wireAccountForm(form, run, done) {
  $('[data-account-cancel]', form)?.addEventListener('click', () => closeDrawer());
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      state.account = await run();
      toast(done);
      closeDrawer();
    } catch (err) {
      btn.disabled = false;
      if (err?.message !== 'unauthorized') toast(accountErrorText(err));
    }
  });
}

function openAccountGoogle() {
  const a = state.account;
  const form = document.createElement('form');
  form.className = 'form';
  const linkedMeta = `<div class="detail__meta"><div class="detail__meta-item"><span class="detail__meta-label">${escapeHTML(STR.accountGoogleEmail)}</span><span class="detail__meta-value">${escapeHTML(a.googleEmail || '')}</span></div></div>`;
  if (!a.googleEmail) {
    form.innerHTML = `<p class="hint">${escapeHTML(STR.accountGoogleLinkIntro)}</p>
      ${accountPasswordField('acc-current', STR.accountCurrentPassword, 'current-password')}
      ${accountActions(STR.loginGoogle)}`;
    wireAccountForm(form, async () => {
      const currentPassword = $('#acc-current', form).value;
      const idToken = await googleIdToken();
      return api('/app/api/account/google/link', { method: 'POST', body: JSON.stringify({ idToken, currentPassword }) });
    }, STR.accountGoogleLinked);
  } else if (a.passwordEnabled) {
    form.innerHTML = `${linkedMeta}<p class="hint">${escapeHTML(STR.accountGoogleUnlinkIntro)}</p>
      ${accountPasswordField('acc-current', STR.accountCurrentPassword, 'current-password')}
      ${accountActions(STR.accountGoogleUnlinkAction, 'btn--danger')}`;
    wireAccountForm(form, () => api('/app/api/account/google/unlink', {
      method: 'POST', body: JSON.stringify({ currentPassword: $('#acc-current', form).value }),
    }), STR.accountGoogleUnlinked);
  } else {
    form.innerHTML = `${linkedMeta}<p class="hint">${escapeHTML(STR.accountGoogleOnlyKeep)}</p>
      <div class="actions"><button class="btn" type="button" data-account-set-password>${escapeHTML(STR.accountSetPasswordAction)}</button></div>`;
    $('[data-account-set-password]', form).addEventListener('click', () => openAccountPassword());
  }
  openDrawer(STR.accountGoogleTitle, form);
}

function openAccountPassword() {
  const a = state.account;
  const form = document.createElement('form');
  form.className = 'form';
  const newFields = `${accountPasswordField('acc-new', STR.accountNewPassword, 'new-password', STR.accountNewPasswordHint)}
    ${accountPasswordField('acc-repeat', STR.accountRepeatPassword, 'new-password')}`;
  const readNewPassword = () => {
    const value = $('#acc-new', form).value;
    if (value !== $('#acc-repeat', form).value) throw Object.assign(new Error('mismatch'), { code: 'mismatch' });
    return value;
  };
  const body = document.createElement('div');
  body.className = 'settings-stack';
  body.appendChild(form);
  if (a.passwordEnabled) {
    form.innerHTML = `${accountPasswordField('acc-current', STR.accountCurrentPassword, 'current-password')}${newFields}
      ${accountActions(STR.accountSavePassword)}`;
    wireAccountForm(form, async () => {
      const newPassword = readNewPassword();
      return api('/app/api/account/password', { method: 'POST', body: JSON.stringify({ currentPassword: $('#acc-current', form).value, newPassword }) });
    }, STR.accountPasswordChanged);
    const only = document.createElement('section');
    only.className = 'panel';
    only.innerHTML = `<header class="panel__head"><h2 class="panel__title">${escapeHTML(STR.accountGoogleOnlyTitle)}</h2></header>
      <div class="panel__body settings-stack">
        <p class="hint">${escapeHTML(a.googleEmail ? STR.accountGoogleOnlyIntro({ email: a.googleEmail }) : STR.accountGoogleOnlyNeedsLink)}</p>
        <div class="actions">${a.googleEmail
          ? `<button class="btn" type="button" data-account-google-only>${escapeHTML(STR.accountGoogleOnlyAction)}</button>`
          : (a.googleAvailable ? `<button class="btn" type="button" data-account-link>${escapeHTML(STR.accountLinkGoogleAction)}</button>` : '')}</div>
      </div>`;
    body.appendChild(only);
    $('[data-account-link]', only)?.addEventListener('click', () => openAccountGoogle());
    $('[data-account-google-only]', only)?.addEventListener('click', async e => {
      const btn = e.currentTarget;
      btn.disabled = true;
      try {
        const idToken = await googleIdToken();
        state.account = await api('/app/api/account/password/disable', { method: 'POST', body: JSON.stringify({ idToken }) });
        toast(STR.accountPasswordOff);
        closeDrawer();
      } catch (err) {
        btn.disabled = false;
        if (err?.message !== 'unauthorized') toast(accountErrorText(err));
      }
    });
  } else {
    form.innerHTML = `<p class="hint">${escapeHTML(STR.accountSetIntro({ email: a.googleEmail || '' }))}</p>${newFields}
      ${accountActions(STR.accountSetAction)}`;
    wireAccountForm(form, async () => {
      const newPassword = readNewPassword();
      const idToken = await googleIdToken();
      return api('/app/api/account/password', { method: 'POST', body: JSON.stringify({ newPassword, idToken }) });
    }, STR.accountPasswordSet);
  }
  openDrawer(STR.accountPassword, body);
}

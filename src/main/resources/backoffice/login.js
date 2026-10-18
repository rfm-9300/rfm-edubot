/* Signing in to the backoffice: with Google (an email on the admins list) or, while ADMIN_PASSWORD_HASH is
   set, the platform password. Google's sign-in itself is in /admin/common.js, shared with /app. */

// Signed out, the top bar keeps only the theme switch: the menu, search, New and Log out all need a session.
// Signed in, setView shows search and New on the views that use them.
function showSessionControls(on) {
  $('#btn-nav').hidden = !on;
  $('#btn-logout').hidden = !on;
  if (!on) {
    $('.topbar__search').hidden = true;
    $('#btn-new').hidden = true;
  }
}

async function startSession(newToken) {
  token = newToken;
  localStorage.setItem('adminToken', token);
  await loadAll();
  showSessionControls(true);
  setView(location.hash.replace(/^#/, '') || 'tenants');
}

async function renderLogin() {
  closeDrawer();
  showSessionControls(false);
  const config = await fetch('/admin/auth/config').then(r => (r.ok ? r.json() : null)).catch(() => null);
  const google = config?.google || null;
  const passwordEnabled = config ? config.passwordEnabled : true;
  if (google) prepareGoogleSignIn(google).catch(() => {});
  $('#view').innerHTML = `
    <div class="auth"><div class="auth__card">
      <div class="auth__mark">BO</div>
      <p class="auth__eyebrow">${escapeHTML(T.login.eyebrow)}</p>
      <h1 class="auth__title">${escapeHTML(T.login.title)}</h1>
      <p class="auth__desc">${escapeHTML(T.login.desc)}</p>
      ${google ? `<button class="btn btn--primary" type="button" id="login-google">${escapeHTML(T.login.google)}</button>` : ''}
      ${google && passwordEnabled ? `<p class="auth__desc">${escapeHTML(T.login.or)}</p>` : ''}
      ${passwordEnabled ? `<form class="form" id="login-form">
        <div class="form__row"><label class="lbl" for="password">${escapeHTML(T.login.password)}</label><input class="inp" id="password" type="password" autocomplete="current-password" required /></div>
        <button class="btn ${google ? 'btn--ghost' : 'btn--primary'}" type="submit">${escapeHTML(T.login.submit)}</button>
      </form>` : ''}
      ${!google && !passwordEnabled ? `<p class="auth__desc">${escapeHTML(T.login.unavailable)}</p>` : ''}
    </div></div>`;
  $('#login-google')?.addEventListener('click', async e => {
    const button = e.currentTarget;
    button.disabled = true;
    try {
      const idToken = await googleIdToken();
      const res = await fetch('/admin/auth/google', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ idToken }) });
      const body = await res.json().catch(() => ({}));
      if (!res.ok) {
        toast(body.error === 'not_allowed' ? T.login.notAllowed : T.login.googleFailed);
        return;
      }
      await startSession(body.token);
    } catch (err) {
      const code = String(err?.code || '');
      toast(code === 'auth/popup-closed-by-user' || code === 'auth/cancelled-popup-request' ? T.login.cancelled : T.login.googleFailed);
    } finally {
      button.disabled = false;
    }
  });
  $('#login-form')?.addEventListener('submit', async e => {
    e.preventDefault();
    try {
      const res = await api('/admin/auth/login', { method: 'POST', body: JSON.stringify({ password: $('#password').value }) });
      await startSession(res.token);
    } catch (err) {
      toast(T.login.invalid);
    }
  });
}

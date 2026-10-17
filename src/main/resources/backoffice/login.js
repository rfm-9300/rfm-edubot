/* Signing in to the backoffice: with Google (an email on the admins list) or, while ADMIN_PASSWORD_HASH is
   set, the platform password. Signed out, the top bar keeps only the theme switch. */

const FIREBASE_SDK = 'https://www.gstatic.com/firebasejs/12.19.0';
let googleSignIn = null;

// Browsers only allow the sign-in popup straight from the click, so the SDK is loaded and set up
// as soon as the login screen shows. The Firebase session is only used to prove who you are: it
// stays in memory and the backoffice keeps its own admin token.
function prepareGoogleSignIn(config) {
  googleSignIn = googleSignIn || Promise.all([import(`${FIREBASE_SDK}/firebase-app.js`), import(`${FIREBASE_SDK}/firebase-auth.js`)])
    .then(([appSdk, authSdk]) => {
      const app = appSdk.getApps().length ? appSdk.getApp() : appSdk.initializeApp(config);
      const auth = authSdk.initializeAuth(app, { persistence: authSdk.inMemoryPersistence, popupRedirectResolver: authSdk.browserPopupRedirectResolver });
      const provider = new authSdk.GoogleAuthProvider();
      provider.setCustomParameters({ prompt: 'select_account' });
      return { auth, provider, signInWithPopup: authSdk.signInWithPopup, signOut: authSdk.signOut };
    });
  googleSignIn.catch(() => { googleSignIn = null; });
  return googleSignIn;
}

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
  const ready = google ? prepareGoogleSignIn(google) : null;
  let loadedSdk = null;
  ready?.then(sdk => { loadedSdk = sdk; }, () => {});
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
      const sdk = loadedSdk || await ready;
      const result = await sdk.signInWithPopup(sdk.auth, sdk.provider);
      const idToken = await result.user.getIdToken();
      sdk.signOut(sdk.auth).catch(() => {});
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

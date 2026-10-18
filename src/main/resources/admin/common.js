/* What the dashboards share (/app and /backoffice): DOM shortcuts, escaping, the toast and the confirm dialog,
   calling the JSON APIs, Google sign-in through Firebase, Meta's SDK and Embedded Signup, and Instagram's
   consent popup. Each page loads it before its own scripts, in the same global scope, and like them it only
   declares things. Each page keeps its own drawer: /app's keeps a trail of the drawers opened from one another
   and holds focus inside, the backoffice's has a save footer and focuses × on touch screens. */

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
const escapeHTML = (s = '') => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');

let toastTimer;
function toast(msg) {
  const el = $('#toast');
  el.innerHTML = `<span class="toast__dot"></span><span>${escapeHTML(msg)}</span>`;
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.hidden = true; }, 2800);
}

// Without an `okLabel` the button keeps the label its page's markup names (its data-i18n key).
function confirmDialog({ title, body, okLabel, danger = true }) {
  return new Promise(resolve => {
    const root = $('#confirm');
    if (!root) return resolve(false);
    $('#confirm-title').textContent = title;
    $('#confirm-body').textContent = body;
    const ok = $('#confirm-ok');
    ok.textContent = okLabel ?? I18N.t(ok.dataset.i18n);
    ok.classList.toggle('btn--danger', danger);
    ok.classList.toggle('btn--primary', !danger);
    root.hidden = false;
    const cleanup = v => {
      root.hidden = true;
      ok.removeEventListener('click', onOk);
      $$('[data-confirm-cancel]', root).forEach(b => b.removeEventListener('click', onCancel));
      resolve(v);
    };
    const onOk = () => cleanup(true);
    const onCancel = () => cleanup(false);
    ok.addEventListener('click', onOk, { once: true });
    $$('[data-confirm-cancel]', root).forEach(b => b.addEventListener('click', onCancel, { once: true }));
  });
}

/** The error a failed response carries: its status, the server's `error` code, when given the field and limit it is about, and the parsed body. */
async function apiError(res) {
  const err = new Error(`HTTP ${res.status}`);
  err.status = res.status;
  const body = await res.json().catch(() => null);
  err.code = body && typeof body.error === 'string' ? body.error : '';
  err.detail = body && typeof body.detail === 'string' ? body.detail : '';
  err.field = body && typeof body.field === 'string' ? body.field : '';
  err.limit = body && typeof body.limit === 'number' ? body.limit : null;
  err.body = body;
  return err;
}

/** What each page's api() calls with its session: sends `options.body` as JSON, signed with [bearer]. A 401 calls
    [onUnauthorized], which signs the page out, and throws `unauthorized`; any other failure throws apiError(). */
async function apiFetch(path, options, bearer, onUnauthorized) {
  const headers = { ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...(bearer ? { Authorization: `Bearer ${bearer}` } : {}) };
  const res = await fetch(path, { ...options, headers: { ...headers, ...(options.headers || {}) } });
  if (res.status === 401) { onUnauthorized(); throw new Error('unauthorized'); }
  if (!res.ok) throw await apiError(res);
  if (res.status === 204) return null;
  return res.json();
}

const FIREBASE_SDK = 'https://www.gstatic.com/firebasejs/12.19.0';
let googleSignIn = null;
let googleSdk = null;

// Browsers only allow the sign-in popup straight from a click, so the SDK loads as soon as a screen
// offering Google shows. The Firebase session only proves who you are: it stays in memory and the
// dashboard keeps its own token.
function prepareGoogleSignIn(config) {
  googleSignIn = googleSignIn || Promise.all([import(`${FIREBASE_SDK}/firebase-app.js`), import(`${FIREBASE_SDK}/firebase-auth.js`)])
    .then(([appSdk, authSdk]) => {
      const app = appSdk.getApps().length ? appSdk.getApp() : appSdk.initializeApp(config);
      const auth = authSdk.initializeAuth(app, { persistence: authSdk.inMemoryPersistence, popupRedirectResolver: authSdk.browserPopupRedirectResolver });
      const provider = new authSdk.GoogleAuthProvider();
      provider.setCustomParameters({ prompt: 'select_account' });
      googleSdk = { auth, provider, signInWithPopup: authSdk.signInWithPopup, signOut: authSdk.signOut };
      return googleSdk;
    });
  googleSignIn.catch(() => { googleSignIn = null; });
  return googleSignIn;
}

// Opens Google's account picker and returns an ID token for the chosen account.
async function googleIdToken() {
  const sdk = googleSdk || await googleSignIn;
  if (!sdk) throw Object.assign(new Error('google_unavailable'), { code: 'google_unavailable' });
  const result = await sdk.signInWithPopup(sdk.auth, sdk.provider);
  const idToken = await result.user.getIdToken();
  sdk.signOut(sdk.auth).catch(() => {});
  return idToken;
}

let fbSdkPromise = null;

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

// Instagram's consent opens in a popup that comes back to the page, whose handleOAuthPopup() posts the outcome
// to this window. Resolves with it ({ status, reason }), or with null at once when the browser blocked the popup.
function instagramConsent(authorizeUrl) {
  const popup = window.open(authorizeUrl, 'ig-oauth', 'width=600,height=750');
  if (!popup) return Promise.resolve(null);
  return new Promise(resolve => {
    const onMessage = ev => {
      if (ev.origin !== window.location.origin || ev.data?.type !== 'ig-oauth') return;
      window.removeEventListener('message', onMessage);
      resolve(ev.data);
    };
    window.addEventListener('message', onMessage);
  });
}

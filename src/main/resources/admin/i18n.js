/* Shared i18n runtime for the web UIs (app/, admin/, backoffice/).
   Load order: catalog.en.js / catalog.pt.js / catalog.es.js populate window.__I18N_CATALOGS,
   then this file exposes window.I18N.

   Locale resolution (highest priority first):
     1. localStorage.uiLocale  — explicit user choice from the language switcher
     2. tenant default         — applied at runtime via I18N.applyTenantDefault(tenant.locale)
     3. navigator.language     — the browser's language, mapped to a supported locale
     4. DEFAULT                — pt-PT

   Catalog values are usually strings (with optional {name} placeholders) but may be functions
   for dynamic copy, e.g. igFailed: reason => `…`. Both are supported. */
(function (global) {
  const CATALOGS = global.__I18N_CATALOGS || (global.__I18N_CATALOGS = {});
  const SUPPORTED = ['en', 'pt-PT', 'es'];
  const DEFAULT = 'pt-PT';
  // Language names shown in their own language (proper nouns, not translated per locale).
  const LANG_NAMES = { en: 'English', 'pt-PT': 'Português', es: 'Español' };

  function normalize(loc) {
    if (!loc) return null;
    if (SUPPORTED.includes(loc)) return loc;
    const l = String(loc).toLowerCase();
    if (l === 'en' || l.startsWith('en-')) return 'en';
    if (l === 'pt' || l.startsWith('pt')) return 'pt-PT';
    if (l === 'es' || l.startsWith('es')) return 'es';
    return null;
  }

  function readOverride() {
    try { return normalize(localStorage.getItem('uiLocale')); } catch (_) { return null; }
  }

  let hasOverride = !!readOverride();
  let active = readOverride() || normalize(global.navigator && navigator.language) || DEFAULT;

  function lookup(loc, key) {
    const cat = CATALOGS[loc];
    if (!cat) return undefined;
    return key.split('.').reduce((o, k) => (o == null ? undefined : o[k]), cat);
  }

  function resolve(key) {
    let v = lookup(active, key);
    if (v === undefined) v = lookup(DEFAULT, key);
    return v;
  }

  function interpolate(str, params) {
    if (!params) return str;
    return str.replace(/\{(\w+)\}/g, (m, k) => (params[k] != null ? params[k] : m));
  }

  // String lookup with interpolation / function support. Returns the key itself when missing.
  function t(key, params) {
    const v = resolve(key);
    if (v === undefined) return key;
    if (typeof v === 'function') return v(params || {});
    return typeof v === 'string' ? interpolate(v, params) : v;
  }

  // Proxy over a namespace prefix so call sites read like a plain object:
  //   const STR = I18N.section('app');  STR.loginTitle  /  STR.igFailed(reason)
  // Functions are returned raw (so they stay callable); strings are returned as-is.
  function section(prefix) {
    return new Proxy({}, {
      get(_t, key) {
        if (typeof key !== 'string') return undefined;
        const v = resolve(prefix + '.' + key);
        return v === undefined ? prefix + '.' + key : v;
      },
    });
  }

  // Fill static markup: data-i18n -> textContent, plus placeholder/aria-label/title variants.
  function applyDom(root) {
    root = root || document;
    root.querySelectorAll('[data-i18n]').forEach(el => { el.textContent = t(el.getAttribute('data-i18n')); });
    root.querySelectorAll('[data-i18n-placeholder]').forEach(el => { el.setAttribute('placeholder', t(el.getAttribute('data-i18n-placeholder'))); });
    root.querySelectorAll('[data-i18n-aria-label]').forEach(el => { el.setAttribute('aria-label', t(el.getAttribute('data-i18n-aria-label'))); });
    root.querySelectorAll('[data-i18n-title]').forEach(el => { el.setAttribute('title', t(el.getAttribute('data-i18n-title'))); });
  }

  // Apply the tenant's default language unless the user picked an explicit override this session.
  function applyTenantDefault(loc) {
    const n = normalize(loc);
    if (n && !hasOverride) active = n;
    return active;
  }

  // Explicit user choice from the switcher — persists across sessions and wins over tenant default.
  function choose(loc) {
    const n = normalize(loc) || DEFAULT;
    active = n;
    hasOverride = true;
    try { localStorage.setItem('uiLocale', n); } catch (_) {}
    return active;
  }

  function locale() { return active; }

  global.I18N = {
    t, section, applyDom, applyTenantDefault, choose, locale, normalize,
    SUPPORTED, DEFAULT, LANG_NAMES,
  };
})(window);

/* Theme switcher + mobile nav shell — shared by backoffice and tenant dashboard.
   Loaded synchronously in <head> so the theme applies before first paint.
   Preference persists per browser in localStorage ("light" | "dark").
   If the user has never chosen, follow prefers-color-scheme. */
(function () {
  const KEY = 'uiTheme';
  const root = document.documentElement;

  function current() {
    return root.dataset.theme === 'dark' ? 'dark' : 'light';
  }

  function themeLabel(theme) {
    const key = theme === 'dark' ? 'common.themeToLight' : 'common.themeToDark';
    if (window.I18N && typeof window.I18N.t === 'function') return window.I18N.t(key);
    return theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme';
  }

  function themeIcon(theme) {
    return theme === 'dark'
      ? '<svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true"><circle cx="8" cy="8" r="3" fill="currentColor"/><path d="M8 1v2M8 13v2M1 8h2M13 8h2M3.2 3.2l1.4 1.4M11.4 11.4l1.4 1.4M12.8 3.2l-1.4 1.4M4.6 11.4L3.2 12.8" stroke="currentColor" stroke-width="1.4" fill="none" stroke-linecap="round"/></svg>'
      : '<svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true"><path d="M12.6 10.4A5.4 5.4 0 0 1 6.2 3.4 5.8 5.8 0 1 0 12.6 10.4z" fill="currentColor"/></svg>';
  }

  function apply(theme) {
    root.dataset.theme = theme;
    const btn = document.getElementById('btn-theme');
    if (btn) {
      btn.innerHTML = themeIcon(theme);
      const label = themeLabel(theme);
      btn.title = label;
      btn.setAttribute('aria-label', label);
    }
  }

  function initialTheme() {
    try {
      const stored = localStorage.getItem(KEY);
      if (stored === 'dark' || stored === 'light') return stored;
    } catch (_) {}
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }

  apply(initialTheme());

  function menuLabel(open) {
    const key = open ? 'common.menuCloseAria' : 'common.menuOpenAria';
    if (window.I18N && typeof window.I18N.t === 'function') return window.I18N.t(key);
    return open ? 'Close menu' : 'Open menu';
  }

  function isMobileNav() {
    return window.matchMedia('(max-width: 920px)').matches;
  }

  function setNavOpen(open) {
    document.body.classList.toggle('nav-open', open);
    const btn = document.getElementById('btn-nav');
    if (btn) {
      btn.setAttribute('aria-expanded', open ? 'true' : 'false');
      btn.setAttribute('aria-label', menuLabel(open));
    }
    const scrim = document.getElementById('nav-scrim');
    if (scrim) scrim.hidden = !open;

    const sidebar = document.querySelector('.sidebar');
    if (sidebar) {
      if (isMobileNav()) {
        sidebar.toggleAttribute('inert', !open);
        sidebar.setAttribute('aria-hidden', open ? 'false' : 'true');
      } else {
        sidebar.removeAttribute('inert');
        sidebar.removeAttribute('aria-hidden');
      }
    }
  }

  function initMobileNav() {
    const sidebar = document.querySelector('.sidebar');
    const btn = document.getElementById('btn-nav');
    if (!sidebar || !btn) return;

    let scrim = document.getElementById('nav-scrim');
    if (!scrim) {
      scrim = document.createElement('div');
      scrim.id = 'nav-scrim';
      scrim.className = 'nav-scrim';
      scrim.hidden = true;
      document.body.appendChild(scrim);
    }

    if (!sidebar.id) sidebar.id = 'app-sidebar';
    btn.setAttribute('aria-controls', sidebar.id);
    btn.setAttribute('aria-expanded', 'false');
    setNavOpen(false);

    const close = () => setNavOpen(false);
    const toggle = () => setNavOpen(!document.body.classList.contains('nav-open'));

    btn.addEventListener('click', toggle);
    scrim.addEventListener('click', close);
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && document.body.classList.contains('nav-open')) close();
    });
    sidebar.addEventListener('click', (e) => {
      if (e.target.closest('.nav__item')) close();
    });
    window.addEventListener('resize', () => {
      if (!isMobileNav()) close();
      else if (!document.body.classList.contains('nav-open')) setNavOpen(false);
    });
  }

  document.addEventListener('DOMContentLoaded', () => {
    apply(current());
    document.getElementById('btn-theme')?.addEventListener('click', () => {
      const next = current() === 'dark' ? 'light' : 'dark';
      try { localStorage.setItem(KEY, next); } catch (_) {}
      apply(next);
    });
    initMobileNav();
  });

  window.refreshThemeLabels = function () { apply(current()); };
})();

/* Theme switcher + mobile nav shell — shared by backoffice, admin CRM and tenant dashboard.
   Loaded synchronously in <head> so the theme applies before first paint.
   Preference persists per browser in localStorage ("light" | "dark"). */
(function () {
  const KEY = 'uiTheme';
  const root = document.documentElement;

  function current() {
    return root.dataset.theme === 'dark' ? 'dark' : 'light';
  }

  function apply(theme) {
    root.dataset.theme = theme;
    const btn = document.getElementById('btn-theme');
    if (btn) {
      btn.textContent = theme === 'dark' ? '☀️' : '🌙';
      btn.title = theme === 'dark' ? 'Tema claro' : 'Tema escuro';
      btn.setAttribute('aria-label', btn.title);
    }
  }

  apply(localStorage.getItem(KEY) === 'dark' ? 'dark' : 'light');

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
      localStorage.setItem(KEY, next);
      apply(next);
    });
    initMobileNav();
  });
})();

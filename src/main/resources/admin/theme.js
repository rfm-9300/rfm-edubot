/* Theme switcher — shared by backoffice, admin CRM and tenant dashboard.
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

  document.addEventListener('DOMContentLoaded', () => {
    apply(current());
    document.getElementById('btn-theme')?.addEventListener('click', () => {
      const next = current() === 'dark' ? 'light' : 'dark';
      localStorage.setItem(KEY, next);
      apply(next);
    });
  });
})();

/* Labels shared by the dashboard and backoffice during a CLI sign-in visit. */
(() => {
  const labels = {
    en: {
      copy: 'Copy CLI token',
      copied: 'Session token copied. Paste it into your local terminal.',
      failed: 'Could not copy the session token.',
    },
    'pt-PT': {
      copy: 'Copiar token da CLI',
      copied: 'Token de sessão copiado. Cole-o no seu terminal local.',
      failed: 'Não foi possível copiar o token de sessão.',
    },
    es: {
      copy: 'Copiar token de la CLI',
      copied: 'Token de sesión copiado. Pégalo en tu terminal local.',
      failed: 'No se pudo copiar el token de sesión.',
    },
  };
  for (const [locale, label] of Object.entries(labels)) {
    window.__I18N_CATALOGS[locale].common.cliSession = label;
  }
})();

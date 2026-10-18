/* Settings (/app → Settings): Home's cards, appearance, channels, WhatsApp templates, the website widget,
   language, the quote and invoice template (doc-template.js) and companies. Connecting channels is in
   integrations.js, templates in whatsapp-templates.js and companies in companies.js. */

function widgetDraft() {
  if (!state.widgetDraft) {
    state.widgetDraft = {
      title: state.me?.tenant?.name || STR.widgetDefaultTitle,
      subtitle: STR.widgetDefaultSubtitle,
      welcome: STR.widgetDefaultWelcome,
      placeholder: STR.widgetDefaultPlaceholder,
      launcher: STR.widgetDefaultLauncher,
      accent: '#7c5cfc',
      position: 'right',
      theme: 'light',
    };
  }
  return state.widgetDraft;
}

function widgetSnippet(key) {
  const config = widgetDraft();
  const attrs = [
    ['data-key', key], ['data-title', config.title], ['data-subtitle', config.subtitle],
    ['data-welcome', config.welcome], ['data-placeholder', config.placeholder],
    ['data-launcher', config.launcher], ['data-accent', config.accent],
    ['data-position', config.position], ['data-theme', config.theme],
  ];
  return `<script src="${location.origin}/widget/widget.js" ${attrs.map(([name, value]) => `${name}="${escapeHTML(value)}"`).join(' ')} defer><\/script>`;
}

function homeLayoutPanel() {
  const layout = state.overviewLayout || { hidden: [], available: [] };
  const hidden = new Set(layout.hidden || []);
  const groups = [
    ['sections', STR.homeLayoutSections],
    ['snapshots', STR.homeLayoutSnapshots],
  ];
  const blocks = groups.map(([group, title]) => {
    const items = (layout.available || []).filter(opt => opt.group === group);
    if (!items.length) return '';
    const rows = items.map(opt => {
      const shown = !hidden.has(opt.id);
      const copy = homeCardCopy(opt.id);
      return `<button type="button" class="queue__item choice ${shown ? 'is-on' : ''}" data-home-card="${escapeHTML(opt.id)}" aria-pressed="${shown}"><div><strong>${escapeHTML(copy.title)}</strong><span>${escapeHTML(copy.detail)}</span></div><span class="queue__meta"><span class="pill ${shown ? 'pill--ok' : ''}">${escapeHTML(shown ? STR.homeLayoutShown : STR.homeLayoutHidden)}</span></span></button>`;
    }).join('');
    return `<div class="panel" style="padding:18px;margin-bottom:18px"><h2 class="view__title" style="font-size:18px;margin-bottom:14px">${escapeHTML(title)}</h2><div class="choice-list">${rows}</div></div>`;
  }).join('');
  return `<div class="panel" style="padding:18px;margin-bottom:18px"><h2 class="view__title" style="font-size:18px;margin-bottom:6px">${escapeHTML(STR.homeLayoutTitle)}</h2><p class="view__desc">${escapeHTML(STR.homeLayoutDesc)}</p></div>${blocks}`;
}

function appearancePanel() {
  const prefs = window.UIPrefs;
  const theme = prefs ? prefs.theme() : 'light';
  const choice = (group, id, title, detail, on) => `<button type="button" class="queue__item choice ${on ? 'is-on' : ''}" data-appearance-${group}="${id}" aria-pressed="${on}"><div><strong>${escapeHTML(title)}</strong><span>${escapeHTML(detail)}</span></div><span class="queue__meta">${on ? `<span class="pill pill--ok">${escapeHTML(STR.appearanceActive)}</span>` : ''}</span></button>`;
  const section = (title, hint, choices) => `<section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(title)}</h2></header><div class="panel__body"><div class="form">${hint ? `<div class="hint">${escapeHTML(hint)}</div>` : ''}<div class="choice-list">${choices}</div></div></div></section>`;
  return `<div class="settings-stack">${section(STR.appearanceThemeTitle, '',
    choice('theme', 'light', STR.themeLight, STR.themeLightDesc, theme === 'light')
    + choice('theme', 'dark', STR.themeDark, STR.themeDarkDesc, theme === 'dark'))}</div>`;
}

async function toggleHomeCard(id) {
  const layout = state.overviewLayout || { hidden: [], available: [] };
  const hidden = new Set(layout.hidden || []);
  if (hidden.has(id)) hidden.delete(id); else hidden.add(id);
  try {
    state.overviewLayout = await api('/app/api/settings/overview', { method: 'PUT', body: JSON.stringify({ hidden: [...hidden] }) });
    state.overview = await api('/app/api/overview').catch(() => state.overview);
    toast(STR.homeLayoutSaved);
    render();
  } catch {
    toast(STR.homeLayoutSaveFailed);
  }
}

function renderSettings(root) {
  const channels = state.me?.tenant?.channels || [];
  const wa = channels.find(c => c.platform === 'WHATSAPP');
  const ig = channels.find(c => c.platform === 'INSTAGRAM');
  const web = state.webWidget || { publicKey: null, allowedOrigins: [] };
  const draft = widgetDraft();
  const unavailable = (state.settingsSection === 'companies' && !companiesEnabled()) || (state.settingsSection === 'templates' && !wa);
  const section = unavailable ? 'channels' : (state.settingsSection || 'channels');
  const tabs = [
    ['home', STR.settingsHome],
    ['appearance', STR.settingsAppearance],
    ['channels', STR.settingsChannels],
    ...(wa ? [['templates', STR.settingsTemplates]] : []),
    ['widget', STR.settingsWidget],
    ['language', STR.settingsLanguage],
    ['documents', STR.settingsDocuments],
    ...(companiesEnabled() ? [['companies', STR.settingsCompanies]] : []),
  ];
  const chips = `<div class="settings-tabs">${tabs.map(([id, label]) => `<button type="button" class="chip ${section === id ? 'is-on' : ''}" data-settings="${id}">${escapeHTML(label)}</button>`).join('')}</div>`;
  const channelsPanel = `<div class="panel" style="padding:18px;margin-bottom:18px">
      <h2 class="view__title" style="font-size:18px;margin-bottom:6px">${escapeHTML(STR.channelsTitle)}</h2>
      <p class="view__desc" style="margin-bottom:14px">${escapeHTML(STR.channelsDesc)}</p>
      <div class="tbl-wrap"><table class="tbl">
        <thead><tr><th>${escapeHTML(STR.colChannel)}</th><th>${escapeHTML(STR.colAccount)}</th><th>${escapeHTML(STR.colStatus)}</th><th class="right">${escapeHTML(STR.colActions)}</th></tr></thead>
        <tbody>
          <tr><td class="name">WhatsApp</td><td class="mono">${escapeHTML(wa?.displayName || wa?.externalId || '—')}</td><td>${wa ? escapeHTML(STR.connected) : `<span class="muted">${escapeHTML(STR.notConnected)}</span>`}</td>
            <td class="right">${state.whatsAppSignup?.enabled ? `<button class="btn btn--sm" id="wa-connect">${escapeHTML(wa ? STR.waReconnect : STR.waConnect)}</button>` : ''}</td></tr>
          <tr><td class="name">Instagram</td><td class="mono">${ig ? escapeHTML(ig.displayName ? '@' + ig.displayName : ig.externalId) : '—'}</td><td>${ig ? escapeHTML(STR.connected) : `<span class="muted">${escapeHTML(STR.notConnected)}</span>`}</td>
            <td class="right"><button class="btn btn--sm" id="ig-connect">${escapeHTML(ig ? STR.igReconnect : STR.igConnect)}</button>${ig ? ` <button class="btn btn--sm btn--ghost" id="ig-disconnect">${escapeHTML(STR.igDisconnect)}</button>` : ''}</td></tr>
          ${googleRowsHtml()}
          <tr><td class="name">${escapeHTML(STR.webRowName)}</td><td class="mono">${web.publicKey ? escapeHTML(web.publicKey) : '—'}</td><td>${web.publicKey ? escapeHTML(STR.connected) : `<span class="muted">${escapeHTML(STR.notConnected)}</span>`}</td>
            <td class="right">${web.publicKey ? `<span class="muted">${escapeHTML(STR.webRegenerate)}</span>` : `<button class="btn btn--sm" id="web-generate">${escapeHTML(STR.webGenerate)}</button>`}</td></tr>
        </tbody>
      </table></div>
      <div class="hint" style="margin-top:10px">${escapeHTML(ig && !ig.commentsEnabled ? STR.instagramReconnectBanner : STR.channelsHint)}</div>
      ${state.integrations?.google?.configured && !googleAccounts().length ? `<div class="hint" style="margin-top:6px">${escapeHTML(GOOGLE.hint)}</div>` : ''}
    </div>`;
  const widgetPanel = `<div class="panel widget-customizer">
      <div class="widget-customizer__head">
        <div>
      <h2 class="view__title" style="font-size:18px;margin-bottom:6px">${escapeHTML(STR.webTitle)}</h2>
          <p class="view__desc">${escapeHTML(STR.webDesc)}</p>
        </div>
        ${web.publicKey ? `<span class="pill pill--ok">${escapeHTML(STR.webRegenerate)}</span>` : ''}
      </div>
      ${web.publicKey ? `
        <div class="widget-customizer__studio">
          <form class="form widget-customizer__controls" id="widget-customizer-form">
            <div class="widget-customizer__section">
              <h3>${escapeHTML(STR.widgetContentTitle)}</h3>
              <div class="form__row">
                <label class="lbl" for="widget-title">${escapeHTML(STR.widgetTitleLabel)}</label>
                <input class="inp" id="widget-title" maxlength="60" value="${escapeHTML(draft.title)}" />
              </div>
              <div class="form__row">
                <label class="lbl" for="widget-subtitle">${escapeHTML(STR.widgetSubtitleLabel)}</label>
                <input class="inp" id="widget-subtitle" maxlength="80" value="${escapeHTML(draft.subtitle)}" />
              </div>
              <div class="form__row">
                <label class="lbl" for="widget-welcome">${escapeHTML(STR.widgetWelcomeLabel)}</label>
                <textarea class="txt" id="widget-welcome" rows="3" maxlength="240">${escapeHTML(draft.welcome)}</textarea>
              </div>
              <div class="form__row">
                <label class="lbl" for="widget-placeholder">${escapeHTML(STR.widgetPlaceholderLabel)}</label>
                <input class="inp" id="widget-placeholder" maxlength="80" value="${escapeHTML(draft.placeholder)}" />
              </div>
              <div class="form__row">
                <label class="lbl" for="widget-launcher">${escapeHTML(STR.widgetLauncherLabel)}</label>
                <input class="inp" id="widget-launcher" maxlength="40" value="${escapeHTML(draft.launcher)}" />
              </div>
            </div>
            <div class="widget-customizer__section">
              <h3>${escapeHTML(STR.widgetAppearanceTitle)}</h3>
              <div class="form__grid form__grid--3">
                <div class="form__row">
                  <label class="lbl" for="widget-accent">${escapeHTML(STR.widgetAccentLabel)}</label>
                  <input class="widget-customizer__color" id="widget-accent" type="color" value="${escapeHTML(draft.accent)}" />
                </div>
                <div class="form__row">
                  <label class="lbl" for="widget-position">${escapeHTML(STR.widgetPositionLabel)}</label>
                  <select class="sel" id="widget-position"><option value="right" ${draft.position === 'right' ? 'selected' : ''}>${escapeHTML(STR.widgetPositionRight)}</option><option value="left" ${draft.position === 'left' ? 'selected' : ''}>${escapeHTML(STR.widgetPositionLeft)}</option></select>
                </div>
                <div class="form__row">
                  <label class="lbl" for="widget-theme">${escapeHTML(STR.widgetThemeLabel)}</label>
                  <select class="sel" id="widget-theme"><option value="light" ${draft.theme === 'light' ? 'selected' : ''}>${escapeHTML(STR.widgetThemeLight)}</option><option value="dark" ${draft.theme === 'dark' ? 'selected' : ''}>${escapeHTML(STR.widgetThemeDark)}</option><option value="auto" ${draft.theme === 'auto' ? 'selected' : ''}>${escapeHTML(STR.widgetThemeAuto)}</option></select>
                </div>
              </div>
            </div>
          </form>
          <div class="widget-preview">
            <div class="widget-preview__label"><span>${escapeHTML(STR.widgetPreview)}</span><span>${escapeHTML(STR.widgetPreviewLive)}</span></div>
            <div class="widget-preview__stage" id="widget-preview-stage">
              <div class="widget-demo widget-demo--${escapeHTML(draft.theme)} widget-demo--${escapeHTML(draft.position)}" id="widget-demo">
                <div class="widget-demo__panel">
                  <div class="widget-demo__header" id="widget-preview-header">
                    <span class="widget-demo__avatar" id="widget-preview-avatar">${escapeHTML(draft.title.charAt(0).toUpperCase() || 'C')}</span>
                    <span><strong id="widget-preview-title">${escapeHTML(draft.title)}</strong><small><i></i><span id="widget-preview-subtitle">${escapeHTML(draft.subtitle)}</span></small></span>
                    <b aria-hidden="true">×</b>
                  </div>
                  <div class="widget-demo__body"><span class="widget-demo__message" id="widget-preview-welcome">${escapeHTML(draft.welcome)}</span></div>
                  <div class="widget-demo__footer"><span id="widget-preview-placeholder">${escapeHTML(draft.placeholder)}</span><i>➤</i></div>
                </div>
                <div class="widget-demo__launcher" id="widget-preview-launcher"><span aria-hidden="true">◇</span><strong>${escapeHTML(draft.launcher)}</strong></div>
              </div>
            </div>
          </div>
        </div>
        <div class="widget-customizer__install">
          <div>
            <h3>${escapeHTML(STR.widgetInstallTitle)}</h3>
            <p class="hint">${escapeHTML(STR.widgetInstallHint)}</p>
          </div>
          <div class="form__row form__row--full">
            <textarea class="txt mono" id="web-snippet" rows="5" readonly>${escapeHTML(widgetSnippet(web.publicKey))}</textarea>
          </div>
          <button class="btn btn--primary" id="web-copy" type="button">${escapeHTML(STR.webCopy)}</button>
        </div>
        <form class="form widget-customizer__security" id="web-origins-form">
          <div class="form__row form__row--full">
            <label class="lbl" for="web-origins">${escapeHTML(STR.webOriginsLabel)}</label>
            <textarea class="txt mono" id="web-origins" rows="3" placeholder="https://www.yoursite.com">${escapeHTML((web.allowedOrigins || []).join('\n'))}</textarea>
            <div class="hint">${escapeHTML(STR.webOriginsHint)}</div>
          </div>
          <button class="btn btn--ghost" type="submit">${escapeHTML(STR.webOriginsSave)}</button>
        </form>
      ` : `<div class="empty widget-customizer__empty"><p class="empty__title">${escapeHTML(STR.widgetEmptyTitle)}</p><p class="empty__desc">${escapeHTML(STR.widgetEmptyDesc)}</p><button class="btn btn--primary" id="web-generate-2" type="button">${escapeHTML(STR.webGenerate)}</button></div>`}
    </div>`;
  const languagePanel = `<div class="panel" style="padding:18px;margin-bottom:18px">
      <h2 class="view__title" style="font-size:18px;margin-bottom:6px">${escapeHTML(I18N.t('common.lang.title'))}</h2>
      <p class="view__desc" style="margin-bottom:14px">${escapeHTML(I18N.t('common.lang.desc'))}</p>
      <div class="form__row" style="max-width:280px">
        <label class="lbl" for="ui-locale">${escapeHTML(I18N.t('common.lang.label'))}</label>
        <select class="sel" id="ui-locale">${I18N.SUPPORTED.map(l => `<option value="${l}" ${l === I18N.locale() ? 'selected' : ''}>${escapeHTML(I18N.LANG_NAMES[l] || l)}</option>`).join('')}</select>
      </div>
    </div>
    <div class="panel"><div class="empty"><p class="empty__title">${escapeHTML(STR.moreSettingsTitle)}</p><p class="empty__desc">${escapeHTML(STR.moreSettingsDesc)}</p></div></div>`;
  const body = section === 'home' ? homeLayoutPanel()
    : section === 'appearance' ? appearancePanel()
    : section === 'widget' ? widgetPanel
    : section === 'language' ? languagePanel
    : section === 'documents' ? renderDocumentTemplatePanel()
    : section === 'companies' ? companiesPanel()
    : section === 'templates' ? waTemplatesPanel()
    : channelsPanel;
  root.innerHTML = `${hero(labels.settings, STR.settingsDesc)}${chips}${body}`;
  $$('[data-settings]', root).forEach(b => b.addEventListener('click', () => { state.settingsSection = b.dataset.settings; render(); }));
  $$('[data-home-card]', root).forEach(b => b.addEventListener('click', () => toggleHomeCard(b.dataset.homeCard)));
  $$('[data-appearance-theme]', root).forEach(b => b.addEventListener('click', () => { window.UIPrefs?.setTheme(b.dataset.appearanceTheme); toast(STR.appearanceSaved); }));
  $('#wa-connect')?.addEventListener('click', connectWhatsApp);
  $('#ig-connect')?.addEventListener('click', connectInstagram);
  $('#ig-disconnect')?.addEventListener('click', () => disconnectInstagram(ig));
  $$('[data-google-connect]', root).forEach(b => b.addEventListener('click', () => {
    const account = googleAccounts().find(c => c.accountEmail === b.dataset.googleConnect);
    connectGoogle(b.dataset.googleConnect, { inbox: readsInbox(account) });
  }));
  $$('[data-google-account]', root).forEach(b => b.addEventListener('click', () => openEmailAccount(b.dataset.googleAccount)));
  const localeSel = $('#ui-locale');
  if (localeSel) localeSel.addEventListener('change', async () => {
    const locale = localeSel.value;
    I18N.choose(locale);
    I18N.applyDom(document);
    document.documentElement.lang = I18N.locale();
    window.refreshThemeLabels?.();
    try { await api('/app/api/settings/locale', { method: 'POST', body: JSON.stringify({ locale }) }); toast(I18N.t('common.lang.saved')); }
    catch { toast(I18N.t('common.lang.saveFailed')); }
    renderNav();
    render();
  });
  $$('#web-generate, #web-generate-2').forEach(b => b.addEventListener('click', generateWebWidget));
  const customizer = $('#widget-customizer-form');
  if (customizer) {
    const controls = {
      title: $('#widget-title'), subtitle: $('#widget-subtitle'), welcome: $('#widget-welcome'),
      placeholder: $('#widget-placeholder'), launcher: $('#widget-launcher'), accent: $('#widget-accent'),
      position: $('#widget-position'), theme: $('#widget-theme'),
    };
    const refreshPreview = () => {
      Object.entries(controls).forEach(([name, control]) => { state.widgetDraft[name] = control.value; });
      const config = state.widgetDraft;
      const demo = $('#widget-demo');
      demo.className = `widget-demo widget-demo--${config.theme} widget-demo--${config.position}`;
      demo.style.setProperty('--widget-demo-accent', config.accent);
      const hex = config.accent.replace('#', '');
      const rgb = /^[0-9a-f]{6}$/i.test(hex) ? [0, 2, 4].map(i => parseInt(hex.slice(i, i + 2), 16)) : [37, 99, 235];
      demo.style.setProperty('--widget-demo-accent-ink', (rgb[0] * 299 + rgb[1] * 587 + rgb[2] * 114) / 1000 > 155 ? '#111827' : '#ffffff');
      $('#widget-preview-title').textContent = config.title;
      $('#widget-preview-avatar').textContent = config.title.charAt(0).toUpperCase() || 'C';
      $('#widget-preview-subtitle').textContent = config.subtitle;
      $('#widget-preview-welcome').textContent = config.welcome;
      $('#widget-preview-placeholder').textContent = config.placeholder;
      $('#widget-preview-launcher strong').textContent = config.launcher;
      $('#web-snippet').value = widgetSnippet(web.publicKey);
    };
    Object.values(controls).forEach(control => {
      control.addEventListener('input', refreshPreview);
      control.addEventListener('change', refreshPreview);
    });
    refreshPreview();
  }
  const copyBtn = $('#web-copy');
  if (copyBtn) copyBtn.addEventListener('click', async () => {
    try { await navigator.clipboard.writeText(widgetSnippet(web.publicKey)); toast(STR.webCopied); }
    catch { const t = $('#web-snippet'); t.select(); document.execCommand('copy'); toast(STR.webCopied); }
  });
  const originsForm = $('#web-origins-form');
  if (originsForm) originsForm.addEventListener('submit', async e => {
    e.preventDefault();
    const allowedOrigins = $('#web-origins').value.split('\n').map(s => s.trim()).filter(Boolean);
    try { state.webWidget = await api('/app/api/web-widget', { method: 'POST', body: JSON.stringify({ allowedOrigins }) }); toast(STR.webOriginsSaved); render(); }
    catch { toast(STR.webGenerateFailed); }
  });
  if (section === 'documents') wireDocumentTemplateForm();
  if (section === 'companies') wireCompaniesPanel(root);
  if (section === 'templates') wireWaTemplatesPanel(root);
}

function renderDocumentTemplatePanel() {
  return `<div id="doc-template-panel"></div>`;
}

function wireDocumentTemplateForm() {
  const host = $('#doc-template-panel');
  if (!host || !window.DocTemplate) return;
  DocTemplate.mount(host, {
    getTemplate: () => state.documentTemplate,
    setTemplate: next => { state.documentTemplate = next; },
    tenantName: () => state.me?.tenant?.name || '',
    api,
    getToken: () => token,
    toast,
    STR,
    escapeHTML,
    openDrawer,
    closeDrawer,
    confirmDialog,
  });
}

async function generateWebWidget() {
  try {
    state.webWidget = await api('/app/api/web-widget', { method: 'POST', body: JSON.stringify({ allowedOrigins: [] }) });
    state.me = await api('/app/api/me');
    toast(STR.webGenerated);
    render();
  } catch { toast(STR.webGenerateFailed); }
}

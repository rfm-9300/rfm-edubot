/* Companies: a tenant can hold several, each with its own data. The brand button switches between them
   and Settings → Companies lists and adds them. Switching swaps the session token for one scoped to the
   other company and reloads, so nothing the previous company loaded survives in `state`. */
const companiesEnabled = () => (state.me?.companyLimit || 1) > 1 || (state.me?.companies || []).length > 1;

function renderCompanySwitch() {
  const brand = $('#brand');
  const switchable = Boolean(token) && (state.me?.companies || []).length > 1;
  brand.disabled = !switchable;
  brand.title = switchable ? STR.companySwitchAria : '';
}

function companyErrorText(err, fallback) {
  const key = `companyErr_${err?.code || ''}`;
  return err?.code && STR[key] !== `app.${key}` ? STR[key] : fallback;
}

function companyRow(c, current, openable) {
  const suspended = c.status === 'SUSPENDED';
  const tone = c.id === current ? 'ok' : suspended ? 'warn' : 'neutral';
  const side = c.id === current ? `<span class="pill pill--ok">${escapeHTML(STR.companyCurrent)}</span>`
    : suspended ? `<span class="pill pill--warn">${escapeHTML(STR.companyStatusSUSPENDED)}</span>`
    : escapeHTML(STR.companyOpen);
  const detail = [c.slug, c.primary ? STR.companyFirst : ''].filter(Boolean).join(' · ');
  return `<li><button class="worklist__item" type="button" data-company="${escapeHTML(c.id)}" data-tone="${tone}"${c.id === current ? ' aria-current="true"' : ''}${c.id === current || !openable ? ' disabled' : ''}>
    <span class="worklist__dot" aria-hidden="true"></span>
    <span class="worklist__main"><span class="worklist__title">${escapeHTML(c.name)}</span><span class="worklist__detail">${escapeHTML(detail)}</span></span>
    <span class="worklist__side"><span class="worklist__meta">${side}</span></span>
  </button></li>`;
}

async function switchCompany(button) {
  button.disabled = true;
  try {
    const res = await api(`/app/api/companies/${encodeURIComponent(button.dataset.company)}/switch`, { method: 'POST' });
    localStorage.setItem('dashboardToken', res.token);
    location.reload();
  } catch (err) {
    button.disabled = false;
    if (err.message !== 'unauthorized') toast(companyErrorText(err, STR.companySwitchFailed));
  }
}

function openCompanySwitcher() {
  const companies = state.me?.companies || [];
  if (companies.length < 2) return;
  const body = document.createElement('div');
  body.className = 'record';
  body.innerHTML = `<section class="panel"><ul class="worklist">${companies.map(c => companyRow(c, state.me.tenant.id, true)).join('')}</ul></section>
    ${hasModule('settings') ? `<div class="actions"><button class="btn btn--ghost" type="button" data-company-manage>${escapeHTML(STR.companiesManage)}</button></div>` : ''}`;
  openDrawer(STR.companiesSwitchTitle, body, false, { eyebrow: state.me.tenant.name });
  $$('[data-company]', body).forEach(b => b.addEventListener('click', () => switchCompany(b)));
  $('[data-company-manage]', body)?.addEventListener('click', () => {
    closeDrawer({ dismissed: true });
    state.settingsSection = 'companies';
    setActive('settings');
  });
}

function companiesPanel() {
  const data = state.companies;
  if (!data) return `<div class="panel"><div class="empty"><p class="empty__title">${escapeHTML(STR.companiesLoadFailed)}</p></div></div>`;
  const openable = new Set((state.me?.companies || []).map(c => c.id));
  const used = data.companies.length;
  const add = !data.canManage ? `<p class="hint">${escapeHTML(STR.companiesAdminOnly)}</p>`
    : used >= data.limit ? `<p class="hint">${escapeHTML(STR.companiesLimitReached({ limit: data.limit }))}</p>`
    : `<form class="form" id="company-form">
        <div class="form__row"><label class="lbl" for="company-name">${escapeHTML(STR.companyNameLabel)}</label><input class="inp" id="company-name" maxlength="80" autocomplete="organization" required /></div>
        <p class="hint">${escapeHTML(STR.companyAddHint)}</p>
        <div class="actions"><button class="btn btn--primary" type="submit">${escapeHTML(STR.companyCreate)}</button></div>
      </form>`;
  return `<div class="record">
    <section class="panel">
      <header class="panel__head"><h2 class="panel__title">${escapeHTML(STR.companiesTitle)} <span class="tag">${escapeHTML(STR.companiesUsage({ used, limit: data.limit }))}</span></h2></header>
      <div class="panel__body"><p class="hint">${escapeHTML(STR.companiesDesc)}</p></div>
      <ul class="worklist">${data.companies.map(c => companyRow(c, data.current, openable.has(c.id))).join('')}</ul>
    </section>
    <section class="panel">
      <header class="panel__head"><h2 class="panel__title">${escapeHTML(STR.companyAddTitle)}</h2></header>
      <div class="panel__body">${add}</div>
    </section>
  </div>`;
}

function wireCompaniesPanel(root) {
  $$('[data-company]', root).forEach(b => b.addEventListener('click', () => switchCompany(b)));
  const form = $('#company-form', root);
  form?.addEventListener('submit', async e => {
    e.preventDefault();
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      const company = await api('/app/api/companies', { method: 'POST', body: JSON.stringify({ name: $('#company-name', form).value }) });
      [state.me, state.companies] = await Promise.all([api('/app/api/me'), api('/app/api/companies')]);
      toast(STR.companyCreated({ name: company.name }));
      render();
    } catch (err) {
      btn.disabled = false;
      if (err.message !== 'unauthorized') toast(companyErrorText(err, STR.companyCreateFailed));
    }
  });
}

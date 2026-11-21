/* The page a client approves or disputes a measurement from (/app/share/measurement/{token}), with no account: the
   work measured as it was sent, what it bills this time less the retention, a PDF of it, and their answer in their
   own name (a dispute says what's wrong). It reads and writes only under its own link, in the company's language. */
(function () {
  const root = document.getElementById('share');
  const token = decodeURIComponent(location.pathname.split('/').filter(Boolean).pop() || '');
  const base = `/app/share/measurement/${encodeURIComponent(token)}`;
  const t = (key, params) => I18N.t(`share.measurement.${key}`, params);
  const esc = s => String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  const locale = () => I18N.locale();
  const money = eur => new Intl.NumberFormat(locale(), { style: 'currency', currency: 'EUR' }).format(Number(eur) || 0);
  const qty = n => new Intl.NumberFormat(locale(), { maximumFractionDigits: 3 }).format(Number(n) || 0);
  const pct = n => new Intl.NumberFormat(locale(), { maximumFractionDigits: 1 }).format(Number(n) || 0);
  const when = iso => (iso ? new Date(iso).toLocaleString(locale(), { day: 'numeric', month: 'long', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : '');
  const day = key => (key ? new Date(`${key}T00:00:00Z`).toLocaleDateString(locale(), { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' }) : '');

  function answerHtml(d) {
    if (d.awaiting) {
      return `<form class="form" id="decide" novalidate>
        <div class="form__row"><label class="lbl" for="dc-name">${esc(t('name'))} <span class="req">●</span></label>
          <input class="inp" id="dc-name" maxlength="160" autocomplete="name" required placeholder="${esc(t('namePh'))}" /></div>
        <div class="form__row"><label class="lbl" for="dc-note">${esc(t('note'))} <span class="opt">${esc(t('optional'))}</span></label>
          <textarea class="txt" id="dc-note" maxlength="1000"></textarea><p class="hint">${esc(t('noteHint'))}</p></div>
        <p class="hint">${esc(t('consent'))}</p>
        <p class="notice notice--warn" role="alert" data-decide-error hidden></p>
        <div class="share__decide">
          <button class="btn btn--primary" type="button" data-decision="APPROVED">${esc(t('approve'))}</button>
          <button class="btn btn--ghost" type="button" data-decision="DISPUTED">${esc(t('dispute'))}</button>
        </div>
      </form>`;
    }
    if (d.status === 'DRAFT' && d.dispute) {
      return `<div class="share__decided">
        <span class="pill pill--bad">${esc(t('disputed'))}</span>
        <p class="share__decided-by">${esc(t('decidedLine', { name: d.dispute.name, when: when(d.dispute.at) }))}</p>
        <p class="panel__text">${esc(d.dispute.note)}</p>
        <p class="hint">${esc(t('disputedIntro'))}</p>
      </div>`;
    }
    const invoiced = d.status === 'INVOICED';
    return `<div class="share__decided">
      <span class="pill pill--ok">${esc(t(invoiced ? 'invoiced' : 'approved'))}</span>
      ${d.approvedBy ? `<p class="share__decided-by">${esc(t('decidedLine', { name: d.approvedBy, when: when(d.approvedAt) }))}</p>` : ''}
    </div>`;
  }

  function render(d, thanks = '') {
    const title = t('title', { n: d.number });
    document.title = `${title} · ${d.projectName} · ${d.company}`;
    const label = key => ` data-label="${esc(t(key))}"`;
    const unit = l => (l.unit ? ` ${esc(l.unit)}` : '');
    const rows = d.lines.map(l => `<tr>
      <td class="name long">${esc(l.description)}</td>
      <td class="right mono nowrap"${label('thContract')}>${esc(qty(l.quantity))}${unit(l)}</td>
      <td class="right mono nowrap"${label('thBefore')}>${esc(qty(l.previousQty))}</td>
      <td class="right mono nowrap"${label('thPeriod')}>${esc(qty(l.periodQty))}</td>
      <td class="right mono nowrap"${label('thToDate')}>${esc(qty(l.toDateQty))} · ${esc(pct(l.pctToDate))}%</td>
      <td class="right mono nowrap"${label('thValue')}>${esc(money(l.periodEur))}</td>
    </tr>`).join('');
    const total = (key, value, strong = false, params) => `<div><dt>${esc(t(key, params))}</dt><dd>${strong ? `<strong>${esc(value)}</strong>` : esc(value)}</dd></div>`;
    root.innerHTML = `
      <header class="share__head">
        <div class="share__brand">
          ${d.hasLogo ? `<span class="share__logo"><img src="${esc(`${base}/logo`)}" alt="${esc(d.company)}" /></span>` : ''}
          <div><div class="share__company">${esc(d.company)}</div><div class="share__eyebrow">${esc(t('eyebrow'))}</div></div>
        </div>
        <h1 class="share__title">${esc(title)}</h1>
        <p class="share__sub"><span>${esc(`${d.projectNumber} · ${d.projectName}`)}</span>${d.address ? `<span>${esc(d.address)}</span>` : ''}<span>${esc(`${t('periodEnd')} ${day(d.periodEnd)}`)}</span></p>
      </header>
      <section class="panel"><div class="panel__body share__progress">
        <div class="share__figure">${esc(money(d.netEur))}<span class="share__figure-label">${esc(t('figureLabel'))}</span></div>
        <div class="progress" role="img" aria-label="${esc(t('done', { pct: pct(d.pctToDate) }))}"><span class="progress__fill" data-tone="accent" style="width:${Math.max(0, Math.min(100, Number(d.pctToDate) || 0))}%"></span></div>
        <dl class="dash-facts dash-facts--row"><div><dt>${esc(t('done', { pct: pct(d.pctToDate) }))}</dt><dd>${esc(money(d.toDateEur))}</dd></div>${d.sentAt ? `<div><dt>${esc(t('sentOn'))}</dt><dd>${esc(when(d.sentAt))}</dd></div>` : ''}</dl>
      </div></section>
      <section class="panel"><header class="panel__head"><h2 class="panel__title">${esc(t('lines'))} <span class="tag">${d.lines.length}</span></h2></header>
        <div class="tbl-wrap"><table class="tbl tbl--stack"><thead><tr><th>${esc(t('thLine'))}</th><th class="right">${esc(t('thContract'))}</th><th class="right">${esc(t('thBefore'))}</th><th class="right">${esc(t('thPeriod'))}</th><th class="right">${esc(t('thToDate'))}</th><th class="right">${esc(t('thValue'))}</th></tr></thead><tbody>${rows}</tbody></table></div>
        ${d.notStarted ? `<p class="share__none">${esc(t('notStarted', { n: d.notStarted }))}</p>` : ''}
      </section>
      <section class="panel"><header class="panel__head"><h2 class="panel__title">${esc(t('totals'))}</h2></header>
        <div class="panel__body"><dl class="dash-facts dash-facts--row">
          ${total('totalContract', money(d.contractEur))}${total('totalPrevious', money(d.previousEur))}${total('totalToDate', money(d.toDateEur))}
          ${total('totalPeriod', money(d.grossEur))}${d.retentionPct > 0 ? total('totalRetention', money(-d.retentionEur), false, { pct: pct(d.retentionPct) }) : ''}${total('totalNet', money(d.netEur), true)}
        </dl></div>
        <div class="panel__foot"><a class="btn btn--sm btn--ghost" href="${esc(`${base}/pdf`)}" target="_blank" rel="noopener">${esc(t('pdf'))}</a></div></section>
      ${d.notes ? `<section class="panel"><header class="panel__head"><h2 class="panel__title">${esc(t('notes'))}</h2></header><div class="panel__body"><p class="panel__text">${esc(d.notes)}</p></div></section>` : ''}
      <section class="panel"><header class="panel__head"><h2 class="panel__title">${esc(t('decideTitle'))}</h2></header>
        <div class="panel__body">${thanks ? `<p class="notice notice--info">${esc(thanks)}</p>` : ''}${answerHtml(d)}</div></section>
      <footer class="share__foot"><span>${esc(d.company)}</span><span>${esc(I18N.t('app.poweredBy'))} <strong>The Bots Lab</strong></span></footer>`;
    wire();
  }

  function wire() {
    const form = document.getElementById('decide');
    if (!form) return;
    // Only a click on Approve or Dispute answers: Enter in a field must not approve on the client's behalf.
    form.addEventListener('submit', e => e.preventDefault());
    form.querySelectorAll('[data-decision]').forEach(b => b.addEventListener('click', () => send(form, b.dataset.decision)));
  }

  async function send(form, decision) {
    const error = form.querySelector('[data-decide-error]');
    const name = form.querySelector('#dc-name').value.trim();
    const note = form.querySelector('#dc-note').value.trim();
    const fail = text => { error.textContent = text; error.hidden = false; };
    if (name.length < 2) { form.querySelector('#dc-name').focus(); return fail(t('errors.name_required')); }
    if (decision === 'DISPUTED' && !note) { form.querySelector('#dc-note').focus(); return fail(t('errors.note_required')); }
    const buttons = form.querySelectorAll('button');
    buttons.forEach(b => { b.disabled = true; });
    try {
      const res = await fetch(`${base}/decision`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({ decision, name, note }),
      });
      const body = await res.json().catch(() => null);
      if (res.ok && body) return render(body, t(decision === 'APPROVED' ? 'thanksApproved' : 'thanksDisputed'));
      if (body?.error === 'already_decided') return load();
      buttons.forEach(b => { b.disabled = false; });
      const key = `share.measurement.errors.${body?.error || ''}`;
      fail(body?.error && I18N.t(key) !== key ? I18N.t(key) : t('errors.failed'));
    } catch {
      buttons.forEach(b => { b.disabled = false; });
      fail(t('errors.failed'));
    }
  }

  function gone() {
    document.title = I18N.t('share.gone');
    root.innerHTML = `<div class="panel"><div class="empty"><p class="empty__title">${esc(I18N.t('share.gone'))}</p><p class="empty__desc">${esc(I18N.t('share.goneDesc'))}</p></div></div>`;
  }

  function load() {
    return fetch(`${base}/data`, { headers: { Accept: 'application/json' } })
      .then(res => (res.ok ? res.json() : null))
      .then(data => {
        if (!data) return gone();
        I18N.applyTenantDefault(data.locale);
        document.documentElement.lang = I18N.locale();
        I18N.applyDom(document);
        render(data);
      })
      .catch(gone);
  }

  I18N.applyDom(document);
  load();
})();

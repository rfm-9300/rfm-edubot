/* The page a client approves or rejects a change order from (/app/share/change/{token}), with no account: the
   change as it was sent, its value and days, and their decision in their own name. It reads and writes only
   under its own link, and speaks the company's language. */
(function () {
  const root = document.getElementById('share');
  const token = decodeURIComponent(location.pathname.split('/').filter(Boolean).pop() || '');
  const base = `/app/share/change/${encodeURIComponent(token)}`;
  const t = (key, params) => I18N.t(`share.change.${key}`, params);
  const esc = s => String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  const locale = () => I18N.locale();
  const money = eur => new Intl.NumberFormat(locale(), { style: 'currency', currency: 'EUR' }).format(Number(eur) || 0);
  const qty = n => new Intl.NumberFormat(locale(), { maximumFractionDigits: 3 }).format(Number(n) || 0);
  const when = iso => (iso ? new Date(iso).toLocaleString(locale(), { day: 'numeric', month: 'long', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : '');

  function days(n) {
    if (!n) return t('daysNone');
    return n > 0 ? t('daysMore', { n }) : t('daysLess', { n: -n });
  }

  function decisionHtml(d) {
    if (d.status === 'SENT') {
      return `<form class="form" id="decide" novalidate>
        <div class="form__row"><label class="lbl" for="dc-name">${esc(t('name'))} <span class="req">●</span></label>
          <input class="inp" id="dc-name" maxlength="160" autocomplete="name" required placeholder="${esc(t('namePh'))}" /></div>
        <div class="form__row"><label class="lbl" for="dc-note">${esc(t('note'))} <span class="opt">${esc(t('optional'))}</span></label>
          <textarea class="txt" id="dc-note" maxlength="1000"></textarea></div>
        <p class="hint">${esc(t('consent'))}</p>
        <p class="notice notice--warn" role="alert" data-decide-error hidden></p>
        <div class="share__decide">
          <button class="btn btn--primary" type="button" data-decision="APPROVED">${esc(t('approve'))}</button>
          <button class="btn btn--ghost" type="button" data-decision="REJECTED">${esc(t('reject'))}</button>
        </div>
      </form>`;
    }
    const approved = d.status === 'APPROVED';
    return `<div class="share__decided">
      <span class="pill ${approved ? 'pill--ok' : 'pill--bad'}">${esc(t(approved ? 'approved' : 'rejected'))}</span>
      ${d.decidedBy ? `<p class="share__decided-by">${esc(t('decidedLine', { name: d.decidedBy, when: when(d.decidedAt) }))}</p>` : ''}
      ${d.decisionNote ? `<p class="panel__text">${esc(d.decisionNote)}</p>` : ''}
    </div>`;
  }

  function render(d, thanks = '') {
    document.title = `${d.number} · ${d.title} · ${d.company}`;
    const addition = d.kind !== 'OMISSION';
    const rows = d.items.map(it => `<tr>
      <td class="name long">${esc(it.description)}</td>
      <td class="right mono nowrap">${esc(qty(it.quantity))}${it.unit ? ` ${esc(it.unit)}` : ''}</td>
      <td class="right mono nowrap">${esc(money(it.unitPriceEur))}</td>
      <td class="right mono nowrap">${esc(money(it.quantity * it.unitPriceEur))}</td>
    </tr>`).join('');
    root.innerHTML = `
      <header class="share__head">
        <div class="share__brand">
          ${d.hasLogo ? `<span class="share__logo"><img src="${esc(`${base}/logo`)}" alt="${esc(d.company)}" /></span>` : ''}
          <div><div class="share__company">${esc(d.company)}</div><div class="share__eyebrow">${esc(t('eyebrow'))}</div></div>
        </div>
        <h1 class="share__title">${esc(d.title)}</h1>
        <p class="share__sub"><span class="mono">${esc(d.number)}</span><span>${esc(`${d.projectNumber} · ${d.projectName}`)}</span>${d.address ? `<span>${esc(d.address)}</span>` : ''}<span class="pill">${esc(t(addition ? 'addition' : 'omission'))}</span></p>
      </header>
      <section class="panel"><div class="panel__body share__progress">
        <div class="share__figure">${esc(money(Math.abs(d.totalEur)))}<span class="share__figure-label">${esc(t(addition ? 'addsPrice' : 'takesPrice'))}</span></div>
        <dl class="dash-facts dash-facts--row"><div><dt>${esc(t('time'))}</dt><dd>${esc(days(d.daysImpact))}</dd></div>${d.sentAt ? `<div><dt>${esc(t('sentOn'))}</dt><dd>${esc(when(d.sentAt))}</dd></div>` : ''}</dl>
      </div></section>
      ${d.description ? `<section class="panel"><div class="panel__body"><p class="panel__text">${esc(d.description)}</p></div></section>` : ''}
      <section class="panel"><header class="panel__head"><h2 class="panel__title">${esc(t('lines'))} <span class="tag">${d.items.length}</span></h2></header>
        <div class="tbl-wrap"><table class="tbl tbl--stack"><thead><tr><th>${esc(t('thLine'))}</th><th class="right">${esc(t('thQty'))}</th><th class="right">${esc(t('thPrice'))}</th><th class="right">${esc(t('thTotal'))}</th></tr></thead><tbody>${rows}</tbody></table></div></section>
      <section class="panel"><header class="panel__head"><h2 class="panel__title">${esc(t('decideTitle'))}</h2></header>
        <div class="panel__body">${thanks ? `<p class="notice notice--info">${esc(thanks)}</p>` : ''}${decisionHtml(d)}</div></section>
      <footer class="share__foot"><span>${esc(d.company)}</span><span>${esc(I18N.t('app.poweredBy'))} <strong>The Bots Lab</strong></span></footer>`;
    wire();
  }

  function wire() {
    const form = document.getElementById('decide');
    if (!form) return;
    // Only a click on Approve or Reject decides: Enter in a field must not approve on the client's behalf.
    form.addEventListener('submit', e => e.preventDefault());
    form.querySelectorAll('[data-decision]').forEach(b => b.addEventListener('click', () => send(form, b.dataset.decision)));
  }

  async function send(form, decision) {
    const error = form.querySelector('[data-decide-error]');
    const name = form.querySelector('#dc-name').value.trim();
    const fail = text => { error.textContent = text; error.hidden = false; };
    if (name.length < 2) { form.querySelector('#dc-name').focus(); return fail(t('errors.name_required')); }
    const buttons = form.querySelectorAll('button');
    buttons.forEach(b => { b.disabled = true; });
    try {
      const res = await fetch(`${base}/decision`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({ decision, name, note: form.querySelector('#dc-note').value }),
      });
      const body = await res.json().catch(() => null);
      if (res.ok && body) return render(body, t(body.status === 'APPROVED' ? 'thanksApproved' : 'thanksRejected'));
      if (body?.error === 'already_decided') return load();
      buttons.forEach(b => { b.disabled = false; });
      const key = `share.change.errors.${body?.error || ''}`;
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

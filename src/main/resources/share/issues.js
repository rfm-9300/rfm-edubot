/* The page a supplier opens from the company's link (/app/share/issues/{token}), with no account: the site issues
   assigned to them across the company's projects, where each one is, its photos, and a way to say they started it
   or fixed it, with a note and photos of the work. Closing an issue stays with the company's team. It reads and
   writes only under its own link, in the company's language. Photos are shrunk in the browser (admin/common.js). */
(function () {
  const root = document.getElementById('share');
  const viewer = document.getElementById('share-viewer');
  const token = decodeURIComponent(location.pathname.split('/').filter(Boolean).pop() || '');
  const base = `/app/share/issues/${encodeURIComponent(token)}`;
  const t = (key, params) => I18N.t(`share.issues.${key}`, params);
  const esc = s => String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  const locale = () => I18N.locale();
  const when = iso => (iso ? new Date(iso).toLocaleString(locale(), { day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit' }) : '');
  const day = key => (key ? new Date(`${key}T00:00:00Z`).toLocaleDateString(locale(), { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' }) : '');
  const photoUrl = (id, thumb) => `${base}/photos/${encodeURIComponent(id)}${thumb ? '?size=thumb' : ''}`;
  const STATUS_PILLS = { OPEN: 'pill--warn', IN_PROGRESS: 'pill--info', RESOLVED: 'pill--ok', CLOSED: 'pill--ok' };
  // The name typed once is kept for the next answer on this device.
  const NAME_KEY = 'share:issuesName';
  let data = null;

  const savedName = () => { try { return localStorage.getItem(NAME_KEY) || ''; } catch { return ''; } };
  const keepName = name => { try { localStorage.setItem(NAME_KEY, name); } catch { /* private mode */ } };

  function errorText(code) {
    const key = `share.issues.errors.${code || ''}`;
    return code && I18N.t(key) !== key ? I18N.t(key) : t('errors.failed');
  }

  function issueHtml(issue) {
    const open = issue.status === 'OPEN' || issue.status === 'IN_PROGRESS';
    const facts = [
      [t('project'), `${issue.projectNumber} · ${issue.projectName}`],
      issue.address ? [t('where'), [issue.address, issue.location].filter(Boolean).join(' · ')] : (issue.location ? [t('where'), issue.location] : null),
      issue.phaseName ? [t('phase'), issue.phaseName] : null,
    ].filter(Boolean);
    const photos = issue.photos.length ? `<div class="photo-grid">${issue.photos.map(p => `<figure class="photo">
        <button class="photo__open" type="button" data-photo="${esc(p.id)}" aria-label="${esc(p.caption || t('photos'))}"><img class="photo__img" src="${esc(photoUrl(p.id, true))}" alt="" loading="lazy" /></button>
        ${p.caption ? `<figcaption class="photo__caption">${esc(p.caption)}</figcaption>` : ''}</figure>`).join('')}</div>` : '';
    const answers = issue.answers.length ? `<ul class="plain-list">${[...issue.answers].reverse().map(a => `<li>${esc(`${t(`status.${a.to}`)} · ${t('answerLine', { name: a.by, when: when(a.at) })}${a.note ? ` · ${a.note}` : ''}`)}</li>`).join('')}</ul>` : '';
    const form = open ? `<form class="form" data-issue-form="${esc(issue.id)}" novalidate>
        <div class="form__grid">
          <div class="form__row"><label class="lbl" for="nm-${esc(issue.id)}">${esc(t('name'))} <span class="req">●</span></label>
            <input class="inp" id="nm-${esc(issue.id)}" data-issue-name maxlength="160" autocomplete="name" required placeholder="${esc(t('namePh'))}" value="${esc(savedName())}" /></div>
          <div class="form__row"><label class="lbl" for="nt-${esc(issue.id)}">${esc(t('note'))} <span class="opt">${esc(t('optional'))}</span></label>
            <input class="inp" id="nt-${esc(issue.id)}" data-issue-note maxlength="1000" /></div>
        </div>
        <p class="notice notice--warn" role="alert" data-issue-error hidden></p>
        <div class="share__decide">
          ${issue.status === 'OPEN' ? `<button class="btn" type="button" data-issue-to="IN_PROGRESS">${esc(t('start'))}</button>` : ''}
          <button class="btn btn--primary" type="button" data-issue-to="RESOLVED">${esc(t('fixed'))}</button>
          <button class="btn btn--ghost" type="button" data-issue-photos>${esc(t('addPhotos'))}</button>
        </div>
      </form>` : '';
    return `<article class="share__update" id="issue-${esc(issue.id)}">
      <header class="share__update-head"><strong>${esc(`${issue.number} · ${issue.title}`)}</strong>
        <span class="pill ${STATUS_PILLS[issue.status] || ''}">${esc(t(`status.${issue.status}`))}</span>
        ${issue.overdue ? `<span class="pill pill--bad">${esc(t('overdue'))}</span>` : issue.dueDate ? `<span class="pill">${esc(t('due', { date: day(issue.dueDate) }))}</span>` : ''}
        <span class="pill">${esc(t(`priority.${issue.priority}`))}</span><span class="pill">${esc(t(`kind.${issue.kind}`))}</span></header>
      <dl class="dash-facts dash-facts--row">${facts.map(([k, v]) => `<div><dt>${esc(k)}</dt><dd>${esc(v)}</dd></div>`).join('')}</dl>
      ${issue.description ? `<p class="panel__text">${esc(issue.description)}</p>` : ''}
      ${photos}
      ${answers ? `<div><p class="lbl">${esc(t('answers'))}</p>${answers}</div>` : ''}
      ${form}
    </article>`;
  }

  function render(d, notice = '', focusId = '') {
    data = d;
    document.title = `${t('eyebrow')} · ${d.company}`;
    const open = d.issues.filter(i => i.status === 'OPEN' || i.status === 'IN_PROGRESS');
    const done = d.issues.filter(i => !open.includes(i));
    root.innerHTML = `
      <header class="share__head">
        <div class="share__brand">
          ${d.hasLogo ? `<span class="share__logo"><img src="${esc(`${base}/logo`)}" alt="${esc(d.company)}" /></span>` : ''}
          <div><div class="share__company">${esc(d.company)}</div><div class="share__eyebrow">${esc(t('eyebrow'))}</div></div>
        </div>
        <h1 class="share__title">${esc(t('title', { name: d.supplier }))}</h1>
        <p class="share__sub">${esc(t('intro', { company: d.company }))}</p>
      </header>
      ${notice ? `<p class="notice notice--info" role="status">${esc(notice)}</p>` : ''}
      <section class="panel"><header class="panel__head"><h2 class="panel__title">${esc(t('open'))} <span class="tag">${open.length}</span></h2></header>
        ${open.length ? open.map(issueHtml).join('') : `<p class="share__none">${esc(t('none'))}</p>`}</section>
      ${done.length ? `<section class="panel"><header class="panel__head"><h2 class="panel__title">${esc(t('done'))} <span class="tag">${done.length}</span></h2></header>${done.map(issueHtml).join('')}</section>` : ''}
      <p class="hint">${esc(t('consent'))}</p>
      <footer class="share__foot"><span>${esc(d.company)}</span><span>${esc(I18N.t('app.poweredBy'))} <strong>The Bots Lab</strong></span></footer>`;
    wire();
    if (focusId) document.getElementById(`issue-${focusId}`)?.scrollIntoView({ block: 'start' });
  }

  function wire() {
    root.querySelectorAll('[data-issue-form]').forEach(form => {
      const id = form.dataset.issueForm;
      const error = form.querySelector('[data-issue-error]');
      const fail = text => { error.textContent = text; error.hidden = false; };
      const name = () => form.querySelector('[data-issue-name]').value.trim();
      // Only a click answers: Enter in a field must not say the issue is fixed.
      form.addEventListener('submit', e => e.preventDefault());
      form.querySelectorAll('[data-issue-to]').forEach(b => b.addEventListener('click', async () => {
        if (name().length < 2) { form.querySelector('[data-issue-name]').focus(); return fail(t('errors.name_required')); }
        const buttons = form.querySelectorAll('button');
        buttons.forEach(x => { x.disabled = true; });
        try {
          const res = await fetch(`${base}/issues/${encodeURIComponent(id)}/status`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
            body: JSON.stringify({ status: b.dataset.issueTo, name: name(), note: form.querySelector('[data-issue-note]').value }),
          });
          const body = await res.json().catch(() => null);
          if (res.ok && body) {
            keepName(name());
            return render(body, t(b.dataset.issueTo === 'RESOLVED' ? 'thanksFixed' : 'thanksStarted'), id);
          }
          if (res.status === 404) return load();
          buttons.forEach(x => { x.disabled = false; });
          fail(errorText(body?.error));
        } catch {
          buttons.forEach(x => { x.disabled = false; });
          fail(t('errors.failed'));
        }
      }));
      form.querySelector('[data-issue-photos]')?.addEventListener('click', () => addPhotos(id, form, fail));
    });
    root.querySelectorAll('[data-photo]').forEach(b => b.addEventListener('click', () => show(b.dataset.photo)));
  }

  // Asks for photos (a phone offers its camera), shrinks each one and sends it to the issue.
  function addPhotos(id, form, fail) {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = 'image/*';
    input.multiple = true;
    input.addEventListener('change', async () => {
      const files = [...(input.files || [])];
      if (!files.length) return;
      const who = form.querySelector('[data-issue-name]').value.trim();
      let added = 0;
      let latest = null;
      for (const [i, file] of files.entries()) {
        if (files.length > 1) toast(t('uploading', { n: i + 1, total: files.length }));
        try {
          const [full, thumb] = await Promise.all([shrinkPhoto(file, 1600, 0.82), shrinkPhoto(file, 480, 0.72)]);
          const body = new FormData();
          body.append('file', full, 'photo.jpg');
          body.append('thumb', thumb, 'thumb.jpg');
          if (file.lastModified) body.append('takenAt', new Date(file.lastModified).toISOString());
          if (who) body.append('name', who);
          const res = await fetch(`${base}/issues/${encodeURIComponent(id)}/photos`, { method: 'POST', body, headers: { Accept: 'application/json' } });
          const answer = await res.json().catch(() => null);
          if (!res.ok || !answer) { fail(errorText(answer?.error)); continue; }
          latest = answer;
          added += 1;
        } catch {
          fail(t('errors.photo_unreadable'));
        }
      }
      if (latest) render(latest, t('photosAdded', { n: added }), id);
    }, { once: true });
    input.click();
  }

  function show(id) {
    const photo = data?.issues.flatMap(i => i.photos).find(p => p.id === id);
    if (!photo) return;
    const img = viewer.querySelector('.share-viewer__img');
    img.src = photoUrl(photo.id, false);
    img.alt = photo.caption || '';
    viewer.querySelector('.share-viewer__caption').textContent = [photo.caption, day(photo.day)].filter(Boolean).join(' · ');
    if (!viewer.open) viewer.showModal();
  }

  viewer.addEventListener('click', e => {
    if (e.target.closest('[data-viewer="close"]') || e.target === viewer) viewer.close();
  });

  function gone() {
    document.title = I18N.t('share.gone');
    root.innerHTML = `<div class="panel"><div class="empty"><p class="empty__title">${esc(I18N.t('share.gone'))}</p><p class="empty__desc">${esc(I18N.t('share.goneDesc'))}</p></div></div>`;
  }

  function load() {
    return fetch(`${base}/data`, { headers: { Accept: 'application/json' } })
      .then(res => (res.ok ? res.json() : null))
      .then(d => {
        if (!d) return gone();
        I18N.applyTenantDefault(d.locale);
        document.documentElement.lang = I18N.locale();
        I18N.applyDom(document);
        render(d);
      })
      .catch(gone);
  }

  I18N.applyDom(document);
  load();
})();

/* The page a client follows their project from (/app/share/{token}), with no account: progress, the plan,
   and the diary days and photos the company shared. It reads only /app/share/{token}/data and the photos
   under it, and speaks the company's language. */
(function () {
  const root = document.getElementById('share');
  const viewer = document.getElementById('share-viewer');
  const token = decodeURIComponent(location.pathname.split('/').filter(Boolean).pop() || '');
  const base = `/app/share/${encodeURIComponent(token)}`;
  const t = (key, params) => I18N.t(`share.${key}`, params);
  const esc = s => String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  const clamp = n => Math.max(0, Math.min(100, Math.round(Number(n) || 0)));
  const locale = () => I18N.locale();
  const day = (key, opts = { day: 'numeric', month: 'long', year: 'numeric' }) => (key ? new Date(`${key}T00:00:00Z`).toLocaleDateString(locale(), { ...opts, timeZone: 'UTC' }) : '');
  const photoUrl = (id, thumb) => `${base}/photos/${encodeURIComponent(id)}${thumb ? '?size=thumb' : ''}`;
  let gallery = [];
  let current = 0;

  function relative(iso) {
    const seconds = (new Date(iso).getTime() - Date.now()) / 1000;
    const rtf = new Intl.RelativeTimeFormat(locale(), { numeric: 'auto' });
    const abs = Math.abs(seconds);
    if (abs < 3600) return rtf.format(Math.round(seconds / 60), 'minute');
    if (abs < 86400) return rtf.format(Math.round(seconds / 3600), 'hour');
    if (abs < 30 * 86400) return rtf.format(Math.round(seconds / 86400), 'day');
    return new Date(iso).toLocaleDateString(locale(), { day: 'numeric', month: 'long', year: 'numeric' });
  }

  function bar(pct, tone) {
    return `<div class="progress" role="img" aria-label="${esc(`${clamp(pct)}% ${t('done')}`)}"><span class="progress__fill" data-tone="${tone}" style="width:${clamp(pct)}%"></span></div>`;
  }

  function photos(list) {
    return `<div class="photo-grid">${list.map(p => `<figure class="photo">
      <button class="photo__open" type="button" data-photo="${esc(p.id)}" aria-label="${esc(p.caption || t('photoAlt', { day: day(p.day) }))}"><img class="photo__img" src="${esc(photoUrl(p.id, true))}" alt="" loading="lazy" /></button>
      ${p.caption ? `<figcaption class="photo__caption">${esc(p.caption)}</figcaption>` : ''}
    </figure>`).join('')}</div>`;
  }

  function render(d) {
    document.title = `${d.name} · ${d.company}`;
    const finished = d.status === 'COMPLETED';
    const tone = finished ? 'ok' : d.status === 'ACTIVE' ? 'accent' : 'neutral';
    const facts = [
      d.actualStart ? [t('started'), day(d.actualStart)] : null,
      finished && d.actualEnd ? [t('finished'), day(d.actualEnd)] : (d.plannedEnd ? [t('plannedEnd'), day(d.plannedEnd)] : null),
    ].filter(Boolean);
    const phases = d.phases.map(p => `<div class="share__phase">
      <div class="share__phase-name"><strong>${esc(p.name)}</strong>${p.plannedStart && p.plannedEnd ? `<span>${esc(`${day(p.plannedStart, { day: 'numeric', month: 'short' })} – ${day(p.plannedEnd, { day: 'numeric', month: 'short' })}`)}</span>` : ''}</div>
      ${bar(p.progressPct, p.state === 'DONE' ? 'ok' : 'accent')}
      <span class="pill${p.state === 'DONE' ? ' pill--ok' : p.state === 'IN_PROGRESS' ? ' pill--info' : ''}">${esc(t(`phase.${p.state}`))}</span>
    </div>`).join('');
    const updates = d.updates.map(u => `<article class="share__update">
      <header class="share__update-head"><strong>${esc(day(u.day, { weekday: 'long', day: 'numeric', month: 'long' }))}</strong>${u.weather ? `<span class="pill">${esc(t(`weather.${u.weather}`))}</span>` : ''}</header>
      <p class="panel__text">${esc(u.workDone)}</p>
      ${u.photos.length ? photos(u.photos) : ''}
    </article>`).join('');
    gallery = d.photos;
    root.innerHTML = `
      <header class="share__head">
        <div class="share__brand">
          ${d.hasLogo ? `<span class="share__logo"><img src="${esc(`${base}/logo`)}" alt="${esc(d.company)}" /></span>` : ''}
          <div><div class="share__company">${esc(d.company)}</div><div class="share__eyebrow">${esc(t('eyebrow'))}</div></div>
        </div>
        <h1 class="share__title">${esc(d.name)}</h1>
        <p class="share__sub"><span class="mono">${esc(d.number)}</span>${d.address ? `<span>${esc(d.address)}</span>` : ''}<span class="pill${finished ? ' pill--ok' : d.status === 'ACTIVE' ? ' pill--info' : ''}">${esc(t(`status.${d.status}`))}</span></p>
      </header>
      <section class="panel"><div class="panel__body share__progress">
        <div class="share__figure">${clamp(d.progressPct)}%<span class="share__figure-label">${esc(t('done'))}</span></div>
        ${bar(d.progressPct, tone)}
        ${facts.length ? `<dl class="dash-facts dash-facts--row">${facts.map(([k, v]) => `<div><dt>${esc(k)}</dt><dd>${esc(v)}</dd></div>`).join('')}</dl>` : ''}
      </div></section>
      ${phases ? `<section class="panel"><header class="panel__head"><h2 class="panel__title">${esc(t('phases'))} <span class="tag">${d.phases.length}</span></h2></header>${phases}</section>` : ''}
      <section class="panel"><header class="panel__head"><h2 class="panel__title">${esc(t('updates'))}</h2></header>${updates || `<p class="share__none">${esc(t('noUpdates'))}</p>`}</section>
      <section class="panel"><header class="panel__head"><h2 class="panel__title">${esc(t('photos'))}${gallery.length ? ` <span class="tag">${gallery.length}</span>` : ''}</h2></header>
        <div class="panel__body">${gallery.length ? photos(gallery) : `<p class="share__none share__none--flush">${esc(t('noPhotos'))}</p>`}</div></section>
      <footer class="share__foot"><span>${esc(t('updated', { when: relative(d.updatedAt) }))}</span><span>${esc(I18N.t('app.poweredBy'))} <strong>The Bots Lab</strong></span></footer>`;
    root.querySelectorAll('[data-photo]').forEach(b => b.addEventListener('click', () => {
      const id = b.dataset.photo;
      const at = gallery.findIndex(p => p.id === id);
      if (at < 0) gallery = [...gallery, ...d.updates.flatMap(u => u.photos).filter(p => p.id === id)];
      show(Math.max(0, gallery.findIndex(p => p.id === id)));
    }));
  }

  function show(index) {
    if (!gallery.length) return;
    current = (index + gallery.length) % gallery.length;
    const photo = gallery[current];
    const img = viewer.querySelector('.share-viewer__img');
    img.src = photoUrl(photo.id, false);
    img.alt = photo.caption || t('photoAlt', { day: day(photo.day) });
    viewer.querySelector('.share-viewer__caption').textContent = [photo.caption, day(photo.day)].filter(Boolean).join(' · ');
    viewer.querySelectorAll('[data-viewer="prev"], [data-viewer="next"]').forEach(b => { b.hidden = gallery.length < 2; });
    if (!viewer.open) viewer.showModal();
  }

  function gone() {
    document.title = t('gone');
    root.innerHTML = `<div class="panel"><div class="empty"><p class="empty__title">${esc(t('gone'))}</p><p class="empty__desc">${esc(t('goneDesc'))}</p></div></div>`;
  }

  viewer.addEventListener('click', e => {
    const action = e.target.closest('[data-viewer]')?.dataset.viewer;
    if (action === 'close' || e.target === viewer) viewer.close();
    if (action === 'prev') show(current - 1);
    if (action === 'next') show(current + 1);
  });
  viewer.addEventListener('keydown', e => {
    if (e.key === 'ArrowLeft') show(current - 1);
    if (e.key === 'ArrowRight') show(current + 1);
  });

  I18N.applyDom(document);
  fetch(`${base}/data`, { headers: { Accept: 'application/json' } })
    .then(res => (res.ok ? res.json() : null))
    .then(data => {
      if (!data) return gone();
      I18N.applyTenantDefault(data.locale);
      document.documentElement.lang = I18N.locale();
      I18N.applyDom(document);
      render(data);
    })
    .catch(gone);
})();

/* What the Building pages share (/app → Projects, Site diary, Issues, Changes, Measurements): their catalog
   labels and pills, the progress bar, the Gantt, project pickers and photos (thumbnails fetched with the
   session's token, shrunk in the browser before they go up). Each page has its own script beside this one. */

const BLD = I18N.section('app.building');
const bt = (key, params) => I18N.t(`app.building.${key}`, params);
// A catalog string, or [fallback] when the key is missing (a server code this page doesn't know).
const btOr = (key, fallback) => {
  const value = bt(key);
  return typeof value === 'string' && value !== `app.building.${key}` ? value : fallback;
};

const PROJECT_STATUSES = ['PLANNING', 'ACTIVE', 'ON_HOLD', 'COMPLETED', 'CANCELLED'];
const HEALTH_TONES = { ON_TRACK: 'ok', AT_RISK: 'warn', DELAYED: 'bad', DONE: 'ok', PLANNING: 'neutral', ON_HOLD: 'muted', CANCELLED: 'muted' };
const HEALTH_PILLS = { ON_TRACK: 'ok', AT_RISK: 'warn', DELAYED: 'bad', DONE: 'info' };
const PHASE_TONES = { DONE: 'ok', IN_PROGRESS: 'accent', LATE: 'bad', NOT_STARTED: 'neutral' };
const WEATHERS = ['CLEAR', 'CLOUDY', 'RAIN', 'WIND', 'STORM', 'FOG'];
const ISSUE_KINDS = ['PUNCH', 'DEFECT', 'SAFETY', 'OTHER'];
const ISSUE_PRIORITIES = ['LOW', 'MEDIUM', 'HIGH'];
const ISSUE_STATUSES = ['OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'];
const CHANGE_STATUSES = ['DRAFT', 'SENT', 'APPROVED', 'REJECTED'];
const MEASUREMENT_STATUSES = ['DRAFT', 'APPROVED', 'INVOICED'];
// How many days without a diary entry an active project can go before the record asks for one.
const DIARY_QUIET_DAYS = 3;

const bldPill = (text, tone) => `<span class="pill${tone ? ` pill--${tone}` : ''}">${escapeHTML(text)}</span>`;
const projectStatusLabel = s => btOr(`status.${s}`, s);
const projectHealthLabel = h => btOr(`health.${h}`, h);
const projectHealthPill = h => bldPill(projectHealthLabel(h), HEALTH_PILLS[h] || '');
const projectStatusPill = s => bldPill(projectStatusLabel(s), { ACTIVE: 'ok', PLANNING: 'accent', ON_HOLD: 'warn' }[s] || '');
const phaseStateLabel = s => btOr(`phaseState.${s}`, s);
const phaseStatePill = s => bldPill(phaseStateLabel(s), { DONE: 'ok', IN_PROGRESS: 'info', LATE: 'bad' }[s] || '');
const weatherLabel = w => (w ? btOr(`weather.${w}`, w) : '');
const issueKindLabel = k => btOr(`issueKind.${k}`, k);
const issuePriorityLabel = p => btOr(`issuePriority.${p}`, p);
const issuePriorityPill = p => bldPill(issuePriorityLabel(p), { HIGH: 'bad', MEDIUM: 'warn' }[p] || '');
const issueStatusLabel = s => btOr(`issueStatus.${s}`, s);
const issueStatusPill = (s, overdue = false) => (overdue ? bldPill(BLD.overdue, 'bad') : bldPill(issueStatusLabel(s), { OPEN: 'warn', IN_PROGRESS: 'info', RESOLVED: 'accent', CLOSED: 'ok' }[s] || ''));
const changeKindLabel = k => btOr(`changeKind.${k}`, k);
const changeStatusLabel = s => btOr(`changeStatus.${s}`, s);
const changeStatusPill = s => bldPill(changeStatusLabel(s), { SENT: 'warn', APPROVED: 'ok', REJECTED: 'bad', DRAFT: 'accent' }[s] || '');
const measurementStatusLabel = s => btOr(`measurementStatus.${s}`, s);
const measurementStatusPill = s => bldPill(measurementStatusLabel(s), { DRAFT: 'accent', APPROVED: 'warn', INVOICED: 'ok' }[s] || '');
const buildingErrorText = (err, fallback) => (err?.code ? btOr(`errors.${err.code}`, fallback) : fallback);
const projectLabel = p => [p?.number, p?.name].filter(Boolean).join(' · ');
const clampPct = n => Math.max(0, Math.min(100, Math.round(Number(n) || 0)));
const fmtPct = n => new Intl.NumberFormat(uiLocale(), { maximumFractionDigits: 1 }).format(Number(n) || 0);
const fmtQty = n => new Intl.NumberFormat(uiLocale(), { maximumFractionDigits: 3 }).format(Number(n) || 0);

// The tone a project's progress is drawn in: its health while it runs, neutral before it starts.
const projectTone = p => HEALTH_TONES[p?.health] || 'accent';

// "3 days behind", "2 days ahead", "On plan", or nothing without a plan.
function scheduleText(days) {
  if (days == null) return '';
  if (days === 0) return BLD.onPlan;
  return days < 0 ? bt('daysBehind', { n: -days }) : bt('daysAhead', { n: days });
}
function scheduleHtml(days) {
  const text = scheduleText(days);
  if (!text) return '<span class="muted">—</span>';
  const cls = days < 0 ? ' schedule--behind' : days > 0 ? ' schedule--ahead' : '';
  return `<span class="${cls.trim()}">${escapeHTML(text)}</span>`;
}

function progressBarHtml(pct, plannedPct, tone) {
  const done = clampPct(pct);
  const plan = plannedPct == null ? null : clampPct(plannedPct);
  const aria = plan == null ? bt('progressAria', { pct: done }) : bt('progressPlanAria', { pct: done, plan });
  return `<div class="progress" role="img" aria-label="${escapeHTML(aria)}"><span class="progress__fill" data-tone="${tone || 'accent'}" style="width:${done}%"></span>${plan == null ? '' : `<span class="progress__plan" style="left:${plan}%"></span>`}</div>`;
}
// The bar over "45% · plan 60%".
function progressCellHtml(pct, plannedPct, tone) {
  const plan = plannedPct == null ? '' : ` · ${escapeHTML(bt('planPct', { pct: clampPct(plannedPct) }))}`;
  return `<div class="progress-cell">${progressBarHtml(pct, plannedPct, tone)}<span class="progress-cell__label"><strong>${clampPct(pct)}%</strong>${plan}</span></div>`;
}

const dayNumber = key => Math.round(dayKeyDate(key).getTime() / 86400000);
const monthStartKey = key => `${key.slice(0, 7)}-01`;
const nextMonthKey = key => {
  const [y, m] = key.split('-').map(Number);
  return new Date(Date.UTC(y, m, 1)).toISOString().slice(0, 10);
};

/**
 * A Gantt of [rows] ({ label, sub, start, end, pct, tone, open }) across whole months, today marked. A row
 * without dates says so in its track. `open` makes the label a button with that data-gantt-open value.
 */
function ganttHtml(rows, headLabel) {
  const dated = rows.filter(r => r.start && r.end);
  if (!dated.length) return '';
  const today = todayKey();
  const first = monthStartKey(dated.reduce((min, r) => (r.start < min ? r.start : min), dated[0].start));
  const lastEnd = dated.reduce((max, r) => (r.end > max ? r.end : max), dated[0].end);
  const end = nextMonthKey(lastEnd);
  const span = dayNumber(end) - dayNumber(first);
  const at = key => ((dayNumber(key) - dayNumber(first)) / span) * 100;
  const months = [];
  for (let key = first; key < end; key = nextMonthKey(key)) months.push(key);
  const step = Math.max(1, Math.ceil(months.length / 12));
  const ticks = months.map((key, i) => {
    const label = i % step ? '' : new Date(`${key}T00:00:00Z`).toLocaleDateString(uiLocale(), { month: 'short', ...(i === 0 || key.endsWith('-01-01') ? { year: '2-digit' } : {}), timeZone: 'UTC' });
    return `<span class="gantt__tick" style="left:${at(key).toFixed(2)}%">${escapeHTML(label)}</span>`;
  }).join('');
  const lines = months.slice(1).map(key => `<span class="gantt__line" style="left:${at(key).toFixed(2)}%"></span>`).join('');
  const now = today >= first && today < end ? `<span class="gantt__now" style="left:${at(today).toFixed(2)}%" title="${escapeHTML(BLD.today)}"></span>` : '';
  const body = rows.map(r => {
    const label = `<span class="gantt__name">${escapeHTML(r.label)}</span>${r.sub ? `<span class="gantt__sub">${escapeHTML(r.sub)}</span>` : ''}`;
    const labelHtml = r.open ? `<button class="gantt__label" type="button" data-gantt-open="${escapeHTML(r.open)}">${label}</button>` : `<div class="gantt__label">${label}</div>`;
    const bar = r.start && r.end
      ? `<span class="gantt__bar" data-tone="${r.tone || 'accent'}" style="left:${at(r.start).toFixed(2)}%;width:${(((dayNumber(r.end) - dayNumber(r.start) + 1) / span) * 100).toFixed(2)}%" title="${escapeHTML(`${fmtDay(r.start)} – ${fmtDay(r.end)} · ${clampPct(r.pct)}%`)}"><span class="gantt__done" style="width:${clampPct(r.pct)}%"></span></span>`
      : `<span class="gantt__undated">${escapeHTML(BLD.noDates)}</span>`;
    return `<div class="gantt__row">${labelHtml}<div class="gantt__track">${lines}${bar}${now}</div></div>`;
  }).join('');
  return `<div class="gantt" role="table" aria-label="${escapeHTML(headLabel)}"><div class="gantt__head"><span class="gantt__head-label">${escapeHTML(headLabel)}</span><div class="gantt__scale">${ticks}</div></div>${body}</div>`;
}

/**
 * A Building list panel: like crmPanel, with the table as cards on a phone (`.tbl--stack`, cells carry their
 * data-label) and, in place of a table, any [body] (the timeline).
 */
function bldPanel({ title, tag = null, views = '', tools = '', head = '', rows = '', empty = '', emptyDesc = '', body = '' }) {
  const headTools = views ? `<div class="panel__views">${views}</div>` : (tools ? `<div class="panel__tools">${tools}</div>` : '');
  const filters = views && tools ? `<div class="panel__filters">${tools}</div>` : '';
  const cols = (String(head).match(/<th/g) || []).length || 1;
  const emptyHtml = `<div class="empty"><p class="empty__title">${escapeHTML(empty)}</p>${emptyDesc ? `<p class="empty__desc">${escapeHTML(emptyDesc)}</p>` : ''}</div>`;
  const content = body || `<div class="tbl-wrap"><table class="tbl tbl--stack"><thead>${head}</thead><tbody>${rows || `<tr><td colspan="${cols}">${emptyHtml}</td></tr>`}</tbody></table></div>`;
  return `<div class="panel">
    <div class="panel__head"><h2 class="panel__title">${escapeHTML(title)}${tag != null ? ` <span class="tag">${escapeHTML(String(tag))}</span>` : ''}</h2>${headTools}</div>
    ${filters}
    ${content}
  </div>`;
}
const bldEmptyHtml = (title, desc = '') => `<div class="empty"><p class="empty__title">${escapeHTML(title)}</p>${desc ? `<p class="empty__desc">${escapeHTML(desc)}</p>` : ''}</div>`;
// A titled block of text someone wrote (work done, a description), with its line breaks; nothing without text.
function textPanel(title, value) {
  return value ? `<section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(title)}</h2></header><div class="panel__body"><p class="panel__text">${escapeHTML(value)}</p></div></section>` : '';
}
// A table cell's data-label attribute, the column heading a phone card shows above the value.
const cellLabel = text => ` data-label="${escapeHTML(text)}"`;

// ── Projects as pickers and lookups ──────────────────────────────────────────────────────────────

async function ensureBuildingProjects(force = false) {
  if (!force && state.fetched.projects) return state.projects;
  state.projects = await api('/app/api/building/projects');
  state.fetched.projects = true;
  return state.projects;
}
// The projects a diary entry, issue, change or measurement can be about: the ones still running first.
function projectChoices(selectedId = '') {
  const open = state.projects.filter(p => p.status !== 'COMPLETED' && p.status !== 'CANCELLED');
  const closed = state.projects.filter(p => !open.includes(p));
  const option = p => `<option value="${escapeHTML(p.id)}"${p.id === selectedId ? ' selected' : ''}>${escapeHTML(projectLabel(p))}</option>`;
  return `${open.map(option).join('')}${closed.length ? `<optgroup label="${escapeHTML(BLD.finishedProjects)}">${closed.map(option).join('')}</optgroup>` : ''}`;
}
function projectSelectHtml(id, selectedId = '', required = true) {
  return `<div class="form__row"><label class="lbl" for="${id}">${escapeHTML(BLD.project)}${required ? ' <span class="req">●</span>' : ''}</label>
    <select class="sel" id="${id}"${required ? ' required' : ''}><option value="">${escapeHTML(BLD.chooseProject)}</option>${projectChoices(selectedId)}</select></div>`;
}
// A compact project filter for a list page's tools.
function projectFilterHtml(selectedId, attr) {
  return `<select class="sel" ${attr} aria-label="${escapeHTML(BLD.filterProject)}"><option value="">${escapeHTML(BLD.allProjects)}</option>${projectChoices(selectedId)}</select>`;
}
const projectTrailEntry = (project, tab) => ({ key: `project:${project.id}`, label: project.number || project.name, open: () => openProjectDrawer(project.id, tab) });

// ── Photos ──────────────────────────────────────────────────────────────────────────────────────

// Object URLs of photos already fetched, by `${id}:${size}`, so a redraw doesn't fetch them again.
const buildingPhotoUrls = new Map();

async function buildingPhotoSrc(id, size) {
  const key = `${id}:${size}`;
  if (!buildingPhotoUrls.has(key)) {
    buildingPhotoUrls.set(key, apiBlob(`/app/api/building/photos/${encodeURIComponent(id)}${size === 'thumb' ? '?size=thumb' : ''}`).then(blob => URL.createObjectURL(blob)));
  }
  try {
    return await buildingPhotoUrls.get(key);
  } catch (err) {
    buildingPhotoUrls.delete(key);
    throw err;
  }
}
// Fills every img[data-photo] under [root] once its file arrives.
function hydratePhotos(root) {
  $$('img[data-photo]:not([src])', root).forEach(img => {
    buildingPhotoSrc(img.dataset.photo, img.dataset.size || 'thumb').then(src => { img.src = src; }).catch(() => {});
  });
}
function photoGridHtml(photos) {
  return `<div class="photo-grid">${photos.map(p => `<figure class="photo">
    <button class="photo__open" type="button" data-photo-open="${escapeHTML(p.id)}" aria-label="${escapeHTML(p.caption || bt('photos.openAria', { day: fmtDay(p.day) }))}"><img class="photo__img" data-photo="${escapeHTML(p.id)}" data-size="thumb" alt="" loading="lazy" /></button>
    ${p.shared ? '' : `<span class="pill photo__flag">${escapeHTML(BLD.photoPrivate)}</span>`}
    <figcaption class="photo__caption">${escapeHTML(p.caption || fmtDay(p.day))}</figcaption>
  </figure>`).join('')}</div>`;
}
// Opens a photo of [photos] when its thumbnail is clicked; [onChange] runs after it is edited or deleted.
function wirePhotoGrid(root, photos, back, onChange) {
  hydratePhotos(root);
  $$('[data-photo-open]', root).forEach(b => b.addEventListener('click', () => {
    const photo = photos.find(p => p.id === b.dataset.photoOpen);
    if (photo) openFrom(back, () => openPhotoViewer(photo, onChange));
  }));
}

// The photo as JPEG, at most [max] px on its longest side; the browser turns the camera's way up.
async function shrinkPhoto(file, max, quality) {
  let image = null;
  if (window.createImageBitmap) image = await createImageBitmap(file, { imageOrientation: 'from-image' }).catch(() => null);
  let url = null;
  if (!image) {
    url = URL.createObjectURL(file);
    image = await new Promise((resolve, reject) => {
      const img = new Image();
      img.onload = () => resolve(img);
      img.onerror = reject;
      img.src = url;
    });
  }
  try {
    const w = image.width || image.naturalWidth;
    const h = image.height || image.naturalHeight;
    const scale = Math.min(1, max / Math.max(w, h));
    const canvas = document.createElement('canvas');
    canvas.width = Math.max(1, Math.round(w * scale));
    canvas.height = Math.max(1, Math.round(h * scale));
    canvas.getContext('2d').drawImage(image, 0, 0, canvas.width, canvas.height);
    const blob = await new Promise(resolve => canvas.toBlob(resolve, 'image/jpeg', quality));
    if (!blob) throw new Error('unreadable');
    return blob;
  } finally {
    if (url) URL.revokeObjectURL(url);
    image.close?.();
  }
}
// Asks for photos (the camera on a phone offers itself); resolves with the files picked, or none.
function pickPhotoFiles() {
  return new Promise(resolve => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = 'image/*';
    input.multiple = true;
    input.addEventListener('change', () => resolve([...(input.files || [])]), { once: true });
    input.click();
  });
}
/**
 * Uploads [files] to project [projectId], each shrunk to 1600 px with a 480 px thumbnail; [extra] carries logId,
 * issueId, phaseId or shared. Resolves with the photos stored; a file that fails is skipped with a toast.
 */
async function uploadProjectPhotos(projectId, files, extra = {}) {
  const stored = [];
  for (const [i, file] of files.entries()) {
    if (files.length > 1) toast(bt('photos.uploading', { n: i + 1, total: files.length }));
    try {
      const [full, thumb] = await Promise.all([shrinkPhoto(file, 1600, 0.82), shrinkPhoto(file, 480, 0.72)]);
      const body = new FormData();
      body.append('file', full, 'photo.jpg');
      body.append('thumb', thumb, 'thumb.jpg');
      if (file.lastModified) body.append('takenAt', new Date(file.lastModified).toISOString());
      Object.entries(extra).forEach(([k, v]) => { if (v != null && v !== '') body.append(k, String(v)); });
      stored.push(await api(`/app/api/building/projects/${encodeURIComponent(projectId)}/photos`, { method: 'POST', body }));
    } catch (err) {
      if (err?.message === 'unauthorized') return stored;
      toast(err?.code ? buildingErrorText(err, BLD.photoFailed) : BLD.photoUnreadable);
    }
  }
  if (stored.length) toast(bt('photos.added', { n: stored.length }));
  return stored;
}
async function addPhotosTo(projectId, extra, onDone) {
  const files = await pickPhotoFiles();
  if (!files.length) return;
  const stored = await uploadProjectPhotos(projectId, files, extra);
  if (stored.length) onDone?.(stored);
}

function openPhotoViewer(photo, onChange) {
  const form = document.createElement('form');
  form.className = 'form';
  form.innerHTML = `
    <img class="photo-view" data-photo="${escapeHTML(photo.id)}" data-size="full" alt="${escapeHTML(photo.caption || '')}" />
    ${dashFacts([[BLD.photoDay, fmtDay(photo.day)], [BLD.photoBy, photo.uploadedBy || '—']])}
    <div class="form__row"><label class="lbl" for="ph-caption">${escapeHTML(BLD.photoCaption)} <span class="opt">${escapeHTML(STR.optional)}</span></label>
      <input class="inp" id="ph-caption" maxlength="300" value="${escapeHTML(photo.caption || '')}" /></div>
    <label class="form__check"><input type="checkbox" id="ph-shared" ${photo.shared ? 'checked' : ''} /> ${escapeHTML(BLD.photoShared)}</label>
    <p class="hint">${escapeHTML(BLD.photoSharedHint)}</p>
    <div class="drawer__foot">
      <button class="btn btn--ghost" type="button" data-photo-delete>${escapeHTML(BLD.photoDelete)}</button>
      <button class="btn btn--accent" type="submit">${escapeHTML(BLD.save)}</button>
    </div>`;
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const btn = $('button[type=submit]', form);
    btn.disabled = true;
    try {
      await api(`/app/api/building/photos/${encodeURIComponent(photo.id)}`, { method: 'PATCH', body: JSON.stringify({ caption: $('#ph-caption', form).value, shared: $('#ph-shared', form).checked }) });
      toast(BLD.photoSaved);
      onChange?.();
      closeDrawer();
    } catch { btn.disabled = false; toast(BLD.photoFailed); }
  });
  $('[data-photo-delete]', form).addEventListener('click', async () => {
    if (!await confirmDialog({ title: BLD.photoDeleteTitle, body: BLD.photoDeleteBody, okLabel: BLD.photoDelete })) return;
    try {
      await api(`/app/api/building/photos/${encodeURIComponent(photo.id)}`, { method: 'DELETE' });
      toast(BLD.photoDeleted);
      onChange?.();
      closeDrawer();
    } catch { toast(BLD.photoFailed); }
  });
  openDrawer(photo.caption || fmtDay(photo.day), form, true, { eyebrow: BLD.photoEyebrow });
  hydratePhotos(form);
}

/**
 * The Building pages' nav counts, from the lists already fetched or else Home's figures: projects delayed
 * (an alert) or running, running sites without today's diary, open issues (overdue ones as an alert), changes
 * waiting for the client and measurements waiting to be invoiced.
 */
function buildingNavCounts(o) {
  const b = o.projects || {};
  const counts = {};
  const alerts = {};
  const running = state.fetched.projects ? state.projects.filter(p => p.status === 'ACTIVE') : null;
  const delayed = running ? running.filter(p => p.health === 'DELAYED').length : (b.delayed || 0);
  counts.projects = delayed || (running ? running.length : (b.active || 0));
  alerts.projects = delayed > 0;
  if (b.activeWithoutDiary) counts['site-diary'] = b.activeWithoutDiary;
  const open = state.fetched.issues ? state.issues.filter(i => i.status === 'OPEN' || i.status === 'IN_PROGRESS') : null;
  const overdue = open ? open.filter(i => i.overdue).length : (b.overdueIssues || 0);
  counts['site-issues'] = overdue || (open ? open.length : (b.openIssues || 0));
  alerts['site-issues'] = overdue > 0;
  const waiting = state.fetched.changeOrders ? state.changeOrders.filter(c => c.status === 'SENT').length : (b.pendingChanges || 0);
  if (waiting) counts['change-orders'] = waiting;
  const toInvoice = state.fetched.measurements ? state.measurements.filter(m => m.status === 'APPROVED').length : (b.toInvoiceCount || 0);
  if (toInvoice) counts.measurements = toInvoice;
  return { counts, alerts };
}

// After a Building record changes, the page behind the drawer and the nav's counts follow.
async function refreshBuildingPage() {
  const tab = state.active;
  if (['projects', 'site-diary', 'site-issues', 'change-orders', 'measurements'].includes(tab)) {
    await loadModule(tab).catch(() => {});
    render();
  } else {
    if (state.fetched.projects) await ensureBuildingProjects(true).catch(() => {});
    renderNav();
  }
}

/* Interactive A4 quote/invoice template studio. Mounted from app.js settings. */
(function () {
  const PAGE_W = 595;
  const PAGE_H = 842;
  const GRID = 8;
  const HANDLES = ['nw', 'n', 'ne', 'e', 'se', 's', 'sw', 'w'];
  const BLOCK_IDS = ['logo', 'contact', 'company', 'title', 'client', 'items', 'totals', 'payment', 'terms', 'footer'];
  const FALLBACK_LAYOUT = [
    { id: 'logo', x: 403, y: 60, w: 150, h: 52, visible: true },
    { id: 'contact', x: 42, y: 48, w: 340, h: 44, visible: true },
    { id: 'company', x: 42, y: 96, w: 280, h: 44, visible: false },
    { id: 'title', x: 42, y: 151, w: 400, h: 48, visible: true },
    { id: 'client', x: 42, y: 214, w: 280, h: 90, visible: true },
    { id: 'items', x: 42, y: 290, w: 511, h: 280, visible: true },
    { id: 'totals', x: 333, y: 590, w: 220, h: 32, visible: true },
    { id: 'payment', x: 50, y: 708, w: 320, h: 56, visible: true },
    { id: 'terms', x: 50, y: 766, w: 320, h: 40, visible: true },
    { id: 'footer', x: 200, y: 812, w: 353, h: 18, visible: true },
  ];
  const MIN = {
    logo: [72, 28], contact: [140, 28], company: [120, 28], title: [160, 32],
    client: [140, 48], items: [220, 80], totals: [120, 24], payment: [140, 36],
    terms: [140, 32], footer: [120, 16],
  };

  let deps = null;
  let host = null;
  let draft = null;
  let selected = 'logo';
  let inspectorFor = '';
  let mode = 'quote';
  let dirty = false;
  let logoUrl = '';
  let drag = null;
  let fitObs = null;

  function STR() { return deps.STR; }
  function esc(s) { return deps.escapeHTML(s); }

  function defaultLayout(template) {
    const fromApi = template?.defaults?.layout;
    return (fromApi && fromApi.length ? fromApi : FALLBACK_LAYOUT).map(copyBlock);
  }

  function copyBlock(b) {
    return { id: b.id, x: +b.x, y: +b.y, w: +b.w, h: +b.h, visible: b.visible !== false };
  }

  function mergeLayout(template) {
    const base = defaultLayout(template);
    const custom = Object.fromEntries((template?.layout || []).map(b => [b.id, copyBlock(b)]));
    return base.map(b => custom[b.id] ? { ...b, ...custom[b.id], id: b.id } : b);
  }

  function cloneDraft(template) {
    const t = template || {};
    return {
      companyName: t.companyName || deps.tenantName() || '',
      tagline: t.tagline || '',
      taxId: t.taxId || '',
      email: t.email || '',
      phone: t.phone || '',
      address: t.address || '',
      quoteTitle: t.quoteTitle || '',
      invoiceTitle: t.invoiceTitle || '',
      quotePaymentTerms: t.quotePaymentTerms || '',
      invoicePaymentTerms: t.invoicePaymentTerms || '',
      termsText: t.termsText || '',
      footerText: t.footerText || '',
      accentColor: normalizeHex(t.accentColor || t.defaults?.accentColor || '#96AAB6'),
      showDecor: t.showDecor !== false,
      layout: mergeLayout(t),
      hasLogo: !!t.hasLogo,
      defaults: t.defaults || {},
    };
  }

  function normalizeHex(value) {
    const m = String(value || '').trim().match(/^#?([0-9a-fA-F]{6})$/);
    return m ? `#${m[1].toUpperCase()}` : '#96AAB6';
  }

  function onBrand(hex) {
    const h = normalizeHex(hex).slice(1);
    const r = parseInt(h.slice(0, 2), 16), g = parseInt(h.slice(2, 4), 16), b = parseInt(h.slice(4, 6), 16);
    return (0.299 * r + 0.587 * g + 0.114 * b) / 255 > 0.62 ? '#121212' : '#ffffff';
  }

  function blockLabel(id) {
    const key = `docTplBlock${id.charAt(0).toUpperCase()}${id.slice(1)}`;
    return STR()[key] || id;
  }

  function blockOf(id) { return draft.layout.find(b => b.id === id); }

  function markDirty() {
    dirty = true;
    const el = host?.querySelector('[data-tpl-dirty]');
    if (el) el.hidden = false;
  }

  function snap(n, alt) { return alt ? Math.round(n) : Math.round(n / GRID) * GRID; }

  function clampBlock(b) {
    const [minW, minH] = MIN[b.id] || [48, 24];
    b.w = Math.min(PAGE_W, Math.max(minW, b.w));
    b.h = Math.min(PAGE_H, Math.max(minH, b.h));
    b.x = Math.min(PAGE_W - 24, Math.max(0, b.x));
    b.y = Math.min(PAGE_H - 16, Math.max(0, b.y));
    return b;
  }

  function pagePoint(ev, page) {
    const r = page.getBoundingClientRect();
    return { x: (ev.clientX - r.left) * (PAGE_W / r.width), y: (ev.clientY - r.top) * (PAGE_H / r.height) };
  }

  function sampleDate() {
    const loc = (window.I18N && I18N.locale()) || 'pt-PT';
    return new Date().toLocaleDateString(loc === 'en' ? 'en-GB' : loc, { day: '2-digit', month: '2-digit', year: 'numeric' });
  }

  function d(key, fallback) {
    const v = draft[key];
    if (v && String(v).trim()) return v;
    return draft.defaults?.[fallback || key] || '';
  }

  function blockInner(id) {
    const S = STR();
    if (id === 'logo') {
      if (logoUrl) return `<div class="tpl-logo tpl-logo--img"><img alt="" src="${esc(logoUrl)}" /></div>`;
      const name = (draft.companyName || deps.tenantName() || '—').toUpperCase();
      const tag = draft.tagline ? `<div class="tpl-sub">${esc(draft.tagline)}</div>` : '';
      return `<div class="tpl-logo">${esc(name.slice(0, 18))}${tag}</div>`;
    }
    if (id === 'contact') {
      return `<div class="tpl-sub">${esc([draft.taxId, draft.email || draft.phone, draft.phone && draft.email ? draft.phone : ''].filter(Boolean).join('   ') || '—')}</div>`;
    }
    if (id === 'company') {
      return `<div class="tpl-name">${esc(draft.companyName || deps.tenantName() || '—')}</div>
        ${draft.tagline ? `<div class="tpl-sub">${esc(draft.tagline)}</div>` : ''}
        ${draft.address ? `<div class="tpl-sub">${esc(draft.address)}</div>` : ''}`;
    }
    if (id === 'title') {
      const title = mode === 'invoice' ? d('invoiceTitle') : d('quoteTitle');
      const num = mode === 'invoice' ? S.docTplSampleInvoiceNo : S.docTplSampleQuoteNo;
      return `<div class="tpl-title">${esc(title)}  ${esc(num)}</div><div class="tpl-meta">${esc(S.docTplDateLabel)}: ${esc(sampleDate())}</div>`;
    }
    if (id === 'client') {
      return `<div class="tpl-kicker">${esc(S.docTplClientLabel)}</div>
        <div class="tpl-name">${esc(S.docTplSampleClient).toUpperCase()}</div>
        <div class="tpl-sub">${esc(S.docTplSamplePhone)}</div>`;
    }
    if (id === 'items') {
      return `<table class="tpl-table"><thead><tr>
        <th>${esc(S.docTplColService)}</th><th>${esc(S.docTplColDesc)}</th><th>${esc(S.docTplColValue)}</th>
      </tr></thead><tbody>
        <tr><td>${esc(S.docTplSampleItem1)}</td><td>${esc(S.docTplSampleItem1Desc)}</td><td>1 500,00 EUR</td></tr>
        <tr><td>${esc(S.docTplSampleItem2)}</td><td>${esc(S.docTplSampleItem2Desc)}</td><td>2 000,00 EUR</td></tr>
      </tbody></table>`;
    }
    if (id === 'totals') return `<div class="tpl-total">${esc(S.docTplTotalLabel)}: 3 500,00 EUR</div>`;
    if (id === 'payment') {
      const text = mode === 'invoice' ? d('invoicePaymentTerms', 'paymentTerms') : d('quotePaymentTerms', 'paymentTerms');
      return `<div class="tpl-kicker">${esc(S.docTplPaymentLabel)}</div><div class="tpl-note">${esc(text)}</div>`;
    }
    if (id === 'terms') {
      return `<div class="tpl-kicker">${esc(S.docTplTermsLabel)}</div><div class="tpl-note">${esc(d('termsText'))}</div>`;
    }
    if (id === 'footer') return `<div class="tpl-foot">${esc(d('footerText'))}</div>`;
    return '';
  }

  function handlesHtml() {
    return HANDLES.map(h => `<span class="tpl__handle tpl__handle--${h}" data-handle="${h}"></span>`).join('');
  }

  function layerHtml(b) {
    return `<div class="tpl__layer ${selected === b.id ? 'is-on' : ''} ${b.visible ? '' : 'is-off'}" data-layer="${esc(b.id)}">
      <span>${esc(blockLabel(b.id))}</span>
      <button class="iconbtn" type="button" data-vis="${esc(b.id)}" aria-label="${esc(STR().docTplHideAria)}" title="${esc(b.visible ? STR().docTplHide : STR().docTplShow)}">${b.visible ? '◉' : '○'}</button>
    </div>`;
  }

  function inspectorFields(id) {
    const S = STR();
    const optMark = () => ` <span class="opt">${esc(S.optional)}</span>`;
    const field = (label, key, extra = '', optional = false) =>
      `<div class="form__row form__row--full"><label class="lbl" for="tpl-${key}">${esc(label)}${optional ? optMark() : ''}</label>
        <input class="inp ${extra}" id="tpl-${key}" data-field="${key}" value="${esc(draft[key] || '')}" /></div>`;
    const area = (label, key, ph) =>
      `<div class="form__row form__row--full"><label class="lbl" for="tpl-${key}">${esc(label)}</label>
        <textarea class="txt" id="tpl-${key}" rows="3" data-field="${key}" placeholder="${esc(ph || '')}">${esc(draft[key] || '')}</textarea></div>`;

    if (id === 'logo' || id === 'company') {
      return `${field(S.docCompanyName, 'companyName')}
        ${field(S.docTagline, 'tagline', '', true)}
        ${id === 'company' ? field(S.docAddress, 'address', '', true) : ''}
        <div class="form__row form__row--full"><label class="lbl">${esc(S.docLogo)}</label>
          <div class="tpl__logo-row">
            <label class="btn btn--sm"><input type="file" id="tpl-logo" accept="image/png,image/jpeg,image/webp" hidden />${esc(S.docLogoUpload)}</label>
            ${draft.hasLogo ? `<button class="btn btn--sm btn--ghost" type="button" id="tpl-logo-remove">${esc(S.docLogoRemove)}</button>` : `<span class="muted">${esc(S.docLogoNone)}</span>`}
          </div>
          <p class="hint">${esc(S.docLogoHint)}</p>
        </div>`;
    }
    if (id === 'contact') {
      return `${field(S.docTaxId, 'taxId', 'inp--mono', true)}
        ${field(S.docEmail, 'email', '', true)}
        ${field(S.docPhone, 'phone', '', true)}
        ${field(S.docAddress, 'address', '', true)}`;
    }
    if (id === 'title') {
      return `${field(S.docQuoteTitle, 'quoteTitle')}${field(S.docInvoiceTitle, 'invoiceTitle')}`;
    }
    if (id === 'payment') {
      return `${area(S.docQuotePayment, 'quotePaymentTerms', d('quotePaymentTerms', 'paymentTerms'))}
        ${area(S.docInvoicePayment, 'invoicePaymentTerms', d('invoicePaymentTerms', 'paymentTerms'))}`;
    }
    if (id === 'terms') return area(S.docTerms, 'termsText', d('termsText'));
    if (id === 'footer') return field(S.docFooter, 'footerText', '', true);
    return `<p class="hint">${esc(S.docTplSampleHint)}</p>`;
  }

  function inspectorHtml() {
    const S = STR();
    const b = blockOf(selected);
    if (!b) return `<p class="hint">${esc(S.docTplEmptySelect)}</p>`;
    return `<div class="form">
      <div class="form__row form__row--full"><strong>${esc(blockLabel(b.id))}</strong></div>
      ${inspectorFields(b.id)}
      <div class="form__row form__row--full"><label class="lbl">${esc(S.docTplPos)}</label>
        <div class="tpl__xy">
          <input class="inp inp--mono" data-geo="x" value="${Math.round(b.x)}" />
          <input class="inp inp--mono" data-geo="y" value="${Math.round(b.y)}" />
        </div>
      </div>
      <div class="form__row form__row--full"><label class="lbl">${esc(S.docTplSize)}</label>
        <div class="tpl__xy">
          <input class="inp inp--mono" data-geo="w" value="${Math.round(b.w)}" />
          <input class="inp inp--mono" data-geo="h" value="${Math.round(b.h)}" />
        </div>
      </div>
    </div>`;
  }

  function studioHtml() {
    const S = STR();
    return `<div class="tpl__head">
      <div>
        <h2 class="view__title">${esc(S.docTemplateTitle)}</h2>
        <p class="view__desc">${esc(S.docTemplateDesc)}</p>
        <p class="hint">${esc(S.docTplDragHint)}</p>
      </div>
      <div class="tpl__actions">
        <span class="tpl__dirty" data-tpl-dirty ${dirty ? '' : 'hidden'}>${esc(S.docTplDirty)}</span>
        <button class="chip ${mode === 'quote' ? 'is-on' : ''}" type="button" data-mode="quote">${esc(S.docTplPreviewQuote)}</button>
        <button class="chip ${mode === 'invoice' ? 'is-on' : ''}" type="button" data-mode="invoice">${esc(S.docTplPreviewInvoice)}</button>
        <button class="btn btn--ghost btn--sm" type="button" id="tpl-reset">${esc(S.docTplResetLayout)}</button>
        <button class="btn btn--primary" type="button" id="tpl-save">${esc(S.docTemplateSave)}</button>
      </div>
    </div>
    <div class="tpl__studio">
      <div class="tpl__pane">
        <p class="tpl__pane-title">${esc(S.docTplLayers)}</p>
        <div class="tpl__layers" id="tpl-layers">${draft.layout.map(layerHtml).join('')}</div>
        <div class="form">
          <div class="form__row form__row--full"><label class="lbl" for="tpl-accent">${esc(S.docTplAccent)}</label>
            <div class="tpl__color">
              <input class="tpl__swatch" id="tpl-accent" type="color" value="${esc(draft.accentColor)}" aria-label="${esc(S.docTplColorAria)}" />
              <input class="inp inp--mono" id="tpl-accent-hex" value="${esc(draft.accentColor)}" />
            </div>
          </div>
          <label class="tpl__check"><input type="checkbox" id="tpl-decor" ${draft.showDecor ? 'checked' : ''} /> ${esc(S.docTplDecor)}</label>
        </div>
      </div>
      <div class="tpl__stage" id="tpl-stage">
        <p class="tpl__stage-label">${esc(S.docTplPageLabel)}</p>
        <div class="tpl__sizer" id="tpl-sizer">
          <div class="tpl__page ${draft.showDecor ? 'is-decor' : ''}" id="tpl-page" tabindex="0">
            ${draft.layout.map(b => `<div class="tpl__block ${selected === b.id ? 'is-on' : ''} ${b.visible ? '' : 'is-off'}" data-block="${esc(b.id)}" role="button" tabindex="0" aria-label="${esc(blockLabel(b.id))}" style="left:${b.x}px;top:${b.y}px;width:${b.w}px;height:${b.h}px">
              <div class="tpl__block-body">${blockInner(b.id)}</div>${handlesHtml()}
            </div>`).join('')}
          </div>
        </div>
      </div>
      <div class="tpl__pane tpl__inspect">
        <p class="tpl__pane-title">${esc(S.docTplInspector)}</p>
        <div id="tpl-inspector">${inspectorHtml()}</div>
      </div>
    </div>`;
  }

  function applyTheme() {
    const page = host.querySelector('#tpl-page');
    if (!page) return;
    page.style.setProperty('--doc-brand', draft.accentColor);
    page.style.setProperty('--doc-brand-ink', onBrand(draft.accentColor));
    page.classList.toggle('is-decor', draft.showDecor);
  }

  function applyGeometry() {
    draft.layout.forEach(b => {
      const el = host.querySelector(`[data-block="${b.id}"]`);
      if (!el) return;
      el.style.left = `${b.x}px`;
      el.style.top = `${b.y}px`;
      el.style.width = `${b.w}px`;
      el.style.height = `${b.h}px`;
      el.classList.toggle('is-on', selected === b.id);
      el.classList.toggle('is-off', !b.visible);
    });
  }

  function applyContent() {
    draft.layout.forEach(b => {
      const body = host.querySelector(`[data-block="${b.id}"] .tpl__block-body`);
      if (body) body.innerHTML = blockInner(b.id);
    });
  }

  function applyLayers() {
    const root = host.querySelector('#tpl-layers');
    if (root) root.innerHTML = draft.layout.map(layerHtml).join('');
  }

  function applyInspector(force) {
    if (!force && inspectorFor === selected) {
      host.querySelectorAll('[data-geo]').forEach(inp => {
        const b = blockOf(selected);
        if (b) inp.value = Math.round(b[inp.dataset.geo]);
      });
      return;
    }
    inspectorFor = selected;
    const box = host.querySelector('#tpl-inspector');
    if (box) box.innerHTML = inspectorHtml();
    wireInspector();
  }

  function fit() {
    const stage = host.querySelector('#tpl-stage');
    const page = host.querySelector('#tpl-page');
    const sizer = host.querySelector('#tpl-sizer');
    if (!stage || !page || !sizer) return;
    const avail = Math.max(220, stage.clientWidth - 36);
    const scale = Math.min(1, avail / PAGE_W);
    page.style.setProperty('--tpl-scale', String(scale));
    sizer.style.width = `${PAGE_W * scale}px`;
    sizer.style.height = `${PAGE_H * scale}px`;
  }

  function select(id) {
    if (!BLOCK_IDS.includes(id)) return;
    selected = id;
    applyGeometry();
    applyLayers();
    applyInspector(true);
  }

  function wireInspector() {
    host.querySelectorAll('[data-field]').forEach(el => {
      el.addEventListener('input', () => {
        draft[el.dataset.field] = el.value;
        markDirty();
        applyContent();
      });
    });
    host.querySelectorAll('[data-geo]').forEach(el => {
      el.addEventListener('change', () => {
        const b = blockOf(selected);
        if (!b) return;
        b[el.dataset.geo] = Number(el.value) || 0;
        clampBlock(b);
        markDirty();
        applyGeometry();
        applyInspector(false);
      });
    });
    host.querySelector('#tpl-logo')?.addEventListener('change', uploadLogo);
    host.querySelector('#tpl-logo-remove')?.addEventListener('click', removeLogo);
  }

  function bindChrome() {
    host.querySelectorAll('[data-mode]').forEach(btn => {
      btn.addEventListener('click', () => {
        mode = btn.dataset.mode;
        host.querySelectorAll('[data-mode]').forEach(b => b.classList.toggle('is-on', b.dataset.mode === mode));
        applyContent();
      });
    });
    host.querySelector('#tpl-reset')?.addEventListener('click', () => {
      draft.layout = defaultLayout(deps.getTemplate()).map(copyBlock);
      markDirty();
      applyGeometry();
      applyLayers();
      applyInspector(true);
      deps.toast(STR().docTplLayoutReset);
    });
    host.querySelector('#tpl-save')?.addEventListener('click', save);
    host.querySelector('#tpl-accent')?.addEventListener('input', e => {
      draft.accentColor = normalizeHex(e.target.value);
      const hex = host.querySelector('#tpl-accent-hex');
      if (hex) hex.value = draft.accentColor;
      markDirty();
      applyTheme();
      applyContent();
    });
    host.querySelector('#tpl-accent-hex')?.addEventListener('change', e => {
      draft.accentColor = normalizeHex(e.target.value);
      const sw = host.querySelector('#tpl-accent');
      if (sw) sw.value = draft.accentColor;
      e.target.value = draft.accentColor;
      markDirty();
      applyTheme();
      applyContent();
    });
    host.querySelector('#tpl-decor')?.addEventListener('change', e => {
      draft.showDecor = e.target.checked;
      markDirty();
      applyTheme();
    });
  }

  function bindLayers() {
    host.querySelector('#tpl-layers')?.addEventListener('click', e => {
      const vis = e.target.closest('[data-vis]');
      if (vis) {
        e.stopPropagation();
        const b = blockOf(vis.dataset.vis);
        if (!b) return;
        b.visible = !b.visible;
        markDirty();
        applyGeometry();
        applyLayers();
        return;
      }
      const layer = e.target.closest('[data-layer]');
      if (layer) select(layer.dataset.layer);
    });
  }

  function bindPage() {
    const page = host.querySelector('#tpl-page');
    if (!page) return;
    page.addEventListener('pointerdown', onPointerDown);
    page.addEventListener('keydown', onPageKey);
    page.addEventListener('click', e => {
      if (e.target === page) { selected = ''; inspectorFor = ''; applyGeometry(); applyLayers(); applyInspector(true); }
    });
  }

  function onPointerDown(e) {
    const handle = e.target.closest('[data-handle]');
    const blockEl = e.target.closest('[data-block]');
    if (!blockEl) return;
    const b = blockOf(blockEl.dataset.block);
    if (!b) return;
    select(b.id);
    const page = host.querySelector('#tpl-page');
    const start = pagePoint(e, page);
    drag = {
      id: b.id,
      handle: handle?.dataset.handle || '',
      startX: start.x,
      startY: start.y,
      orig: { x: b.x, y: b.y, w: b.w, h: b.h },
    };
    page.classList.add('is-dragging');
    blockEl.setPointerCapture?.(e.pointerId);
    e.preventDefault();
  }

  function onPointerMove(e) {
    if (!drag) return;
    const page = host.querySelector('#tpl-page');
    if (!page) return;
    const pt = pagePoint(e, page);
    const dx = pt.x - drag.startX;
    const dy = pt.y - drag.startY;
    const b = blockOf(drag.id);
    if (!b) return;
    const o = drag.orig;
    const alt = e.altKey;
    if (!drag.handle) {
      b.x = snap(o.x + dx, alt);
      b.y = snap(o.y + dy, alt);
    } else {
      const h = drag.handle;
      if (h.includes('e')) b.w = snap(o.w + dx, alt);
      if (h.includes('s')) b.h = snap(o.h + dy, alt);
      if (h.includes('w')) { b.x = snap(o.x + dx, alt); b.w = snap(o.w - dx, alt); }
      if (h.includes('n')) { b.y = snap(o.y + dy, alt); b.h = snap(o.h - dy, alt); }
    }
    clampBlock(b);
    applyGeometry();
    applyInspector(false);
  }

  function onPointerUp() {
    if (!drag) return;
    drag = null;
    host.querySelector('#tpl-page')?.classList.remove('is-dragging');
    markDirty();
  }

  function onPageKey(e) {
    const b = blockOf(selected);
    if (!b) return;
    const step = e.shiftKey ? GRID : 1;
    const map = { ArrowLeft: ['x', -step], ArrowRight: ['x', step], ArrowUp: ['y', -step], ArrowDown: ['y', step] };
    const move = map[e.key];
    if (!move) return;
    e.preventDefault();
    b[move[0]] += move[1];
    clampBlock(b);
    markDirty();
    applyGeometry();
    applyInspector(false);
  }

  async function save() {
    const btn = host.querySelector('#tpl-save');
    if (btn) btn.disabled = true;
    try {
      const body = {
        companyName: draft.companyName.trim(),
        tagline: draft.tagline.trim(),
        taxId: draft.taxId.trim(),
        email: draft.email.trim(),
        phone: draft.phone.trim(),
        address: draft.address.trim(),
        quoteTitle: draft.quoteTitle.trim(),
        invoiceTitle: draft.invoiceTitle.trim(),
        quotePaymentTerms: draft.quotePaymentTerms.trim(),
        invoicePaymentTerms: draft.invoicePaymentTerms.trim(),
        termsText: draft.termsText.trim(),
        footerText: draft.footerText.trim(),
        accentColor: draft.accentColor,
        showDecor: draft.showDecor,
        layout: draft.layout.map(copyBlock),
      };
      const saved = await deps.api('/app/api/settings/document-template', { method: 'PUT', body: JSON.stringify(body) });
      deps.setTemplate(saved);
      draft = cloneDraft(saved);
      draft.hasLogo = !!saved.hasLogo;
      dirty = false;
      const flag = host.querySelector('[data-tpl-dirty]');
      if (flag) flag.hidden = true;
      applyContent();
      applyGeometry();
      deps.toast(STR().docTemplateSaved);
    } catch {
      deps.toast(STR().docTemplateSaveFailed);
    }
    if (btn) btn.disabled = false;
  }

  async function uploadLogo(e) {
    const file = e.target.files?.[0];
    if (!file) return;
    const fd = new FormData();
    fd.append('file', file);
    try {
      const res = await fetch('/app/api/settings/document-template/logo', {
        method: 'POST',
        headers: deps.getToken() ? { Authorization: `Bearer ${deps.getToken()}` } : {},
        body: fd,
      });
      if (res.status === 401) throw new Error('unauthorized');
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const saved = await res.json();
      deps.setTemplate(saved);
      draft.hasLogo = true;
      await loadLogo();
      applyInspector(true);
      applyContent();
      deps.toast(STR().docLogoUploaded);
    } catch {
      deps.toast(STR().docLogoUploadFailed);
    }
    e.target.value = '';
  }

  async function removeLogo() {
    try {
      const saved = await deps.api('/app/api/settings/document-template/logo', { method: 'DELETE' });
      deps.setTemplate(saved);
      draft.hasLogo = false;
      if (logoUrl) URL.revokeObjectURL(logoUrl);
      logoUrl = '';
      applyInspector(true);
      applyContent();
      deps.toast(STR().docLogoRemoved);
    } catch {
      deps.toast(STR().docLogoUploadFailed);
    }
  }

  async function loadLogo() {
    if (!draft.hasLogo) return;
    try {
      const res = await fetch('/app/api/settings/document-template/logo', {
        headers: deps.getToken() ? { Authorization: `Bearer ${deps.getToken()}` } : {},
      });
      if (!res.ok) return;
      const blob = await res.blob();
      if (logoUrl) URL.revokeObjectURL(logoUrl);
      logoUrl = URL.createObjectURL(blob);
      applyContent();
    } catch { /* keep placeholder */ }
  }

  function mount(el, next) {
    deps = next;
    host = el;
    if (!draft || !dirty) draft = cloneDraft(deps.getTemplate());
    host.className = 'panel tpl';
    host.innerHTML = studioHtml();
    inspectorFor = selected;
    applyTheme();
    bindChrome();
    bindLayers();
    bindPage();
    wireInspector();
    fit();
    loadLogo();
    if (fitObs) fitObs.disconnect();
    fitObs = new ResizeObserver(fit);
    const stage = host.querySelector('#tpl-stage');
    if (stage) fitObs.observe(stage);
  }

  window.addEventListener('pointermove', onPointerMove);
  window.addEventListener('pointerup', onPointerUp);
  window.addEventListener('pointercancel', onPointerUp);

  window.DocTemplate = { mount };
})();

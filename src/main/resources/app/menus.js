/* Persona → Menus (/app): fixed messages with buttons that answer Instagram before the AI, like ManyChat's
   keyword automations. A list of menus that open in place, each with its keywords, a map of where its
   buttons lead and its messages, beside the test chat app.js draws. app.js mounts it with init() and passes
   its helpers; the server checks every rule again when menus are saved or tried (`/app/api/persona/menus`).
   Unsaved edits live in state.personaDraft.menus, a copy of the saved menus taken on the first edit. */
(function () {
  let d = null;
  const M = I18N.section('app.personaStudio.menus');
  const PS = I18N.section('app.personaStudio');
  const tr = (key, params) => I18N.t(`app.personaStudio.menus.${key}`, params);
  const esc = s => d.escapeHTML(s == null ? '' : String(s));
  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
  const count = n => d.fmtCount(n);

  // Not part of the draft: which menus are open and which maps are folded (by menu id), whether a save was
  // refused (problems then show under their fields), the message being checked, a keyword being typed and a
  // note about the last keyword entry.
  const ui = { open: new Set(), maps: new Set(), checked: false, probe: '', pending: {}, note: null, seeded: null };

  const utf8Bytes = text => new TextEncoder().encode(text || '').length;
  /** A keyword as the server matches it: lowercase words without accents or punctuation. */
  const words = text => (text || '').toLowerCase().normalize('NFD').replace(/\p{M}+/gu, '').replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
  /** Whether [said] (already normalized) holds [phrase] as whole words, as the server's keyword match does. */
  const holds = (said, phrase) => { const w = words(phrase); return !!w && ` ${said} `.includes(` ${w} `); };
  const newId = prefix => `${prefix}-${Math.random().toString(36).slice(2, 8)}`;
  const firstLine = (text, max = 48) => {
    const line = (text || '').split('\n').map(t => t.trim()).find(Boolean) || '';
    return line.length > max ? `${line.slice(0, max - 1).trimEnd()}…` : line;
  };
  const painted = new WeakMap();
  /** Replaces [el]'s content only when it changed, so a button about to be clicked isn't swapped under the pointer. */
  const setHtml = (el, html) => {
    if (!el || painted.get(el) === html) return;
    el.innerHTML = html;
    painted.set(el, html);
  };

  const saved = () => d.state.personaFlows;
  const limitsOf = () => saved()?.limits || {};
  const isDirty = () => !!d?.state?.personaDraft?.menus;
  const canEdit = () => !!saved()?.canEdit && d.state.persona?.canEdit !== false;
  const menuName = (f, i) => (f?.name || '').trim() || tr('defaultName', { n: i + 1 });
  const titleOk = (title, limits) => { const t = (title || '').trim(); return !!t && t.length <= (limits.titleChars || 20); };

  async function load() {
    d.state.personaFlows = await d.api('/app/api/persona/menus');
  }

  /** The menus as they stand: the unsaved draft, else the saved ones. */
  function menus() {
    return d.state.personaDraft?.menus || { flows: saved()?.flows || [], teamOnlyChannels: saved()?.teamOnlyChannels || [] };
  }

  /** The draft to change, started as a copy of the saved menus. */
  function editable() {
    if (!d.state.personaDraft.menus) d.state.personaDraft.menus = JSON.parse(JSON.stringify(menus()));
    return d.state.personaDraft.menus;
  }

  /** Keywords as the server keeps them: trimmed, without blanks or repeats. */
  function cleanKeywords(list = []) {
    const seen = new Set();
    return list.map(k => (k || '').trim()).filter(k => {
      const w = words(k);
      if (!w || seen.has(w)) return false;
      seen.add(w);
      return true;
    });
  }

  function payload(m) {
    return {
      flows: (m.flows || []).map(f => ({
        id: f.id,
        name: (f.name || '').trim(),
        enabled: !!f.enabled,
        keywords: cleanKeywords(f.keywords),
        steps: (f.steps || []).map(s => ({ id: s.id, text: (s.text || '').trim(), buttons: (s.buttons || []).map(b => ({ title: (b.title || '').trim(), next: b.next || '' })) })),
      })),
      teamOnlyChannels: [...(m.teamOnlyChannels || [])].sort(),
    };
  }

  /** Drops the draft once it matches the saved menus again, so an edit undone by hand isn't left "unsaved". */
  function settle() {
    const draft = d.state.personaDraft.menus;
    const was = { flows: saved()?.flows || [], teamOnlyChannels: saved()?.teamOnlyChannels || [] };
    if (draft && JSON.stringify(payload(draft)) === JSON.stringify(payload(was))) delete d.state.personaDraft.menus;
  }

  // ── What the menus do: problems, keyword clashes, where buttons lead ───────────────────────────

  /** The first reason the menus can't be saved, in the server's order and with its codes. */
  function firstProblem(m, limits = limitsOf()) {
    const flows = m.flows || [];
    if (flows.length > limits.menus) return { problem: 'menus', limit: limits.menus };
    for (const [menu, f] of flows.entries()) {
      const name = (f.name || '').trim();
      if (!name || name.length > limits.nameChars) return { problem: 'name', menu, limit: limits.nameChars };
      const keywords = cleanKeywords(f.keywords);
      if (keywords.length > limits.keywords) return { problem: 'keywords_limit', menu, limit: limits.keywords };
      if (keywords.some(k => k.length > limits.keywordChars)) return { problem: 'keyword', menu, limit: limits.keywordChars };
      const steps = f.steps || [];
      if (steps.length > limits.steps) return { problem: 'steps_limit', menu, limit: limits.steps };
      if (!keywords.length) return { problem: 'keywords', menu };
      if (!steps.length) return { problem: 'steps', menu };
      const ids = steps.map(s => s.id);
      for (const [step, s] of steps.entries()) {
        const text = (s.text || '').trim();
        const buttons = s.buttons || [];
        if (!text || utf8Bytes(text) > limits.textBytes) return { problem: 'text', menu, step, limit: limits.textBytes };
        if (buttons.length > limits.buttons) return { problem: 'buttons', menu, step, limit: limits.buttons };
        if (buttons.some(b => !titleOk(b.title, limits))) return { problem: 'title', menu, step, limit: limits.titleChars };
        if (buttons.some(b => !ids.includes(b.next))) return { problem: 'next', menu, step };
      }
    }
    return null;
  }

  /** Everything that stops menu [f] from being saved, by field: the server's codes, without its order. */
  function errorsOf(f, limits) {
    const out = [];
    const name = (f.name || '').trim();
    if (!name || name.length > limits.nameChars) out.push({ code: 'name', limit: limits.nameChars });
    const keywords = cleanKeywords(f.keywords);
    if (!keywords.length) out.push({ code: 'keywords' });
    if (keywords.length > limits.keywords) out.push({ code: 'keywords_limit', limit: limits.keywords });
    if (keywords.some(k => k.length > limits.keywordChars)) out.push({ code: 'keyword', limit: limits.keywordChars });
    const steps = f.steps || [];
    if (!steps.length) out.push({ code: 'steps' });
    if (steps.length > limits.steps) out.push({ code: 'steps_limit', limit: limits.steps });
    const ids = steps.map(s => s.id);
    steps.forEach((s, step) => {
      const text = (s.text || '').trim();
      if (!text || utf8Bytes(text) > limits.textBytes) out.push({ code: 'text', step, limit: limits.textBytes });
      const buttons = s.buttons || [];
      if (buttons.length > limits.buttons) out.push({ code: 'buttons', step, limit: limits.buttons });
      buttons.forEach((b, button) => {
        if (!titleOk(b.title, limits)) out.push({ code: 'title', step, button, limit: limits.titleChars });
        if (!ids.includes(b.next)) out.push({ code: 'next', step, button });
      });
    });
    return out;
  }

  /**
   * Per menu, its keywords that open an earlier menu instead. Only menus that are on answer, and the first
   * with a keyword the message holds wins, so a keyword can never reach a menu below one that catches it.
   */
  function clashesOf(m) {
    const out = (m.flows || []).map(() => []);
    const live = (m.flows || []).map((f, i) => ({ f, i, keywords: cleanKeywords(f.keywords) })).filter(x => x.f.enabled);
    live.forEach(({ i, keywords }, pos) => {
      keywords.forEach(keyword => {
        const said = words(keyword);
        for (const earlier of live.slice(0, pos)) {
          const other = earlier.keywords.find(k => holds(said, k));
          if (other) { out[i].push({ keyword, menu: earlier.i, other }); break; }
        }
      });
    });
    return out;
  }

  /** The messages a customer can reach from the first one by tapping buttons, as positions. */
  function reachOf(f) {
    const steps = f.steps || [];
    const at = new Map(steps.map((s, j) => [s.id, j]));
    const seen = new Set();
    const walk = j => {
      if (seen.has(j)) return;
      seen.add(j);
      (steps[j].buttons || []).forEach(b => { if (at.has(b.next)) walk(at.get(b.next)); });
    };
    if (steps.length) walk(0);
    return seen;
  }

  /** The buttons that lead to message [j], as positions and titles. */
  function linksTo(f, j) {
    const target = f.steps[j]?.id;
    return (f.steps || []).flatMap((s, k) => (s.buttons || []).filter(b => b.next === target).map(b => ({ from: k, title: (b.title || '').trim() })));
  }

  function analyze(m, limits = limitsOf()) {
    const clashes = clashesOf(m);
    return (m.flows || []).map((f, i) => {
      const reach = reachOf(f);
      const unreached = (f.steps || []).map((s, j) => j).filter(j => j > 0 && !reach.has(j));
      return { errors: errorsOf(f, limits), clashes: clashes[i], reach, unreached, warnings: clashes[i].length + unreached.length };
    });
  }

  /** Which menu answers [text], picked as the server does; `off` when only a menu that is off would. */
  function answerFor(m, text) {
    const said = words(text);
    if (!said) return null;
    const flows = m.flows || [];
    const find = live => {
      for (const [i, f] of flows.entries()) {
        if (live && !f.enabled) continue;
        const keyword = cleanKeywords(f.keywords).find(k => holds(said, k));
        if (keyword) return { menu: i, keyword, off: !live };
      }
      return null;
    };
    return find(true) || find(false);
  }

  /** The keyword "Try it" sends: the first that opens this menu, else its first. */
  function tryKeyword(m, i) {
    const keywords = cleanKeywords(m.flows[i]?.keywords);
    return keywords.find(k => answerFor(m, k)?.menu === i) || keywords[0] || '';
  }

  const usageOf = id => (saved()?.usage?.menus || []).find(u => u.id === id) || null;
  const isSaved = id => (saved()?.flows || []).some(f => f.id === id);

  // ── Drawing ─────────────────────────────────────────────────────────────────────────────────────

  function render(p) {
    const s = saved();
    if (!s) return `<section class="panel"><div class="panel__body"><p class="hint">${esc(M.loading)}</p></div></section>`;
    if (s.failed) {
      return `<section class="panel"><div class="panel__body"><div class="empty"><p class="empty__title">${esc(M.loadFailed)}</p>
        <div class="empty__actions"><button type="button" class="btn btn--sm" data-menus-retry>${esc(PS.retry)}</button></div></div></div></section>`;
    }
    seedOpen();
    const m = menus();
    const limits = limitsOf();
    const edit = canEdit();
    const ctx = analyze(m, limits);
    const notice = s.instagramConnected ? '' : d.personaNotice('info', M.igMissingTitle, M.igMissingDesc,
      `<div class="notice__actions"><button type="button" class="btn btn--sm" data-menus-settings>${esc(M.openSettings)}</button></div>`);
    const flows = m.flows || [];
    const list = flows.length ? flows.map((f, i) => menuPanel(m, f, i, limits, edit, ctx)).join('') : emptyState(edit);
    const room = flows.length < (limits.menus || 10);
    const add = !edit || !flows.length ? ''
      : room ? `<div><button type="button" class="btn btn--ghost" data-menu-add><span class="btn__plus">+</span> ${esc(M.add)}</button></div>`
        : `<p class="hint">${esc(tr('menusFull', { max: count(limits.menus || 10) }))}</p>`;
    return `${notice}<form class="form flow-menus" id="persona-menus-form" novalidate>${overview(m, edit)}${list}${add}${edit ? saveBar(ctx) : ''}</form>`;
  }

  /** On a fresh load a single menu starts open; with several, the list shows them folded. */
  function seedOpen() {
    if (ui.seeded === saved()) return;
    ui.seeded = saved();
    const flows = menus().flows || [];
    if (flows.length === 1) ui.open.add(flows[0].id);
  }

  function overview(m, edit) {
    const s = saved();
    const teamOnly = (m.teamOnlyChannels || []).includes('INSTAGRAM');
    // Menus written outside the dashboard have no author: the support team wrote them.
    const meta = s.updatedAt ? `<span class="panel__meta">${esc(tr('savedBy', { when: d.relTime(s.updatedAt), who: d.personaAuthor(s.updatedBy || 'operator') }))}</span>` : '';
    const any = (m.flows || []).length > 0;
    const probe = any ? `<div class="form__row"><label class="lbl" for="pm-probe">${esc(M.probeLabel)}</label>
            <input class="inp" id="pm-probe" autocomplete="off" enterkeyhint="search" placeholder="${esc(M.probePh)}" value="${esc(ui.probe)}" />
            <p class="hint" data-pm-probe aria-live="polite">${probeHtml(m)}</p></div>` : '';
    return `<section class="panel"><header class="panel__head"><h2 class="panel__title">${esc(M.title)} <span class="tag" data-pm-count>${count((m.flows || []).length)}</span></h2>${meta}</header>
      <div class="panel__body form">
        <p class="hint">${esc(M.intro)} ${esc(M.orderHint)} ${esc(M.desktopHint)}</p>
        <div data-pm-kpis>${kpis(m)}</div>
        <div class="form__grid">
          <div class="form__row${any ? '' : ' form__row--full'}"><label class="lbl" for="pm-fallback">${esc(M.fallbackLabel)}</label>
            <select class="sel" id="pm-fallback"${edit ? '' : ' disabled'}><option value="ai"${teamOnly ? '' : ' selected'}>${esc(M.fallbackAi)}</option><option value="team"${teamOnly ? ' selected' : ''}>${esc(M.fallbackTeam)}</option></select>
            <p class="hint">${esc(M.fallbackHint)}</p></div>
          ${probe}
        </div>
      </div>
    </section>`;
  }

  function kpis(m) {
    const flows = m.flows || [];
    if (!flows.length) return '';
    const used = (saved()?.usage?.menus || []).filter(u => flows.some(f => f.id === u.id));
    const sent = used.reduce((n, u) => n + (u.sent || 0), 0);
    const refused = used.reduce((n, u) => n + (u.failed || 0), 0);
    const last = used.map(u => u.lastSentAt).filter(Boolean).sort().pop();
    const days = saved()?.usage?.days || 30;
    return `<div class="record-kpis" data-count="${refused ? 4 : 3}">
      <div class="record-kpi"><span class="record-kpi__label">${esc(M.kpiOn)}</span><span class="record-kpi__value">${esc(tr('kpiOnValue', { n: count(flows.filter(f => f.enabled).length), total: count(flows.length) }))}</span></div>
      <div class="record-kpi"><span class="record-kpi__label">${esc(tr('kpiSent', { days: count(days) }))}</span><span class="record-kpi__value">${esc(count(sent))}</span></div>
      <div class="record-kpi"><span class="record-kpi__label">${esc(M.kpiLast)}</span><span class="record-kpi__value">${esc(last ? d.relTime(last) : '—')}</span></div>
      ${refused ? `<div class="record-kpi" data-tone="bad"><span class="record-kpi__label">${esc(M.kpiRefused)}</span><span class="record-kpi__value">${esc(count(refused))}</span></div>` : ''}
    </div>`;
  }

  function probeHtml(m) {
    const text = ui.probe.trim();
    if (!text) return esc(M.probeIdle);
    const hit = answerFor(m, text);
    const fallback = (m.teamOnlyChannels || []).includes('INSTAGRAM') ? M.probeTeam : M.probeAi;
    if (!hit) return esc(fallback);
    const name = menuName(m.flows[hit.menu], hit.menu);
    if (hit.off) return esc(`${tr('probeOff', { menu: name })} ${fallback}`);
    return `${esc(tr('probeMenu', { menu: name, keyword: hit.keyword }))} <button type="button" class="linkbtn" data-goto="${hit.menu}:0">${esc(M.probeShow)}</button>`;
  }

  // A speech bubble and three quick replies, one picked.
  const EMPTY_ART = `<svg class="empty__art" viewBox="0 0 128 84" aria-hidden="true" focusable="false">
    <g class="art-line"><path d="M34 8h52a9 9 0 0 1 9 9v17a9 9 0 0 1-9 9H52l-11 8v-8h-7a9 9 0 0 1-9-9V17a9 9 0 0 1 9-9z"/><path d="M37 21h37M37 30h24"/>
    <rect x="13" y="61" width="31" height="14" rx="7"/><rect x="49" y="61" width="31" height="14" rx="7"/></g>
    <rect class="art-accent" x="85" y="61" width="31" height="14" rx="7"/>
    <path class="art-accent" d="M108 11l1.7 3.8 3.8 1.7-3.8 1.7-1.7 3.8-1.7-3.8-3.8-1.7 3.8-1.7z"/>
  </svg>`;

  function emptyState(edit) {
    return `<section class="panel"><div class="panel__body"><div class="empty empty--art">${EMPTY_ART}<p class="empty__title">${esc(M.emptyTitle)}</p><p class="empty__desc">${esc(M.emptyDesc)}</p>
      ${edit ? `<div class="empty__actions"><button type="button" class="btn btn--primary" data-menu-add><span class="btn__plus">+</span> ${esc(M.add)}</button></div>` : ''}</div></div></section>`;
  }

  function menuPills(f, a) {
    const state = `<span class="pill${f.enabled ? ' pill--ok' : ''}">${esc(f.enabled ? M.on : M.off)}</span>`;
    if (a.errors.length) return `${state}<span class="pill pill--warn">${esc(tr('toFix', { n: count(a.errors.length) }))}</span>`;
    if (a.warnings) return `${state}<span class="pill pill--warn">${esc(tr('toCheck', { n: count(a.warnings) }))}</span>`;
    return state;
  }

  function menuSummary(f) {
    const keywords = cleanKeywords(f.keywords);
    const shown = keywords.slice(0, 3).join(' · ');
    const parts = [
      keywords.length ? (keywords.length > 3 ? tr('keywordsMore', { list: shown, n: count(keywords.length - 3) }) : shown) : M.noKeywords,
      tr('messagesCount', { n: (f.steps || []).length }),
    ];
    const used = usageOf(f.id);
    const days = saved()?.usage?.days || 30;
    if (used?.sent) parts.push(tr('sentSummary', { n: used.sent, customers: used.customers, days }));
    else if (isSaved(f.id)) parts.push(tr('sentNone', { days }));
    const refused = used?.failed ? ` · <span class="hint--warn">${esc(tr('refused', { n: used.failed }))}</span>` : '';
    return esc(parts.join(' · ')) + refused;
  }

  function tryState(m, i) {
    const f = m.flows[i];
    if (!f.enabled) return { disabled: true, title: M.tryOff };
    if (!cleanKeywords(f.keywords).length) return { disabled: true, title: M.tryNoKeyword };
    return { disabled: false, title: tr('tryTitle', { keyword: tryKeyword(m, i) }) };
  }

  function menuPanel(m, f, i, limits, edit, ctx) {
    const open = ui.open.has(f.id);
    const id = `pm-${i}`;
    const last = (m.flows || []).length - 1;
    const tried = tryState(m, i);
    const tools = `<div class="panel__tools">
        <button type="button" class="btn btn--sm btn--ghost" data-menu-try="${i}" title="${esc(tried.title)}"${tried.disabled ? ' disabled' : ''}>${esc(M.tryIt)}</button>
        ${edit ? `<button class="iconbtn" type="button" id="${id}-up" data-menu-up="${i}"${i ? '' : ' disabled'} aria-label="${esc(M.moveMenuUp)}" title="${esc(M.moveMenuUp)}">↑</button>
        <button class="iconbtn" type="button" id="${id}-down" data-menu-down="${i}"${i < last ? '' : ' disabled'} aria-label="${esc(M.moveMenuDown)}" title="${esc(M.moveMenuDown)}">↓</button>` : ''}
      </div>`;
    return `<section class="panel flow-menu${open ? ' is-open' : ''}" data-menu="${i}" id="${id}">
      <header class="panel__head flow-menu__head" data-menu-head="${i}">
        <h2 class="flow-menu__title"><button type="button" class="flow-menu__toggle" id="${id}-toggle" data-menu-toggle="${i}" aria-expanded="${open}" aria-controls="${id}-body">
          <span class="flow-menu__chevron" aria-hidden="true"></span><span class="flow__index" title="${esc(M.positionTitle)}">${i + 1}</span><span class="flow-menu__name" data-menu-title="${i}">${esc(menuName(f, i))}</span></button></h2>
        <span class="flow-menu__pills" data-menu-pills="${i}">${menuPills(f, ctx[i])}</span>
        ${tools}
        <p class="flow-menu__summary" data-menu-summary="${i}">${menuSummary(f)}</p>
      </header>
      <div class="panel__body form flow-menu__body" id="${id}-body"${open ? '' : ' hidden'}>${open ? menuBody(m, f, i, limits, edit, ctx) : ''}</div>
    </section>`;
  }

  function menuBody(m, f, i, limits, edit, ctx) {
    const id = `pm-${i}`;
    const off = edit ? '' : ' disabled';
    const steps = f.steps || [];
    const room = (m.flows || []).length < (limits.menus || 10);
    const foot = edit ? `<div class="flow-menu__foot">
        ${steps.length < (limits.steps || 30) ? `<button type="button" class="btn btn--sm btn--ghost" data-step-add="${i}"><span class="btn__plus">+</span> ${esc(M.addMessage)}</button>` : `<span class="hint">${esc(tr('stepsFull', { max: count(limits.steps || 30) }))}</span>`}
        <span class="actions">${room ? `<button type="button" class="btn btn--sm btn--ghost" data-menu-duplicate="${i}">${esc(M.duplicate)}</button>` : ''}<button type="button" class="btn btn--sm btn--ghost" data-menu-remove="${i}">${esc(M.remove)}</button></span>
      </div>` : '';
    const keywordsLabel = edit ? `<label class="lbl" for="${id}-keywords">${esc(M.keywords)}</label>` : `<span class="lbl">${esc(M.keywords)}</span>`;
    return `<div class="form__grid">
        <div class="form__row"><label class="lbl" for="${id}-name">${esc(M.name)}</label>
          <input class="inp" id="${id}-name" data-menu-field="name" maxlength="${limits.nameChars || 60}" autocomplete="off" value="${esc(f.name || '')}"${off} />
          <p class="hint">${esc(M.nameHint)}</p>
          <p class="hint hint--bad" data-issue="${i}:name" hidden></p></div>
        <div class="form__row"><span class="lbl">${esc(M.state)}</span>
          <label class="form__check"><input type="checkbox" id="${id}-enabled" data-menu-field="enabled"${f.enabled ? ' checked' : ''}${off} /> ${esc(M.enabled)}</label></div>
        <div class="form__row form__row--full">
          <div class="form__row-head">${keywordsLabel}<span class="hint" data-kw-count="${i}"></span></div>
          <div class="tokens${edit ? '' : ' is-readonly'}" data-tokens="${i}">${edit ? `<input class="tokens__input" id="${id}-keywords" data-kw-input="${i}" maxlength="${limits.keywordChars || 60}" autocomplete="off" enterkeyhint="enter" aria-describedby="${id}-keywords-hint" value="${esc(ui.pending[f.id] || '')}" />` : ''}</div>
          <p class="hint" id="${id}-keywords-hint">${esc(edit ? M.keywordsHelp : M.keywordsHelpRead)}</p>
          <p class="hint hint--bad" data-issue="${i}:keywords" hidden></p>
          <div class="flow-menu__notes" data-kw-notes="${i}" aria-live="polite"></div></div>
      </div>
      <div class="flow-menu__map" data-map="${i}"></div>
      <div class="form__row form__row--full"><span class="lbl">${esc(M.messages)}</span><p class="hint">${esc(M.messagesHint)}</p></div>
      <div class="flow flow--edit">${steps.map((s, j) => stepCard(f, i, s, j, limits, edit)).join('')}</div>
      <p class="hint hint--bad" data-issue="${i}:steps" hidden></p>
      ${foot}`;
  }

  /** "2 · We do check-ups…" for a message in a list of where buttons lead. */
  const targetLabel = (step, n) => { const line = firstLine(step.text, 40); return line ? `${n} · ${line}` : tr('message', { n }); };

  function targetOptions(steps, current, edit) {
    const known = steps.some(s => s.id === current);
    return `${known ? '' : `<option value="" selected>${esc(M.pickMessage)}</option>`}${steps.map((s, n) => `<option value="${esc(s.id)}"${s.id === current ? ' selected' : ''}>${esc(targetLabel(s, n + 1))}</option>`).join('')}${edit ? `<option value="__new">${esc(M.newMessage)}</option>` : ''}`;
  }

  /** Instagram's limit is in bytes, so the counter only shows once a message gets close to it. */
  function sizeHint(text, limits) {
    const max = limits.textBytes || 1000;
    const n = utf8Bytes((text || '').trim());
    const tone = n > max ? ' hint--bad' : n > max * 0.8 ? ' hint--warn' : '';
    return { cls: `hint${tone}`, show: !!tone, text: tr('textSize', { n: count(n), max: count(max) }) };
  }

  function goLabel(t) { return t < 0 ? M.goNowhere : tr('goTo', { n: t + 1 }); }

  function buttonRow(f, i, j, b, k, limits, edit) {
    const id = `pm-${i}-${j}`;
    const off = edit ? '' : ' disabled';
    const t = (f.steps || []).findIndex(s => s.id === b.next);
    return `<div class="line">
      <input id="${id}-b${k}" data-button-field="title" data-at="${i}:${j}:${k}" maxlength="${limits.titleChars || 20}" value="${esc(b.title || '')}" aria-label="${esc(M.buttonAria)}"${off} />
      <select class="sel" id="${id}-n${k}" data-button-field="next" data-at="${i}:${j}:${k}" aria-label="${esc(M.leadsToAria)}"${off}>${targetOptions(f.steps, b.next, edit)}</select>
      <button class="l-go" type="button" id="${id}-g${k}" data-goto="${i}:${t}"${t < 0 ? ' disabled' : ''} aria-label="${esc(goLabel(t))}" title="${esc(goLabel(t))}">→</button>
      ${edit ? `<button class="l-rm" type="button" data-button-remove="${i}:${j}:${k}" aria-label="${esc(M.removeButton)}" title="${esc(M.removeButton)}">×</button>` : '<span></span>'}
    </div>`;
  }

  function stepCard(f, i, step, j, limits, edit) {
    const off = edit ? '' : ' disabled';
    const id = `pm-${i}-${j}`;
    const steps = f.steps;
    const tools = edit ? `<span class="flow__card-tools">
        <button class="iconbtn" type="button" data-step-up="${i}:${j}"${j ? '' : ' disabled'} aria-label="${esc(M.moveUp)}" title="${esc(M.moveUp)}">↑</button>
        <button class="iconbtn" type="button" data-step-down="${i}:${j}"${j < steps.length - 1 ? '' : ' disabled'} aria-label="${esc(M.moveDown)}" title="${esc(M.moveDown)}">↓</button>
        <button class="iconbtn" type="button" data-step-remove="${i}:${j}"${steps.length > 1 ? '' : ' disabled'} aria-label="${esc(M.removeMessage)}" title="${esc(M.removeMessage)}">×</button>
      </span>` : '';
    const buttons = step.buttons || [];
    const addButton = edit && buttons.length < (limits.buttons || 13)
      ? `<button type="button" class="btn btn--sm btn--ghost" data-button-add="${i}:${j}"><span class="btn__plus">+</span> ${esc(M.addButton)}</button>` : '<span></span>';
    const size = sizeHint(step.text, limits);
    const sent = usageOf(f.id)?.steps?.[step.id];
    return `${j ? '<div class="flow__connector" aria-hidden="true"></div>' : ''}<div class="flow__card" id="${id}">
      <div class="flow__card-head"><span class="flow__index">${j + 1}</span><span class="flow__title">${esc(tr('message', { n: j + 1 }))}</span>
        <span class="flow__card-summary" data-step-summary="${i}:${j}"></span>
        ${sent ? `<span class="flow-menu__stat">${esc(tr('stepSent', { n: sent }))}</span>` : ''}${tools}</div>
      <div class="form__row form__row--full"><label class="visually-hidden" for="${id}-text">${esc(M.textLabel)}</label>
        <textarea class="txt" id="${id}-text" data-step-field="text" data-at="${i}:${j}" rows="3" placeholder="${esc(M.textPh)}"${off}>${esc(step.text || '')}</textarea>
        <p class="${size.cls}" data-step-size${size.show ? '' : ' hidden'}>${esc(size.text)}</p>
        <p class="hint hint--bad" data-issue="${i}:${j}:text" hidden></p></div>
      <div class="lines lines--replies">
        <div class="lines__head"><span>${esc(M.thButton)}</span><span>${esc(M.thLeadsTo)}</span><span></span><span></span></div>
        ${buttons.map((b, k) => buttonRow(f, i, j, b, k, limits, edit)).join('')}
        <p class="lines__empty"${buttons.length ? ' hidden' : ''}>${esc(M.noButtons)}</p>
        <div class="lines__foot">${addButton}<span class="hint">${esc(tr('buttonsCount', { n: count(buttons.length), max: count(limits.buttons || 13), chars: count(limits.titleChars || 20) }))}</span></div>
      </div>
      <p class="hint hint--bad" data-issue="${i}:${j}:buttons" hidden></p>
    </div>`;
  }

  function stepSummary(f, i, j, a) {
    if (j === 0) return `<span class="muted">${esc(M.first)}</span>`;
    const from = linksTo(f, j);
    if (!from.length) return `<span class="pill pill--warn">${esc(M.unlinked)}</span>`;
    if (!a.reach.has(j)) return `<span class="pill pill--warn">${esc(M.unreachable)}</span>`;
    const refs = from.slice(0, 3).map(r => `<button type="button" class="linkbtn" data-goto="${i}:${r.from}">${esc(tr('fromRef', { n: r.from + 1, title: r.title || M.untitledButton }))}</button>`).join(', ');
    return `<span class="muted">${esc(M.from)}</span> ${refs}${from.length > 3 ? ` <span class="muted">${esc(tr('moreRefs', { n: count(from.length - 3) }))}</span>` : ''}`;
  }

  function tokenItems(f, i, edit, a, limits) {
    const clashes = new Map(a.clashes.map(c => [words(c.keyword), c]));
    return (f.keywords || []).map((k, n) => {
      const clash = clashes.get(words(k));
      const long = k.length > (limits.keywordChars || 60);
      const warn = clash || long;
      const title = clash ? tr('clash', { keyword: k, menu: menuName(menus().flows[clash.menu], clash.menu), other: clash.other }) : long ? tr('issues.keyword', { limit: count(limits.keywordChars || 60) }) : '';
      return `<span class="token${warn ? ' token--warn' : ''}"${title ? ` title="${esc(title)}"` : ''}>${edit
        ? `<button type="button" class="token__text" tabindex="-1" data-kw-edit="${i}:${n}" aria-label="${esc(tr('keywordEdit', { keyword: k }))}">${esc(k)}</button><button type="button" class="token__rm" data-kw-remove="${i}:${n}" aria-label="${esc(tr('keywordRemove', { keyword: k }))}">×</button>`
        : `<span class="token__text">${esc(k)}</span>`}</span>`;
    }).join('');
  }

  /** Where each button leads, as a tree from the first message; a message reached again is a link back. */
  function mapHtml(f, i, a) {
    const steps = f.steps || [];
    const at = new Map(steps.map((s, j) => [s.id, j]));
    const seen = new Set();
    const msg = (j, ref) => `<button type="button" class="flow-map__msg${ref ? ' flow-map__msg--ref' : ''}" data-goto="${i}:${j}" title="${esc(tr('goTo', { n: j + 1 }))}"><span class="flow__index">${j + 1}</span><span class="flow-map__text">${esc(firstLine(steps[j].text) || M.emptyMessage)}</span></button>`;
    const chip = b => `<span class="flow-map__btn">${esc((b.title || '').trim() || M.untitledButton)}</span>`;
    const row = html => `<span class="flow-map__row">${html}</span>`;
    // A message's line (head) and the branches under it (list); marked seen first, so a loop back ends there.
    const node = j => {
      seen.add(j);
      const branches = (steps[j].buttons || []).map(b => {
        const t = at.get(b.next);
        if (t == null) return `<li class="flow-map__branch">${row(`${chip(b)}<span class="flow-map__note">${esc(M.mapNowhere)}</span>`)}</li>`;
        if (seen.has(t)) return `<li class="flow-map__branch">${row(`${chip(b)}<span class="flow-map__back">${esc(M.mapBack)}</span>${msg(t, true)}`)}</li>`;
        const child = node(t);
        return `<li class="flow-map__branch">${row(chip(b) + child.head)}${child.list}</li>`;
      }).join('');
      return {
        head: `${msg(j)}${branches ? '' : `<span class="flow-map__end">${esc(M.mapEnd)}</span>`}`,
        list: branches ? `<ul class="flow-map__list">${branches}</ul>` : '',
      };
    };
    const root = steps.length ? node(0) : null;
    const tree = root ? row(root.head) + root.list : '';
    const orphans = a.unreached.length
      ? `<p class="flow-map__orphans"><span class="hint hint--warn">${esc(M.mapUnreached)}</span> ${a.unreached.map(j => msg(j, true)).join(' ')}</p>` : '';
    return `<div class="flow-map__tree">${tree}</div>${orphans}`;
  }

  function mapSection(f, i, a) {
    if ((f.steps || []).length < 2) return '';
    const hidden = ui.maps.has(f.id);
    return `<div class="form__row-head"><span class="lbl">${esc(M.map)}</span><button type="button" class="linkbtn" data-map-toggle="${i}" aria-expanded="${!hidden}">${esc(hidden ? M.mapShow : M.mapHide)}</button></div>
      ${hidden ? '' : `<div class="flow-map">${mapHtml(f, i, a)}</div>`}`;
  }

  function saveBar(ctx) {
    return `<div class="savebar${isDirty() ? ' is-dirty' : ''}" data-pm-savebar>
      <p class="savebar__status" data-pm-status aria-live="polite">${saveStatus(ctx)}</p>
      <div class="actions"><button class="btn btn--ghost" type="button" data-pm-discard${isDirty() ? '' : ' disabled'}>${esc(PS.discard)}</button><button class="btn btn--primary" type="submit" data-pm-save${isDirty() ? '' : ' disabled'}>${esc(M.save)}</button></div>
    </div>`;
  }

  function saveStatus(ctx) {
    if (!isDirty()) return esc(M.allSaved);
    const errors = ctx.reduce((n, a) => n + a.errors.length, 0);
    return `<span class="savebar__dot" aria-hidden="true"></span>${esc(errors ? tr('unsavedFix', { n: count(errors) }) : M.unsaved)}`;
  }

  /** Brings what the page shows in step with the draft without touching the fields someone may be typing in. */
  function paint() {
    const form = $('#persona-menus-form');
    if (!form) return;
    const m = menus();
    const limits = limitsOf();
    const ctx = analyze(m, limits);
    const edit = canEdit();
    setHtml($('[data-pm-count]', form), count((m.flows || []).length));
    setHtml($('[data-pm-kpis]', form), kpis(m));
    setHtml($('[data-pm-probe]', form), probeHtml(m));
    (m.flows || []).forEach((f, i) => {
      const a = ctx[i];
      setHtml($(`[data-menu-title="${i}"]`, form), esc(menuName(f, i)));
      setHtml($(`[data-menu-pills="${i}"]`, form), menuPills(f, a));
      setHtml($(`[data-menu-summary="${i}"]`, form), menuSummary(f));
      const tryButton = $(`[data-menu-try="${i}"]`, form);
      if (tryButton) { const s = tryState(m, i); tryButton.disabled = s.disabled; tryButton.title = s.title; }
      if (!ui.open.has(f.id) || !$(`#pm-${i}-body`, form)?.childElementCount) return;
      paintKeywords(form, f, i, a, limits, edit);
      setHtml($(`[data-map="${i}"]`, form), mapSection(f, i, a));
      (f.steps || []).forEach((s, j) => setHtml($(`[data-step-summary="${i}:${j}"]`, form), stepSummary(f, i, j, a)));
      paintIssues(form, f, i, a);
    });
    setHtml($('[data-pm-status]', form), saveStatus(ctx));
    $('[data-pm-savebar]', form)?.classList.toggle('is-dirty', isDirty());
    const save = $('[data-pm-save]', form);
    if (save) save.disabled = !isDirty();
    const discard = $('[data-pm-discard]', form);
    if (discard) discard.disabled = !isDirty();
    d.onDraft();
  }

  function paintKeywords(form, f, i, a, limits, edit) {
    const box = $(`[data-tokens="${i}"]`, form);
    if (!box) return;
    const chips = tokenItems(f, i, edit, a, limits);
    if (painted.get(box) !== chips) {
      $$('.token', box).forEach(t => t.remove());
      box.insertAdjacentHTML('afterbegin', chips);
      painted.set(box, chips);
    }
    const max = limits.keywords || 50;
    const n = (f.keywords || []).length;
    const input = $('[data-kw-input]', box);
    if (input) {
      input.disabled = n >= max;
      input.placeholder = n >= max ? tr('keywordsFull', { max: count(max) }) : n ? M.keywordsPhMore : M.keywordsPh;
    }
    const counter = $(`[data-kw-count="${i}"]`, form);
    if (counter) counter.textContent = tr('keywordsCount', { n: count(n), max: count(max) });
    const notes = $(`[data-kw-notes="${i}"]`, form);
    if (!notes) return;
    const lines = a.clashes.map(c => `<p class="hint hint--warn">${esc(tr('clash', { keyword: c.keyword, menu: menuName(menus().flows[c.menu], c.menu), other: c.other }))}</p>`);
    if (ui.note?.menu === f.id) lines.unshift(`<p class="hint">${esc(ui.note.text)}</p>`);
    setHtml(notes, lines.join(''));
  }

  function issueText(issue) {
    return tr(`issues.${issue.code}`, { limit: issue.limit != null ? count(issue.limit) : '' });
  }

  /** After a refused save, each field says what is wrong with it until it's right. */
  function paintIssues(form, f, i, a) {
    const show = ui.checked;
    const say = (key, list) => {
      const el = $(`[data-issue="${key}"]`, form);
      if (!el) return;
      const text = show && list.length ? issueText(list[0]) : '';
      el.textContent = text;
      el.hidden = !text;
    };
    const mark = (selector, bad) => { const el = $(selector, form); if (el) el.toggleAttribute('aria-invalid', show && bad); };
    const of = (codes, step) => a.errors.filter(e => codes.includes(e.code) && (step == null || e.step === step));
    say(`${i}:name`, of(['name']));
    mark(`#pm-${i}-name`, of(['name']).length > 0);
    say(`${i}:keywords`, of(['keywords', 'keywords_limit', 'keyword']));
    mark(`#pm-${i}-keywords`, of(['keywords', 'keywords_limit', 'keyword']).length > 0);
    say(`${i}:steps`, of(['steps', 'steps_limit']));
    (f.steps || []).forEach((s, j) => {
      say(`${i}:${j}:text`, of(['text'], j));
      mark(`#pm-${i}-${j}-text`, of(['text'], j).length > 0);
      say(`${i}:${j}:buttons`, of(['buttons', 'title', 'next'], j));
      (s.buttons || []).forEach((b, k) => {
        mark(`#pm-${i}-${j}-b${k}`, a.errors.some(e => e.step === j && e.button === k && e.code === 'title'));
        mark(`#pm-${i}-${j}-n${k}`, a.errors.some(e => e.step === j && e.button === k && e.code === 'next'));
      });
    });
  }

  /** "Menu name, message 2: …" for a problem found here or by the server (`menu_problem`). */
  function errorText(problem = {}, m = menus()) {
    const flow = (m.flows || [])[problem.menu];
    const menu = flow ? menuName(flow, problem.menu) : '';
    const key = `app.personaStudio.menus.errors.${problem.problem}`;
    const text = I18N.t(key, { menu, step: problem.step != null ? problem.step + 1 : '', limit: problem.limit != null ? count(problem.limit) : '' });
    return text === key ? PS.errorGeneric : text;
  }

  // ── Doing ───────────────────────────────────────────────────────────────────────────────────────

  /** Redraws the page and puts the cursor in [focusId] (or the first of several ids that exists and is enabled). */
  function redraw(...focusIds) {
    d.render();
    for (const id of focusIds) {
      const el = id && document.getElementById(id);
      if (el && !el.disabled) { el.focus(); break; }
    }
  }

  function toggle(i, open) {
    const f = menus().flows[i];
    if (!f) return;
    const want = open ?? !ui.open.has(f.id);
    if (want === ui.open.has(f.id) && open != null) return;
    if (want) ui.open.add(f.id); else ui.open.delete(f.id);
    const panel = $(`#pm-${i}`);
    if (!panel) return;
    panel.classList.toggle('is-open', want);
    $(`[data-menu-toggle="${i}"]`, panel)?.setAttribute('aria-expanded', String(want));
    const body = $(`#pm-${i}-body`, panel);
    const m = menus();
    body.innerHTML = want ? menuBody(m, f, i, limitsOf(), canEdit(), analyze(m)) : '';
    body.hidden = !want;
    if (want) paint();
  }

  /** Opens menu [i] if needed, scrolls to message [j] and puts the cursor in its text. */
  function goTo(i, j) {
    if (!(j >= 0)) return;
    toggle(i, true);
    const card = $(`#pm-${i}-${j}`);
    if (!card) return;
    card.scrollIntoView({ block: 'center', behavior: 'smooth' });
    $(`#pm-${i}-${j}-text`)?.focus({ preventScroll: true });
    card.classList.remove('is-flash');
    void card.offsetWidth;
    card.classList.add('is-flash');
    setTimeout(() => card.classList.remove('is-flash'), 1600);
  }

  function addKeywords(i, texts) {
    const f = editable().flows[i];
    const limits = limitsOf();
    const max = limits.keywords || 50;
    f.keywords = f.keywords || [];
    const have = new Map(f.keywords.map(k => [words(k), k]));
    ui.note = null;
    for (const raw of texts) {
      const keyword = raw.trim().slice(0, limits.keywordChars || 60);
      if (!keyword) continue;
      const w = words(keyword);
      if (!w) { ui.note = { menu: f.id, text: tr('keywordNoWords', { keyword }) }; continue; }
      if (have.has(w)) { ui.note = { menu: f.id, text: tr('keywordRepeat', { keyword: have.get(w) }) }; continue; }
      if (f.keywords.length >= max) { ui.note = { menu: f.id, text: tr('keywordsFull', { max: count(max) }) }; break; }
      f.keywords.push(keyword);
      have.set(w, keyword);
    }
    delete ui.pending[f.id];
    settle();
    paint();
  }

  /** Adds what is typed in a keyword field; Enter, leaving the field and pasting lines all do it. */
  function commitKeyword(input) {
    const text = input.value;
    if (!text.trim()) return;
    input.value = '';
    addKeywords(Number(input.dataset.kwInput), [text]);
  }

  function duplicateMenu(i) {
    const m = editable();
    const source = m.flows[i];
    const ids = new Map(source.steps.map(s => [s.id, newId('msg')]));
    const copy = {
      id: newId('menu'),
      name: tr('copyName', { name: menuName(source, i) }).slice(0, limitsOf().nameChars || 60),
      enabled: false,
      keywords: [...(source.keywords || [])],
      steps: source.steps.map(s => ({ id: ids.get(s.id), text: s.text, buttons: (s.buttons || []).map(b => ({ title: b.title, next: ids.get(b.next) || '' })) })),
    };
    m.flows.splice(i + 1, 0, copy);
    ui.open.add(copy.id);
    settle();
    redraw(`pm-${i + 1}-name`);
    d.toast(M.duplicated);
  }

  async function save() {
    if (!isDirty()) return;
    const form = $('#persona-menus-form');
    const m = menus();
    const problem = firstProblem(m);
    if (problem) return refuse(problem, m);
    const button = $('[data-pm-save]', form);
    if (button) button.disabled = true;
    const sent = JSON.stringify(payload(m));
    try {
      d.state.personaFlows = await d.api('/app/api/persona/menus', { method: 'PUT', body: sent });
    } catch (err) {
      if (button) button.disabled = false;
      if (err?.code !== 'menu_problem') return d.fail(err);
      return refuse(err.body, m);
    }
    // Typing goes on while the request is out; only what was sent is saved, the rest stays unsaved.
    if (JSON.stringify(payload(menus())) === sent) delete d.state.personaDraft.menus;
    else settle();
    ui.checked = false;
    d.toast(M.saved);
    d.renderKeepingFocus();
  }

  /** A save that can't go through: says why, opens the menus with problems and puts the cursor on the first. */
  function refuse(problem, m) {
    ui.checked = true;
    d.toast(errorText(problem, m));
    const ctx = analyze(m);
    (m.flows || []).forEach((f, i) => { if (ctx[i].errors.length || i === problem?.menu) ui.open.add(f.id); });
    d.render();
    focusProblem(problem);
  }

  /** Scrolls to the field a problem is about and puts the cursor in it. */
  function focusProblem(problem) {
    if (problem?.menu == null) return;
    const limits = limitsOf();
    const base = `pm-${problem.menu}`;
    const card = problem.step != null ? `${base}-${problem.step}` : null;
    const el = {
      name: () => $(`#${base}-name`),
      keywords: () => $(`#${base}-keywords`),
      keywords_limit: () => $(`#${base}-keywords`),
      keyword: () => $(`#${base}-keywords`),
      text: () => card && $(`#${card}-text`),
      title: () => card && $$(`#${card} [data-button-field="title"]`).find(input => !titleOk(input.value, limits)),
      next: () => card && $$(`#${card} [data-button-field="next"]`).find(select => !select.value || select.value === '__new'),
      buttons: () => card && $(`#${card}`),
    }[problem.problem]?.();
    if (!el) return;
    el.scrollIntoView({ block: 'center', behavior: 'smooth' });
    if (typeof el.focus === 'function') el.focus({ preventScroll: true });
  }

  function wire(root) {
    $('[data-menus-settings]', root)?.addEventListener('click', async () => {
      d.state.settingsSection = 'channels';
      await d.setActive('settings');
    });
    $('[data-menus-retry]', root)?.addEventListener('click', async () => {
      d.state.personaFlows = null;
      d.render();
      await load().catch(err => { d.fail(err); d.state.personaFlows = { failed: true }; });
      d.render();
    });
    const form = $('#persona-menus-form', root);
    if (!form) return;
    const edit = canEdit();
    const at = el => (el.dataset.at || '').split(':').map(Number);
    const pair = value => String(value).split(':').map(Number);
    const menuOf = el => Number(el.closest('[data-menu]')?.dataset.menu);

    form.addEventListener('input', e => {
      const el = e.target;
      if (el.id === 'pm-probe') {
        ui.probe = el.value;
        setHtml($('[data-pm-probe]', form), probeHtml(menus()));
        return;
      }
      if (!edit) return;
      if (el.dataset.kwInput != null) {
        const f = menus().flows[Number(el.dataset.kwInput)];
        if (f) ui.pending[f.id] = el.value;
        return;
      }
      if (el.dataset.menuField === 'name') {
        editable().flows[menuOf(el)].name = el.value;
      } else if (el.dataset.stepField === 'text') {
        const [i, j] = at(el);
        const step = editable().flows[i].steps[j];
        step.text = el.value;
        const size = $(`#pm-${i}-${j} [data-step-size]`, form);
        const hint = sizeHint(el.value, limitsOf());
        if (size) { size.className = hint.cls; size.hidden = !hint.show; size.textContent = hint.text; }
        $$(`#pm-${i}-body option[value="${CSS.escape(step.id)}"]`, form).forEach(o => { o.textContent = targetLabel(step, j + 1); });
      } else if (el.dataset.buttonField === 'title') {
        const [i, j, k] = at(el);
        editable().flows[i].steps[j].buttons[k].title = el.value;
      } else {
        return;
      }
      settle();
      paint();
    });

    form.addEventListener('change', e => {
      const el = e.target;
      if (!edit) return;
      if (el.id === 'pm-fallback') {
        const m = editable();
        const others = (m.teamOnlyChannels || []).filter(c => c !== 'INSTAGRAM');
        m.teamOnlyChannels = el.value === 'team' ? [...others, 'INSTAGRAM'] : others;
      } else if (el.dataset.menuField === 'enabled') {
        editable().flows[menuOf(el)].enabled = el.checked;
      } else if (el.dataset.buttonField === 'next') {
        const [i, j, k] = at(el);
        const flow = editable().flows[i];
        if (el.value === '__new') {
          const step = { id: newId('msg'), text: '', buttons: [] };
          flow.steps.push(step);
          flow.steps[j].buttons[k].next = step.id;
          settle();
          return redraw(`pm-${i}-${flow.steps.length - 1}-text`);
        }
        flow.steps[j].buttons[k].next = el.value;
        if (el.value) $('option[value=""]', el)?.remove();
        const t = flow.steps.findIndex(s => s.id === el.value);
        const go = $(`#pm-${i}-${j}-g${k}`, form);
        if (go) { go.dataset.goto = `${i}:${t}`; go.disabled = t < 0; go.title = goLabel(t); go.setAttribute('aria-label', goLabel(t)); }
      } else {
        return;
      }
      settle();
      paint();
    });

    form.addEventListener('keydown', e => {
      const el = e.target;
      if ((e.ctrlKey || e.metaKey) && !e.altKey && e.key.toLowerCase() === 's') {
        e.preventDefault();
        if (el.dataset.kwInput != null) commitKeyword(el);
        if (edit) save();
        return;
      }
      if (el.dataset.kwInput != null) {
        if (e.key === 'Enter') { e.preventDefault(); commitKeyword(el); return; }
        if (e.key === 'Backspace' && !el.value) {
          const f = editable().flows[Number(el.dataset.kwInput)];
          if (!f.keywords?.length) return;
          e.preventDefault();
          el.value = f.keywords.pop();
          ui.pending[f.id] = el.value;
          settle();
          paint();
        }
        return;
      }
      // Enter in a one-line field doesn't save the whole form; Save menus (or Ctrl+S) does.
      if (e.key === 'Enter' && el.tagName === 'INPUT' && el.type !== 'checkbox') e.preventDefault();
    });

    form.addEventListener('focusout', e => {
      if (e.target.dataset?.kwInput != null && edit) commitKeyword(e.target);
    });

    // A keyword's buttons work without taking the cursor out of the field.
    form.addEventListener('mousedown', e => {
      if (e.target.closest('.token__rm, .token__text')) e.preventDefault();
    });

    form.addEventListener('paste', e => {
      const el = e.target;
      if (el.dataset?.kwInput == null || !edit) return;
      const text = e.clipboardData?.getData('text') || '';
      if (!/[\r\n]/.test(text)) return;
      e.preventDefault();
      el.value = '';
      addKeywords(Number(el.dataset.kwInput), text.split(/\r?\n/));
    });

    form.addEventListener('click', async e => {
      const b = e.target.closest('button');
      const head = e.target.closest('[data-menu-head]');
      if (!b && head) return toggle(Number(head.dataset.menuHead));
      if (!b) {
        const tokens = e.target.closest('[data-tokens]');
        if (tokens) $('[data-kw-input]', tokens)?.focus();
        return;
      }
      if (!form.contains(b)) return;
      const ds = b.dataset;
      if (ds.menuToggle != null) return toggle(Number(ds.menuToggle));
      if (ds.goto != null) { const [i, j] = pair(ds.goto); return goTo(i, j); }
      if (ds.mapToggle != null) {
        const f = menus().flows[Number(ds.mapToggle)];
        if (ui.maps.has(f.id)) ui.maps.delete(f.id); else ui.maps.add(f.id);
        paint();
        return $(`[data-map-toggle="${ds.mapToggle}"]`, form)?.focus();
      }
      if (ds.menuTry != null) {
        const i = Number(ds.menuTry);
        const keyword = tryKeyword(menus(), i);
        if (keyword) d.tryInChat(keyword);
        return;
      }
      if (!edit) return;
      if (ds.kwRemove != null) {
        const [i, n] = pair(ds.kwRemove);
        editable().flows[i].keywords.splice(n, 1);
        ui.note = null;
        settle();
        paint();
        return $(`#pm-${i}-keywords`, form)?.focus();
      }
      if (ds.kwEdit != null) {
        const [i, n] = pair(ds.kwEdit);
        const input = $(`#pm-${i}-keywords`, form);
        if (!input) return;
        if (input.value.trim()) commitKeyword(input);
        const f = editable().flows[i];
        const [keyword] = f.keywords.splice(n, 1);
        input.value = keyword;
        ui.pending[f.id] = keyword;
        settle();
        paint();
        input.disabled = false;
        return input.focus();
      }
      if (ds.menuAdd != null) {
        const m = editable();
        const flow = { id: newId('menu'), name: tr('defaultName', { n: m.flows.length + 1 }), enabled: true, keywords: [], steps: [{ id: newId('msg'), text: '', buttons: [] }] };
        m.flows.push(flow);
        ui.open.add(flow.id);
        settle();
        return redraw(`pm-${m.flows.length - 1}-keywords`);
      }
      if (ds.menuUp != null || ds.menuDown != null) {
        const i = Number(ds.menuUp ?? ds.menuDown);
        const to = ds.menuUp != null ? i - 1 : i + 1;
        const flows = editable().flows;
        if (to < 0 || to >= flows.length) return;
        [flows[i], flows[to]] = [flows[to], flows[i]];
        settle();
        return redraw(...(ds.menuUp != null ? [`pm-${to}-up`, `pm-${to}-down`] : [`pm-${to}-down`, `pm-${to}-up`]));
      }
      if (ds.menuDuplicate != null) return duplicateMenu(Number(ds.menuDuplicate));
      if (ds.menuRemove != null) {
        const i = Number(ds.menuRemove);
        const name = menuName(menus().flows[i], i);
        if (!(await d.confirmDialog({ title: tr('removeTitle', { name }), body: M.removeBody, okLabel: M.remove }))) return;
        const flows = editable().flows;
        const [gone] = flows.splice(i, 1);
        ui.open.delete(gone.id);
        settle();
        return flows.length ? redraw(`pm-${Math.min(i, flows.length - 1)}-toggle`) : redraw();
      }
      if (ds.stepAdd != null) {
        const i = Number(ds.stepAdd);
        const steps = editable().flows[i].steps;
        steps.push({ id: newId('msg'), text: '', buttons: [] });
        settle();
        return redraw(`pm-${i}-${steps.length - 1}-text`);
      }
      if (ds.stepUp != null || ds.stepDown != null) {
        const [i, j] = pair(ds.stepUp ?? ds.stepDown);
        const to = ds.stepUp != null ? j - 1 : j + 1;
        const steps = editable().flows[i].steps;
        if (to < 0 || to >= steps.length) return;
        [steps[j], steps[to]] = [steps[to], steps[j]];
        settle();
        return redraw(`pm-${i}-${to}-text`);
      }
      if (ds.stepRemove != null) {
        const [i, j] = pair(ds.stepRemove);
        const flow = editable().flows[i];
        const [gone] = flow.steps.splice(j, 1);
        flow.steps.forEach(s => (s.buttons || []).forEach(btn => { if (btn.next === gone.id) btn.next = ''; }));
        settle();
        return redraw(`pm-${i}-${Math.min(j, flow.steps.length - 1)}-text`);
      }
      if (ds.buttonAdd != null) {
        const [i, j] = pair(ds.buttonAdd);
        const buttons = editable().flows[i].steps[j].buttons;
        buttons.push({ title: '', next: '' });
        settle();
        return redraw(`pm-${i}-${j}-b${buttons.length - 1}`);
      }
      if (ds.buttonRemove != null) {
        const [i, j, k] = ds.buttonRemove.split(':').map(Number);
        const buttons = editable().flows[i].steps[j].buttons;
        buttons.splice(k, 1);
        settle();
        return redraw(`pm-${i}-${j}-b${Math.min(k, buttons.length - 1)}`, `pm-${i}-${j}-text`);
      }
      if (ds.pmDiscard != null) {
        if (!(await d.confirmDialog({ title: M.discardTitle, body: M.discardBody, okLabel: PS.discard }))) return;
        delete d.state.personaDraft.menus;
        ui.checked = false;
        ui.note = null;
        ui.pending = {};
        return redraw();
      }
    });

    form.addEventListener('submit', e => {
      e.preventDefault();
      save();
    });

    paint();
  }

  window.MenusUI = {
    init(deps) {
      d = deps;
      // Leaving the dashboard drops the draft, so the browser asks first.
      window.addEventListener('beforeunload', e => {
        if (!isDirty()) return;
        e.preventDefault();
        e.returnValue = '';
      });
    },
    load,
    render,
    wire,
    /** Whether the menus have unsaved changes, which the test chat then tries. */
    dirty: isDirty,
    /** The unsaved menus as the test chat sends them, or null when nothing is unsaved. */
    draftPayload: () => (isDirty() ? payload(d.state.personaDraft.menus) : null),
    errorText,
  };
})();

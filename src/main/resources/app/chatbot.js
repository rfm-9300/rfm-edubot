/* Chatbot (/app → Chatbot): the company's fixed chat flows, which answer customers before the AI. A flow starts when
   a message holds one of its keywords and walks through steps: a message with options (buttons the customer taps, or
   numbers and words they type), a question whose answer is kept, a condition (opening hours, a client we know, an
   earlier answer), an action (create a client, create a booking, tell the team) or a hand-over to a person. A flow can
   let the AI read an answer nothing else could; the AI only says what it means, never what to reply. On the right, a
   test chat runs the flows turn by turn (POST /app/api/chatbot/test) with the unsaved edits, writing nothing.
   app.js mounts it with init() and passes its helpers; the server checks every rule again when the flows are saved
   (PUT /app/api/chatbot/flows). Unsaved edits live in `draft`, a copy of the saved flows taken on the first edit. */
(function () {
  let d = null;
  const T = I18N.section('app.chatbot');
  const PS = I18N.section('app.personaStudio');
  const tr = (key, params) => I18N.t(`app.chatbot.${key}`, params);
  const esc = s => d.escapeHTML(s == null ? '' : String(s));
  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
  const count = n => d.fmtCount(n);

  const KINDS = ['MESSAGE', 'ASK', 'CONDITION', 'ACTION', 'HANDOFF'];
  const EXPECTS = ['TEXT', 'NAME', 'PHONE', 'EMAIL', 'NUMBER', 'DATE', 'TIME', 'YES_NO'];
  const CHECKS = ['HOURS', 'KNOWN_CLIENT', 'ANSWER'];
  const ACTIONS = ['CREATE_CLIENT', 'CREATE_BOOKING', 'NOTIFY_TEAM'];
  const SHOWS = ['AUTO', 'TAPS_AND_NUMBERS', 'NUMBERED', 'HIDDEN'];
  const CHANNELS = ['WHATSAPP', 'INSTAGRAM', 'WEB'];
  /** Answers other steps read by name; picking one also sets what the question expects. */
  const KEYS = { name: 'NAME', phone: 'PHONE', email: 'EMAIL', service: 'TEXT', date: 'DATE', time: 'TIME', note: 'TEXT' };
  const DAYS = [1, 2, 3, 4, 5, 6, 7];

  // The unsaved flows (null: nothing changed yet), which flows are open, and the test chat. None of it is saved.
  let draft = null;
  let guard = false;
  const ui = { open: new Set(), seeded: null, checked: false, saving: false, pending: {}, note: null, flash: null, chat: [], chatState: null, busy: false, channel: 'WHATSAPP', at: '', known: false };

  const utf8Bytes = text => new TextEncoder().encode(text || '').length;
  const words = text => (text || '').toLowerCase().normalize('NFD').replace(/\p{M}+/gu, '').replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
  const newId = prefix => `${prefix}-${Math.random().toString(36).slice(2, 8)}`;
  const clone = value => JSON.parse(JSON.stringify(value));
  const firstLine = (text, max = 40) => {
    const line = (text || '').split('\n').map(t => t.trim()).find(Boolean) || '';
    return line.length > max ? `${line.slice(0, max - 1).trimEnd()}…` : line;
  };

  const saved = () => d.state.chatbot;
  const ready = () => saved() && !saved().failed;
  const limitsOf = () => saved()?.limits || {};
  const canEdit = () => !!saved()?.canEdit;
  const flows = () => draft || saved()?.flows || [];
  const isDirty = () => !!draft && JSON.stringify(payload(draft)) !== JSON.stringify(payload(saved()?.flows || []));
  const channelName = channel => PS.channel?.[channel] || channel;
  const flowName = (f, i) => (f?.name || '').trim() || tr('defaultName', { n: i + 1 });

  async function load() {
    d.state.chatbot = await d.api('/app/api/chatbot/flows');
  }

  function failed(err) {
    d.state.chatbot = { failed: true };
    if (err?.message !== 'unauthorized') d.toast(T.loadFailed);
  }

  /** The draft to change, started as a copy of the saved flows. */
  function editable() {
    if (!draft) draft = clone(saved()?.flows || []);
    return draft;
  }

  /** The flows as the server keeps them: trimmed, with keywords and option words free of blanks and repeats. */
  function payload(list) {
    const clean = (items = []) => {
      const seen = new Set();
      return items.map(k => (k || '').trim()).filter(k => { const w = words(k); if (!w || seen.has(w)) return false; seen.add(w); return true; });
    };
    return {
      flows: list.map(f => ({
        ...f,
        name: (f.name || '').trim(),
        keywords: clean(f.keywords),
        handoverText: (f.handoverText || '').trim(),
        steps: (f.steps || []).map(s => ({
          ...s,
          text: (s.text || '').trim(),
          key: s.kind === 'MESSAGE' && !(s.options || []).length ? '' : (s.key || '').trim(),
          options: (s.options || []).map(o => ({ title: (o.title || '').trim(), next: o.next || '', words: clean(o.words) })),
        })),
      })),
    };
  }

  /** Drops the draft once it matches the saved flows again, so an edit undone by hand isn't left "unsaved". */
  function settle() {
    if (draft && !isDirty()) draft = null;
  }

  // ── The page ───────────────────────────────────────────────────────────────────────────────────

  function render(root) {
    if (!saved()) { root.innerHTML = `<section class="panel"><div class="panel__body"><p class="hint">${esc(T.loading)}</p></div></section>`; return; }
    if (saved().failed) {
      root.innerHTML = `<section class="panel"><div class="panel__body"><div class="empty"><p class="empty__title">${esc(T.loadFailed)}</p>
        <div class="empty__actions"><button type="button" class="btn btn--sm" data-chatbot-retry>${esc(PS.retry)}</button></div></div></div></section>`;
      $('[data-chatbot-retry]', root).addEventListener('click', async () => {
        d.state.chatbot = null;
        d.render();
        await load().catch(failed);
        d.render();
      });
      return;
    }
    seedOpen();
    const list = flows();
    const edit = canEdit();
    const limits = limitsOf();
    const on = list.filter(f => f.enabled).length;
    const used = saved().usage?.flows || [];
    const sent = used.reduce((n, u) => n + u.sent, 0);
    const customers = used.reduce((n, u) => n + u.customers, 0);
    const stats = statCards([
      { label: T.statOn, value: tr('statOnValue', { n: count(on), total: count(list.length) }) },
      { label: tr('statSent', { days: saved().usage?.days || 30 }), value: count(sent) },
      { label: T.statCustomers, value: count(customers) },
    ]);
    const add = !edit ? '' : list.length < (limits.flows || 10)
      ? `<div><button type="button" class="btn btn--ghost" data-flow-add><span class="btn__plus">+</span> ${esc(T.add)}</button></div>`
      : `<p class="hint">${esc(tr('flowsFull', { max: count(limits.flows || 10) }))}</p>`;
    root.innerHTML = `${hero(labels.chatbot, T.desc, stats)}
      <div class="persona-studio">
        <div class="persona-studio__main">
          ${introPanel()}
          <form class="form chatbot-flows" id="chatbot-form" novalidate>${list.length ? list.map((f, i) => flowPanel(f, i, edit)).join('') : emptyState(edit)}${add}</form>
          ${edit ? savebarHtml() : ''}
        </div>
        <aside class="persona-studio__test" aria-label="${esc(T.testTitle)}">${testPanel()}</aside>
      </div>`;
    wire(root);
  }

  /** On a fresh load a single flow starts open; with several, the list shows them folded. */
  function seedOpen() {
    if (ui.seeded === saved()) return;
    ui.seeded = saved();
    if (flows().length === 1) ui.open.add(flows()[0].id);
  }

  function introPanel() {
    const off = (saved().teamOnlyChannels || []).map(channelName);
    return `<section class="panel"><header class="panel__head"><h2 class="panel__title">${esc(T.introTitle)}</h2></header>
      <div class="panel__body form"><p class="hint">${esc(T.intro)}</p><p class="hint">${esc(T.orderHint)}</p>
        ${off.length ? `<p class="hint"><strong>${esc(tr('aiOff', { channels: off.join(', ') }))}</strong></p>` : ''}</div>
    </section>`;
  }

  const EMPTY_ART = `<svg class="empty__art" viewBox="0 0 128 84" aria-hidden="true" focusable="false"><rect x="10" y="10" width="68" height="22" rx="8" fill="none" stroke="currentColor" stroke-width="2"/><rect x="50" y="42" width="68" height="22" rx="8" fill="none" stroke="currentColor" stroke-width="2"/><path d="M44 32v10h6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>`;

  function emptyState(edit) {
    return `<section class="panel"><div class="panel__body"><div class="empty empty--art">${EMPTY_ART}<p class="empty__title">${esc(T.emptyTitle)}</p>
      <p class="empty__desc">${esc(T.emptyDesc)}</p>
      ${edit ? `<div class="empty__actions"><button type="button" class="btn btn--primary" data-flow-add><span class="btn__plus">+</span> ${esc(T.add)}</button></div>` : ''}</div></div></section>`;
  }

  const usageOf = id => (saved().usage?.flows || []).find(u => u.id === id);
  const isSaved = id => (saved().flows || []).some(f => f.id === id);

  function flowSummary(f) {
    const keywords = (f.keywords || []).filter(k => words(k));
    const shown = keywords.slice(0, 3).join(' · ');
    const parts = [
      keywords.length ? (keywords.length > 3 ? tr('keywordsMore', { list: shown, n: count(keywords.length - 3) }) : shown) : T.noKeywords,
      tr('stepsCount', { n: (f.steps || []).length }),
      (f.channels || []).map(channelName).join(', ') || T.noChannels,
    ];
    const used = usageOf(f.id);
    const days = saved().usage?.days || 30;
    if (used?.sent) parts.push(tr('sentSummary', { n: used.sent, customers: used.customers, days }));
    else if (isSaved(f.id)) parts.push(tr('sentNone', { days }));
    return esc(parts.join(' · ')) + (used?.failed ? ` · <span class="hint--warn">${esc(tr('refused', { n: used.failed }))}</span>` : '');
  }

  function flowPanel(f, i, edit) {
    const open = ui.open.has(f.id);
    const id = `cb-${i}`;
    const last = flows().length - 1;
    const tools = edit ? `<div class="panel__tools">
        <button class="iconbtn" type="button" data-flow-up="${i}"${i ? '' : ' disabled'} aria-label="${esc(T.moveUp)}" title="${esc(T.moveUp)}">↑</button>
        <button class="iconbtn" type="button" data-flow-down="${i}"${i < last ? '' : ' disabled'} aria-label="${esc(T.moveDown)}" title="${esc(T.moveDown)}">↓</button>
      </div>` : '';
    return `<section class="panel flow-menu${open ? ' is-open' : ''}" data-flow="${i}" id="${id}">
      <header class="panel__head flow-menu__head" data-flow-head="${i}">
        <h2 class="flow-menu__title"><button type="button" class="flow-menu__toggle" id="${id}-toggle" data-flow-toggle="${i}" aria-expanded="${open}" aria-controls="${id}-body">
          <span class="flow-menu__chevron" aria-hidden="true"></span><span class="flow__index" title="${esc(T.positionTitle)}">${i + 1}</span><span class="flow-menu__name" data-flow-title="${i}">${esc(flowName(f, i))}</span></button></h2>
        <span class="flow-menu__pills" data-flow-pills="${i}"><span class="pill${f.enabled ? ' pill--ok' : ''}">${esc(f.enabled ? T.on : T.off)}</span></span>
        ${tools}
        <p class="flow-menu__summary" data-flow-summary="${i}">${flowSummary(f)}</p>
      </header>
      <div class="panel__body form flow-menu__body" id="${id}-body"${open ? '' : ' hidden'}>${open ? flowBody(f, i, edit) : ''}</div>
    </section>`;
  }

  const off = edit => (edit ? '' : ' disabled');

  /** A control bound to a field: its path (`data-c` kind and `data-b` positions) says what an edit changes. */
  const bind = (c, at, field, rr = false) => `data-c="${c}" data-b="${at}" data-f="${field}"${rr ? ' data-rr' : ''}`;

  function flowBody(f, i, edit) {
    const id = `cb-${i}`;
    const limits = limitsOf();
    const connected = saved().connected || [];
    const room = flows().length < (limits.flows || 10);
    const steps = f.steps || [];
    const stepAdd = edit ? (steps.length < (limits.steps || 60)
      ? `<select class="sel" id="${id}-addstep" data-step-add="${i}" aria-label="${esc(T.addStep)}"><option value="">+ ${esc(T.addStep)}</option>${KINDS.filter(k => kindAvailable(k)).map(k => `<option value="${k}">${esc(tr(`kind.${k}`))}</option>`).join('')}</select>`
      : `<span class="hint">${esc(tr('stepsFull', { max: count(limits.steps || 60) }))}</span>`) : '';
    const foot = edit ? `<div class="flow-menu__foot">${stepAdd}
        <span class="actions">${room ? `<button type="button" class="btn btn--sm btn--ghost" data-flow-duplicate="${i}">${esc(T.duplicate)}</button>` : ''}<button type="button" class="btn btn--sm btn--ghost" data-flow-remove="${i}">${esc(T.remove)}</button></span>
      </div>` : '';
    const channelsRow = CHANNELS.map(ch => `<label class="form__check"><input type="checkbox" ${bind('c', i, ch)} data-channel="${ch}"${(f.channels || []).includes(ch) ? ' checked' : ''}${off(edit)} /> ${esc(channelName(ch))}${connected.includes(ch) ? '' : ` <span class="hint">· ${esc(T.notConnected)}</span>`}</label>`).join('');
    return `<div class="form__grid">
        <div class="form__row"><label class="lbl" for="${id}-name">${esc(T.name)}</label>
          <input class="inp" id="${id}-name" ${bind('f', i, 'name')} maxlength="${limits.nameChars || 60}" autocomplete="off" value="${esc(f.name || '')}"${off(edit)} />
          <p class="hint">${esc(T.nameHint)}</p></div>
        <div class="form__row"><span class="lbl">${esc(T.state)}</span>
          <label class="form__check"><input type="checkbox" id="${id}-enabled" ${bind('f', i, 'enabled')}${f.enabled ? ' checked' : ''}${off(edit)} /> ${esc(T.enabled)}</label></div>
        <div class="form__row form__row--full"><span class="lbl">${esc(T.channels)}</span>
          <div class="chip-picks" role="group" aria-label="${esc(T.channels)}">${channelsRow}</div>
          <p class="hint">${esc(T.channelsHint)}</p></div>
        <div class="form__row form__row--full">
          <div class="form__row-head">${edit ? `<label class="lbl" for="${id}-keywords">${esc(T.keywords)}</label>` : `<span class="lbl">${esc(T.keywords)}</span>`}<span class="hint" data-kw-count="${i}"></span></div>
          <div class="tokens${edit ? '' : ' is-readonly'}" data-tokens="${i}">${tokenChips(f, i, edit)}${edit ? `<input class="tokens__input" id="${id}-keywords" data-kw-input="${i}" maxlength="${limits.keywordChars || 60}" autocomplete="off" enterkeyhint="enter" placeholder="${esc((f.keywords || []).length ? T.keywordsPhMore : T.keywordsPh)}" value="${esc(ui.pending[f.id] || '')}" />` : ''}</div>
          <p class="hint">${esc(edit ? T.keywordsHelp : T.keywordsHelpRead)}</p>
          <div class="flow-menu__notes" data-kw-notes="${i}" aria-live="polite">${ui.note?.flow === f.id ? `<p class="hint">${esc(ui.note.text)}</p>` : ''}</div></div>
        <div class="form__row form__row--full"><span class="lbl">${esc(T.lostTitle)}</span>
          <label class="form__check"><input type="checkbox" id="${id}-ai" ${bind('f', i, 'aiReads')}${f.aiReads ? ' checked' : ''}${off(edit)} /> ${esc(T.aiReads)}</label>
          <p class="hint">${esc(T.aiReadsHelp)}</p>
          <div class="form__row"><label class="lbl" for="${id}-retries">${esc(T.retriesLabel)}</label>
            <select class="sel" id="${id}-retries" ${bind('f', i, 'retries')}${off(edit)}>${[0, 1, 2].map(v => `<option value="${v}"${Number(f.retries) === v ? ' selected' : ''}>${esc(tr(`retries.${v}`))}</option>`).join('')}</select>
            <p class="hint">${esc(T.retriesHint)}</p></div>
          <div class="form__row"><label class="lbl" for="${id}-after">${esc(T.afterMissesLabel)}</label>
            <select class="sel" id="${id}-after" ${bind('f', i, 'afterMisses', true)}${off(edit)}>${['HAND_OVER', 'LET_AI'].map(v => `<option value="${v}"${f.afterMisses === v ? ' selected' : ''}>${esc(tr(`afterMisses.${v}`))}</option>`).join('')}</select>
            <p class="hint">${esc(f.afterMisses === 'LET_AI' ? T.afterAiHint : T.afterTeamHint)}</p></div>
          ${f.afterMisses === 'HAND_OVER' ? `<div class="form__row"><label class="lbl" for="${id}-handover">${esc(T.handoverText)}</label>
            <textarea class="txt" id="${id}-handover" ${bind('f', i, 'handoverText')} rows="2" placeholder="${esc(T.handoverPh)}"${off(edit)}>${esc(f.handoverText || '')}</textarea>
            <p class="hint">${esc(T.handoverHint)}</p></div>` : ''}
        </div>
      </div>
      <div class="form__row form__row--full"><span class="lbl">${esc(T.steps)}</span><p class="hint">${esc(T.stepsHint)}</p></div>
      <div class="flow flow--edit">${steps.map((s, j) => stepCard(f, i, s, j, edit)).join('')}</div>
      ${foot}`;
  }

  /** A step kind the company can use: actions that write records need their module, booking needs a service to book. */
  function kindAvailable() { return true; }

  function tokenChips(f, i, edit) {
    return (f.keywords || []).map((w, n) => `<span class="token">${edit
      ? `<button type="button" class="token__text" tabindex="-1" data-kw-edit="${i}:${n}" aria-label="${esc(tr('keywordEdit', { keyword: w }))}">${esc(w)}</button><button type="button" class="token__rm" data-kw-remove="${i}:${n}" aria-label="${esc(tr('keywordRemove', { keyword: w }))}">×</button>`
      : `<span class="token__text">${esc(w)}</span>`}</span>`).join('');
  }

  // ── Steps ──────────────────────────────────────────────────────────────────────────────────────

  const stepLabel = (step, n) => { const line = firstLine(step.text, 34); return line ? `${n} · ${line}` : `${n} · ${tr(`kind.${step.kind}`)}`; };

  /** Where a step can lead: every step of the flow (except [self] for conditions' own), and the end of the flow when [end]. */
  function targetSelect(f, at, field, current, edit, { end = true, newStep = false, rr = false, id = '', c = 't' } = {}) {
    const steps = f.steps || [];
    const options = [
      end ? `<option value=""${current ? '' : ' selected'}>${esc(T.endHere)}</option>` : `<option value=""${current ? '' : ' selected'} disabled>${esc(T.pickStep)}</option>`,
      ...steps.map((s, n) => `<option value="${esc(s.id)}"${s.id === current ? ' selected' : ''}>${esc(stepLabel(s, n + 1))}</option>`),
      newStep ? `<option value="__new">${esc(T.newStep)}</option>` : '',
    ].join('');
    return `<select class="sel" ${id ? `id="${id}"` : ''} ${bind(c, at, field, rr)}${off(edit)}>${options}</select>`;
  }

  function stepCard(f, i, step, j, edit) {
    const id = `cb-${i}-${j}`;
    const at = `${i}.${j}`;
    const steps = f.steps;
    const limits = limitsOf();
    const tools = edit ? `<span class="flow__card-tools">
        <button class="iconbtn" type="button" data-step-up="${i}:${j}"${j ? '' : ' disabled'} aria-label="${esc(T.moveUp)}" title="${esc(T.moveUp)}">↑</button>
        <button class="iconbtn" type="button" data-step-down="${i}:${j}"${j < steps.length - 1 ? '' : ' disabled'} aria-label="${esc(T.moveDown)}" title="${esc(T.moveDown)}">↓</button>
        <button class="iconbtn" type="button" data-step-remove="${i}:${j}"${steps.length > 1 ? '' : ' disabled'} aria-label="${esc(T.removeStep)}" title="${esc(T.removeStep)}">×</button>
      </span>` : '';
    const sent = usageOf(f.id)?.steps?.[step.id];
    const kind = edit
      ? `<select class="sel" id="${id}-kind" ${bind('s', at, 'kind', true)} aria-label="${esc(T.kindLabel)}">${KINDS.map(k => `<option value="${k}"${step.kind === k ? ' selected' : ''}>${esc(tr(`kind.${k}`))}</option>`).join('')}</select>`
      : `<span class="pill">${esc(tr(`kind.${step.kind}`))}</span>`;
    return `${j ? '<div class="flow__connector" aria-hidden="true"></div>' : ''}<div class="flow__card" id="${id}" data-step="${i}:${j}">
      <div class="flow__card-head"><span class="flow__index">${j + 1}</span>${kind}
        <span class="flow__card-summary">${j === 0 ? `<span class="muted">${esc(T.first)}</span>` : ''}</span>
        ${sent ? `<span class="flow-menu__stat">${esc(tr('stepSent', { n: sent }))}</span>` : ''}${tools}</div>
      ${stepBody(f, i, step, j, edit, id, at, limits)}
    </div>`;
  }

  const textField = (id, at, field, value, edit, { rows = 3, ph = '', label = '', max = 1000 } = {}) => {
    const bytes = utf8Bytes(value);
    const near = bytes > max * 0.8;
    return `<div class="form__row form__row--full">${label ? `<label class="lbl" for="${id}">${esc(label)}</label>` : `<label class="visually-hidden" for="${id}">${esc(T.textLabel)}</label>`}
      <textarea class="txt" id="${id}" ${bind('s', at, field)} data-size-for="${field}" rows="${rows}" placeholder="${esc(ph)}"${off(edit)}>${esc(value || '')}</textarea>
      <p class="${bytes > max ? 'hint hint--bad' : 'hint hint--warn'}" data-size="${id}"${near ? '' : ' hidden'}>${esc(near ? tr('textSize', { n: count(bytes), max: count(max) }) : '')}</p></div>`;
  };

  function stepBody(f, i, step, j, edit, id, at, limits) {
    switch (step.kind) {
      case 'ASK': return askBody(f, i, step, j, edit, id, at, limits);
      case 'CONDITION': return conditionBody(f, i, step, j, edit, id, at);
      case 'ACTION': return actionBody(f, i, step, j, edit, id, at, limits);
      case 'HANDOFF': return textField(`${id}-text`, at, 'text', step.text, edit, { ph: T.handoffPh, label: T.handoffText, max: limits.textBytes || 1000 }) + `<p class="hint">${esc(T.handoffHint)}</p>`;
      default: return messageBody(f, i, step, j, edit, id, at, limits);
    }
  }

  function messageBody(f, i, step, j, edit, id, at, limits) {
    const options = step.options || [];
    const max = limits.options || 13;
    const rows = options.map((o, k) => `<div class="line" data-option="${i}:${j}:${k}">
        <input class="inp" id="${id}-o${k}" ${bind('o', `${at}.${k}`, 'title')} maxlength="${limits.titleChars || 20}" autocomplete="off" aria-label="${esc(T.optionTitle)}" placeholder="${esc(T.optionTitlePh)}" value="${esc(o.title || '')}"${off(edit)} />
        ${targetSelect(f, `${at}.${k}`, 'next', o.next, edit, { end: false, newStep: true, c: 'o' })}
        <input class="inp" ${bind('w', `${at}.${k}`, 'words')} autocomplete="off" aria-label="${esc(T.optionWords)}" placeholder="${esc(T.optionWordsPh)}" value="${esc((o.words || []).join(', '))}"${off(edit)} />
        ${edit ? `<button class="iconbtn" type="button" data-option-remove="${i}:${j}:${k}" aria-label="${esc(T.removeOption)}" title="${esc(T.removeOption)}">×</button>` : '<span></span>'}
      </div>`).join('');
    const addOption = edit && options.length < max ? `<button type="button" class="btn btn--sm btn--ghost" data-option-add="${i}:${j}"><span class="btn__plus">+</span> ${esc(T.addOption)}</button>` : '<span></span>';
    const show = options.length ? `<div class="form__row"><label class="lbl" for="${id}-show">${esc(T.showLabel)}</label>
        <select class="sel" id="${id}-show" ${bind('s', at, 'show', true)}${off(edit)}>${SHOWS.map(v => `<option value="${v}"${step.show === v ? ' selected' : ''}>${esc(tr(`show.${v}`))}</option>`).join('')}</select>
        <p class="hint">${esc(tr(`showHint.${step.show}`))}</p></div>
      ${options.length > 3 && (step.show === 'AUTO' || step.show === 'TAPS_AND_NUMBERS') ? `<div class="form__row"><label class="lbl" for="${id}-list">${esc(T.listLabel)}</label>
        <input class="inp" id="${id}-list" ${bind('s', at, 'listLabel')} maxlength="${limits.listLabelChars || 20}" autocomplete="off" placeholder="${esc(T.listLabelPh)}" value="${esc(step.listLabel || '')}"${off(edit)} />
        <p class="hint">${esc(T.listLabelHint)}</p></div>` : ''}
      <div class="form__row"><label class="lbl" for="${id}-key">${esc(T.keepChoice)}</label>
        <input class="inp" id="${id}-key" ${bind('s', at, 'key', true)} list="${id}-key-list" maxlength="30" autocomplete="off" placeholder="business_type" value="${esc(step.key || '')}"${off(edit)} />
        <datalist id="${id}-key-list">${Object.keys(KEYS).map(k => `<option value="${k}"></option>`).join('')}</datalist>
        <p class="hint">${esc(T.keepChoiceHint)}</p></div>
      <div class="form__row"><label class="lbl" for="${id}-retry">${esc(T.retry)}</label>
        <input class="inp" id="${id}-retry" ${bind('s', at, 'retryText')} autocomplete="off" placeholder="${esc(T.retryPh)}" value="${esc(step.retryText || '')}"${off(edit)} /><p class="hint">${esc(T.retryHint)}</p></div>`
      : `<div class="form__row"><span class="lbl">${esc(T.thenGo)}</span>${targetSelect(f, at, 'next', step.next, edit, { end: true, id: `${id}-next` })}<p class="hint">${esc(T.noOptionsHint)}</p></div>`;
    return `${textField(`${id}-text`, at, 'text', step.text, edit, { ph: T.textPh, max: limits.textBytes || 1000 })}
      <div class="lines lines--options">
        <div class="lines__head"><span>${esc(T.thOption)}</span><span>${esc(T.thLeadsTo)}</span><span>${esc(T.thWords)}</span><span></span></div>
        ${rows}<p class="lines__empty"${options.length ? ' hidden' : ''}>${esc(T.noOptions)}</p>
        <div class="lines__foot">${addOption}<span class="hint">${esc(tr('optionsCount', { n: count(options.length), max: count(max), chars: count(limits.titleChars || 20) }))}</span></div>
      </div>
      ${show}`;
  }

  function askBody(f, i, step, j, edit, id, at, limits) {
    const keyId = `${id}-key`;
    return `${textField(`${id}-text`, at, 'text', step.text, edit, { ph: T.askPh, max: limits.textBytes || 1000 })}
      <div class="form__grid">
        <div class="form__row"><label class="lbl" for="${keyId}">${esc(T.saveAs)}</label>
          <input class="inp" id="${keyId}" ${bind('s', at, 'key', true)} list="${keyId}-list" maxlength="30" autocomplete="off" placeholder="name" value="${esc(step.key || '')}"${off(edit)} />
          <datalist id="${keyId}-list">${Object.keys(KEYS).map(k => `<option value="${k}">${esc(tr(`key.${k}`))}</option>`).join('')}</datalist>
          <p class="hint">${esc(T.saveAsHint)}</p></div>
        <div class="form__row"><label class="lbl" for="${id}-expect">${esc(T.expectLabel)}</label>
          <select class="sel" id="${id}-expect" ${bind('s', at, 'expect')}${off(edit)}>${EXPECTS.map(v => `<option value="${v}"${step.expect === v ? ' selected' : ''}>${esc(tr(`expect.${v}`))}</option>`).join('')}</select>
          <p class="hint">${esc(tr(`expectHint.${step.expect}`))}</p></div>
        <div class="form__row"><label class="lbl" for="${id}-retry">${esc(T.retry)}</label>
          <input class="inp" id="${id}-retry" ${bind('s', at, 'retryText')} autocomplete="off" placeholder="${esc(T.retryAskPh)}" value="${esc(step.retryText || '')}"${off(edit)} /><p class="hint">${esc(T.retryHint)}</p></div>
        <div class="form__row"><span class="lbl">${esc(T.thenGo)}</span>${targetSelect(f, at, 'next', step.next, edit, { end: true, id: `${id}-next` })}</div>
      </div>`;
  }

  function conditionBody(f, i, step, j, edit, id, at) {
    const limits = limitsOf();
    let detail = '';
    if (step.check === 'HOURS') {
      const rows = (step.hours || []).map((h, k) => `<div class="line line--hours" data-hours="${i}:${j}:${k}">
          <select class="sel" ${bind('h', `${at}.${k}`, 'day')} aria-label="${esc(T.day)}"${off(edit)}>${DAYS.map(n => `<option value="${n}"${h.day === n ? ' selected' : ''}>${esc(tr(`days.${n}`))}</option>`).join('')}</select>
          <input class="inp" type="time" ${bind('h', `${at}.${k}`, 'from')} aria-label="${esc(T.from)}" value="${esc(h.from || '')}"${off(edit)} />
          <input class="inp" type="time" ${bind('h', `${at}.${k}`, 'to')} aria-label="${esc(T.to)}" value="${esc(h.to || '')}"${off(edit)} />
          ${edit ? `<button class="iconbtn" type="button" data-hours-remove="${i}:${j}:${k}" aria-label="${esc(T.removeHours)}" title="${esc(T.removeHours)}">×</button>` : '<span></span>'}
        </div>`).join('');
      detail = `<div class="form__row form__row--full"><span class="lbl">${esc(T.openHours)}</span><p class="hint">${esc(T.openHoursHint)}</p>
        <div class="lines lines--hours">${rows}<p class="lines__empty"${(step.hours || []).length ? ' hidden' : ''}>${esc(T.noHours)}</p>
          ${edit ? `<div class="lines__foot"><span class="actions">${(step.hours || []).length < (limits.hours || 21) ? `<button type="button" class="btn btn--sm btn--ghost" data-hours-add="${i}:${j}"><span class="btn__plus">+</span> ${esc(T.addHours)}</button>` : ''}<button type="button" class="btn btn--sm btn--ghost" data-hours-weekdays="${i}:${j}">${esc(T.weekdays)}</button></span></div>` : ''}
        </div></div>`;
    } else if (step.check === 'ANSWER') {
      detail = `<div class="form__row"><label class="lbl" for="${id}-akey">${esc(T.answerKey)}</label>
          <input class="inp" id="${id}-akey" ${bind('s', at, 'answerKey')} list="${id}-akey-list" maxlength="30" autocomplete="off" placeholder="service" value="${esc(step.answerKey || '')}"${off(edit)} />
          <datalist id="${id}-akey-list">${Object.keys(KEYS).map(k => `<option value="${k}"></option>`).join('')}</datalist></div>
        <div class="form__row"><label class="lbl" for="${id}-ais">${esc(T.answerIs)}</label>
          <input class="inp" id="${id}-ais" ${bind('s', at, 'answerIs')} maxlength="${limits.keywordChars || 60}" autocomplete="off" placeholder="yes" value="${esc(step.answerIs || '')}"${off(edit)} />
          <p class="hint">${esc(T.answerIsHint)}</p></div>`;
    } else {
      detail = `<p class="hint form__row--full">${esc(T.knownHint)}</p>`;
    }
    return `<div class="form__grid">
        <div class="form__row"><label class="lbl" for="${id}-check">${esc(T.checkLabel)}</label>
          <select class="sel" id="${id}-check" ${bind('s', at, 'check', true)}${off(edit)}>${CHECKS.map(v => `<option value="${v}"${step.check === v ? ' selected' : ''}>${esc(tr(`check.${v}`))}</option>`).join('')}</select></div>
        ${detail}
        <div class="form__row"><span class="lbl">${esc(T.ifYes)}</span>${targetSelect(f, at, 'whenTrue', step.whenTrue, edit, { end: false, id: `${id}-yes` })}</div>
        <div class="form__row"><span class="lbl">${esc(T.ifNo)}</span>${targetSelect(f, at, 'whenFalse', step.whenFalse, edit, { end: false, id: `${id}-no` })}</div>
      </div>`;
  }

  function actionBody(f, i, step, j, edit, id, at, limits) {
    const modules = saved();
    const blocked = (step.action === 'CREATE_CLIENT' && !modules.clientsModule) || (step.action === 'CREATE_BOOKING' && !modules.bookingsModule);
    const services = modules.services || [];
    const booking = step.action === 'CREATE_BOOKING' ? `<div class="form__row"><label class="lbl" for="${id}-service">${esc(T.service)}</label>
        <select class="sel" id="${id}-service" ${bind('s', at, 'serviceId')}${off(edit)}><option value=""${step.serviceId ? '' : ' selected'}>${esc(T.serviceFromAnswer)}</option>${services.map(s => `<option value="${esc(s.id)}"${s.id === step.serviceId ? ' selected' : ''}>${esc(s.name)}</option>`).join('')}</select>
        <p class="hint">${esc(T.serviceHint)}</p></div>
      ${step.serviceId ? '' : `<div class="form__row"><label class="lbl" for="${id}-key">${esc(T.serviceKey)}</label>
        <input class="inp" id="${id}-key" ${bind('s', at, 'key')} maxlength="30" autocomplete="off" placeholder="service" value="${esc(step.key || '')}"${off(edit)} /></div>`}` : '';
    return `<div class="form__grid">
        <div class="form__row"><label class="lbl" for="${id}-action">${esc(T.actionLabel)}</label>
          <select class="sel" id="${id}-action" ${bind('s', at, 'action', true)}${off(edit)}>${ACTIONS.map(v => `<option value="${v}"${step.action === v ? ' selected' : ''}>${esc(tr(`action.${v}`))}</option>`).join('')}</select>
          <p class="hint">${esc(tr(`actionHint.${step.action}`))}</p>
          ${blocked ? `<p class="hint hint--warn">${esc(tr('actionNoModule', { module: step.action === 'CREATE_CLIENT' ? labels.clients : labels.bookings }))}</p>` : ''}</div>
        ${booking}
      </div>
      ${textField(`${id}-text`, at, 'text', step.text, edit, { rows: 2, ph: T.actionTextPh, label: T.actionText, max: limits.textBytes || 1000 })}
      <div class="form__grid">
        <div class="form__row"><span class="lbl">${esc(T.thenGo)}</span>${targetSelect(f, at, 'next', step.next, edit, { end: true, id: `${id}-next` })}</div>
        ${step.action === 'NOTIFY_TEAM' ? '' : `<div class="form__row"><span class="lbl">${esc(T.ifFails)}</span>${targetSelect(f, at, 'failNext', step.failNext, edit, { end: true, id: `${id}-fail` })}<p class="hint">${esc(T.ifFailsHint)}</p></div>`}
      </div>`;
  }

  // ── Test chat ──────────────────────────────────────────────────────────────────────────────────

  function testPanel() {
    return `<section class="panel">
      <header class="panel__head"><h2 class="panel__title">${esc(T.testTitle)}</h2>
        <div class="panel__tools"><span class="pill pill--warn" id="chatbot-test-draft"${isDirty() ? '' : ' hidden'}>${esc(T.testDraft)}</span><button class="btn btn--sm btn--ghost" type="button" id="chatbot-chat-clear">${esc(T.testRestart)}</button></div></header>
      <div class="panel__body form">
        <p class="hint">${esc(T.testHint)}</p>
        <div class="form__grid">
          <div class="form__row"><label class="lbl" for="chatbot-channel">${esc(T.testChannel)}</label>
            <select class="sel" id="chatbot-channel">${CHANNELS.map(ch => `<option value="${ch}"${ui.channel === ch ? ' selected' : ''}>${esc(channelName(ch))}</option>`).join('')}</select></div>
          <div class="form__row"><label class="lbl" for="chatbot-at">${esc(T.testAt)}</label>
            <input class="inp" id="chatbot-at" type="datetime-local" value="${esc(ui.at)}" /></div>
        </div>
        <label class="form__check"><input type="checkbox" id="chatbot-known"${ui.known ? ' checked' : ''} /> ${esc(T.testKnown)}</label>
        <div class="chat__log" id="chatbot-chat-log" role="log" aria-live="polite"></div>
        <form class="chat__form" id="chatbot-chat-form">
          <input class="inp chat__input" id="chatbot-chat-input" placeholder="${esc(STR.chatPlaceholder)}" aria-label="${esc(STR.chatPlaceholder)}" autocomplete="off" />
          <button class="btn btn--primary" type="submit" id="chatbot-chat-send"${ui.busy ? ' disabled' : ''}>${esc(STR.send)}</button>
        </form>
      </div>
    </section>`;
  }

  function renderChatLog() {
    const log = $('#chatbot-chat-log');
    if (!log) return;
    if (!ui.chat.length && !ui.busy) { log.innerHTML = `<div class="chat__empty">${esc(T.testEmpty)}</div>`; return; }
    log.innerHTML = ui.chat.map((m, index) => {
      if (m.role === 'note') return `<div class="chat__note">${esc(m.content)}</div>`;
      if (m.role === 'user') return `<div class="chat__msg chat__msg--user">${renderChatText(m.content)}</div>`;
      const last = index === ui.chat.length - 1;
      const replies = last && !ui.busy && m.replies?.length
        ? `<div class="chip-picks" role="group" aria-label="${esc(T.testRepliesAria)}">${m.replies.map((q, k) => `<button type="button" class="chip" data-reply="${index}:${k}">${esc(q.title)}</button>`).join('')}</div>` : '';
      const label = m.listLabel && last ? `<div class="chat__note">${esc(tr('testList', { label: m.listLabel }))}</div>` : '';
      return `<div class="chat__msg chat__msg--bot">${renderChatText(m.content)}</div>${replies}${label}`;
    }).join('') + (ui.busy ? `<div class="chat__msg chat__msg--bot chat__typing">${esc(STR.typing)}</div>` : '');
    log.scrollTop = log.scrollHeight;
  }

  function effectNote(e) {
    const action = tr(`effect.${e.action}`);
    return e.ok ? tr('effectOk', { action, detail: e.detail }) : tr('effectFailed', { action, detail: e.detail });
  }

  /** Sends a test turn: [text] typed or the title of a tapped option, whose payload [tapped] says where it leads. */
  async function sendTest(text, tapped) {
    const content = (text || '').trim();
    if (ui.busy || !content) return;
    ui.chat.push({ role: 'user', content });
    ui.busy = true;
    $('#chatbot-chat-send')?.setAttribute('disabled', '');
    renderChatLog();
    try {
      const body = { text: content, channel: ui.channel, knownClient: ui.known, ...(tapped && { payload: tapped }), ...(ui.chatState && { state: ui.chatState }), ...(ui.at && { at: ui.at }) };
      if (isDirty()) body.flows = payload(draft).flows;
      const res = await d.api('/app/api/chatbot/test', { method: 'POST', body: JSON.stringify(body) });
      ui.chatState = res.state || null;
      if (!res.handled) {
        ui.chat.push({ role: 'note', content: res.aiAnswers ? T.testNoFlowAi : T.testNoFlowTeam });
      } else {
        res.messages.forEach(m => ui.chat.push({ role: 'assistant', content: m.text, replies: m.replies || [], listLabel: m.listLabel }));
        (res.effects || []).forEach(e => ui.chat.push({ role: 'note', content: effectNote(e) }));
        if (res.handedOver) ui.chat.push({ role: 'note', content: tr('testHandedOver', { reason: res.reason || '' }) });
        else if (!res.state) ui.chat.push({ role: 'note', content: T.testEnded });
      }
    } catch (err) {
      if (err?.message !== 'unauthorized') ui.chat.push({ role: 'note', content: err?.code === 'flow_problem' ? errorText(err.body) : T.testFailed });
    } finally {
      ui.busy = false;
      renderChatLog();
      $('#chatbot-chat-send')?.removeAttribute('disabled');
      $('#chatbot-chat-input')?.focus();
    }
  }

  function restartChat() {
    ui.chat = [];
    ui.chatState = null;
    renderChatLog();
  }

  // ── Saving ─────────────────────────────────────────────────────────────────────────────────────

  function savebarHtml() {
    return `<div class="savebar" data-chatbot-savebar>
      <p class="savebar__status" data-chatbot-status aria-live="polite"></p>
      <div class="actions"><button class="btn btn--ghost" type="button" data-chatbot-discard disabled>${esc(T.discard)}</button><button class="btn btn--primary" type="button" data-chatbot-save disabled>${esc(T.save)}</button></div>
    </div>`;
  }

  function paintSavebar() {
    const bar = $('[data-chatbot-savebar]');
    $('#chatbot-test-draft')?.toggleAttribute('hidden', !isDirty());
    if (!bar) return;
    const dirty = isDirty();
    bar.classList.toggle('is-dirty', dirty);
    const status = dirty ? `<span class="savebar__dot" aria-hidden="true"></span>${esc(T.unsaved)}` : esc(T.allSaved);
    const el = $('[data-chatbot-status]', bar);
    if (el.innerHTML !== status) el.innerHTML = status;
    const save = $('[data-chatbot-save]', bar);
    save.disabled = !dirty || ui.saving;
    save.textContent = ui.saving ? T.saving : T.save;
    $('[data-chatbot-discard]', bar).disabled = !dirty || ui.saving;
  }

  /** "Flow, step 2: …" for a problem the server found (`flow_problem`). */
  function errorText(problem = {}) {
    const flow = flows()[problem.flow];
    const key = `app.chatbot.errors.${problem.problem}`;
    const text = I18N.t(key, {
      flow: flow ? flowName(flow, problem.flow) : '',
      step: problem.step != null ? problem.step + 1 : '',
      limit: problem.limit != null ? count(problem.limit) : '',
    });
    return text === key ? T.errorGeneric : text;
  }

  /** A save the server refused: says what is wrong and where, opens that flow and marks the step. */
  function refuse(problem) {
    ui.checked = true;
    d.toast(errorText(problem));
    const f = flows()[problem?.flow];
    if (f) ui.open.add(f.id);
    d.render();
    if (!f) return;
    const card = problem.step != null ? $(`#cb-${problem.flow}-${problem.step}`) : $(`#cb-${problem.flow}`);
    const field = card && $('input:not([type=checkbox]), textarea, select', card);
    (card || $(`#cb-${problem.flow}`))?.scrollIntoView({ block: 'center', behavior: 'smooth' });
    card?.classList.add('is-flash');
    field?.focus({ preventScroll: true });
  }

  async function save() {
    if (ui.saving || !isDirty()) return;
    commitPending();
    const sent = JSON.stringify(payload(draft));
    ui.saving = true;
    paintSavebar();
    try {
      d.state.chatbot = await d.api('/app/api/chatbot/flows', { method: 'PUT', body: sent });
    } catch (err) {
      ui.saving = false;
      if (err?.message === 'unauthorized') return;
      if (err?.code === 'flow_problem') refuse(err.body);
      else d.toast(T.saveFailed);
      return paintSavebar();
    }
    ui.saving = false;
    // Typing goes on while the request is out; only what was sent is saved, the rest stays unsaved.
    if (JSON.stringify(payload(draft)) === sent) draft = null;
    ui.checked = false;
    d.toast(T.saved);
    d.renderKeepingFocus();
  }

  async function discard() {
    if (!(await d.confirmDialog({ title: T.discardTitle, body: T.discardBody, okLabel: T.discard, danger: false }))) return;
    draft = null;
    ui.pending = {};
    ui.note = null;
    ui.checked = false;
    d.render();
  }

  /** A keyword still being typed counts as added when the page saves. */
  function commitPending() {
    const el = document.activeElement;
    if (el?.dataset?.kwInput != null && canEdit()) commitKeyword(el);
  }

  // ── Editing ────────────────────────────────────────────────────────────────────────────────────

  function redraw(focusId) {
    d.render();
    const el = focusId && document.getElementById(focusId);
    if (el && !el.disabled) el.focus();
  }

  const defaultStep = kind => ({
    id: newId('s'), kind, text: '', options: [], show: 'AUTO', listLabel: '', retryText: '', next: '', key: '', expect: 'TEXT', check: 'HOURS', hours: [],
    answerKey: '', answerIs: '', whenTrue: '', whenFalse: '', action: 'NOTIFY_TEAM', serviceId: '', failNext: '',
  });

  const newFlow = () => ({
    id: newId('flow'), name: '', enabled: false, channels: ['WHATSAPP'], keywords: [], aiReads: false, retries: 1, afterMisses: 'HAND_OVER', handoverText: '',
    steps: [{ ...defaultStep('MESSAGE'), text: '' }],
  });

  /** The object a control edits, from its kind (`data-c`) and positions (`data-b`, dotted). */
  function target(c, b) {
    const [i, j, k] = String(b).split('.').map(Number);
    const f = draft?.[i];
    if (c === 'f' || c === 'c') return f;
    const s = f?.steps?.[j];
    if (c === 's' || c === 't') return s;
    if (c === 'o' || c === 'w') return s?.options?.[k];
    if (c === 'h') return s?.hours?.[k];
    return null;
  }

  /** Cleans a step when its kind changes so nothing from the old kind lingers in what is saved. */
  function resetKind(step) {
    const fresh = defaultStep(step.kind);
    for (const key of Object.keys(fresh)) if (!['id', 'kind', 'text'].includes(key)) step[key] = fresh[key];
    if (step.kind === 'CONDITION') step.text = '';
  }

  /** A text box whose every keystroke is an edit, as opposed to a choice, a checkbox, a time or a box that redraws on change. */
  const typedField = el => el.hasAttribute('data-c') && ['INPUT', 'TEXTAREA'].includes(el.tagName) && !['checkbox', 'time'].includes(el.type) && !el.hasAttribute('data-rr');

  function onChange(el) {
    const { c, b, f: field } = el.dataset;
    if (!c) return;
    editable();
    const obj = target(c, b);
    if (!obj) return;
    if (c === 'c') {
      const set = new Set(obj.channels || []);
      el.checked ? set.add(el.dataset.channel) : set.delete(el.dataset.channel);
      obj.channels = CHANNELS.filter(ch => set.has(ch));
    } else if (c === 'w') {
      obj.words = el.value.split(/[,;\n]/).map(w => w.trim()).filter(Boolean);
    } else if ((c === 't' || c === 'o') && el.value === '__new') {
      const i = Number(b.split('.')[0]);
      const step = { ...defaultStep('MESSAGE') };
      draft[i].steps.push(step);
      obj[field] = step.id;
      settle();
      return redraw(`cb-${i}-${draft[i].steps.length - 1}-text`);
    } else if (el.type === 'checkbox') {
      obj[field] = el.checked;
    } else if (field === 'day' || field === 'retries') {
      obj[field] = Number(el.value);
    } else {
      obj[field] = el.value;
    }
    if (field === 'kind') resetKind(obj);
    if (field === 'key' && c === 's' && obj.kind === 'ASK' && KEYS[obj.key] && (obj.expect || 'TEXT') === 'TEXT') obj.expect = KEYS[obj.key];
    settle();
    if (el.hasAttribute('data-rr')) return redraw(el.id);
    paint();
  }

  /** Brings the lines that summarise the draft in step with it without redrawing the fields being typed in. */
  function paint() {
    const form = $('#chatbot-form');
    if (form) {
      flows().forEach((f, i) => {
        const title = $(`[data-flow-title="${i}"]`, form);
        if (title) title.textContent = flowName(f, i);
        const pills = $(`[data-flow-pills="${i}"]`, form);
        if (pills) pills.innerHTML = `<span class="pill${f.enabled ? ' pill--ok' : ''}">${esc(f.enabled ? T.on : T.off)}</span>`;
        const summary = $(`[data-flow-summary="${i}"]`, form);
        if (summary) summary.innerHTML = flowSummary(f);
        const counter = $(`[data-kw-count="${i}"]`, form);
        if (counter) counter.textContent = tr('keywordsCount', { n: count((f.keywords || []).length), max: count(limitsOf().keywords || 50) });
        (f.steps || []).forEach((s, j) => {
          const hint = $(`[data-size="cb-${i}-${j}-text"]`, form);
          if (!hint) return;
          const max = limitsOf().textBytes || 1000;
          const bytes = utf8Bytes(s.text);
          hint.hidden = bytes <= max * 0.8;
          hint.className = bytes > max ? 'hint hint--bad' : 'hint hint--warn';
          hint.textContent = hint.hidden ? '' : tr('textSize', { n: count(bytes), max: count(max) });
          // A step's label in the other steps' choices follows its text.
          $$(`select[data-c="t"] option[value="${CSS.escape(s.id)}"], select[data-c="o"] option[value="${CSS.escape(s.id)}"]`, form).forEach(o => { o.textContent = stepLabel(s, j + 1); });
        });
      });
    }
    paintSavebar();
  }

  function addKeywords(i, texts) {
    const f = editable()[i];
    const limits = limitsOf();
    const max = limits.keywords || 50;
    const have = new Map((f.keywords || []).map(w => [words(w), w]));
    ui.note = null;
    f.keywords = f.keywords || [];
    for (const raw of texts) {
      const keyword = raw.trim().slice(0, limits.keywordChars || 60);
      if (!keyword) continue;
      const w = words(keyword);
      if (!w) { ui.note = { flow: f.id, text: tr('keywordNoWords', { keyword }) }; continue; }
      if (have.has(w)) { ui.note = { flow: f.id, text: tr('keywordRepeat', { keyword: have.get(w) }) }; continue; }
      if (f.keywords.length >= max) { ui.note = { flow: f.id, text: tr('keywordsFull', { max: count(max) }) }; break; }
      f.keywords.push(keyword);
      have.set(w, keyword);
    }
    delete ui.pending[f.id];
    settle();
    redraw(`cb-${i}-keywords`);
  }

  function commitKeyword(input) {
    const text = input.value;
    if (!text.trim()) return;
    input.value = '';
    addKeywords(Number(input.dataset.kwInput), [text]);
  }

  function move(list, from, to) {
    if (to < 0 || to >= list.length) return;
    list.splice(to, 0, ...list.splice(from, 1));
  }

  /** Removes step [j] of flow [i], and every link that led to it. */
  function removeStep(i, j) {
    const f = editable()[i];
    const [gone] = f.steps.splice(j, 1);
    for (const s of f.steps) {
      for (const key of ['next', 'whenTrue', 'whenFalse', 'failNext']) if (s[key] === gone.id) s[key] = '';
      s.options = (s.options || []).filter(o => o.next !== gone.id);
    }
  }

  function wire(root) {
    const form = $('#chatbot-form', root);
    const edit = canEdit();
    renderChatLog();
    paintSavebar();
    paint();
    $('[data-chatbot-save]', root)?.addEventListener('click', save);
    $('[data-chatbot-discard]', root)?.addEventListener('click', discard);
    $('#chatbot-chat-clear', root).addEventListener('click', restartChat);
    $('#chatbot-channel', root).addEventListener('change', e => { ui.channel = e.target.value; restartChat(); });
    $('#chatbot-at', root).addEventListener('change', e => { ui.at = e.target.value; });
    $('#chatbot-known', root).addEventListener('change', e => { ui.known = e.target.checked; });
    $('#chatbot-chat-log', root).addEventListener('click', e => {
      const chip = e.target.closest('[data-reply]');
      if (!chip) return;
      const [index, k] = chip.dataset.reply.split(':').map(Number);
      const reply = ui.chat[index]?.replies?.[k];
      if (reply) sendTest(reply.title, reply.payload);
    });
    $('#chatbot-chat-form', root).addEventListener('submit', e => {
      e.preventDefault();
      const input = $('#chatbot-chat-input');
      const text = input.value;
      input.value = '';
      sendTest(text);
    });
    $$('[data-flow-add]', root).forEach(b => b.addEventListener('click', () => {
      const list = editable();
      const flow = newFlow();
      list.push(flow);
      ui.open.add(flow.id);
      redraw(`cb-${list.length - 1}-name`);
    }));
    if (!form) return;

    form.addEventListener('click', e => {
      const hit = sel => e.target.closest(sel);
      let el;
      if ((el = hit('[data-flow-toggle]'))) {
        const i = Number(el.dataset.flowToggle);
        const id = flows()[i].id;
        ui.open.has(id) ? ui.open.delete(id) : ui.open.add(id);
        return redraw(`cb-${i}-toggle`);
      }
      if ((el = hit('[data-flow-head]')) && !hit('button, input, select, a, label')) {
        const i = Number(el.dataset.flowHead);
        const id = flows()[i].id;
        ui.open.has(id) ? ui.open.delete(id) : ui.open.add(id);
        return redraw(`cb-${i}-toggle`);
      }
      if (!edit) return;
      if ((el = hit('[data-flow-up]'))) { const i = Number(el.dataset.flowUp); move(editable(), i, i - 1); settle(); return redraw(`cb-${i - 1}-toggle`); }
      if ((el = hit('[data-flow-down]'))) { const i = Number(el.dataset.flowDown); move(editable(), i, i + 1); settle(); return redraw(`cb-${i + 1}-toggle`); }
      if ((el = hit('[data-flow-remove]'))) return removeFlow(Number(el.dataset.flowRemove));
      if ((el = hit('[data-flow-duplicate]'))) {
        const i = Number(el.dataset.flowDuplicate);
        const copy = clone(editable()[i]);
        const ids = new Map(copy.steps.map(s => [s.id, newId('s')]));
        copy.id = newId('flow');
        copy.name = tr('copyName', { name: flowName(copy, i) });
        copy.enabled = false;
        copy.steps.forEach(s => {
          s.id = ids.get(s.id);
          for (const key of ['next', 'whenTrue', 'whenFalse', 'failNext']) if (s[key]) s[key] = ids.get(s[key]) || '';
          s.options.forEach(o => { o.next = ids.get(o.next) || ''; });
        });
        draft.splice(i + 1, 0, copy);
        ui.open.add(copy.id);
        d.toast(T.duplicated);
        return redraw(`cb-${i + 1}-name`);
      }
      if ((el = hit('[data-step-up]'))) { const [i, j] = el.dataset.stepUp.split(':').map(Number); move(editable()[i].steps, j, j - 1); settle(); return redraw(`cb-${i}-${j - 1}-kind`); }
      if ((el = hit('[data-step-down]'))) { const [i, j] = el.dataset.stepDown.split(':').map(Number); move(editable()[i].steps, j, j + 1); settle(); return redraw(`cb-${i}-${j + 1}-kind`); }
      if ((el = hit('[data-step-remove]'))) { const [i, j] = el.dataset.stepRemove.split(':').map(Number); removeStep(i, j); settle(); return redraw(`cb-${i}-${Math.max(0, j - 1)}-kind`); }
      if ((el = hit('[data-option-add]'))) {
        const [i, j] = el.dataset.optionAdd.split(':').map(Number);
        editable()[i].steps[j].options.push({ title: '', next: '', words: [] });
        return redraw(`cb-${i}-${j}-o${draft[i].steps[j].options.length - 1}`);
      }
      if ((el = hit('[data-option-remove]'))) { const [i, j, k] = el.dataset.optionRemove.split(':').map(Number); editable()[i].steps[j].options.splice(k, 1); settle(); return redraw(`cb-${i}-${j}-text`); }
      if ((el = hit('[data-hours-add]'))) {
        const [i, j] = el.dataset.hoursAdd.split(':').map(Number);
        editable()[i].steps[j].hours.push({ day: 1, from: '09:00', to: '18:00' });
        return redraw(`cb-${i}-${j}-check`);
      }
      if ((el = hit('[data-hours-weekdays]'))) {
        const [i, j] = el.dataset.hoursWeekdays.split(':').map(Number);
        editable()[i].steps[j].hours = [1, 2, 3, 4, 5].map(day => ({ day, from: '09:00', to: '18:00' }));
        return redraw(`cb-${i}-${j}-check`);
      }
      if ((el = hit('[data-hours-remove]'))) { const [i, j, k] = el.dataset.hoursRemove.split(':').map(Number); editable()[i].steps[j].hours.splice(k, 1); settle(); return redraw(`cb-${i}-${j}-check`); }
      if ((el = hit('[data-kw-remove]'))) { const [i, n] = el.dataset.kwRemove.split(':').map(Number); editable()[i].keywords.splice(n, 1); settle(); return redraw(`cb-${i}-keywords`); }
      if ((el = hit('[data-kw-edit]'))) {
        const [i, n] = el.dataset.kwEdit.split(':').map(Number);
        const [word] = editable()[i].keywords.splice(n, 1);
        ui.pending[draft[i].id] = word;
        settle();
        return redraw(`cb-${i}-keywords`);
      }
    });

    form.addEventListener('input', e => {
      const el = e.target;
      if (!edit) return;
      if (el.dataset.kwInput != null) { const f = flows()[Number(el.dataset.kwInput)]; if (f) ui.pending[f.id] = el.value; return; }
      if (typedField(el)) onChange(el);
    });
    form.addEventListener('change', e => {
      const el = e.target;
      if (!edit) return;
      if (el.dataset.stepAdd != null) {
        if (!el.value) return;
        const i = Number(el.dataset.stepAdd);
        const step = defaultStep(el.value);
        editable()[i].steps.push(step);
        settle();
        return redraw(`cb-${i}-${draft[i].steps.length - 1}-kind`);
      }
      // Fields typed into change the draft as they are typed; the others (and any that redraw the page) when they are left.
      if (el.hasAttribute('data-c') && !typedField(el)) onChange(el);
    });
    form.addEventListener('keydown', e => {
      const el = e.target;
      if ((e.ctrlKey || e.metaKey) && !e.altKey && e.key.toLowerCase() === 's') { e.preventDefault(); save(); return; }
      if (el.dataset.kwInput != null) {
        if (e.key === 'Enter') { e.preventDefault(); commitKeyword(el); return; }
        if (e.key === 'Backspace' && !el.value) {
          const f = editable()[Number(el.dataset.kwInput)];
          if (!f.keywords?.length) return;
          e.preventDefault();
          ui.pending[f.id] = f.keywords.pop();
          settle();
          return redraw(el.id);
        }
        return;
      }
      if (e.key === 'Enter' && el.tagName === 'INPUT' && el.type !== 'checkbox') e.preventDefault();
    });
    form.addEventListener('focusout', e => { if (e.target.dataset?.kwInput != null && edit) commitKeyword(e.target); });
    form.addEventListener('mousedown', e => { if (e.target.closest('.token__rm, .token__text')) e.preventDefault(); });
    form.addEventListener('paste', e => {
      const el = e.target;
      if (!edit || el.dataset?.kwInput == null) return;
      const text = e.clipboardData?.getData('text') || '';
      if (!/[\n\r]/.test(text)) return;
      e.preventDefault();
      addKeywords(Number(el.dataset.kwInput), text.split(/\r?\n/));
    });
    form.addEventListener('submit', e => { e.preventDefault(); if (edit) save(); });
  }

  async function removeFlow(i) {
    const f = flows()[i];
    const ok = await d.confirmDialog({ title: tr('removeTitle', { name: flowName(f, i) }), body: T.removeBody, okLabel: T.remove, danger: true });
    if (!ok) return;
    editable().splice(i, 1);
    ui.open.delete(f.id);
    settle();
    d.render();
  }

  window.ChatbotUI = {
    init(deps) {
      d = deps;
      // Leaving the page with edits that aren't saved asks first.
      window.addEventListener('beforeunload', e => {
        if (guard || !ready() || !isDirty()) return;
        e.preventDefault();
        e.returnValue = '';
      });
    },
    load,
    failed,
    render,
    dirty: () => ready() && isDirty(),
  };
})();

/* Persona (/app → Persona): how the bot talks to customers. Settings, knowledge, instructions and history
   on the left; a test chat on the right that uses unsaved edits (state.personaDraft) so a change can be
   tried before it reaches customers. The Menus tab is menus.js (MenusUI). */

let personaChatBusy = false;
const PS = I18N.section('app.personaStudio');
const ps = (key, params) => I18N.t(`app.personaStudio.${key}`, params);
const PERSONA_TABS = ['behavior', 'knowledge', 'instructions', 'menus', 'history'];
const PERSONA_OPTIONS = {
  tone: ['FRIENDLY', 'PROFESSIONAL', 'FORMAL', 'CASUAL', 'EMPATHETIC'],
  addressForm: ['INFORMAL', 'FORMAL'],
  replyLength: ['SHORT', 'MEDIUM', 'DETAILED'],
  emoji: ['NONE', 'LIGHT', 'EXPRESSIVE'],
};
const PERSONA_TRIES = ['who', 'hours', 'price', 'human', 'jailbreak', 'language'];
// Extensions the upload accepts as other names for a listed type, left out of the hint.
const PERSONA_TYPE_ALIASES = ['markdown', 'text'];
const fmtCount = n => new Intl.NumberFormat(uiLocale()).format(Number(n || 0));
const fmtMegabytes = bytes => new Intl.NumberFormat(uiLocale(), { style: 'unit', unit: 'megabyte', maximumFractionDigits: 0 }).format(Number(bytes || 0) / (1024 * 1024));
const personaStatusLabel = s => ps(`status.${s || 'EMPTY'}`);
const personaAuthor = a => (a === 'operator' ? PS.byOperator : a);
const personaLanguageLabel = code => ps(`lang.${code}`);

function personaErrorText(code, err = {}) {
  const field = err.field ? ps(`fields.${err.field.replace('.', '_')}`) : '';
  const key = `app.personaStudio.errors.${code}`;
  const text = code ? I18N.t(key, { field, limit: err.limit != null ? fmtCount(err.limit) : '', size: err.limit != null ? fmtMegabytes(err.limit) : '' }) : key;
  return text === key ? PS.errorGeneric : text;
}
const personaFail = err => { if (err?.message !== 'unauthorized') toast(personaErrorText(err?.code, err)); };

/** The unsaved edits the test chat and the preview should use; null when nothing is unsaved. */
function personaDraftPayload() {
  const d = state.personaDraft || {};
  const out = {};
  if (d.behavior) out.behavior = d.behavior;
  if (d.compiledInstructions != null) out.compiledInstructions = d.compiledInstructions;
  return Object.keys(out).length ? out : null;
}
const personaBehavior = () => state.personaDraft?.behavior || state.persona?.behavior || {};
const personaInstructions = () => state.personaDraft?.compiledInstructions ?? state.persona?.compiledInstructions ?? '';

/** Settings as the server stores them (trimmed, blanks unset), so an edit undone by hand doesn't count as unsaved. */
function normalizedBehavior(b = {}) {
  const text = v => (typeof v === 'string' ? v.trim() : '') || null;
  return {
    botName: text(b.botName), language: text(b.language), languageStrict: !!(text(b.language) && b.languageStrict),
    tone: text(b.tone), addressForm: text(b.addressForm), replyLength: text(b.replyLength), emoji: text(b.emoji),
    greeting: text(b.greeting), rules: (b.rules || []).map(r => r.trim()).filter(Boolean),
    handoff: { enabled: !!b.handoff?.enabled, triggers: text(b.handoff?.triggers), message: text(b.handoff?.message) },
  };
}
const sameBehavior = (a, b) => JSON.stringify(normalizedBehavior(a)) === JSON.stringify(normalizedBehavior(b));

let personaPollTimer;
async function refreshPersona() {
  const before = state.persona;
  state.persona = await api('/app/api/persona');
  if (before?.status === 'COMPILING' && state.persona.status !== 'COMPILING') {
    state.personaVersions = null;
    state.personaPreview = null;
    if (state.persona.status === 'ERROR') toast(personaErrorText(state.persona.lastError));
    else if (state.persona.version !== before.version) toast(ps('synthesized', { n: state.persona.version }));
  }
  if (state.active === 'persona') renderKeepingFocus();
  clearTimeout(personaPollTimer);
  if (state.persona.status === 'COMPILING') personaPollTimer = setTimeout(() => refreshPersona().catch(() => {}), 3000);
}

/** Redraws the page without dropping the field someone is typing in (polls redraw while they work). */
function renderKeepingFocus() {
  const el = document.activeElement;
  const id = el?.id;
  const range = el && typeof el.selectionStart === 'number' ? [el.selectionStart, el.selectionEnd] : null;
  render();
  const again = id && document.getElementById(id);
  if (!again) return;
  again.focus();
  if (range && again.setSelectionRange) { try { again.setSelectionRange(range[0], range[1]); } catch { /* not a text field */ } }
}

async function loadPersonaVersions() {
  state.personaVersions = await api('/app/api/persona/versions');
}

function renderPersona(root) {
  const p = state.persona || {};
  const tab = PERSONA_TABS.includes(state.personaTab) ? state.personaTab : 'behavior';
  const stats = statCards([
    { label: PS.statStatus, value: personaStatusLabel(p.status) },
    { label: PS.statVersion, value: p.version ? ps('versionShort', { n: p.version }) : '—' },
    { label: PS.statTokens, value: fmtCount(p.tokenEstimate) },
    { label: PS.statUpdated, value: p.updatedAt && p.version ? relTime(p.updatedAt) : '—' },
  ]);
  const tabs = `<div class="settings-tabs" role="tablist" aria-label="${escapeHTML(PS.tabsAria)}">${PERSONA_TABS.map(id => `<button type="button" role="tab" class="chip ${tab === id ? 'is-on' : ''}" aria-selected="${tab === id}" data-persona-tab="${id}">${escapeHTML(ps(`tab.${id}`))}${id === 'knowledge' && p.pendingSources ? `<span class="chip__count">${fmtCount(p.pendingSources)}</span>` : ''}</button>`).join('')}</div>`;
  root.innerHTML = `${hero(labels.persona, STR.personaDesc, stats)}
    ${personaNotices(p)}
    <div class="persona-studio">
      <div class="persona-studio__main">
        ${tabs}
        <div class="persona-studio__pane" role="tabpanel">${personaPane(tab, p)}</div>
      </div>
      <aside class="persona-studio__test" aria-label="${escapeHTML(PS.testTitle)}">${personaTestPanel()}</aside>
    </div>`;
  wirePersona(root, tab, p);
}

function personaNotice(tone, title, desc, actions = '') {
  return `<div class="notice${tone ? ` notice--${tone}` : ''}" role="status"><div class="notice__text"><strong>${escapeHTML(title)}</strong><span>${escapeHTML(desc)}</span></div>${actions}</div>`;
}

function personaNotices(p) {
  const act = (attr, label) => (p.canEdit ? `<div class="notice__actions"><button type="button" class="btn btn--sm" ${attr}>${escapeHTML(label)}</button></div>` : '');
  const out = [];
  if (p.status === 'COMPILING') out.push(personaNotice('info', PS.compilingTitle, PS.compilingDesc));
  else if (p.status === 'ERROR') out.push(personaNotice('warn', PS.errorTitle, `${personaErrorText(p.lastError)} ${PS.errorKeeps}`, act('data-persona-compile', PS.retry)));
  else if (p.pendingSources) out.push(personaNotice('info', ps('pendingTitle', { n: p.pendingSources }), PS.pendingDesc, act('data-persona-compile', PS.synthesizeNow)));
  if (p.stale && p.status !== 'COMPILING') out.push(personaNotice('warn', PS.staleTitle, PS.staleDesc, act('data-persona-rebuild', PS.rebuild)));
  if (p.canEdit === false) out.push(personaNotice('', PS.readOnlyTitle, PS.readOnlyDesc));
  return out.join('');
}

function personaPane(tab, p) {
  if (tab === 'knowledge') return personaKnowledgePane(p);
  if (tab === 'instructions') return personaInstructionsPane(p);
  if (tab === 'menus') return window.MenusUI.render(p);
  if (tab === 'history') return personaHistoryPane(p);
  return personaBehaviorPane(p);
}

function personaBehaviorPane(p) {
  const b = personaBehavior();
  const limits = p.limits || {};
  const off = p.canEdit ? '' : ' disabled';
  const select = field => `<select class="sel" id="pb-${field}"${off}><option value="">${escapeHTML(PS.optionUnset)}</option>${PERSONA_OPTIONS[field].map(v => `<option value="${v}"${b[field] === v ? ' selected' : ''}>${escapeHTML(ps(`options.${field}.${v}`))}</option>`).join('')}</select>`;
  const languages = (limits.languages || []).map(code => `<option value="${escapeHTML(code)}"${b.language === code ? ' selected' : ''}>${escapeHTML(personaLanguageLabel(code))}</option>`).join('');
  const h = b.handoff || {};
  const ruleCount = (b.rules || []).filter(r => r.trim()).length;
  const opt = `<span class="opt">${escapeHTML(PS.optional)}</span>`;
  return `<form class="form" id="persona-behavior-form" novalidate>
    <section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(PS.identityTitle)}</h2></header>
      <div class="panel__body form">
        <p class="hint">${escapeHTML(PS.identityHint)}</p>
        <div class="form__grid">
          <div class="form__row"><label class="lbl" for="pb-botName">${escapeHTML(PS.botName)} ${opt}</label>
            <input class="inp" id="pb-botName" maxlength="${limits.botNameChars || 60}" autocomplete="off" placeholder="${escapeHTML(PS.botNamePh)}" value="${escapeHTML(b.botName || '')}"${off} /></div>
          <div class="form__row"><label class="lbl" for="pb-language">${escapeHTML(PS.language)}</label>
            <select class="sel" id="pb-language"${off}><option value="">${escapeHTML(PS.languageAuto)}</option>${languages}</select></div>
          <div class="form__row form__row--full"><label class="form__check"><input type="checkbox" id="pb-languageStrict"${b.languageStrict ? ' checked' : ''}${b.language && p.canEdit ? '' : ' disabled'} /> ${escapeHTML(PS.languageStrict)}</label></div>
        </div>
      </div>
    </section>
    <section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(PS.styleTitle)}</h2></header>
      <div class="panel__body form">
        <div class="form__grid">
          <div class="form__row"><label class="lbl" for="pb-tone">${escapeHTML(PS.tone)}</label>${select('tone')}</div>
          <div class="form__row"><label class="lbl" for="pb-addressForm">${escapeHTML(PS.addressForm)}</label>${select('addressForm')}</div>
          <div class="form__row"><label class="lbl" for="pb-replyLength">${escapeHTML(PS.replyLength)}</label>${select('replyLength')}</div>
          <div class="form__row"><label class="lbl" for="pb-emoji">${escapeHTML(PS.emoji)}</label>${select('emoji')}</div>
          <div class="form__row form__row--full"><label class="lbl" for="pb-greeting">${escapeHTML(PS.greeting)} ${opt}</label>
            <textarea class="txt" id="pb-greeting" rows="2" maxlength="${limits.greetingChars || 400}" placeholder="${escapeHTML(PS.greetingPh)}"${off}>${escapeHTML(b.greeting || '')}</textarea>
            <p class="hint">${escapeHTML(PS.greetingHint)}</p></div>
        </div>
      </div>
    </section>
    <section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(PS.rulesTitle)} <span class="tag" id="pb-rules-count">${fmtCount(ruleCount)}</span></h2></header>
      <div class="panel__body form">
        <p class="hint">${escapeHTML(PS.rulesHint)}</p>
        <label class="visually-hidden" for="pb-rules">${escapeHTML(PS.rulesTitle)}</label>
        <textarea class="txt" id="pb-rules" rows="6" placeholder="${escapeHTML(PS.rulesPh)}"${off}>${escapeHTML((b.rules || []).join('\n'))}</textarea>
        <p class="hint" id="pb-rules-limit">${escapeHTML(ps('rulesLimit', { max: fmtCount(limits.rules || 25), chars: fmtCount(limits.ruleChars || 300) }))}</p>
      </div>
    </section>
    <section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(PS.handoffTitle)}</h2><span class="pill${h.enabled ? ' pill--ok' : ''}" id="pb-handoff-pill">${escapeHTML(h.enabled ? PS.handoffOn : PS.handoffOff)}</span></header>
      <div class="panel__body form">
        <label class="form__check"><input type="checkbox" id="pb-handoff"${h.enabled ? ' checked' : ''}${off} /> ${escapeHTML(PS.handoffEnable)}</label>
        <div class="form__row form__row--full"><label class="lbl" for="pb-handoff-triggers">${escapeHTML(PS.handoffWhen)} ${opt}</label>
          <textarea class="txt" id="pb-handoff-triggers" rows="2" maxlength="${limits.handoffChars || 500}" placeholder="${escapeHTML(PS.handoffWhenPh)}"${off}>${escapeHTML(h.triggers || '')}</textarea></div>
        <div class="form__row form__row--full"><label class="lbl" for="pb-handoff-message">${escapeHTML(PS.handoffMessage)} ${opt}</label>
          <textarea class="txt" id="pb-handoff-message" rows="2" maxlength="${limits.handoffChars || 500}" placeholder="${escapeHTML(PS.handoffMessagePh)}"${off}>${escapeHTML(h.message || '')}</textarea></div>
        <p class="hint">${escapeHTML(PS.handoffHint)}</p>
      </div>
    </section>
    ${p.canEdit ? `<div class="actions"><button class="btn btn--primary" type="submit" data-pb-save>${escapeHTML(PS.saveSettings)}</button><button class="btn btn--ghost" type="button" data-pb-discard${state.personaDraft?.behavior ? '' : ' disabled'}>${escapeHTML(PS.discard)}</button></div>` : ''}
  </form>
  <div class="persona-agents" id="persona-agents" hidden></div>`;
}

function readPersonaBehavior(form) {
  const v = id => $(`#${id}`, form);
  return {
    botName: v('pb-botName').value,
    language: v('pb-language').value || null,
    languageStrict: v('pb-languageStrict').checked,
    tone: v('pb-tone').value || null,
    addressForm: v('pb-addressForm').value || null,
    replyLength: v('pb-replyLength').value || null,
    emoji: v('pb-emoji').value || null,
    greeting: v('pb-greeting').value,
    rules: v('pb-rules').value.split('\n'),
    handoff: { enabled: v('pb-handoff').checked, triggers: v('pb-handoff-triggers').value, message: v('pb-handoff-message').value },
  };
}

function personaKnowledgePane(p) {
  const limits = p.limits || {};
  const sources = p.sources || [];
  const used = sources.reduce((n, s) => n + (s.chars || 0), 0);
  const note = state.personaDraft?.note || '';
  const types = limits.fileTypes || [];
  const add = p.canEdit ? `<section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(PS.addTitle)}</h2></header>
      <div class="panel__body form">
        <p class="hint">${escapeHTML(PS.addHint)}</p>
        <form class="form" id="persona-note-form">
          <div class="form__row form__row--full"><label class="lbl" for="persona-note">${escapeHTML(PS.noteLabel)}</label>
            <textarea class="txt" id="persona-note" rows="5" maxlength="${limits.noteChars || 20000}" placeholder="${escapeHTML(PS.notePh)}">${escapeHTML(note)}</textarea>
            <p class="hint" id="persona-note-count">${escapeHTML(ps('charCount', { n: fmtCount(note.length), max: fmtCount(limits.noteChars || 20000) }))}</p></div>
          <div class="actions"><button class="btn btn--primary" type="submit">${escapeHTML(PS.addNote)}</button></div>
        </form>
        <div class="form__row form__row--full"><label class="lbl" for="persona-file">${escapeHTML(PS.fileLabel)}</label>
          <input class="inp" id="persona-file" type="file" accept="${escapeHTML(types.map(t => `.${t}`).join(','))}" />
          <p class="hint">${escapeHTML(ps('fileHint', { types: types.filter(t => !PERSONA_TYPE_ALIASES.includes(t)).map(t => t.toUpperCase()).join(', '), size: fmtMegabytes(limits.uploadBytes) }))}</p></div>
      </div></section>` : '';
  const rows = sources.map(s => `<tr>
      <td class="name long">${escapeHTML(s.label)}<div class="sub">${escapeHTML([ps(`kind.${s.kind}`), ps('chars', { n: fmtCount(s.chars) }), s.addedBy ? personaAuthor(s.addedBy) : ''].filter(Boolean).join(' · '))}</div>${s.truncated ? `<p class="hint hint--warn">${escapeHTML(PS.truncated)}</p>` : ''}</td>
      <td data-label="${escapeHTML(PS.thState)}">${s.compiled ? `<span class="pill pill--ok">${escapeHTML(PS.sourceSynced)}</span>` : `<span class="pill pill--warn">${escapeHTML(PS.sourcePending)}</span>`}</td>
      <td class="nowrap" data-label="${escapeHTML(PS.thAdded)}">${escapeHTML(fmtDate(s.createdAt))}</td>
      <td class="right"><div class="actions"><button class="btn btn--sm btn--ghost" type="button" data-view-source="${escapeHTML(s.id)}">${escapeHTML(PS.view)}</button>${p.canEdit ? `<button class="btn btn--sm btn--ghost" type="button" data-del-source="${escapeHTML(s.id)}">${escapeHTML(PS.remove)}</button>` : ''}</div></td>
    </tr>`).join('');
  const empty = `<tr><td colspan="4"><div class="empty"><p class="empty__title">${escapeHTML(PS.noSourcesTitle)}</p><p class="empty__desc">${escapeHTML(PS.noSourcesDesc)}</p></div></td></tr>`;
  const tools = p.canEdit && sources.length ? `<div class="panel__tools"><button class="btn btn--sm btn--ghost" type="button" data-persona-rebuild${p.status === 'COMPILING' ? ' disabled' : ''}>${escapeHTML(PS.rebuild)}</button></div>` : '';
  return `${add}
    <section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(PS.sourcesTitle)} <span class="tag">${fmtCount(sources.length)}</span></h2>${tools}</header>
      <div class="tbl-wrap"><table class="tbl tbl--stack"><thead><tr><th>${escapeHTML(PS.thSource)}</th><th>${escapeHTML(PS.thState)}</th><th>${escapeHTML(PS.thAdded)}</th><th class="right">${escapeHTML(STR.thActions)}</th></tr></thead><tbody>${rows || empty}</tbody></table></div>
      <div class="panel__body"><p class="hint">${escapeHTML(ps('sourcesUsage', { n: fmtCount(sources.length), max: fmtCount(limits.sources || 100), chars: fmtCount(used), maxChars: fmtCount(limits.totalSourceChars || 600000) }))}</p></div>
    </section>`;
}

function personaCountHint(n, max, withTokens) {
  const tone = n > max ? ' hint--bad' : n > max * 0.8 ? ' hint--warn' : '';
  const text = withTokens
    ? ps('charCountTokens', { n: fmtCount(n), max: fmtCount(max), tokens: fmtCount(Math.ceil(n / 4)) })
    : ps('charCount', { n: fmtCount(n), max: fmtCount(max) });
  return { cls: `hint${tone}`, text };
}

function personaInstructionsPane(p) {
  const max = p.limits?.instructionsChars || 12000;
  const text = personaInstructions();
  const count = personaCountHint(text.length, max, true);
  const preview = state.personaPreview;
  return `<section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(PS.instructionsTitle)}</h2>${p.version ? `<span class="panel__meta">${escapeHTML(ps('versionShort', { n: p.version }))}</span>` : ''}</header>
      <div class="panel__body form">
        <p class="hint">${escapeHTML(PS.instructionsHint)}</p>
        <form class="form" id="persona-instructions-form">
          <label class="visually-hidden" for="persona-text">${escapeHTML(PS.instructionsTitle)}</label>
          <textarea class="txt" id="persona-text" rows="18" placeholder="${escapeHTML(PS.instructionsPh)}"${p.canEdit ? '' : ' readonly'}>${escapeHTML(text)}</textarea>
          <p class="${count.cls}" id="persona-text-count">${escapeHTML(count.text)}</p>
          ${p.canEdit ? `<div class="actions"><button class="btn btn--primary" type="submit" data-pi-save>${escapeHTML(PS.saveInstructions)}</button><button class="btn btn--ghost" type="button" data-pi-discard${state.personaDraft?.compiledInstructions == null ? ' disabled' : ''}>${escapeHTML(PS.discard)}</button></div>` : ''}
        </form>
      </div>
    </section>
    <section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(PS.previewTitle)}</h2><div class="panel__tools"><button class="btn btn--sm btn--ghost" type="button" data-persona-preview>${escapeHTML(PS.previewRefresh)}</button></div></header>
      <div class="panel__body form">
        <p class="hint">${escapeHTML(PS.previewHint)}</p>
        <textarea class="txt" id="persona-preview" rows="12" readonly aria-label="${escapeHTML(PS.previewTitle)}">${escapeHTML(preview?.block || '')}</textarea>
        <p class="hint" id="persona-preview-meta">${escapeHTML(personaPreviewMeta(preview))}</p>
      </div>
    </section>`;
}

function personaPreviewMeta(preview) {
  if (!preview) return PS.previewLoading;
  if (!preview.block) return PS.previewEmpty;
  return ps(preview.draft ? 'previewTokensDraft' : 'previewTokens', { n: fmtCount(preview.tokenEstimate) });
}

async function loadPersonaPreview() {
  const draft = personaDraftPayload();
  try {
    const res = await api('/app/api/persona/preview', { method: 'POST', body: JSON.stringify(draft ? { draft } : {}) });
    state.personaPreview = { ...res, draft: !!draft };
  } catch (err) {
    state.personaPreview = null;
    return personaFail(err);
  }
  const box = $('#persona-preview');
  if (box) box.value = state.personaPreview.block || '';
  const meta = $('#persona-preview-meta');
  if (meta) meta.textContent = personaPreviewMeta(state.personaPreview);
}

const personaChangeText = v => ps(`change.${v.change}`, { n: v.sourceCount, from: v.restoredFrom });

function personaHistoryPane(p) {
  const versions = state.personaVersions;
  if (!versions) return `<section class="panel"><div class="panel__body"><p class="hint">${escapeHTML(PS.historyLoading)}</p></div></section>`;
  const rows = versions.map(v => `<tr${v.live ? ' class="is-current"' : ''}>
      <td class="name nowrap">${escapeHTML(ps('versionShort', { n: v.version }))}${v.live ? ` <span class="pill pill--ok">${escapeHTML(PS.live)}</span>` : ''}</td>
      <td class="long" data-label="${escapeHTML(PS.thChange)}">${escapeHTML(personaChangeText(v))}${v.trimmed ? `<div class="sub">${escapeHTML(PS.trimmed)}</div>` : ''}</td>
      <td data-label="${escapeHTML(PS.thBy)}">${escapeHTML(v.author ? personaAuthor(v.author) : PS.byAutomatic)}</td>
      <td class="nowrap" data-label="${escapeHTML(PS.thWhen)}">${escapeHTML(fmtDate(v.createdAt))}</td>
      <td class="right"><div class="actions"><button class="btn btn--sm btn--ghost" type="button" data-version="${v.version}">${escapeHTML(PS.view)}</button>${p.canEdit && !v.live ? `<button class="btn btn--sm" type="button" data-restore="${v.version}">${escapeHTML(PS.restore)}</button>` : ''}</div></td>
    </tr>`).join('');
  const empty = `<tr><td colspan="5"><div class="empty"><p class="empty__title">${escapeHTML(PS.historyEmptyTitle)}</p><p class="empty__desc">${escapeHTML(PS.historyEmptyDesc)}</p></div></td></tr>`;
  return `<section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(PS.historyTitle)} <span class="tag">${fmtCount(versions.length)}</span></h2></header>
      <div class="panel__body"><p class="hint">${escapeHTML(ps('historyHint', { n: fmtCount(versions.length) }))}</p></div>
      <div class="tbl-wrap"><table class="tbl tbl--stack"><thead><tr><th>${escapeHTML(PS.thVersion)}</th><th>${escapeHTML(PS.thChange)}</th><th>${escapeHTML(PS.thBy)}</th><th>${escapeHTML(PS.thWhen)}</th><th class="right">${escapeHTML(STR.thActions)}</th></tr></thead><tbody>${rows || empty}</tbody></table></div>
    </section>`;
}

// Persona → Menus lives in menus.js (MenusUI); the test chat beside it shows what a menu answers.
const PM = I18N.section('app.personaStudio.menus');
const pm = (key, params) => I18N.t(`app.personaStudio.menus.${key}`, params);

function personaBehaviorFacts(b = {}) {
  const choice = field => (b[field] ? ps(`options.${field}.${b[field]}`) : null);
  return dashFacts([
    [PS.botName, b.botName || PS.optionUnset],
    [PS.language, b.language ? `${personaLanguageLabel(b.language)}${b.languageStrict ? ` · ${PS.languageStrictShort}` : ''}` : PS.languageAuto],
    [PS.tone, choice('tone') || PS.optionUnset],
    [PS.addressForm, choice('addressForm') || PS.optionUnset],
    [PS.replyLength, choice('replyLength') || PS.optionUnset],
    [PS.emoji, choice('emoji') || PS.optionUnset],
    b.greeting && [PS.greeting, b.greeting],
    (b.rules || []).length && [PS.rulesTitle, b.rules.join(' · ')],
    [PS.handoffTitle, b.handoff?.enabled ? [PS.handoffOn, b.handoff.message].filter(Boolean).join(' · ') : PS.handoffOff],
  ]);
}

async function openPersonaVersion(n) {
  let v;
  try { v = await api(`/app/api/persona/versions/${encodeURIComponent(n)}`); } catch (err) { return personaFail(err); }
  const canRestore = state.persona?.canEdit && !v.live;
  const body = document.createElement('div');
  body.className = 'form';
  body.innerHTML = `${dashFacts([[PS.thChange, personaChangeText(v)], [PS.thBy, v.author ? personaAuthor(v.author) : PS.byAutomatic], [PS.thWhen, fmtDate(v.createdAt)]])}
    <section class="panel"><header class="panel__head"><h2 class="panel__title">${escapeHTML(PS.versionSettings)}</h2></header><div class="panel__body">${personaBehaviorFacts(v.behavior)}</div></section>
    <div class="form__row form__row--full"><label class="lbl" for="pv-text">${escapeHTML(PS.instructionsTitle)}</label>
      <textarea class="txt" id="pv-text" rows="14" readonly>${escapeHTML(v.compiledInstructions || '')}</textarea></div>
    <div class="drawer__foot"><button class="btn btn--ghost" type="button" data-close>${escapeHTML(PS.close)}</button>${canRestore ? `<button class="btn btn--accent" type="button" data-restore-version>${escapeHTML(PS.restoreThis)}</button>` : ''}</div>`;
  openDrawer(ps('versionTitle', { n: v.version }), body, true, { eyebrow: labels.persona });
  $('[data-restore-version]', body)?.addEventListener('click', () => restorePersonaVersion(v.version));
}

async function restorePersonaVersion(n) {
  const ok = await confirmDialog({ title: ps('restoreTitle', { n }), body: PS.restoreBody, okLabel: PS.restore, danger: false });
  if (!ok) return;
  try {
    state.persona = await api(`/app/api/persona/versions/${encodeURIComponent(n)}/restore`, { method: 'POST', body: '{}' });
  } catch (err) { return personaFail(err); }
  state.personaDraft = {};
  state.personaPreview = null;
  closeDrawer({ dismissed: true });
  toast(ps('restored', { n }));
  await loadPersonaVersions().catch(() => { state.personaVersions = null; });
  render();
}

async function openPersonaSource(id) {
  let s;
  try { s = await api(`/app/api/persona/sources/${encodeURIComponent(id)}`); } catch (err) { return personaFail(err); }
  const body = document.createElement('div');
  body.className = 'form';
  body.innerHTML = `${dashFacts([
      [PS.thState, s.compiled ? PS.sourceSynced : PS.sourcePending],
      [PS.thAdded, fmtDate(s.createdAt)],
      s.addedBy && [PS.thBy, personaAuthor(s.addedBy)],
      [PS.size, ps('chars', { n: fmtCount(s.chars) })],
    ])}
    ${s.truncated ? `<p class="hint hint--warn">${escapeHTML(PS.truncated)}</p>` : ''}
    <div class="form__row form__row--full"><label class="lbl" for="ps-text">${escapeHTML(PS.sourceText)}</label>
      <textarea class="txt" id="ps-text" rows="18" readonly>${escapeHTML(s.content)}</textarea></div>
    <div class="drawer__foot"><button class="btn btn--ghost" type="button" data-close>${escapeHTML(PS.close)}</button></div>`;
  openDrawer(s.label, body, true, { eyebrow: ps(`kind.${s.kind}`) });
}

async function removePersonaSource(source) {
  const ok = await confirmDialog({ title: ps('removeTitle', { name: source.label }), body: source.compiled ? PS.removeCompiledBody : PS.removePendingBody, okLabel: PS.remove });
  if (!ok) return;
  try {
    state.persona = await api(`/app/api/persona/sources/${encodeURIComponent(source.id)}`, { method: 'DELETE' });
  } catch (err) { return personaFail(err); }
  toast(PS.sourceRemoved);
  render();
}

async function startPersonaSynthesis(rebuild) {
  if (rebuild && !(await confirmDialog({ title: PS.rebuildTitle, body: PS.rebuildBody, okLabel: PS.rebuild, danger: false }))) return;
  try {
    state.persona = await api(`/app/api/persona/${rebuild ? 'rebuild' : 'compile'}`, { method: 'POST', body: '{}' });
  } catch (err) { return personaFail(err); }
  toast(rebuild ? PS.rebuildStarted : PS.synthesisStarted);
  render();
  refreshPersona().catch(() => {});
}

async function uploadPersonaFile(file) {
  const form = new FormData();
  form.append('file', file);
  const res = await fetch('/app/api/persona/sources/file', { method: 'POST', headers: token ? { Authorization: `Bearer ${token}` } : {}, body: form });
  if (res.status === 401) { localStorage.removeItem('dashboardToken'); token = ''; renderLogin(); throw new Error('unauthorized'); }
  if (!res.ok) throw await apiError(res);
  return res.json();
}

/** Whether test messages go with unsaved changes: settings, instructions or menus. */
const personaTestDirty = () => !!personaDraftPayload() || !!window.MenusUI?.dirty();

function personaTestPanel() {
  const dirty = personaTestDirty();
  return `<section class="panel">
    <header class="panel__head"><h2 class="panel__title">${escapeHTML(PS.testTitle)}</h2>
      <div class="panel__tools"><span class="pill pill--warn" id="persona-test-draft"${dirty ? '' : ' hidden'}>${escapeHTML(PS.testDraft)}</span><button class="btn btn--sm btn--ghost" type="button" id="persona-chat-clear">${escapeHTML(STR.clear)}</button></div></header>
    <div class="panel__body form">
      <p class="hint">${escapeHTML(PS.testHint)}</p>
      <div class="chip-picks" role="group" aria-label="${escapeHTML(PS.triesAria)}">${PERSONA_TRIES.map(k => `<button type="button" class="chip" data-persona-try="${k}">${escapeHTML(ps(`try.${k}`))}</button>`).join('')}</div>
      <div class="chat__log" id="persona-chat-log" role="log" aria-live="polite"></div>
      <form class="chat__form" id="persona-chat-form">
        <input class="inp chat__input" id="persona-chat-input" placeholder="${escapeHTML(STR.chatPlaceholder)}" aria-label="${escapeHTML(STR.chatPlaceholder)}" autocomplete="off" />
        <button class="btn btn--primary" type="submit" id="persona-chat-send"${personaChatBusy ? ' disabled' : ''}>${escapeHTML(STR.send)}</button>
      </form>
    </div>
  </section>`;
}

function renderPersonaChatLog(busy = false) {
  const log = $('#persona-chat-log');
  if (!log) return;
  const msgs = state.personaChat || [];
  if (!msgs.length && !busy) {
    log.innerHTML = `<div class="chat__empty">${escapeHTML(STR.chatEmpty)}</div>`;
    return;
  }
  log.innerHTML = msgs.map((m, index) => {
    if (m.role === 'note') return `<div class="chat__note">${escapeHTML(m.content)}</div>`;
    const bubble = `<div class="chat__msg chat__msg--${m.role === 'user' ? 'user' : 'bot'}">${renderChatText(m.content)}</div>`;
    // Like on Instagram, a menu's buttons only stay under its latest message.
    const replies = index === msgs.length - 1 && !busy && m.quickReplies?.length
      ? `<div class="chip-picks" role="group" aria-label="${escapeHTML(PM.repliesAria)}">${m.quickReplies.map((q, k) => `<button type="button" class="chip" data-quick-reply="${k}">${escapeHTML(q.title)}</button>`).join('')}</div>`
      : '';
    const notes = [
      m.handoff && (m.reason ? ps('handoffNoteReason', { reason: m.reason }) : PS.handoffNote),
      m.menu && pm('testMenu', { name: m.menu }),
      m.teamOnly && PM.testTeamOnly,
    ].filter(Boolean);
    return bubble + replies + notes.map(n => `<div class="chat__note">${escapeHTML(n)}</div>`).join('');
  }).join('') + (busy ? `<div class="chat__msg chat__msg--bot chat__typing">${escapeHTML(STR.typing)}</div>` : '');
  log.scrollTop = log.scrollHeight;
}

/** Sends a test message; [payload] is a tapped menu button's, which moves the menu on like on Instagram. */
async function sendPersonaTest(text, payload) {
  const content = (text || '').trim();
  if (personaChatBusy || !content) return;
  state.personaChat.push({ role: 'user', content });
  personaChatBusy = true;
  const send = $('#persona-chat-send');
  if (send) send.disabled = true;
  renderPersonaChatLog(true);
  const draft = personaDraftPayload();
  const flows = window.MenusUI?.draftPayload();
  try {
    const messages = state.personaChat.filter(m => m.role === 'user' || m.role === 'assistant').map(({ role, content: c }) => ({ role, content: c }));
    const body = { messages, ...(draft && { draft }), ...(flows && { flows }), ...(payload && { payload }) };
    const res = await api('/app/api/persona/test', { method: 'POST', body: JSON.stringify(body) });
    state.personaChat.push({ role: 'assistant', content: res.reply, handoff: res.handoff, reason: res.handoffReason, menu: res.menu, quickReplies: res.quickReplies || [], teamOnly: !!res.teamOnly });
  } catch (err) {
    if (err?.message !== 'unauthorized') {
      state.personaChat.push({ role: 'note', content: err?.code === 'menu_problem' ? window.MenusUI.errorText(err.body) : personaErrorText(err?.code, err) });
    }
  } finally {
    personaChatBusy = false;
    renderPersonaChatLog();
    const again = $('#persona-chat-send');
    if (again) again.disabled = false;
    $('#persona-chat-input')?.focus();
  }
}

/** "Try it" on a menu: a new test conversation that starts with one of the menu's keywords. */
function tryPersonaMenu(text) {
  if (personaChatBusy) return;
  state.personaChat = [];
  renderPersonaChatLog();
  const panel = $('.persona-studio__test');
  const box = panel?.getBoundingClientRect();
  if (box && (box.top >= window.innerHeight || box.bottom <= 0)) panel.scrollIntoView({ behavior: 'smooth', block: 'start' });
  sendPersonaTest(text);
}

/** Keeps the "testing unsaved changes" pill and the discard buttons in step with the draft, without a redraw. */
function paintPersonaDraft() {
  const pill = $('#persona-test-draft');
  if (pill) pill.hidden = !personaTestDirty();
  const behavior = $('[data-pb-discard]');
  if (behavior) behavior.disabled = !state.personaDraft?.behavior;
  const instructions = $('[data-pi-discard]');
  if (instructions) instructions.disabled = state.personaDraft?.compiledInstructions == null;
}

function wirePersona(root, tab, p) {
  $$('[data-persona-tab]', root).forEach(b => b.addEventListener('click', async () => {
    state.personaTab = b.dataset.personaTab;
    if (state.personaTab === 'history' && !state.personaVersions) {
      render();
      await loadPersonaVersions().catch(err => { personaFail(err); state.personaVersions = []; });
    }
    if (state.personaTab === 'menus' && (!state.personaFlows || state.personaFlows.failed)) {
      state.personaFlows = null;
      render();
      await window.MenusUI.load().catch(err => { personaFail(err); state.personaFlows = { failed: true }; });
    }
    if (state.personaTab === 'instructions') state.personaPreview = null;
    render();
  }));
  if (tab === 'menus') window.MenusUI.wire(root);
  $$('[data-persona-compile]', root).forEach(b => b.addEventListener('click', () => startPersonaSynthesis(false)));
  $$('[data-persona-rebuild]', root).forEach(b => b.addEventListener('click', () => startPersonaSynthesis(true)));

  const behaviorForm = $('#persona-behavior-form', root);
  if (behaviorForm) {
    const onEdit = () => {
      const value = readPersonaBehavior(behaviorForm);
      state.personaDraft.behavior = sameBehavior(value, p.behavior) ? undefined : value;
      const strict = $('#pb-languageStrict', behaviorForm);
      strict.disabled = !value.language || !p.canEdit;
      if (!value.language) strict.checked = false;
      $('#pb-rules-count', behaviorForm).textContent = fmtCount(value.rules.filter(r => r.trim()).length);
      const pill = $('#pb-handoff-pill', behaviorForm);
      pill.textContent = value.handoff.enabled ? PS.handoffOn : PS.handoffOff;
      pill.classList.toggle('pill--ok', value.handoff.enabled);
      paintPersonaDraft();
    };
    behaviorForm.addEventListener('input', onEdit);
    behaviorForm.addEventListener('change', onEdit);
    behaviorForm.addEventListener('submit', async e => {
      e.preventDefault();
      const save = $('[data-pb-save]', behaviorForm);
      save.disabled = true;
      try {
        state.persona = await api('/app/api/persona/behavior', { method: 'PUT', body: JSON.stringify(readPersonaBehavior(behaviorForm)) });
      } catch (err) {
        save.disabled = false;
        return personaFail(err);
      }
      delete state.personaDraft.behavior;
      state.personaVersions = null;
      state.personaPreview = null;
      toast(PS.settingsSaved);
      render();
    });
    $('[data-pb-discard]', behaviorForm)?.addEventListener('click', () => { delete state.personaDraft.behavior; render(); });
    const agents = $('#persona-agents', root);
    if (agents && hasModule('agents') && window.AgentsUI) {
      window.AgentsUI.mountPersonaAgents(agents, p).then(() => { agents.hidden = !agents.childElementCount; });
    }
  }

  const noteForm = $('#persona-note-form', root);
  if (noteForm) {
    const note = $('#persona-note', noteForm);
    const max = p.limits?.noteChars || 20000;
    note.addEventListener('input', () => {
      state.personaDraft.note = note.value;
      const count = personaCountHint(note.value.length, max, false);
      const hint = $('#persona-note-count', noteForm);
      hint.className = count.cls;
      hint.textContent = count.text;
    });
    noteForm.addEventListener('submit', async e => {
      e.preventDefault();
      const content = note.value.trim();
      if (!content) return toast(personaErrorText('content_required', { field: 'content' }));
      const submit = $('button[type=submit]', noteForm);
      submit.disabled = true;
      try {
        state.persona = await api('/app/api/persona/sources', { method: 'POST', body: JSON.stringify({ content }) });
      } catch (err) {
        submit.disabled = false;
        return personaFail(err);
      }
      delete state.personaDraft.note;
      toast(PS.noteAdded);
      render();
      refreshPersona().catch(() => {});
    });
  }
  $('#persona-file', root)?.addEventListener('change', async e => {
    const input = e.target;
    const file = input.files[0];
    if (!file) return;
    const limits = p.limits || {};
    const ext = file.name.includes('.') ? file.name.split('.').pop().toLowerCase() : '';
    if (limits.fileTypes && !limits.fileTypes.includes(ext)) { input.value = ''; return toast(personaErrorText('unsupported_file')); }
    if (limits.uploadBytes && file.size > limits.uploadBytes) { input.value = ''; return toast(personaErrorText('file_too_large', { limit: limits.uploadBytes, field: 'file' })); }
    input.disabled = true;
    try {
      state.persona = await uploadPersonaFile(file);
    } catch (err) {
      input.disabled = false;
      input.value = '';
      return personaFail(err);
    }
    toast(PS.fileUploaded);
    render();
    refreshPersona().catch(() => {});
  });
  $$('[data-view-source]', root).forEach(b => b.addEventListener('click', () => openPersonaSource(b.dataset.viewSource)));
  $$('[data-del-source]', root).forEach(b => b.addEventListener('click', () => {
    const source = (p.sources || []).find(s => s.id === b.dataset.delSource);
    if (source) removePersonaSource(source);
  }));

  const instructionsForm = $('#persona-instructions-form', root);
  if (instructionsForm) {
    const area = $('#persona-text', instructionsForm);
    const max = p.limits?.instructionsChars || 12000;
    area.addEventListener('input', () => {
      state.personaDraft.compiledInstructions = area.value === (p.compiledInstructions || '') ? undefined : area.value;
      const count = personaCountHint(area.value.length, max, true);
      const hint = $('#persona-text-count', instructionsForm);
      hint.className = count.cls;
      hint.textContent = count.text;
      paintPersonaDraft();
    });
    instructionsForm.addEventListener('submit', async e => {
      e.preventDefault();
      if (area.value.trim().length > max) return toast(personaErrorText('too_long', { field: 'compiledInstructions', limit: max }));
      const save = $('[data-pi-save]', instructionsForm);
      save.disabled = true;
      try {
        state.persona = await api('/app/api/persona', { method: 'PUT', body: JSON.stringify({ compiledInstructions: area.value }) });
      } catch (err) {
        save.disabled = false;
        return personaFail(err);
      }
      delete state.personaDraft.compiledInstructions;
      state.personaVersions = null;
      state.personaPreview = null;
      toast(PS.instructionsSaved);
      render();
    });
    $('[data-pi-discard]', instructionsForm)?.addEventListener('click', () => { delete state.personaDraft.compiledInstructions; state.personaPreview = null; render(); });
    $('[data-persona-preview]', root)?.addEventListener('click', loadPersonaPreview);
    if (!state.personaPreview) loadPersonaPreview();
  }

  $$('[data-version]', root).forEach(b => b.addEventListener('click', () => openPersonaVersion(b.dataset.version)));
  $$('[data-restore]', root).forEach(b => b.addEventListener('click', () => restorePersonaVersion(b.dataset.restore)));

  renderPersonaChatLog(personaChatBusy);
  $('#persona-chat-log', root).addEventListener('click', e => {
    const chip = e.target.closest('[data-quick-reply]');
    const last = state.personaChat[state.personaChat.length - 1];
    const reply = chip && last?.quickReplies?.[Number(chip.dataset.quickReply)];
    if (reply) sendPersonaTest(reply.title, reply.payload);
  });
  $('#persona-chat-clear', root).addEventListener('click', () => { state.personaChat = []; renderPersonaChatLog(); });
  $$('[data-persona-try]', root).forEach(b => b.addEventListener('click', () => sendPersonaTest(ps(`try.${b.dataset.personaTry}`))));
  $('#persona-chat-form', root).addEventListener('submit', e => {
    e.preventDefault();
    const input = $('#persona-chat-input');
    const text = input.value;
    input.value = '';
    sendPersonaTest(text);
  });
}

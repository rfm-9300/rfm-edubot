/* Persona → Channels (/app): who the bot answers on each channel. Each channel row says whether the AI answers
   there (WhatsApp and Instagram can leave it to the team; the website always gets the AI) and its start words: a
   new chat there is only answered when its first message holds one. The fixed chat flows that answer before the AI
   are the Chatbot page's (chatbot.js); this tab only points at them. app.js mounts it with init() and passes its
   helpers; the server checks every rule again when the settings are saved (`/app/api/persona/channels`). Unsaved
   edits live in state.personaDraft.channels, a copy of the saved settings taken on the first edit, and the Persona
   page's one save bar saves them (save()). */
(function () {
  let d = null;
  const C = I18N.section('app.personaStudio.channels');
  const PS = I18N.section('app.personaStudio');
  const tr = (key, params) => I18N.t(`app.personaStudio.channels.${key}`, params);
  const esc = s => d.escapeHTML(s == null ? '' : String(s));
  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
  const count = n => d.fmtCount(n);

  // Not part of the draft: whether a save was refused (problems then show under their fields), a start word being
  // typed and a note about the last start-word entry.
  const ui = { checked: false, pending: {}, gateNote: null };

  /** A word as the server matches it: lowercase words without accents or punctuation. */
  const words = text => (text || '').toLowerCase().normalize('NFD').replace(/\p{M}+/gu, '').replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
  const painted = new WeakMap();
  /** Replaces [el]'s content only when it changed, so a button about to be clicked isn't swapped under the pointer. */
  const setHtml = (el, html) => {
    if (!el || painted.get(el) === html) return;
    el.innerHTML = html;
    painted.set(el, html);
  };

  const saved = () => d.state.personaChannels;
  const isDirty = () => !!d?.state?.personaDraft?.channels;
  const canEdit = () => !!saved()?.canEdit && d.state.persona?.canEdit !== false;

  async function load() {
    d.state.personaChannels = await d.api('/app/api/persona/channels');
  }

  /** The settings as they stand: the unsaved draft, else the saved ones. */
  function current() {
    return d.state.personaDraft?.channels || savedSettings();
  }

  function savedSettings() {
    return { teamOnlyChannels: saved()?.teamOnlyChannels || [], startGates: saved()?.startGates || [] };
  }

  /** The draft to change, started as a copy of the saved settings. */
  function editable() {
    if (!d.state.personaDraft.channels) d.state.personaDraft.channels = JSON.parse(JSON.stringify(current()));
    return d.state.personaDraft.channels;
  }

  /** Start words as the server keeps them: trimmed, without blanks or repeats. */
  function cleanWords(list = []) {
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
      teamOnlyChannels: [...(m.teamOnlyChannels || [])].sort(),
      // A gate that is off and has no words is nothing to keep.
      startGates: (m.startGates || [])
        .map(g => ({ channel: g.channel, enabled: !!g.enabled, words: cleanWords(g.words) }))
        .filter(g => g.enabled || g.words.length)
        .sort((a, b) => (a.channel < b.channel ? -1 : a.channel > b.channel ? 1 : 0)),
    };
  }

  const startChannels = () => saved()?.startChannels || [];
  const channelName = channel => PS.channel?.[channel] || channel;
  const gateOf = (m, channel) => (m.startGates || []).find(g => g.channel === channel) || { channel, enabled: false, words: [] };

  /** The gate of [channel] in the draft, added when the channel has none yet. */
  function editableGate(channel) {
    const m = editable();
    m.startGates = m.startGates || [];
    let g = m.startGates.find(x => x.channel === channel);
    if (!g) { g = { channel, enabled: false, words: [] }; m.startGates.push(g); }
    g.words = g.words || [];
    return g;
  }

  /** What stops gate [g] from being saved, with the server's code: too many words, a word too long, or on with none. */
  function gateIssue(g) {
    const list = cleanWords(g.words);
    const s = saved() || {};
    if (list.length > s.startWords) return { code: 'start_words_limit', limit: s.startWords };
    if (list.some(w => w.length > s.keywordChars)) return { code: 'start_word', limit: s.keywordChars };
    if (g.enabled && !list.length) return { code: 'start_words' };
    return null;
  }
  const gateIssues = m => startChannels().map(c => gateIssue(gateOf(m, c))).filter(Boolean).length;

  /** Drops the draft once it matches the saved settings again, so an edit undone by hand isn't left "unsaved". */
  function settle() {
    const draft = d.state.personaDraft.channels;
    if (draft && JSON.stringify(payload(draft)) === JSON.stringify(payload(savedSettings()))) delete d.state.personaDraft.channels;
  }

  // ── The tab ────────────────────────────────────────────────────────────────────────────────────

  function render() {
    const s = saved();
    if (!s) return `<section class="panel"><div class="panel__body"><p class="hint">${esc(C.loading)}</p></div></section>`;
    if (s.failed) {
      return `<section class="panel"><div class="panel__body"><div class="empty"><p class="empty__title">${esc(C.loadFailed)}</p>
        <div class="empty__actions"><button type="button" class="btn btn--sm" data-channels-retry>${esc(PS.retry)}</button></div></div></div></section>`;
    }
    return `<form class="form" id="persona-channels-form" novalidate>${channelsPanel(current(), canEdit())}${chatbotPanel(s)}</form>`;
  }

  /** Whether the company has [channel] set up: from the persona when the page has it, else from the settings. */
  function connected(channel) {
    const known = (d.state.persona?.channels || []).find(c => c.channel === channel);
    if (known) return !!known.connected;
    return channel === 'INSTAGRAM' ? !!saved()?.instagramConnected : channel === 'WHATSAPP' ? !!saved()?.whatsappConnected : false;
  }

  /** One row per channel: whether the AI answers there and the start words new chats need. */
  function channelsPanel(m, edit) {
    const offOptions = saved()?.teamOnlyOptions || ['INSTAGRAM', 'WHATSAPP'];
    const order = ['WHATSAPP', 'INSTAGRAM', 'WEB'];
    const rows = order.map(channel => {
      const on = connected(channel);
      const name = `<div class="bot-channel__name"><strong>${esc(channelName(channel))}</strong><span class="pill${on ? ' pill--ok' : ''}">${esc(on ? C.connected : C.notConnected)}</span></div>`;
      if (!offOptions.includes(channel)) {
        return `<div class="bot-channel" data-channel="${channel}">${name}<div class="bot-channel__body"><p class="hint">${esc(C.webAlways)}</p></div></div>`;
      }
      const aiOn = !(m.teamOnlyChannels || []).includes(channel);
      const aiHint = aiOn ? '' : `<p class="hint">${esc(saved()?.flowsOn ? C.aiOffHintFlows : C.aiOffHint)}</p>`;
      return `<div class="bot-channel" data-channel="${channel}">${name}<div class="bot-channel__body">
          <div class="form__row"><label class="form__check"><input type="checkbox" id="pc-ai-${channel}" data-ai-on="${channel}"${aiOn ? ' checked' : ''}${edit ? '' : ' disabled'} /> ${esc(C.aiReplies)}</label>${aiHint}</div>
          ${startChannels().includes(channel) ? gateRow(m, channel, edit) : ''}
        </div></div>`;
    }).join('');
    const settings = edit && order.some(c => !connected(c))
      ? `<div class="panel__tools"><button type="button" class="btn btn--sm btn--ghost" data-channels-settings>${esc(C.openSettings)}</button></div>` : '';
    return `<section class="panel" id="pc-channels"><header class="panel__head"><h2 class="panel__title">${esc(C.title)}</h2>${settings}</header>
      <div class="panel__body form"><p class="hint">${esc(C.hint)}</p><div class="bot-channels">${rows}</div></div>
    </section>`;
  }

  /** A pointer to the Chatbot page, where the fixed flows that answer before the AI are built. */
  function chatbotPanel(s) {
    const n = s.flowsOn || 0;
    return `<section class="panel" id="pc-chatbot"><header class="panel__head"><h2 class="panel__title">${esc(C.chatbotTitle)}</h2>
      <div class="panel__tools"><button type="button" class="btn btn--sm" data-open-chatbot>${esc(C.chatbotOpen)}</button></div></header>
      <div class="panel__body form"><p class="hint">${esc(C.chatbotHint)}</p>
        <p class="hint"><strong>${esc(n ? tr('chatbotOn', { n: count(n) }) : C.chatbotNone)}</strong></p></div>
    </section>`;
  }

  /** A channel's start words: a box to ask for them and, once on, the words as tokens. */
  function gateRow(m, channel, edit) {
    const g = gateOf(m, channel);
    const id = `pc-gate-${channel}`;
    const wordsHtml = !g.enabled ? '' : `<div class="form__row-head"><label class="lbl" for="${id}-words">${esc(C.startWords)}</label><span class="hint" data-gate-count="${channel}"></span></div>
        <div class="tokens${edit ? '' : ' is-readonly'}" data-gate-tokens="${channel}">${edit ? `<input class="tokens__input" id="${id}-words" data-gate-input="${channel}" maxlength="${saved()?.keywordChars || 60}" autocomplete="off" enterkeyhint="enter" aria-describedby="${id}-hint" value="${esc(ui.pending[`gate:${channel}`] || '')}" />` : ''}</div>
        <p class="hint" id="${id}-hint">${esc(C.startWordsHelp)}</p>
        <p class="hint hint--bad" data-gate-issue="${channel}" hidden></p>
        <div class="flow-menu__notes" data-gate-notes="${channel}" aria-live="polite"></div>`;
    return `<div class="form__row" data-gate="${channel}">
        <label class="form__check"><input type="checkbox" id="${id}-on" data-gate-on="${channel}"${g.enabled ? ' checked' : ''}${edit ? '' : ' disabled'} /> ${esc(C.startOnly)}</label>
        ${wordsHtml}</div>`;
  }

  function gateTokens(g, edit) {
    return (g.words || []).map((w, n) => `<span class="token">${edit
      ? `<button type="button" class="token__text" tabindex="-1" data-gate-edit="${g.channel}:${n}" aria-label="${esc(tr('startWordEdit', { word: w }))}">${esc(w)}</button><button type="button" class="token__rm" data-gate-remove="${g.channel}:${n}" aria-label="${esc(tr('startWordRemove', { word: w }))}">×</button>`
      : `<span class="token__text">${esc(w)}</span>`}</span>`).join('');
  }

  /** Brings each open gate's chips, counter, note and problem in step with the draft, leaving the field being typed in alone. */
  function paintGates(form, m, edit) {
    const s = saved() || {};
    for (const channel of startChannels()) {
      const g = gateOf(m, channel);
      const box = $(`[data-gate-tokens="${channel}"]`, form);
      if (!g.enabled || !box) continue;
      const chips = gateTokens(g, edit);
      if (painted.get(box) !== chips) {
        $$('.token', box).forEach(t => t.remove());
        box.insertAdjacentHTML('afterbegin', chips);
        painted.set(box, chips);
      }
      const max = s.startWords || 50;
      const n = (g.words || []).length;
      const input = $('[data-gate-input]', box);
      if (input) {
        input.disabled = n >= max;
        input.placeholder = n >= max ? tr('startWordsFull', { max: count(max) }) : n ? C.startWordsPhMore : C.startWordsPh;
      }
      const counter = $(`[data-gate-count="${channel}"]`, form);
      if (counter) counter.textContent = tr('startWordsCount', { n: count(n), max: count(max) });
      const notes = $(`[data-gate-notes="${channel}"]`, form);
      if (notes) setHtml(notes, ui.gateNote?.channel === channel ? `<p class="hint">${esc(ui.gateNote.text)}</p>` : '');
      const issue = gateIssue(g);
      const say = $(`[data-gate-issue="${channel}"]`, form);
      if (say) {
        const text = ui.checked && issue ? issueText(issue) : '';
        say.textContent = text;
        say.hidden = !text;
      }
      input?.toggleAttribute('aria-invalid', ui.checked && !!issue);
    }
  }

  function addGateWords(channel, texts) {
    const g = editableGate(channel);
    const s = saved() || {};
    const max = s.startWords || 50;
    const have = new Map(g.words.map(w => [words(w), w]));
    ui.gateNote = null;
    for (const raw of texts) {
      const word = raw.trim().slice(0, s.keywordChars || 60);
      if (!word) continue;
      const w = words(word);
      if (!w) { ui.gateNote = { channel, text: tr('startWordNoWords', { word }) }; continue; }
      if (have.has(w)) { ui.gateNote = { channel, text: tr('startWordRepeat', { word: have.get(w) }) }; continue; }
      if (g.words.length >= max) { ui.gateNote = { channel, text: tr('startWordsFull', { max: count(max) }) }; break; }
      g.words.push(word);
      have.set(w, word);
    }
    delete ui.pending[`gate:${channel}`];
    settle();
    paint();
  }

  /** Adds what is typed in a start-word field; Enter, leaving the field and pasting lines all do it. */
  function commitGateWord(input) {
    const text = input.value;
    if (!text.trim()) return;
    input.value = '';
    addGateWords(input.dataset.gateInput, [text]);
  }

  function problemCount() {
    if (!isDirty() || !saved() || saved().failed) return 0;
    return gateIssues(current());
  }

  /** Brings what the page shows in step with the draft without touching the fields someone may be typing in. */
  function paint() {
    const form = $('#persona-channels-form');
    if (!form) return;
    paintGates(form, current(), canEdit());
    d.onDraft();
  }

  function issueText(issue) {
    return tr(`issues.${issue.code}`, { limit: issue.limit != null ? count(issue.limit) : '' });
  }

  /** "WhatsApp: …" for a problem found here or by the server (`flow_problem` with a channel). */
  function errorText(problem = {}) {
    const key = `app.personaStudio.channels.errors.${problem.problem}`;
    const text = I18N.t(key, { channel: problem.channel ? channelName(problem.channel) : '', limit: problem.limit != null ? count(problem.limit) : '' });
    return text === key ? PS.errorGeneric : text;
  }

  /** The first reason the unsaved settings can't be saved, in the server's codes, or null. */
  function problem() {
    if (!isDirty() || !saved() || saved().failed) return null;
    const m = current();
    for (const channel of startChannels()) {
      const issue = gateIssue(gateOf(m, channel));
      if (issue) return { problem: issue.code, channel, limit: issue.limit };
    }
    return null;
  }

  /** Redraws the page and puts the cursor in [focusId]. */
  function redraw(focusId) {
    d.render();
    const el = focusId && document.getElementById(focusId);
    if (el && !el.disabled) el.focus();
  }

  /**
   * Saves what is unsaved with the page's save bar. True when saved (or nothing was unsaved); false when refused,
   * with the problem shown and the cursor on it.
   */
  async function save() {
    if (!isDirty()) return true;
    commitPending();
    const found = problem();
    if (found) { refuse(found); return false; }
    const sent = JSON.stringify(payload(current()));
    try {
      d.state.personaChannels = await d.api('/app/api/persona/channels', { method: 'PUT', body: sent });
    } catch (err) {
      if (err?.code !== 'flow_problem') { d.fail(err); return false; }
      refuse(err.body);
      return false;
    }
    // Typing goes on while the request is out; only what was sent is saved, the rest stays unsaved.
    if (JSON.stringify(payload(current())) === sent) delete d.state.personaDraft.channels;
    else settle();
    ui.checked = false;
    return true;
  }

  /** A start word still being typed counts as added when the page saves. */
  function commitPending() {
    const el = document.activeElement;
    if (!el?.dataset || !canEdit()) return;
    if (el.dataset.gateInput != null) commitGateWord(el);
  }

  function discard() {
    delete d.state.personaDraft.channels;
    ui.checked = false;
    ui.gateNote = null;
    ui.pending = {};
  }

  /** A save that can't go through: says why and puts the cursor on the start words at fault. */
  function refuse(found) {
    ui.checked = true;
    d.toast(errorText(found));
    d.render();
    if (found?.channel) {
      const field = $(`#pc-gate-${found.channel}-words`) || $(`#pc-gate-${found.channel}-on`);
      field?.scrollIntoView({ block: 'center', behavior: 'smooth' });
      field?.focus({ preventScroll: true });
    }
  }

  function wire(root) {
    $('[data-channels-settings]', root)?.addEventListener('click', async () => {
      d.state.settingsSection = 'channels';
      await d.setActive('settings');
    });
    $('[data-open-chatbot]', root)?.addEventListener('click', () => d.setActive('chatbot'));
    $('[data-channels-retry]', root)?.addEventListener('click', async () => {
      d.state.personaChannels = null;
      d.render();
      await load().catch(err => { d.fail(err); d.state.personaChannels = { failed: true }; });
      d.render();
    });
    const form = $('#persona-channels-form', root);
    if (!form) return;
    const edit = canEdit();

    form.addEventListener('input', e => {
      if (edit && e.target.dataset.gateInput != null) ui.pending[`gate:${e.target.dataset.gateInput}`] = e.target.value;
    });

    form.addEventListener('change', e => {
      const el = e.target;
      if (!edit) return;
      if (el.dataset.gateOn != null) {
        editableGate(el.dataset.gateOn).enabled = el.checked;
        settle();
        return redraw(el.checked ? `pc-gate-${el.dataset.gateOn}-words` : `pc-gate-${el.dataset.gateOn}-on`);
      }
      if (el.dataset.aiOn != null) {
        const m = editable();
        const channel = el.dataset.aiOn;
        const others = (m.teamOnlyChannels || []).filter(c => c !== channel);
        m.teamOnlyChannels = el.checked ? others : [...others, channel];
        settle();
        redraw(el.id);
      }
    });

    form.addEventListener('keydown', e => {
      const el = e.target;
      // Ctrl+S goes on to the page, which saves every tab (and adds a word still being typed first).
      if ((e.ctrlKey || e.metaKey) && !e.altKey && e.key.toLowerCase() === 's') return;
      if (el.dataset.gateInput != null) {
        if (e.key === 'Enter') { e.preventDefault(); commitGateWord(el); return; }
        if (e.key === 'Backspace' && !el.value) {
          const g = editableGate(el.dataset.gateInput);
          if (!g.words.length) return;
          e.preventDefault();
          el.value = g.words.pop();
          ui.pending[`gate:${g.channel}`] = el.value;
          settle();
          paint();
        }
        return;
      }
      // Enter in a one-line field doesn't save the whole page; Save (or Ctrl+S) does.
      if (e.key === 'Enter' && el.tagName === 'INPUT' && el.type !== 'checkbox') e.preventDefault();
    });

    form.addEventListener('focusout', e => {
      if (e.target.dataset?.gateInput != null && edit) commitGateWord(e.target);
    });

    // A start word's buttons work without taking the cursor out of the field.
    form.addEventListener('mousedown', e => {
      if (e.target.closest('.token__rm, .token__text')) e.preventDefault();
    });

    form.addEventListener('click', e => {
      if (!edit) return;
      const remove = e.target.closest('[data-gate-remove]');
      const editWord = e.target.closest('[data-gate-edit]');
      const hit = remove || editWord;
      if (!hit) return;
      const [channel, index] = (remove ? remove.dataset.gateRemove : editWord.dataset.gateEdit).split(':');
      const g = editableGate(channel);
      const [word] = g.words.splice(Number(index), 1);
      if (editWord) {
        const input = $(`[data-gate-input="${channel}"]`, form);
        if (input) { input.value = word; ui.pending[`gate:${channel}`] = word; input.focus(); }
      }
      settle();
      paint();
    });

    // A pasted list adds one start word per line.
    form.addEventListener('paste', e => {
      const el = e.target;
      if (!edit || el.dataset?.gateInput == null) return;
      const text = e.clipboardData?.getData('text') || '';
      if (!/[\n\r]/.test(text)) return;
      e.preventDefault();
      addGateWords(el.dataset.gateInput, text.split(/\r?\n/));
    });

    form.addEventListener('submit', e => {
      e.preventDefault();
      if (edit) d.save();
    });

    paint();
  }

  window.ChannelsUI = {
    init(deps) {
      d = deps;
    },
    load,
    render,
    wire,
    /** Whether the channel settings have unsaved changes. */
    dirty: isDirty,
    /** The settings as they stand (the draft, else the saved ones), or null before they load. */
    current: () => (saved() && !saved().failed ? current() : null),
    /** The first reason the unsaved settings can't be saved, or null. */
    problem,
    problemCount,
    /** Shows why a save can't go through and puts the cursor on it (the Channels tab must be open). */
    refuse,
    save,
    discard,
    commitPending,
    errorText,
  };
})();

/* AI Assistant module (/app → AI Assistant): the team's assistant for the company's data. Conversations
   on the left; on the right the chat, where every change waits on a card for the person to confirm; the
   company's settings for it in a drawer. app.js mounts it and passes its shared helpers to init().
   Replies are rendered from a small markdown subset after escaping, so the model can't inject markup. */
(function () {
  let d = null;
  const T = I18N.section('app.assistant');
  const ui = { threads: [], current: null, settings: null, busy: false, query: '', draft: '', older: false, keepScroll: null };

  const AGENT_TOOLS = new Set(['run_agent', 'pause_agent', 'activate_agent', 'approve_agent_item', 'draft_agent']);
  const STATUS_TONES = { PENDING: 'pill--warn', EXECUTING: 'pill--info', CONFIRMED: 'pill--ok', FAILED: 'pill--bad' };
  const MAX_MESSAGE = 4000;
  const CLIENT_FIELDS = ['name', 'phone', 'email', 'tax_id', 'address', 'postal_code', 'city', 'contact_person', 'notes'];
  /** The record a confirmed change made or touched, by its tool, when the result doesn't say. */
  const RESULT_TYPES = { mark_invoice_paid: 'invoice', create_invoice: 'invoice', create_quote: 'quote', update_quote: 'quote' };
  /** Whose status label a preview's `status_from` uses. */
  const STATUS_ENTITY = { mark_invoice_paid: 'invoice', update_quote: 'quote', cancel_booking: 'booking', confirm_booking: 'booking', mark_payment_paid: 'payment' };

  const esc = s => d.escapeHTML(s);
  const fold = s => String(s || '').toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '');
  const threadPath = id => `/app/api/assistant/threads/${encodeURIComponent(id)}`;
  const view = () => document.getElementById('view');
  const repaint = () => { if (d.state.active === 'ai-assistant') render(view()); };

  // ── Markdown: escape first, then turn a known subset into tags ──

  function inline(raw) {
    const codes = [];
    let s = esc(raw).replace(/`([^`]+)`/g, (_, code) => { codes.push(code); return `\u0000${codes.length - 1}\u0000`; });
    s = s.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
    s = s.replace(/(^|[^*\w])\*(?!\s)([^*]+?)\*(?![*\w])/g, '$1<em>$2</em>');
    s = s.replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>');
    return s.replace(/\u0000(\d+)\u0000/g, (_, i) => `<code>${codes[Number(i)]}</code>`);
  }

  const isRow = line => /^\|.*\|$/.test(line.trim());
  const isRule = line => /^\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?$/.test(line.trim());
  const cells = line => line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map(c => c.trim());
  const looksNumeric = text => /^[-+]?[\d\s.,]+\s?(€|%)?$/.test(text) || /^€\s?[\d.,]+$/.test(text);

  function table(head, rows) {
    const cell = (tag, text) => `<${tag}${tag === 'td' && looksNumeric(text) ? ' class="num"' : ''}>${inline(text)}</${tag}>`;
    return `<div class="chat__table"><table><thead><tr>${head.map(h => cell('th', h)).join('')}</tr></thead>`
      + `<tbody>${rows.map(r => `<tr>${head.map((_, i) => cell('td', r[i] || '')).join('')}</tr>`).join('')}</tbody></table></div>`;
  }

  function markdown(source = '') {
    const lines = String(source).replace(/\r\n?/g, '\n').split('\n');
    const out = [];
    let para = [];
    let list = null;
    const flushPara = () => { if (para.length) out.push(`<p>${para.map(inline).join('<br>')}</p>`); para = []; };
    const flushList = () => { if (list) out.push(`<${list.type}>${list.items.map(i => `<li>${inline(i)}</li>`).join('')}</${list.type}>`); list = null; };
    for (let i = 0; i < lines.length; i += 1) {
      const line = lines[i];
      const text = line.trim();
      if (isRow(text) && i + 1 < lines.length && isRule(lines[i + 1])) {
        flushPara(); flushList();
        const head = cells(text);
        const rows = [];
        i += 1;
        while (i + 1 < lines.length && isRow(lines[i + 1])) { i += 1; rows.push(cells(lines[i])); }
        out.push(table(head, rows));
        continue;
      }
      if (!text) { flushPara(); flushList(); continue; }
      const heading = /^#{1,6}\s+(.+)$/.exec(text);
      if (heading) { flushPara(); flushList(); out.push(`<p class="chat__heading">${inline(heading[1])}</p>`); continue; }
      const bullet = /^[-*•]\s+(.+)$/.exec(text);
      const numbered = /^\d+[.)]\s+(.+)$/.exec(text);
      if (bullet || numbered) {
        flushPara();
        const type = bullet ? 'ul' : 'ol';
        if (list?.type !== type) { flushList(); list = { type, items: [] }; }
        list.items.push((bullet || numbered)[1]);
        continue;
      }
      if (list && /^\s{2,}\S/.test(line)) { list.items[list.items.length - 1] += ` ${text}`; continue; }
      flushList();
      para.push(text);
    }
    flushPara();
    flushList();
    return out.join('');
  }

  // ── Data ──

  async function load() {
    const [threads, settings] = await Promise.all([
      d.api('/app/api/assistant/threads'),
      d.api('/app/api/assistant/settings').catch(() => null),
    ]);
    ui.threads = threads;
    ui.settings = settings;
    if (ui.current && !threads.some(t => t.id === ui.current.thread.id)) ui.current = null;
    const id = ui.current?.thread.id || threads[0]?.id;
    ui.current = id ? await d.api(threadPath(id)) : null;
  }

  async function refreshThreads() {
    ui.threads = await d.api('/app/api/assistant/threads').catch(() => ui.threads);
  }

  async function openThread(id) {
    if (ui.busy) return;
    ui.current = await d.api(threadPath(id));
    ui.draft = '';
    repaint();
    focusComposer();
  }

  async function newThread() {
    const thread = await d.api('/app/api/assistant/threads', { method: 'POST', body: JSON.stringify({ title: d.STR.assistantNewThread }) });
    ui.threads = [thread, ...ui.threads.filter(t => t.id !== thread.id)];
    ui.current = { thread, messages: [] };
    return thread;
  }

  async function send(content) {
    const text = content.trim();
    if (!text || ui.busy) return;
    if (text.length > MAX_MESSAGE) return d.toast(T.errMessage);
    ui.busy = true;
    try {
      if (!ui.current) await newThread();
      ui.current.messages.push({ id: `local-${Date.now()}`, role: 'user', content: text });
      ui.draft = '';
      repaint();
      ui.current = await d.api(`${threadPath(ui.current.thread.id)}/messages`, { method: 'POST', body: JSON.stringify({ content: text }) });
    } catch (err) {
      d.toast(err.code === 'invalid_message' ? T.errMessage : d.STR.assistantError);
      if (ui.current?.thread) ui.current = await d.api(threadPath(ui.current.thread.id)).catch(() => ui.current);
    } finally {
      ui.busy = false;
      await refreshThreads();
      repaint();
      focusComposer();
    }
  }

  async function retry() {
    if (ui.busy || !ui.current) return;
    ui.busy = true;
    repaint();
    try {
      ui.current = await d.api(`${threadPath(ui.current.thread.id)}/retry`, { method: 'POST', body: '{}' });
    } catch {
      d.toast(d.STR.assistantError);
    } finally {
      ui.busy = false;
      repaint();
    }
  }

  async function decide(actionId, decision) {
    if (ui.busy || !ui.current) return;
    ui.busy = true;
    repaint();
    try {
      ui.current = await d.api(`${threadPath(ui.current.thread.id)}/actions/${encodeURIComponent(actionId)}/${decision}`, { method: 'POST', body: '{}' });
    } catch (err) {
      d.toast({ action_not_pending: T.errActionNotPending, changes_off: T.errChangesOff }[err.code] || d.STR.assistantActionError);
      ui.current = await d.api(threadPath(ui.current.thread.id)).catch(() => ui.current);
    } finally {
      ui.busy = false;
      await refreshThreads();
      repaint();
      d.onChange?.();
    }
  }

  async function loadOlder() {
    const first = ui.current?.messages[0];
    if (!first || ui.older) return;
    ui.older = true;
    const log = document.getElementById('assistant-log');
    ui.keepScroll = log ? log.scrollHeight - log.scrollTop : null;
    try {
      const page = await d.api(`${threadPath(ui.current.thread.id)}?before=${encodeURIComponent(first.id)}`);
      ui.current = { ...ui.current, messages: [...page.messages, ...ui.current.messages], hasMore: page.hasMore };
    } catch {
      d.toast(d.STR.loadFailed);
    } finally {
      ui.older = false;
      repaint();
    }
  }

  function renameThread() {
    const thread = ui.current?.thread;
    if (!thread) return;
    const form = document.createElement('form');
    form.className = 'form';
    form.innerHTML = `
      <div class="form__row form__row--full"><label class="lbl" for="as-title">${esc(T.renameLabel)}</label>
        <input class="inp" id="as-title" maxlength="80" required autocomplete="off" value="${esc(thread.title)}" /></div>
      <div class="actions">
        <button class="btn btn--primary" type="submit">${esc(T.renameSave)}</button>
        <button class="btn btn--ghost" type="button" data-form-cancel>${esc(d.STR.cancel)}</button>
      </div>`;
    form.querySelector('[data-form-cancel]').addEventListener('click', () => d.closeDrawer());
    form.addEventListener('submit', async e => {
      e.preventDefault();
      const title = form.querySelector('#as-title').value.trim();
      if (!title) return;
      try {
        const renamed = await d.api(threadPath(thread.id), { method: 'PATCH', body: JSON.stringify({ title }) });
        ui.current = { ...ui.current, thread: { ...ui.current.thread, ...renamed } };
        ui.threads = ui.threads.map(t => (t.id === renamed.id ? { ...t, ...renamed } : t));
        d.closeDrawer();
        d.toast(T.renamed);
        repaint();
      } catch {
        d.toast(T.renameFailed);
      }
    });
    d.openDrawer(T.renameTitle, form, false, { eyebrow: T.settingsEyebrow });
  }

  async function removeThread() {
    const thread = ui.current?.thread;
    if (!thread) return;
    const ok = await d.confirmDialog({ title: T.removeTitle, body: T.removeBody, okLabel: T.removeOk });
    if (!ok) return;
    try {
      await d.api(threadPath(thread.id), { method: 'DELETE' });
      ui.threads = ui.threads.filter(t => t.id !== thread.id);
      ui.current = ui.threads[0] ? await d.api(threadPath(ui.threads[0].id)) : null;
      d.toast(T.removed);
      repaint();
    } catch {
      d.toast(T.removeFailed);
    }
  }

  async function copy(text) {
    try {
      await navigator.clipboard.writeText(text);
      d.toast(T.copied);
    } catch {
      d.toast(T.copyFailed);
    }
  }

  // ── Settings ──

  function openSettings() {
    const s = ui.settings;
    if (!s) return d.toast(d.STR.loadFailed);
    const locked = !s.canEdit;
    const off = attr => (locked ? ` ${attr}` : '');
    const styles = ['CONCISE', 'BALANCED', 'DETAILED'];
    const languages = [['', T.languageAuto], ...I18N.SUPPORTED.map(code => [code, I18N.LANG_NAMES[code] || code])];
    const form = document.createElement('form');
    form.className = 'form';
    form.innerHTML = `
      <p class="hint">${esc(T.settingsDesc)}</p>
      ${locked ? `<p class="hint hint--warn">${esc(T.settingsMemberHint)}</p>` : ''}
      <div class="form__row form__row--full">
        <label class="lbl" for="as-instructions">${esc(T.instructionsLabel)}</label>
        <textarea class="txt" id="as-instructions" rows="8" maxlength="${s.maxInstructions}" placeholder="${esc(T.instructionsPlaceholder)}"${off('disabled')}>${esc(s.instructions || '')}</textarea>
        <p class="hint">${esc(T.instructionsHint)} <span class="assistant__count" id="as-count"></span></p>
      </div>
      <div class="form__grid">
        <div class="form__row"><label class="lbl" for="as-style">${esc(T.styleLabel)}</label>
          <select class="sel" id="as-style"${off('disabled')}>${styles.map(v => `<option value="${v}"${v === s.replyStyle ? ' selected' : ''}>${esc(T[`style${v}`])}</option>`).join('')}</select></div>
        <div class="form__row"><label class="lbl" for="as-language">${esc(T.languageLabel)}</label>
          <select class="sel" id="as-language"${off('disabled')}>${languages.map(([v, label]) => `<option value="${v}"${v === (s.language || '') ? ' selected' : ''}>${esc(label)}</option>`).join('')}</select></div>
      </div>
      <div class="form__row form__row--full">
        <label class="form__check"><input type="checkbox" id="as-changes"${s.allowChanges ? ' checked' : ''}${off('disabled')} /> ${esc(T.changesLabel)}</label>
        <p class="hint">${esc(T.changesHint)}</p>
      </div>
      ${s.areas.length ? `<div class="form__row form__row--full">
        <span class="lbl" id="as-areas-label">${esc(T.areasLabel)}</span>
        <div class="form__checks" role="group" aria-labelledby="as-areas-label">${s.areas.map(a => `<label class="form__check"><input type="checkbox" data-area="${esc(a)}"${s.disabledModules.includes(a) ? '' : ' checked'}${off('disabled')} /> ${esc(d.labels[a] || a)}</label>`).join('')}</div>
        <p class="hint">${esc(T.areasHint)}</p>
      </div>` : ''}
      ${s.updatedAt ? `<p class="hint">${esc(T.settingsUpdated({ who: s.updatedBy || '—', when: d.fmtDate(s.updatedAt) }))}</p>` : ''}
      <div class="actions">
        ${locked ? '' : `<button class="btn btn--primary" type="submit">${esc(T.save)}</button>`}
        <button class="btn btn--ghost" type="button" data-form-cancel>${esc(locked ? T.close : d.STR.cancel)}</button>
      </div>`;
    const instructions = form.querySelector('#as-instructions');
    const count = form.querySelector('#as-count');
    const paintCount = () => { count.textContent = T.count({ n: instructions.value.length, max: s.maxInstructions }); };
    instructions.addEventListener('input', paintCount);
    paintCount();
    form.querySelector('[data-form-cancel]').addEventListener('click', () => d.closeDrawer());
    form.addEventListener('submit', async e => {
      e.preventDefault();
      if (locked) return;
      const button = form.querySelector('button[type=submit]');
      button.disabled = true;
      const body = {
        instructions: instructions.value,
        replyStyle: form.querySelector('#as-style').value,
        language: form.querySelector('#as-language').value || null,
        allowChanges: form.querySelector('#as-changes').checked,
        // Areas the company doesn't have are kept as they were.
        disabledModules: [
          ...s.disabledModules.filter(a => !s.areas.includes(a)),
          ...[...form.querySelectorAll('[data-area]')].filter(box => !box.checked).map(box => box.dataset.area),
        ],
      };
      try {
        ui.settings = await d.api('/app/api/assistant/settings', { method: 'PUT', body: JSON.stringify(body) });
        d.closeDrawer();
        d.toast(T.saved);
        repaint();
      } catch (err) {
        d.toast(err.code === 'too_long' ? T.errTooLong({ limit: s.maxInstructions }) : T.saveFailed);
        button.disabled = false;
      }
    });
    d.openDrawer(T.settingsTitle, form, false, { eyebrow: T.settingsEyebrow });
  }

  // ── Cards ──

  function statusLabel(status) {
    if (status === 'EXPIRED') return T.statusEXPIRED;
    return d.STR[`assistantStatus${status}`] || status;
  }

  function actionTitle(action) {
    const args = action.arguments || {};
    const p = action.preview || {};
    const step = p.action ? (window.AgentsUI?.actionLabel(p.action) || p.action) : '';
    const S = d.STR;
    switch (action.toolName) {
      case 'run_agent': return S.assistantRunAgent({ agent: p.agent || args.agent_id || '', record: p.record || '' });
      case 'pause_agent': return S.assistantPauseAgent({ agent: p.agent || args.agent_id || '' });
      case 'activate_agent': return S.assistantActivateAgent({ agent: p.agent || args.agent_id || '' });
      case 'approve_agent_item': return args.decision === 'reject' ? S.assistantRejectAgentItem({ action: step }) : S.assistantApproveAgentItem({ action: step });
      case 'draft_agent': return S.assistantDraftAgent;
      case 'create_client': return S.assistantCreateClient({ name: args.name || '' });
      case 'update_client': return T.updateClient({ name: p.client || args.client_id || '' });
      case 'create_quote': return p.client ? T.createQuoteFor({ client: p.client }) : S.assistantCreateQuote;
      case 'update_quote': return S.assistantUpdateQuote({ id: p.quote || args.quote_id || '' });
      case 'create_invoice': return p.client ? T.createInvoiceFor({ client: p.client }) : S.assistantCreateInvoice;
      case 'convert_quote_to_invoice': return T.convertQuote({ number: p.quote || args.quote_id || '' });
      case 'mark_invoice_paid': return S.assistantMarkPaid({ id: p.invoice || args.invoice_id || '' });
      case 'mark_payment_paid': return T.markPaymentPaid({ number: p.payment || args.payment_id || '' });
      case 'reply_to_conversation': return T.reply({ contact: p.contact || '' });
      case 'create_booking': return p.service ? T.bookService({ service: p.service }) : S.assistantCreateBooking;
      case 'reschedule_booking': return S.assistantRescheduleBooking;
      case 'cancel_booking': return S.assistantCancelBooking;
      case 'confirm_booking': return S.assistantConfirmBooking;
      default: return S.assistantChangeData;
    }
  }

  function agentDetails(action) {
    const S = d.STR;
    const args = action.arguments || {};
    const p = action.preview || {};
    const rows = [];
    if (action.toolName === 'approve_agent_item') {
      if (p.agent) rows.push(S.assistantAgentName({ name: p.agent }));
      if (p.record) rows.push(S.assistantAgentRecord({ record: p.record }));
      if (p.recipients?.length) rows.push(S.assistantAgentTo({ to: p.recipients.join(', ') }));
      if (p.subject) rows.push(S.assistantAgentSubject({ subject: p.subject }));
      if (p.body) rows.push(S.assistantAgentMessage({ text: p.body }));
      if (args.decision === 'reject' && args.reason) rows.push(S.assistantAgentReason({ reason: args.reason }));
    }
    if (action.toolName === 'draft_agent' && args.request) rows.push(S.assistantAgentRequest({ text: args.request }));
    return rows;
  }

  /**
   * What the card lists: the names, numbers and amounts behind the change, never raw ids when the server named
   * them, and not the names its title already gives.
   */
  function actionDetails(action, title) {
    if (AGENT_TOOLS.has(action.toolName)) return agentDetails(action);
    const S = d.STR;
    const D = T.detail;
    const args = action.arguments || {};
    const p = action.preview || {};
    const named = Object.keys(p).length > 0;
    const rows = [];
    const add = (label, value) => { if (value !== undefined && value !== null && value !== '') rows.push(`${label}: ${value}`); };
    const name = (label, value) => { if (value && !title.includes(String(value))) add(label, value); };
    const money = v => (v === undefined || v === null ? '' : d.fmtEUR(v));
    const day = v => (v ? d.fmtDay(v) : '');
    name(D.client, p.client);
    name(D.quote, p.quote);
    name(D.invoice, p.invoice);
    name(D.payment, p.payment);
    name(D.payee, p.payee);
    add(D.booking, p.booking);
    name(D.contact, p.contact);
    add(D.channel, p.channel ? (T.channels[p.channel] || p.channel) : '');
    name(D.service, p.service);
    add(D.whenFrom, p.when_from);
    add(D.when, p.when);
    add(D.duration, p.duration_minutes ? d.fmtMinutes(p.duration_minutes) : '');
    if (action.toolName === 'create_client') CLIENT_FIELDS.forEach(field => (field === 'name' ? name : add)(T.fields[field], args[field]));
    (p.changes || []).forEach(c => rows.push(`${T.fields[c.field] || c.field}: ${c.from || '—'} → ${c.to || '—'}`));
    (args.items || []).forEach(item => rows.push(`${item.description} · ${item.quantity || 1} × ${d.fmtEUR(item.price_eur)}`));
    add(D.totalFrom, money(p.total_from_eur));
    add(D.total, money(p.total_eur));
    add(D.outstanding, money(p.outstanding_eur));
    add(D.price, money(p.price_eur));
    add(D.due, day(p.due_date || args.due_date));
    add(D.validUntil, day(p.valid_until || args.valid_until));
    const entity = STATUS_ENTITY[action.toolName];
    if (p.status_from && entity) add(D.statusFrom, d.statusLabel(entity, p.status_from) || p.status_from);
    if (p.status && action.toolName === 'mark_payment_paid') add(D.statusFrom, d.statusLabel('payment', p.status) || p.status);
    if (args.status && action.toolName !== 'create_booking') add(D.status, d.statusLabel(action.toolName.includes('quote') ? 'quote' : 'booking', String(args.status).toUpperCase()) || args.status);
    if (action.toolName !== 'create_client') add(D.notes, args.notes);
    if (!named) {
      if (args.client_id) rows.push(S.assistantClientRef({ id: args.client_id }));
      if (args.quote_id) rows.push(S.assistantQuoteRef({ id: args.quote_id }));
      if (args.invoice_id) rows.push(S.assistantInvoiceRef({ id: args.invoice_id }));
      if (args.booking_id) rows.push(S.assistantBookingRef({ id: args.booking_id }));
      if (args.start_at) rows.push(`${S.bookingsStart}: ${String(args.start_at).replace('T', ' ')}`);
      if (args.contact_name) rows.push(`${S.bookingsContactName}: ${args.contact_name}`);
    }
    return rows;
  }

  /** The record a confirmed change made or touched, as an agents-style subject the app can open. */
  function resultSubject(action) {
    if (action.status !== 'CONFIRMED') return null;
    const result = action.result || {};
    const type = result.type || RESULT_TYPES[action.toolName];
    const id = result.id || result.client?.id;
    return type && id ? { type, id } : null;
  }

  function actionLinks(action) {
    if (action.status !== 'CONFIRMED') return '';
    const result = action.result || {};
    const buttons = [];
    const subject = resultSubject(action);
    if (subject && d.canOpenSubject(subject) && T.open[subject.type]) {
      buttons.push(`<button class="btn btn--sm" type="button" data-assistant-open="${esc(`${subject.type}:${subject.id}`)}">${esc(T.open[subject.type])}</button>`);
    }
    if ((subject?.type === 'invoice' || subject?.type === 'quote') && d.hasModule(subject.type === 'invoice' ? 'invoices' : 'quotes')) {
      buttons.push(d.pdfButton(subject.id, subject.type === 'invoice' ? 'invoices' : 'quotes', true, d.STR.assistantDownloadPdf({ number: result.number || '' })));
    }
    if (d.hasModule('agents') && window.AgentsUI) {
      const ref = action.toolName === 'draft_agent' && result.agent_id ? `agent:${result.agent_id}` : action.toolName === 'run_agent' && result.run_id ? `run:${result.run_id}` : '';
      if (ref) buttons.push(`<button class="btn btn--sm" type="button" data-assistant-agent-ref="${esc(ref)}">${esc(ref.startsWith('agent:') ? d.STR.assistantOpenDraft : d.STR.assistantOpenRun)}</button>`);
    }
    return buttons.length ? `<div class="assistant__action-buttons">${buttons.join('')}</div>` : '';
  }

  /** Why a confirmed change failed, in the person's language when the dashboard knows the reason; the tool's own text is for the model. */
  function failureReason(action) {
    const code = action.result?.error || '';
    const known = key => { const value = d.STR[key]; return typeof value === 'string' && value !== `app.${key}` ? value : ''; };
    if (!code) return '';
    if (code === 'phone_taken') return known('clientPhoneTaken');
    if (code === 'invalid_email') return known('clientInvalidEmail');
    return known(`inboxErr_${code}`) || known(`bookingsErr_${code}`);
  }

  function actionCard(action) {
    const p = action.preview || {};
    const title = actionTitle(action);
    const details = actionDetails(action, title);
    const status = action.status;
    const note = status === 'EXPIRED' ? T.expiredHint : status === 'FAILED' ? T.failedHint({ reason: failureReason(action) }) : '';
    const message = action.toolName === 'reply_to_conversation' ? (p.text || action.arguments?.text || '') : '';
    return `<div class="assistant__action assistant__action--${esc(status.toLowerCase())}">
      <div class="assistant__action-head"><div><span class="assistant__action-label">${esc(d.STR.assistantProposedAction)}</span><strong>${esc(title)}</strong></div>
        <span class="pill ${STATUS_TONES[status] || ''}">${esc(statusLabel(status))}</span></div>
      ${details.length ? `<ul class="assistant__action-details">${details.map(row => `<li>${esc(row)}</li>`).join('')}</ul>` : ''}
      ${message ? `<blockquote class="assistant__quote">${esc(message)}</blockquote>` : ''}
      ${p.window_open === false && status === 'PENDING' ? `<p class="hint hint--warn">${esc(T.windowClosed)}</p>` : ''}
      ${note ? `<p class="hint${status === 'FAILED' ? ' hint--bad' : ''}">${esc(note)}</p>` : ''}
      ${status === 'PENDING' ? `<div class="assistant__action-buttons">
        <button class="btn btn--sm btn--ghost" type="button" data-assistant-cancel="${esc(action.id)}"${ui.busy ? ' disabled' : ''}>${esc(d.STR.assistantCancel)}</button>
        <button class="btn btn--sm btn--primary" type="button" data-assistant-confirm="${esc(action.id)}"${ui.busy ? ' disabled' : ''}>${esc(d.STR.assistantConfirm)}</button></div>` : ''}
      ${actionLinks(action)}
    </div>`;
  }

  // ── Messages ──

  function botMessage(m) {
    const sources = (m.sources || []).map(s => d.labels[s] || s);
    return `<div class="assistant__turn">
      <div class="chat__msg chat__msg--bot chat__msg--rich">${markdown(m.content)}</div>
      <div class="assistant__meta">${sources.length ? `<span>${esc(T.sources({ list: sources.join(' · ') }))}</span>` : ''}
        <button class="assistant__copy" type="button" data-assistant-copy="${esc(m.id)}">${esc(T.copy)}</button></div>
    </div>`;
  }

  function errorMessage(m, last) {
    const text = T[`error_${m.error}`] || d.STR.assistantError;
    const retryable = last && m.error !== 'budget_exceeded';
    return `<div class="assistant__turn">
      <div class="chat__msg chat__msg--bot assistant__failed" role="alert">${esc(text)}</div>
      ${retryable ? `<div class="assistant__meta"><button class="btn btn--sm" type="button" data-assistant-retry${ui.busy ? ' disabled' : ''}>${esc(T.retry)}</button></div>` : ''}
    </div>`;
  }

  function messageHtml(m, index, all) {
    if (m.error) return errorMessage(m, index === all.length - 1);
    const text = m.content ? (m.role === 'user' ? `<div class="chat__msg chat__msg--user">${esc(m.content)}</div>` : botMessage(m)) : '';
    return m.action ? text + actionCard(m.action) : text;
  }

  /** The questions worth asking first, for what the assistant can use here. */
  function starters() {
    const s = ui.settings;
    const usable = new Set((s?.areas || []).filter(a => !(s?.disabledModules || []).includes(a)));
    const list = [T.starters.overview];
    if (usable.has('invoices')) list.push(T.starters.overdue);
    if (usable.has('conversations')) list.push(T.starters.waiting);
    if (usable.has('bookings')) list.push(T.starters.tomorrow);
    if (usable.has('quotes')) list.push(T.starters.quotes);
    if (usable.has('payments')) list.push(T.starters.bills);
    if (usable.has('services')) list.push(T.starters.unbilled);
    if (usable.has('clients') && s?.allowChanges !== false) list.push(T.starters.client);
    return list.slice(0, 6);
  }

  function welcome() {
    return `<div class="assistant__welcome">
      <p class="assistant__welcome-title">${esc(T.welcomeTitle)}</p>
      <p>${esc(T.welcomeDesc)}</p>
      <div class="assistant__starters">${starters().map(q => `<button class="btn btn--sm" type="button" data-assistant-starter="${esc(q)}"${ui.busy ? ' disabled' : ''}>${esc(q)}</button>`).join('')}</div>
    </div>`;
  }

  function threadRow(t) {
    const active = ui.current?.thread.id === t.id;
    const pending = t.pending ? `<span class="pill pill--warn assistant__pending" aria-label="${esc(T.pendingAria({ n: t.pending }))}">${t.pending}</span>` : '';
    return `<button class="assistant__thread${active ? ' is-active' : ''}" type="button" data-assistant-thread="${esc(t.id)}"${active ? ' aria-current="true"' : ''}>
      <strong>${esc(t.title)}</strong><span>${esc(d.relTime(t.updatedAt))}</span>${pending}</button>`;
  }

  // ── Page ──

  function render(root) {
    const current = ui.current;
    const s = ui.settings;
    // The box stays while a search is on, so a list that shrank below it can still be cleared.
    const searchable = ui.threads.length > 4 || !!ui.query;
    const threads = ui.query ? ui.threads.filter(t => fold(t.title).includes(fold(ui.query))) : ui.threads;
    const messages = current?.messages || [];
    const settingsButton = `<div class="actions"><button class="btn btn--sm" type="button" id="assistant-settings">${esc(T.settings)}</button></div>`;
    const head = current ? `<div class="assistant__head">
        <p class="assistant__title" title="${esc(current.thread.title)}">${esc(current.thread.title)}</p>
        <div class="actions">${s && !s.allowChanges ? `<span class="pill" title="${esc(T.readOnlyHint)}">${esc(T.readOnly)}</span>` : ''}
          <button class="btn btn--sm btn--ghost" type="button" id="assistant-rename">${esc(T.rename)}</button>
          <button class="btn btn--sm btn--ghost" type="button" id="assistant-delete">${esc(T.remove)}</button></div>
      </div>` : (s && !s.allowChanges ? `<div class="assistant__head"><span></span><span class="pill" title="${esc(T.readOnlyHint)}">${esc(T.readOnly)}</span></div>` : '');
    const log = messages.length
      ? `${current.hasMore ? `<button class="btn btn--sm btn--ghost assistant__older" type="button" id="assistant-older"${ui.older ? ' disabled' : ''}>${esc(T.earlier)}</button>` : ''}${messages.map(messageHtml).join('')}`
      : welcome();
    root.innerHTML = `${d.hero(d.labels['ai-assistant'], d.STR.assistantDesc, settingsButton)}
      <div class="assistant">
        <aside class="assistant__sidebar">
          <button class="btn btn--primary" id="assistant-new" type="button"${ui.busy ? ' disabled' : ''}>${esc(d.STR.assistantNewThread)}</button>
          ${searchable ? `<input class="inp assistant__search" id="assistant-search" type="search" placeholder="${esc(T.search)}" aria-label="${esc(T.search)}" value="${esc(ui.query)}" />` : ''}
          <div class="assistant__threads">${threads.map(threadRow).join('') || `<p class="chat__empty">${esc(ui.query ? T.searchEmpty : d.STR.assistantNoThreads)}</p>`}</div>
        </aside>
        <div class="panel assistant__chat">
          ${head}
          <div class="chat__log assistant__log" id="assistant-log" role="log" aria-live="polite">${log}${ui.busy ? `<div class="chat__msg chat__msg--bot chat__typing">${esc(d.STR.typing)}</div>` : ''}</div>
          <form class="chat__form" id="assistant-form">
            <textarea class="inp chat__input assistant__input" id="assistant-input" rows="1" maxlength="${MAX_MESSAGE}" aria-label="${esc(T.composerLabel)}" placeholder="${esc(d.STR.assistantPlaceholder)}"${ui.busy ? ' disabled' : ''}>${esc(ui.draft)}</textarea>
            <button class="btn btn--primary" type="submit"${ui.busy ? ' disabled' : ''}>${esc(d.STR.send)}</button>
          </form>
          <p class="assistant__composer-hint"><span>${esc(T.composerHint)}</span><span id="assistant-count"></span></p>
        </div>
      </div>`;
    bind(root);
  }

  function bind(root) {
    const $ = sel => root.querySelector(sel);
    const $$ = sel => [...root.querySelectorAll(sel)];
    $('#assistant-settings').addEventListener('click', openSettings);
    $('#assistant-new').addEventListener('click', async () => {
      if (ui.busy) return;
      try { await newThread(); } catch { return d.toast(d.STR.assistantError); }
      ui.draft = '';
      repaint();
      focusComposer();
    });
    $('#assistant-search')?.addEventListener('input', e => {
      ui.query = e.target.value;
      const list = $('.assistant__threads');
      const threads = ui.query ? ui.threads.filter(t => fold(t.title).includes(fold(ui.query))) : ui.threads;
      list.innerHTML = threads.map(threadRow).join('') || `<p class="chat__empty">${esc(T.searchEmpty)}</p>`;
      bindThreads(list);
    });
    bindThreads(root);
    $('#assistant-rename')?.addEventListener('click', renameThread);
    $('#assistant-delete')?.addEventListener('click', removeThread);
    $('#assistant-older')?.addEventListener('click', loadOlder);
    $$('[data-assistant-starter]').forEach(b => b.addEventListener('click', () => send(b.dataset.assistantStarter)));
    $$('[data-assistant-confirm]').forEach(b => b.addEventListener('click', () => decide(b.dataset.assistantConfirm, 'confirm')));
    $$('[data-assistant-cancel]').forEach(b => b.addEventListener('click', () => decide(b.dataset.assistantCancel, 'cancel')));
    $$('[data-assistant-retry]').forEach(b => b.addEventListener('click', retry));
    $$('[data-assistant-copy]').forEach(b => b.addEventListener('click', () => {
      const message = ui.current?.messages.find(m => m.id === b.dataset.assistantCopy);
      if (message) copy(message.content);
    }));
    $$('[data-assistant-open]').forEach(b => b.addEventListener('click', () => {
      const [type, id] = b.dataset.assistantOpen.split(':');
      d.openSubject({ type, id });
    }));
    $$('[data-assistant-agent-ref]').forEach(b => b.addEventListener('click', () => window.AgentsUI.openRef(b.dataset.assistantAgentRef)));
    d.wirePdfButtons(root);

    const log = $('#assistant-log');
    log.scrollTop = ui.keepScroll === null ? log.scrollHeight : log.scrollHeight - ui.keepScroll;
    ui.keepScroll = null;

    const composer = $('#assistant-input');
    const count = $('#assistant-count');
    const resize = () => {
      composer.style.height = 'auto';
      composer.style.height = `${Math.min(composer.scrollHeight, 140)}px`;
      count.textContent = composer.value.length > MAX_MESSAGE - 500 ? T.count({ n: composer.value.length, max: MAX_MESSAGE }) : '';
    };
    composer.addEventListener('input', () => { ui.draft = composer.value; resize(); });
    composer.addEventListener('keydown', e => {
      if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
        e.preventDefault();
        $('#assistant-form').requestSubmit();
      }
    });
    resize();
    $('#assistant-form').addEventListener('submit', e => {
      e.preventDefault();
      send(composer.value);
    });
  }

  function bindThreads(scope) {
    scope.querySelectorAll('[data-assistant-thread]').forEach(b => b.addEventListener('click', () => openThread(b.dataset.assistantThread)));
  }

  function focusComposer() {
    requestAnimationFrame(() => document.getElementById('assistant-input')?.focus());
  }

  window.AssistantUI = {
    init(deps) { d = deps; },
    load,
    render,
    markdown,
  };
})();

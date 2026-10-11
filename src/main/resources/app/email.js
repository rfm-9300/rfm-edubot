/* The Email page (`email` module): the company's Gmail threads, read with the team and answered in their
   thread, and for each received email the dashboard actions it calls for (add the sender as a client,
   prepare a quote, mark an invoice paid, book them, register a supplier's bill, a follow-up task).
   Actions open the dashboard's own forms, started from what the email says; the page records what
   they led to. app.js mounts it as window.EmailUI and gives it its forms through init(). */
(() => {
  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
  const LIST_POLL_MS = 30000;
  const SEARCH_DELAY_MS = 300;
  const MAX_REPLY = 20000;
  const FILTERS = ['all', 'unread', 'clients', 'new'];
  const API = '/app/api/email/inbox';
  const ICONS = {
    client: '<svg viewBox="0 0 16 16" aria-hidden="true" focusable="false"><circle cx="8" cy="5.5" r="2.7"/><path d="M2.8 13.5c.6-2.6 2.7-4 5.2-4s4.6 1.4 5.2 4"/></svg>',
    quote: '<svg viewBox="0 0 16 16" aria-hidden="true" focusable="false"><path d="M4 1.5h5l3.5 3.5v9.5H4z"/><path d="M9 1.5V5h3.5M6 8.5h4M6 11h4"/></svg>',
    invoice: '<svg viewBox="0 0 16 16" aria-hidden="true" focusable="false"><path d="M3.5 1.5h9v13l-2-1.2-2.5 1.2-2.5-1.2-2 1.2z"/><path d="m5.8 8 1.6 1.6 3-3.2"/></svg>',
    booking: '<svg viewBox="0 0 16 16" aria-hidden="true" focusable="false"><rect x="2" y="3" width="12" height="11" rx="1.5"/><path d="M2 6.5h12M5.5 1.5v3M10.5 1.5v3"/></svg>',
    bill: '<svg viewBox="0 0 16 16" aria-hidden="true" focusable="false"><rect x="1.5" y="4" width="13" height="8.5" rx="1.5"/><path d="M1.5 7h13M4.5 10h2.5"/></svg>',
    task: '<svg viewBox="0 0 16 16" aria-hidden="true" focusable="false"><rect x="3" y="2.5" width="10" height="12" rx="1.5"/><path d="m5.6 8.6 1.6 1.6 3.2-3.4"/></svg>',
    spark: '<svg viewBox="0 0 16 16" aria-hidden="true" focusable="false"><path d="M8 1.8 9.4 6.6 14.2 8 9.4 9.4 8 14.2 6.6 9.4 1.8 8 6.6 6.6z"/></svg>',
    file: '<svg viewBox="0 0 16 16" aria-hidden="true" focusable="false"><path d="M4 1.5h5l3.5 3.5v9.5H4z"/><path d="M9 1.5V5h3.5"/></svg>',
    send: '<svg viewBox="0 0 16 16" aria-hidden="true" focusable="false"><path d="M2.5 8 13.5 2.5 10.5 13.5 8 9Z"/></svg>',
    back: '<svg viewBox="0 0 16 16" aria-hidden="true" focusable="false"><path d="M10 3 5 8l5 5"/></svg>',
    close: '<svg viewBox="0 0 16 16" aria-hidden="true" focusable="false"><path d="M4 4l8 8M12 4l-8 8"/></svg>',
  };
  const ACTION_ICONS = {
    'client.create': 'client', 'client.update': 'client', 'quote.create': 'quote', 'quote.accept': 'quote',
    'invoice.paid': 'invoice', 'booking.create': 'booking', 'bill.create': 'bill', 'task.create': 'task',
  };
  const ACTION_MODULES = {
    'client.create': 'clients', 'client.update': 'clients', 'quote.create': 'quotes', 'quote.accept': 'quotes',
    'invoice.paid': 'invoices', 'booking.create': 'bookings', 'bill.create': 'payments', 'task.create': 'agents',
  };

  let d = null;
  let root = null;
  let pollTimer = null;
  let searchTimer = null;
  let listSeq = 0;
  let threadSeq = 0;
  const data = { status: null, threads: [], thread: null };
  const ui = {
    filter: 'all', account: '', query: '', selected: null, selectedId: null, focusId: null,
    threadError: false, analyzing: null, aiError: null, autoTried: new Set(), drafts: {}, requestIds: {}, sending: false, busy: null,
  };

  const tr = (key, params) => I18N.t(`app.email.${key}`, params);
  const trOr = (key, fallback, params) => {
    const text = tr(key, params);
    return typeof text === 'string' && text !== `app.email.${key}` ? text : fallback;
  };
  const esc = s => d.escapeHTML(s == null ? '' : String(s));
  const icon = name => `<span class="inbox-ico">${ICONS[name] || ''}</span>`;
  const actionKey = type => String(type).replace('.', '_');
  const euros = cents => d.fmtEUR((Number(cents) || 0) / 100);
  const initials = name => {
    const words = String(name || '').replace(/<.*?>/g, '').split(/[\s@._-]+/).filter(Boolean);
    return (words.slice(0, 2).map(w => w[0]).join('') || '?').toUpperCase();
  };
  const setHTML = (el, html) => { if (el && el._html !== html) { el._html = html; el.innerHTML = html; } };

  // ── loading ──────────────────────────────────────────────────────────
  function listQuery() {
    const query = new URLSearchParams();
    if (ui.filter !== 'all') query.set('filter', ui.filter);
    if (ui.account) query.set('account', ui.account);
    if (ui.query) query.set('q', ui.query);
    const text = query.toString();
    return text ? `?${text}` : '';
  }

  async function load() {
    ui.query = (d.state.search || '').trim();
    const [status, threads] = await Promise.all([d.api(API), d.api(`${API}/threads${listQuery()}`)]);
    data.status = status;
    data.threads = threads;
    if (ui.account && !(status.accounts || []).some(a => a.id === ui.account)) ui.account = '';
  }

  async function reloadList({ quiet = true } = {}) {
    const seq = ++listSeq;
    try {
      const [threads, status] = await Promise.all([d.api(`${API}/threads${listQuery()}`), d.api(API)]);
      if (seq !== listSeq) return;
      data.threads = threads;
      data.status = status;
      renderRows();
      d.renderNav();
      const row = data.threads.find(r => r.key === ui.selected);
      if (row && data.thread && row.id !== data.thread.messages[data.thread.messages.length - 1]?.id) openThread(row.id, row.key, { quiet: true });
    } catch (err) {
      if (!quiet && err?.message !== 'unauthorized') d.toast(d.STR.loadFailed);
    }
  }

  function schedulePoll() {
    clearTimeout(pollTimer);
    pollTimer = setTimeout(async () => {
      if (d.state.active !== 'email') return;
      if (document.visibilityState === 'visible') await reloadList();
      schedulePoll();
    }, LIST_POLL_MS);
  }

  function stop() {
    clearTimeout(pollTimer);
    clearTimeout(searchTimer);
    pollTimer = null;
  }

  // ── page ─────────────────────────────────────────────────────────────
  function render(r) {
    root = r;
    const query = (d.state.search || '').trim();
    const page = $('.email-page', r);
    if (page && page.dataset.locale === d.uiLocale()) {
      if (query !== ui.query) {
        ui.query = query;
        clearTimeout(searchTimer);
        searchTimer = setTimeout(() => reloadList({ quiet: false }), SEARCH_DELAY_MS);
      }
      renderRows();
      if (ui.focusId) openFocused();
      return;
    }
    mount();
  }

  function mount() {
    const status = data.status || {};
    const accounts = status.accounts || [];
    const picker = accounts.length > 1
      ? `<div class="inbox__list-top"><select class="sel inbox__asset" id="em-account" aria-label="${esc(tr('accountAria'))}">
          <option value="">${esc(tr('accountAll'))}</option>
          ${accounts.map(a => `<option value="${esc(a.id)}" ${a.id === ui.account ? 'selected' : ''}>${esc(a.accountEmail)}</option>`).join('')}
        </select></div>`
      : '';
    root.innerHTML = `<div class="email-page" data-locale="${esc(d.uiLocale())}">
      ${d.hero(d.labels.email, tr('desc'))}
      <div class="email-setup" id="em-setup">${setupHtml(status)}</div>
      <div class="inbox email-inbox" data-thread-open="${ui.selected ? 'true' : 'false'}">
        <aside class="inbox__list" aria-label="${esc(tr('listAria'))}">
          <div class="inbox__list-head">${picker}<div class="inbox__filters" id="em-filters" role="group" aria-label="${esc(tr('filterAria'))}"></div></div>
          <ul class="inbox__rows" id="em-rows" role="list"></ul>
        </aside>
        <section class="inbox__thread" id="em-thread" aria-label="${esc(tr('threadAria'))}"></section>
        <p class="visually-hidden" id="em-announce" aria-live="polite"></p>
      </div>
    </div>`;
    renderRows();
    wirePage();
    if (ui.focusId) openFocused();
    else if (ui.selected && data.thread) {
      renderThread();
      openThread(ui.selectedId, ui.selected, { quiet: true });
    } else if (!d.isNarrow() && data.threads[0]) openThread(data.threads[0].id, data.threads[0].key, { quiet: true });
    else renderPlaceholder();
    schedulePoll();
  }

  function wirePage() {
    $('#em-account', root)?.addEventListener('change', e => {
      ui.account = e.target.value;
      reloadList({ quiet: false });
    });
    $('#em-filters', root).addEventListener('click', e => {
      const chip = e.target.closest('[data-em-filter]');
      if (!chip) return;
      ui.filter = chip.dataset.emFilter;
      reloadList({ quiet: false });
    });
    $('#em-rows', root).addEventListener('click', e => {
      const row = e.target.closest('[data-em-thread]');
      if (row) return openThread(row.dataset.emId, row.dataset.emThread, { focus: true });
      if (e.target.closest('[data-em-filter-all]')) { ui.filter = 'all'; reloadList({ quiet: false }); }
    });
    $('#em-setup', root).addEventListener('click', e => {
      const button = e.target.closest('[data-em-setup]');
      if (button) setupAction(button.dataset.emSetup, button.dataset.emAccount, button);
    });
    const pane = $('#em-thread', root);
    pane.addEventListener('click', onThreadClick);
    pane.addEventListener('submit', e => {
      if (e.target.id !== 'em-reply-form') return;
      e.preventDefault();
      sendReply();
    });
    pane.addEventListener('input', e => {
      if (e.target.id !== 'em-reply') return;
      ui.drafts[ui.selected] = e.target.value;
      autosize(e.target);
      updateComposer();
    });
    pane.addEventListener('keydown', e => {
      if (e.target.id === 'em-reply' && e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
        e.preventDefault();
        sendReply();
      }
    });
  }

  // What stops the page from showing new mail, with the one thing that fixes it.
  function setupHtml(status) {
    if (!status || !Object.keys(status).length) return '';
    const notice = (tone, title, desc, actions = '') => `<div class="notice notice--${tone}" role="status">
      <div class="notice__text"><strong>${esc(title)}</strong><span>${esc(desc)}</span></div>${actions ? `<div class="notice__actions">${actions}</div>` : ''}</div>`;
    const button = (action, label, account = '', primary = true) => `<button class="btn btn--sm${primary ? ' btn--primary' : ''}" type="button" data-em-setup="${action}" data-em-account="${esc(account)}">${esc(label)}</button>`;
    const accounts = status.accounts || [];
    const admin = status.canConnect;
    if (!status.configured) return notice('warn', tr('setup.notConfiguredTitle'), tr('setup.notConfiguredDesc'));
    if (!accounts.length) {
      return notice('info', tr('setup.noAccountTitle'), admin ? tr('setup.noAccountDesc') : `${tr('setup.noAccountDesc')} ${tr('setup.noAccountMember')}`, admin ? button('connect', tr('setup.connect')) : '');
    }
    const broken = accounts.find(a => a.status !== 'ACTIVE');
    if (broken) {
      return notice('warn', tr('setup.reconnectTitle', { account: broken.accountEmail }), admin ? tr('setup.reconnectDesc') : `${tr('setup.reconnectDesc')} ${tr('setup.adminFixes')}`, admin ? button('reconnect', tr('setup.reconnect'), broken.accountEmail) : '');
    }
    if (!status.inboxAvailable) return notice('info', tr('setup.readingUnavailableTitle'), tr('setup.readingUnavailableDesc'));
    const reading = accounts.filter(a => a.inboxSync);
    if (!reading.length) {
      const account = accounts.find(a => a.isDefault) || accounts[0];
      return notice('info', tr('setup.readingOffTitle'), `${tr('setup.readingOffDesc', { account: account.accountEmail })}${admin ? '' : ` ${tr('setup.adminFixes')}`}`, admin ? button('read', tr('setup.turnOn'), account.id) : '');
    }
    const failing = reading.find(a => a.inboxError);
    if (failing) {
      const desc = d.googleTextOr(`inbox.errors.${failing.inboxError}`, d.googleTextOr('inbox.errors.generic', ''));
      return notice('warn', tr('setup.readingPausedTitle', { account: failing.accountEmail }), desc, admin && failing.inboxError === 'missing_scope' ? button('consent', tr('setup.allowReading'), failing.accountEmail) : '');
    }
    if (!reading.some(a => a.inboxSyncedAt)) return notice('info', tr('setup.firstSyncTitle'), tr('setup.firstSyncDesc'));
    return '';
  }

  async function setupAction(action, account, button) {
    const status = data.status || {};
    if (action === 'connect') return d.connectGoogle(null, { inbox: !!status.inboxAvailable });
    if (action === 'reconnect') {
      const match = (status.accounts || []).find(a => a.accountEmail === account);
      return d.connectGoogle(account, { inbox: !!(match?.inboxSync && status.inboxAvailable) });
    }
    if (action === 'consent') return d.connectGoogle(account, { inbox: true });
    if (action === 'read') {
      const match = (status.accounts || []).find(a => a.id === account);
      if (!match) return;
      if (!match.canRead) return d.connectGoogle(match.accountEmail, { inbox: true });
      button.disabled = true;
      try {
        await d.api(`/app/api/integrations/${encodeURIComponent(match.id)}`, { method: 'PATCH', body: JSON.stringify({ inboxSync: true }) });
        d.toast(d.googleTextOr('inbox.turnedOn', tr('setup.turnOn')));
        await refresh();
      } catch (err) {
        button.disabled = false;
        if (err?.message !== 'unauthorized') d.toast(d.googleTextOr(`inbox.patchErrors.${err?.code || ''}`, d.googleTextOr('inbox.failed', d.STR.loadFailed)));
      }
    }
  }

  /** Reloads everything, e.g. after Gmail was connected from this page. */
  async function refresh() {
    try { await load(); } catch { return d.toast(d.STR.loadFailed); }
    if (d.state.active === 'email' && root) {
      setHTML($('#em-setup', root), setupHtml(data.status));
      renderRows();
      d.renderNav();
    }
  }

  // ── list ─────────────────────────────────────────────────────────────
  function renderRows() {
    const list = $('#em-rows', root);
    if (!list) return;
    setHTML($('#em-filters', root), FILTERS.map(f => {
      const on = ui.filter === f;
      return `<button type="button" class="chip${on ? ' is-on' : ''}" data-em-filter="${f}" aria-pressed="${on}">${esc(tr(`filter.${f}`))}${f === 'unread' && data.status?.unread ? `<span class="chip__count">${data.status.unread}</span>` : ''}</button>`;
    }).join(''));
    const focused = document.activeElement?.closest?.('[data-em-thread]')?.dataset.emThread;
    setHTML(list, data.threads.length ? data.threads.map(rowHtml).join('') : `<li class="inbox__empty">${listEmptyHtml()}</li>`);
    if (focused) $(`[data-em-thread="${CSS.escape(focused)}"]`, list)?.focus();
  }

  function listEmptyHtml() {
    const empty = (title, desc = '', action = '') => `<div class="empty"><p class="empty__title">${esc(title)}</p>${desc ? `<p class="empty__desc">${esc(desc)}</p>` : ''}${action}</div>`;
    if (ui.query) return empty(tr('searchEmpty'));
    if (ui.filter !== 'all') return empty(tr(`filterEmpty.${ui.filter}`), '', `<button class="btn btn--sm btn--ghost" type="button" data-em-filter-all>${esc(tr('showAll'))}</button>`);
    return empty(tr('emptyTitle'), tr('emptyDesc'));
  }

  function rowHtml(row) {
    const active = row.key === ui.selected;
    const unread = row.unread > 0;
    const name = row.clientName || row.name;
    const out = row.direction === 'OUTBOUND';
    const intent = row.intent ? `<span class="email-row__intent">${esc(tr(`intent.${row.intent}`))}</span>` : '';
    const count = row.count > 1 ? `<span class="email-row__count" aria-hidden="true">${row.count}</span>` : '';
    const badge = unread ? `<span class="inbox-row__badge">${row.unread > 99 ? '99+' : row.unread}</span>` : '';
    const label = [name, row.subject, unread ? tr('unreadAria', { n: row.unread }) : '', row.intent ? tr(`intent.${row.intent}`) : '', row.snippet].filter(Boolean).join(' · ');
    return `<li><button type="button" class="inbox-row email-row${active ? ' is-active' : ''}${unread ? ' is-unread' : ''}${row.automated ? ' is-automated' : ''}" data-em-thread="${esc(row.key)}" data-em-id="${esc(row.id)}" aria-current="${active ? 'true' : 'false'}" aria-label="${esc(label)}">
      <span class="inbox-avatar" aria-hidden="true">${esc(initials(name))}</span>
      <span class="inbox-row__main" aria-hidden="true">
        <span class="inbox-row__top"><span class="email-row__who"><span class="inbox-row__name">${esc(name)}</span>${count}</span><time class="inbox-row__time" datetime="${esc(row.date)}">${esc(d.relTime(row.date))}</time></span>
        <span class="email-row__subject">${esc(row.subject || tr('noSubject'))}</span>
        <span class="inbox-row__bottom"><span class="inbox-row__preview">${out ? `<span class="inbox-row__who">${esc(tr('youPrefix'))}</span>` : ''}${esc(row.snippet)}</span>${intent}${badge}</span>
      </span>
    </button></li>`;
  }

  // ── thread ───────────────────────────────────────────────────────────
  /** Opens the thread of the email [id] next time the page shows, e.g. from an automation's record link. */
  function focus(id) {
    ui.focusId = id;
  }

  function openFocused() {
    const id = ui.focusId;
    ui.focusId = null;
    openThread(id, null, { focus: true });
  }

  async function openThread(id, key, { focus = false, quiet = false } = {}) {
    if (!id) return;
    const switching = key == null || key !== ui.selected;
    ui.selectedId = id;
    if (key) ui.selected = key;
    const seq = ++threadSeq;
    if (switching) {
      ui.threadError = false;
      ui.aiError = null;
      if (!quiet || !data.thread) renderThreadLoading();
    }
    markActiveRow();
    let thread;
    try {
      thread = await d.api(`${API}/threads/${encodeURIComponent(id)}`);
    } catch (err) {
      if (seq !== threadSeq || err?.message === 'unauthorized') return;
      ui.threadError = true;
      return renderThreadError();
    }
    if (seq !== threadSeq) return;
    data.thread = thread;
    ui.selected = thread.key;
    markActiveRow();
    renderThread();
    if (focus && d.isNarrow()) $('#em-thread-name', root)?.focus();
    if (thread.messages.some(m => m.unread) && document.visibilityState === 'visible') markRead(thread);
    if (thread.autoAnalyze && thread.focusId && !ui.autoTried.has(thread.focusId)) {
      ui.autoTried.add(thread.focusId);
      analyze(false);
    }
  }

  function markActiveRow() {
    $('.email-inbox', root)?.setAttribute('data-thread-open', ui.selected ? 'true' : 'false');
    $$('[data-em-thread]', root).forEach(b => {
      const on = b.dataset.emThread === ui.selected;
      b.classList.toggle('is-active', on);
      b.setAttribute('aria-current', on ? 'true' : 'false');
    });
  }

  function closeThread() {
    const key = ui.selected;
    ui.selected = null;
    ui.selectedId = null;
    data.thread = null;
    threadSeq++;
    markActiveRow();
    renderPlaceholder();
    if (key) $(`[data-em-thread="${CSS.escape(key)}"]`, root)?.focus();
  }

  async function markRead(thread) {
    const row = data.threads.find(r => r.key === thread.key);
    if (row) row.unread = 0;
    thread.messages.forEach(m => { m.unread = false; });
    renderRows();
    try {
      const res = await d.api(`${API}/threads/${encodeURIComponent(thread.messages[thread.messages.length - 1].id)}/read`, { method: 'POST' });
      if (data.status) data.status.unread = res.unread;
      renderRows();
      d.renderNav();
    } catch { /* the next list refresh shows the real state */ }
  }

  async function markUnread(button) {
    const thread = data.thread;
    if (!thread) return;
    button.disabled = true;
    try {
      const res = await d.api(`${API}/threads/${encodeURIComponent(thread.messages[thread.messages.length - 1].id)}/unread`, { method: 'POST' });
      if (data.status) data.status.unread = res.unread;
      d.toast(tr('markedUnread'));
      d.renderNav();
      if (d.isNarrow()) closeThread();
      await reloadList();
    } catch (err) {
      button.disabled = false;
      if (err?.message !== 'unauthorized') d.toast(d.STR.loadFailed);
    }
  }

  function renderPlaceholder() {
    const pane = $('#em-thread', root);
    if (!pane) return;
    pane._html = '';
    pane.innerHTML = `<div class="thread-empty empty"><p class="empty__title">${esc(tr('noThread'))}</p><p class="empty__desc">${esc(tr('noThreadDesc'))}</p></div>`;
  }

  function renderThreadLoading() {
    const pane = $('#em-thread', root);
    if (!pane) return;
    pane._html = '';
    pane.innerHTML = '<div class="thread-log"><div class="thread-skeleton" aria-hidden="true"><span></span><span></span><span></span></div></div>';
  }

  function renderThreadError() {
    const pane = $('#em-thread', root);
    if (!pane) return;
    pane._html = '';
    pane.innerHTML = `<div class="thread-empty empty"><p class="empty__title">${esc(tr('threadFailed'))}</p><button type="button" class="btn btn--sm" data-em-retry>${esc(tr('tryAgain'))}</button></div>`;
  }

  function counterpart(thread) {
    const received = [...thread.messages].reverse().find(m => m.direction === 'INBOUND');
    if (received) return { name: received.fromName || received.from, address: received.from };
    const last = thread.messages[thread.messages.length - 1];
    const to = (last?.to || [])[0] || '';
    return { name: to, address: to };
  }

  function renderThread() {
    const pane = $('#em-thread', root);
    const thread = data.thread;
    if (!pane || !thread) return;
    const who = counterpart(thread);
    const name = thread.client?.name || who.name;
    const clientButton = thread.client && d.hasModule('clients')
      ? `<button type="button" class="btn btn--sm btn--ghost" data-em-client>${icon('client')}<span>${esc(thread.client.name)}</span></button>`
      : '';
    const hasReceived = thread.messages.some(m => m.direction === 'INBOUND');
    const sub = [who.address !== name ? who.address : '', thread.account ? tr('inAccount', { account: thread.account }) : ''].filter(Boolean).join(' · ');
    const scroll = $('#em-log', pane)?.scrollTop || 0;
    // A reading or a new message can land while someone types: keep their place in the reply.
    const typing = document.activeElement?.id === 'em-reply' ? { start: document.activeElement.selectionStart, end: document.activeElement.selectionEnd } : null;
    pane.innerHTML = `
      <header class="thread-head">
        <button type="button" class="iconbtn thread-head__back" data-em-back aria-label="${esc(tr('back'))}">${icon('back')}</button>
        <span class="inbox-avatar inbox-avatar--lg" aria-hidden="true">${esc(initials(name))}</span>
        <div class="thread-head__who"><h2 class="thread-head__name" id="em-thread-name" tabindex="-1">${esc(thread.subject || tr('noSubject'))}</h2><span class="thread-head__sub">${esc([name, sub].filter(Boolean).join(' · '))}</span></div>
        <div class="thread-head__tools">${clientButton}${hasReceived ? `<button type="button" class="btn btn--sm btn--ghost" data-em-unread>${esc(tr('markUnread'))}</button>` : ''}</div>
      </header>
      <div class="thread-log email-thread" id="em-log" tabindex="0" aria-label="${esc(tr('logAria', { name }))}">
        ${actionsHtml(thread)}
        ${messagesHtml(thread)}
      </div>
      <footer class="composer email-composer" id="em-composer">${composerHtml(thread)}</footer>`;
    pane._html = '';
    const log = $('#em-log', pane);
    if (scroll && log) log.scrollTop = scroll;
    const input = $('#em-reply', pane);
    if (input) {
      autosize(input);
      if (typing) {
        input.focus();
        input.setSelectionRange(typing.start, typing.end);
      }
    }
    updateComposer();
  }

  function messagesHtml(thread) {
    const last = thread.messages.length - 1;
    return thread.messages.map((m, i) => {
      const out = m.direction === 'OUTBOUND';
      const fromLine = `<strong>${esc(m.fromName || m.from)}</strong>${m.fromName ? ` <span class="email-msg__addr">${esc(m.from)}</span>` : ''}`;
      const meta = [
        (m.to || []).length ? tr('to', { to: m.to.join(', ') }) : '',
        (m.cc || []).length ? tr('cc', { cc: m.cc.join(', ') }) : '',
        !out && m.replyTo ? tr('replyToNote', { address: m.replyTo }) : '',
        out && m.sentByType === 'USER' && m.sentBy ? tr('sentBy', { name: m.sentBy }) : '',
        out && m.sentByType === 'AGENT' ? tr('sentByAutomation', { name: m.sentBy || '' }) : '',
      ].filter(Boolean).join(' · ');
      const tags = [
        out ? `<span class="pill pill--info">${esc(tr('sent'))}</span>` : '',
        m.automated ? `<span class="pill" title="${esc(tr('automatedHint'))}">${esc(tr('automated'))}</span>` : '',
        m.unread ? `<span class="pill pill--accent">${esc(tr('new'))}</span>` : '',
      ].join('');
      const body = m.bodyPurged ? `<p class="hint">${esc(tr('purged'))}</p>` : `<div class="email-msg__body">${esc(m.body ?? m.snippet ?? '')}</div>`;
      const files = (m.attachments || []).length ? `<p class="email-msg__files">${icon('file')}<span>${esc(m.attachments.join(', '))}</span></p>` : '';
      return `<details class="email-msg${out ? ' email-msg--out' : ''}" data-message="${esc(m.id)}" ${i === last || m.unread ? 'open' : ''}>
        <summary class="email-msg__head">
          <span class="email-msg__from">${fromLine}</span>
          <span class="email-msg__side">${tags}<time class="email-msg__date" datetime="${esc(m.date)}" title="${esc(d.fmtDate(m.date))}">${esc(d.fmtDate(m.date))}</time></span>
          <span class="email-msg__peek">${esc(m.snippet || '')}</span>
        </summary>
        ${meta ? `<p class="email-msg__meta">${esc(meta)}</p>` : ''}
        ${body}
        ${files}
      </details>`;
    }).join('');
  }

  // ── what the email calls for ─────────────────────────────────────────
  function actionsHtml(thread) {
    if (!thread.focusId) return '';
    const insights = thread.insights;
    const analyzing = ui.analyzing === thread.focusId;
    const intent = insights ? `<span class="pill pill--info">${esc(tr(`intent.${insights.intent}`))}</span>` : '';
    const aiButton = analyzing ? ''
      : thread.insightsState === 'ready' ? `<button type="button" class="btn btn--sm btn--ghost" data-em-analyze="again">${esc(tr('aiAgain'))}</button>`
      : thread.insightsState === 'pending' ? `<button type="button" class="btn btn--sm" data-em-analyze="read">${icon('spark')}<span>${esc(tr('aiAnalyze'))}</span></button>`
      : '';
    const summary = insights ? `<div class="email-actions__summary">${icon('spark')}<div><p>${esc(insights.summary)}</p><p class="hint">${esc(tr('aiNote'))}</p></div></div>` : '';
    const reading = analyzing ? `<div class="email-actions__reading" aria-live="polite">${icon('spark')}<span>${esc(tr('aiReading'))}</span></div>` : '';
    const failed = !analyzing && ui.aiError ? `<p class="hint hint--warn">${esc(trOr(`aiErrors.${ui.aiError}`, tr('aiFailed')))}</p>` : '';
    const suggestions = (thread.suggestions || []).filter(s => !ACTION_MODULES[s.type] || d.hasModule(ACTION_MODULES[s.type]));
    const list = suggestions.length
      ? `<ul class="email-actions__list" role="list">${suggestions.map(s => suggestionHtml(s, thread)).join('')}</ul>`
      : (!analyzing ? `<p class="hint">${esc(tr('noActions'))}</p>` : '');
    const documents = (thread.documents || []).length
      ? `<div class="email-actions__docs"><span class="email-actions__docs-label">${esc(tr('documents'))}</span>${thread.documents.map(doc => `<button type="button" class="chip" data-em-doc="${esc(doc.type)}:${esc(doc.id)}">${esc(doc.number)} · ${esc(statusText(doc.type, doc.status))} · ${esc(euros(doc.totalCents))}</button>`).join('')}</div>`
      : '';
    return `<section class="email-actions" aria-labelledby="em-actions-title">
      <header class="email-actions__head"><h3 class="email-actions__title" id="em-actions-title">${esc(tr('actionsTitle'))}</h3>${intent}<span class="email-actions__tools">${aiButton}</span></header>
      ${summary}${reading}${failed}${list}${documents}
    </section>`;
  }

  const statusText = (type, status) => d.statusLabel?.(type, status) || status;

  function suggestionHtml(s, thread) {
    const key = actionKey(s.type);
    const p = s.prefill || {};
    const sender = counterpart(thread).name;
    const done = s.status === 'DONE';
    const name = p.name || thread.client?.name || sender;
    const title = done
      ? tr(`action.${key}.done`, { label: s.recordLabel || '' })
      : tr(`action.${key}.title`, { name: s.type === 'bill.create' ? (p.supplierName || sender) : name, label: s.recordLabel || '' });
    const detail = done ? '' : suggestionDetail(s, thread);
    const busy = ui.busy === `${s.type}:${s.recordId || ''}`;
    const side = done
      ? `<span class="pill pill--ok">${esc(tr('done'))}</span>${canOpen(s) ? `<button type="button" class="btn btn--sm btn--ghost" data-em-open="${esc(s.recordType)}:${esc(s.recordId)}">${esc(tr('open'))}</button>` : ''}`
      : `<button type="button" class="iconbtn email-action__dismiss" data-em-dismiss="${esc(s.type)}" data-em-record="${esc(s.recordId || '')}" aria-label="${esc(tr('dismissAria', { action: title }))}" title="${esc(tr('dismiss'))}">${icon('close')}</button>
         <button type="button" class="btn btn--sm${s.primary ? ' btn--primary' : ''}" data-em-act="${esc(s.type)}" data-em-record="${esc(s.recordId || '')}" ${busy ? 'disabled' : ''}>${esc(tr(`action.${key}.button`))}</button>`;
    return `<li class="email-action${s.primary && !done ? ' is-primary' : ''}${done ? ' is-done' : ''}">
      <span class="email-action__icon" aria-hidden="true">${icon(ACTION_ICONS[s.type] || 'spark')}</span>
      <span class="email-action__main"><span class="email-action__title">${esc(title)}</span>${detail ? `<span class="email-action__detail">${esc(detail)}</span>` : ''}</span>
      <span class="email-action__side">${side}</span>
    </li>`;
  }

  /** What the form will start with, so the person sees it before opening it. */
  function suggestionDetail(s, thread) {
    const p = s.prefill || {};
    const items = Array.isArray(p.items) ? p.items : [];
    const parts = {
      'client.create': [p.contactPerson, p.email, p.phone, p.taxId ? tr('taxId', { id: p.taxId }) : '', p.city],
      'client.update': [p.taxId ? tr('taxId', { id: p.taxId }) : '', p.address, p.postalCode, p.city, p.contactPerson, p.email],
      'quote.create': [items.length ? items.map(i => i.description).join(', ') : '', !p.clientId ? tr('clientFirst') : ''],
      'quote.accept': [p.amountCents != null ? euros(p.amountCents) : ''],
      'invoice.paid': [p.amountCents != null ? euros(p.amountCents) : ''],
      'booking.create': [p.date ? d.fmtDay(p.date) : '', p.time || '', p.contactPhone || ''],
      'bill.create': [p.amountCents != null ? euros(p.amountCents) : '', p.dueDate ? tr('dueOn', { date: d.fmtDay(p.dueDate) }) : '', p.description, !p.supplierId ? tr('supplierFirst') : ''],
      'task.create': [thread.insights?.summary ? '' : thread.subject],
    }[s.type] || [];
    return parts.filter(Boolean).join(' · ');
  }

  function canOpen(s) {
    if (!s.recordType || !s.recordId) return false;
    if (s.recordType === 'task') return d.hasModule('agents') && !!window.AgentsUI;
    return d.canOpenRecord({ type: s.recordType, id: s.recordId });
  }

  async function analyze(refresh) {
    const thread = data.thread;
    if (!thread?.focusId) return;
    const key = thread.key;
    ui.analyzing = thread.focusId;
    ui.aiError = null;
    renderThread();
    try {
      const fresh = await d.api(`${API}/threads/${encodeURIComponent(thread.focusId)}/insights${refresh ? '?refresh=1' : ''}`, { method: 'POST' });
      if (ui.selected !== key) return;
      data.thread = fresh;
      const row = data.threads.find(r => r.key === key);
      if (row && fresh.insights) { row.intent = fresh.insights.intent; renderRows(); }
    } catch (err) {
      if (err?.message === 'unauthorized') return;
      if (ui.selected === key) ui.aiError = err?.code || 'ai_unavailable';
    } finally {
      if (ui.analyzing === thread.focusId) ui.analyzing = null;
      if (ui.selected === key) renderThread();
    }
  }

  async function record(type, recordId, { status = 'done', quiet = false } = {}) {
    const thread = data.thread;
    if (!thread?.focusId) return null;
    const key = thread.key;
    try {
      const fresh = await d.api(`${API}/threads/${encodeURIComponent(thread.focusId)}/actions`, { method: 'POST', body: JSON.stringify({ type, status, recordId: recordId || undefined }) });
      if (ui.selected === key) {
        data.thread = fresh;
        renderThread();
      }
      if (!quiet) reloadList();
      if (status === 'done') d.refreshCounts();
      return fresh;
    } catch (err) {
      if (err?.message !== 'unauthorized') d.toast(tr('actionFailed'));
      return null;
    }
  }

  function findSuggestion(type, recordId) {
    return (data.thread?.suggestions || []).find(s => s.type === type && (s.recordId || '') === (recordId || ''));
  }

  /** The thread's client, or a new one from the sender first; [then] continues with it. */
  function withClient(then) {
    const thread = data.thread;
    if (thread.client) return then({ id: thread.client.id, name: thread.client.name, phone: thread.client.phone });
    const create = findSuggestion('client.create', '');
    const who = counterpart(thread);
    d.openClientForm(null, {
      prefill: create?.prefill || { name: who.name, email: who.address },
      onSaved: async client => {
        await record('client.create', client.id, { quiet: true });
        then(client);
      },
    });
  }

  async function act(type, recordId, button) {
    const thread = data.thread;
    const s = findSuggestion(type, recordId);
    if (!thread || !s) return;
    const p = s.prefill || {};
    const focusId = thread.focusId;
    const lines = (Array.isArray(p.items) ? p.items : []).map(i => ({ description: i.description, quantity: i.quantity ?? 1, unit: i.unit || '' }));
    if (type === 'client.create') {
      return d.openClientForm(null, { prefill: p, onSaved: client => record(type, client.id) });
    }
    if (type === 'client.update') {
      let client;
      try { client = await d.api(`/app/api/crm/clients/${encodeURIComponent(s.recordId)}`); }
      catch { return d.toast(d.STR.loadFailed); }
      const merged = { ...client };
      Object.entries(p).forEach(([field, value]) => { if (!merged[field]) merged[field] = value; });
      return d.openClientForm(merged, { onSaved: saved => record(type, saved.id) });
    }
    if (type === 'quote.create') {
      return withClient(client => d.openQuoteForm({ client, items: lines, notes: p.notes || '', onSaved: quote => record(type, quote.id) }));
    }
    if (type === 'booking.create') {
      const client = thread.client ? { id: thread.client.id, name: thread.client.name, phone: thread.client.phone } : null;
      return d.openBookingForm({ client, prefill: p, onSaved: booking => record(type, booking.id) });
    }
    if (type === 'bill.create') {
      const items = [{ description: p.description || thread.subject, quantity: 1, unit: '', unitPriceEur: p.amountCents != null ? p.amountCents / 100 : '' }];
      const pay = supplierId => d.openPaymentForm(supplierId, null, null, { items, dueDate: p.dueDate, notes: thread.subject, onSaved: payment => record(type, payment.id) });
      if (p.supplierId) return pay(p.supplierId);
      return d.openSupplierForm(null, { prefill: { name: p.supplierName, phone: p.supplierPhone, address: p.supplierAddress }, onSaved: supplier => pay(supplier.id) });
    }
    if (type === 'task.create') {
      const who = counterpart(thread);
      const detail = [thread.insights?.summary, tr('taskFrom', { name: who.name, address: who.address }), tr('taskSubject', { subject: thread.subject })].filter(Boolean).join('\n');
      return d.openTask({ type: 'email', id: focusId }, {
        draft: { title: tr('taskTitle', { name: thread.client?.name || who.name, subject: thread.subject || tr('noSubject') }).slice(0, 200), detail, dueDate: p.dueDate },
        onSaved: task => record(type, task.id),
      });
    }
    if (type === 'invoice.paid' || type === 'quote.accept') {
      const paid = type === 'invoice.paid';
      const ok = await d.confirmDialog(paid
        ? { title: d.STR.markPaidConfirmTitle, body: d.STR.markPaidConfirmBody({ number: s.recordLabel }), okLabel: d.STR.markPaid, danger: false }
        : { title: tr('acceptTitle', { number: s.recordLabel }), body: tr('acceptBody'), okLabel: tr('action.quote_accept.button'), danger: false });
      if (!ok) return;
      ui.busy = `${type}:${recordId || ''}`;
      if (button) button.disabled = true;
      try {
        if (paid) await d.api(`/app/api/crm/invoices/${encodeURIComponent(s.recordId)}/paid`, { method: 'PATCH' });
        else await d.api(`/app/api/crm/quotes/${encodeURIComponent(s.recordId)}`, { method: 'PATCH', body: JSON.stringify({ status: 'ACEITO' }) });
        d.toast(paid ? d.STR.markedPaid({ number: s.recordLabel }) : tr('accepted', { number: s.recordLabel }));
        await record(type, s.recordId);
      } catch (err) {
        if (err?.message !== 'unauthorized') d.toast(paid ? d.STR.markPaidFailed : tr('actionFailed'));
      } finally {
        ui.busy = null;
        renderThread();
      }
    }
  }

  async function dismiss(type, recordId) {
    const fresh = await record(type, recordId, { status: 'dismissed', quiet: true });
    if (fresh) d.toast(tr('dismissed'));
  }

  function openDone(ref) {
    const [type, id] = ref.split(':');
    if (type === 'task') return window.AgentsUI?.openRef(`task:${id}`);
    d.openRecord({ type, id });
  }

  // ── reply ────────────────────────────────────────────────────────────
  function composerHtml(thread) {
    const reply = thread.reply || {};
    if (!reply.canSend) {
      const admin = data.status?.canConnect;
      const text = trOr(`replyBlocked.${reply.problem || ''}`, tr('replyBlocked.no_email_account'));
      return `<div class="composer__notice"><div class="composer__notice-text"><p>${esc(text)}</p></div>${admin ? `<button type="button" class="btn btn--sm" data-em-settings>${esc(tr('openSettings'))}</button>` : ''}</div>`;
    }
    const draft = ui.drafts[thread.key] || '';
    const suggested = thread.insights?.reply;
    return `<form class="composer__form email-reply" id="em-reply-form" novalidate>
        <div class="email-reply__head"><span class="email-reply__to">${esc(tr('replyTo', { to: reply.to || '' }))}</span>
          ${suggested ? `<button type="button" class="btn btn--sm btn--ghost" data-em-draft>${icon('spark')}<span>${esc(tr('useDraft'))}</span></button>` : ''}</div>
        <label class="visually-hidden" for="em-reply">${esc(tr('replyAria'))}</label>
        <div class="email-reply__row">
          <textarea class="inp composer__input email-reply__input" id="em-reply" rows="2" maxlength="${MAX_REPLY}" placeholder="${esc(tr('replyPlaceholder'))}" aria-describedby="em-reply-hint">${esc(draft)}</textarea>
          <div class="composer__actions"><button type="submit" class="btn btn--primary composer__send" id="em-send">${icon('send')}<span>${esc(tr('send'))}</span></button></div>
        </div>
      </form>
      <div class="composer__foot"><span class="hint${reply.problem === 'automated_sender' ? ' hint--warn' : ''}" id="em-reply-hint">${esc(reply.problem === 'automated_sender' ? tr('automatedWarning') : tr('replyHint'))}</span></div>
      <p class="hint hint--bad composer__error" id="em-reply-error" role="alert" hidden></p>`;
  }

  function autosize(input) {
    input.style.height = 'auto';
    input.style.height = `${Math.min(input.scrollHeight, 240)}px`;
  }

  function updateComposer() {
    const input = $('#em-reply', root);
    const send = $('#em-send', root);
    if (!input || !send) return;
    const text = input.value.trim();
    send.disabled = !text || text.length > MAX_REPLY || ui.sending;
  }

  async function sendReply() {
    const thread = data.thread;
    const input = $('#em-reply', root);
    if (!thread || !input || ui.sending) return;
    const text = input.value.trim();
    if (!text) return updateComposer();
    const key = thread.key;
    if (!ui.requestIds[key]) ui.requestIds[key] = d.newRequestId().replace(/[^A-Za-z0-9_-]/g, '').slice(0, 64);
    ui.sending = true;
    updateComposer();
    $('#em-reply-error', root)?.setAttribute('hidden', '');
    try {
      const fresh = await d.api(`${API}/threads/${encodeURIComponent(thread.messages[thread.messages.length - 1].id)}/reply`, {
        method: 'POST',
        body: JSON.stringify({ text, requestId: ui.requestIds[key] }),
      });
      delete ui.drafts[key];
      delete ui.requestIds[key];
      d.toast(tr('replySent', { to: thread.reply?.to || '' }));
      if (ui.selected === key) {
        data.thread = fresh;
        renderThread();
        const log = $('#em-log', root);
        if (log) log.scrollTop = log.scrollHeight;
      }
      reloadList();
    } catch (err) {
      if (err?.message === 'unauthorized') return;
      const message = d.googleTextOr(`sendErrors.${err?.code || ''}`, tr('replyFailed'));
      const box = $('#em-reply-error', root);
      if (box) { box.textContent = message; box.hidden = false; } else d.toast(message);
    } finally {
      ui.sending = false;
      updateComposer();
    }
  }

  // ── clicks in the thread ─────────────────────────────────────────────
  function onThreadClick(e) {
    const target = e.target.closest('button');
    if (!target) return;
    if (target.hasAttribute('data-em-back')) return closeThread();
    if (target.hasAttribute('data-em-retry')) return openThread(ui.selectedId, ui.selected);
    if (target.hasAttribute('data-em-unread')) return markUnread(target);
    if (target.hasAttribute('data-em-client')) return d.openClientDrawer(data.thread.client.id);
    if (target.dataset.emAnalyze) return analyze(target.dataset.emAnalyze === 'again');
    if (target.dataset.emAct) return act(target.dataset.emAct, target.dataset.emRecord || '', target);
    if (target.dataset.emDismiss) return dismiss(target.dataset.emDismiss, target.dataset.emRecord || '');
    if (target.dataset.emOpen) return openDone(target.dataset.emOpen);
    if (target.dataset.emDoc) {
      const [type, id] = target.dataset.emDoc.split(':');
      return type === 'quote' ? d.openQuoteDetail(id) : d.openInvoiceDetail(id);
    }
    if (target.hasAttribute('data-em-settings')) return d.openSettingsAccount(data.status?.accounts?.find(a => a.id === data.thread?.connectionId)?.id);
    if (target.hasAttribute('data-em-draft')) {
      const input = $('#em-reply', root);
      const suggested = data.thread?.insights?.reply;
      if (!input || !suggested) return;
      input.value = input.value.trim() ? `${input.value.trim()}\n\n${suggested}` : suggested;
      ui.drafts[data.thread.key] = input.value;
      autosize(input);
      updateComposer();
      input.focus();
      d.toast(tr('draftUsed'));
    }
  }

  /** Nav count: threads with received mail nobody opened. */
  function badge() {
    return data.status ? { count: data.status.unread || 0, alert: (data.status.unread || 0) > 0 } : null;
  }

  function init(deps) {
    d = deps;
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible' && d.state.active === 'email' && root) reloadList();
    });
  }

  window.EmailUI = { init, load, render, badge, stop, focus, refresh };
})();

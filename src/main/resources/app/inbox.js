/* The Inbox group: Contacts (who wrote in, and blocking them) and Conversations, with the template drawer
   that sends a WhatsApp template past the 24-hour window or starts a conversation. */

function renderContacts(root) {
  const q = state.search.toLowerCase();
  const rows = state.contacts.filter(c => !q || `${c.displayName || ''} ${c.waId}`.toLowerCase().includes(q)).map(c => `<tr><td class="name">${escapeHTML(c.displayName || '—')}</td><td>${escapeHTML(c.channel)}</td><td class="mono">${escapeHTML(c.waId)}</td><td>${contactPill(c.status)}</td><td class="mono muted">${fmtDate(c.lastSeenAt)}</td><td class="right"><button class="btn btn--sm" data-contact-status="${c.id}" data-status="${c.status === 'BLOCKED' ? 'ACTIVE' : 'BLOCKED'}">${c.status === 'BLOCKED' ? STR.unblock : STR.block}</button></td></tr>`).join('');
  root.innerHTML = hero(labels.contacts, STR.contactsDesc) + panelTable(`<tr><th>${STR.thName}</th><th>${STR.colChannel}</th><th>${STR.colAccount}</th><th>${STR.thStatus}</th><th>${STR.thLastSeen}</th><th class="right">${STR.thActions}</th></tr>`, rows, STR.noData);
  $$('[data-contact-status]').forEach(b => b.addEventListener('click', async () => { await api(`/app/api/contacts/${b.dataset.contactStatus}/status`, { method: 'PATCH', body: JSON.stringify({ status: b.dataset.status }) }); await loadModule('contacts'); render(); }));
}
function assetLabel(asset) {
  if (asset.platform === 'WEB') return `${asset.platform} · ${STR.webWidgetAsset}`;
  return `${asset.platform} · ${asset.displayName || STR.unnamedAsset} · ${asset.externalId}`;
}
function rememberedAsset() {
  try { return localStorage.getItem('dashboardAsset') || ''; } catch { return ''; }
}
function rememberAsset(id) {
  state.selectedAsset = id;
  try { localStorage.setItem('dashboardAsset', id); } catch { /* ignore */ }
}
// ── Conversations inbox ─────────────────────────────────────────────────────────────────────────
// A list and one open thread. The open thread polls /updates (new messages and WhatsApp delivery
// ticks) every few seconds and the list every 15 s; both pause while the browser tab is hidden.
// WhatsApp only allows free-form replies for 24 h after the customer's last message, so the
// composer turns into "send a template" once that window closes. An agent reply pauses the AI.
const INBOX_THREAD_POLL_MS = 4000;
const INBOX_LIST_POLL_MS = 15000;
const INBOX_MAX_TEXT = 4096;
const INBOX_CLOSING_SOON_MS = 2 * 3600 * 1000;
const INBOX_FILTERS = ['all', 'needs', 'unread', 'paused'];
const INBOX_MEDIA_KINDS = ['image', 'audio', 'video', 'document'];
const INBOX_ICONS = {
  file: '<svg viewBox="0 0 16 16" aria-hidden="true" focusable="false"><path d="M4 1.5h5l3.5 3.5v9.5H4z"/><path d="M9 1.5V5h3.5"/></svg>',
  check: '<svg viewBox="0 0 16 16" aria-hidden="true" focusable="false"><path d="M3 8.5 6.5 12 13 4.5"/></svg>',
  checks: '<svg viewBox="0 0 20 16" aria-hidden="true" focusable="false"><path d="M1.5 8.5 5 12 11.5 4.5"/><path d="M8.6 11.3 9.3 12 15.8 4.5"/></svg>',
  clock: '<svg viewBox="0 0 16 16" aria-hidden="true" focusable="false"><circle cx="8" cy="8" r="6"/><path d="M8 4.8V8l2.2 1.4"/></svg>',
  alert: '<svg viewBox="0 0 16 16" aria-hidden="true" focusable="false"><circle cx="8" cy="8" r="6.2"/><path d="M8 4.6v4.2M8 11.1v.3"/></svg>',
  back: '<svg viewBox="0 0 16 16" aria-hidden="true" focusable="false"><path d="M10 3 5 8l5 5"/></svg>',
  send: '<svg viewBox="0 0 16 16" aria-hidden="true" focusable="false"><path d="M2.5 8 13.5 2.5 10.5 13.5 8 9Z"/></svg>',
  template: '<svg viewBox="0 0 16 16" aria-hidden="true" focusable="false"><rect x="2.5" y="2.5" width="11" height="11" rx="2"/><path d="M5 6h6M5 8.5h6M5 11h3.5"/></svg>',
  plus: '<svg viewBox="0 0 16 16" aria-hidden="true" focusable="false"><path d="M8 3v10M3 8h10"/></svg>',
  down: '<svg viewBox="0 0 16 16" aria-hidden="true" focusable="false"><path d="M8 3v10M4 9l4 4 4-4"/></svg>',
};
let inboxThreadTimer = null;
let inboxListTimer = null;
let inboxFailures = 0;

const isNarrowInbox = () => window.matchMedia('(max-width: 920px)').matches;
const inboxAuthor = m => m.author || (m.role === 'USER' ? 'customer' : 'ai');
const inboxContact = c => (c?.channel === 'WHATSAPP' && /^\d+$/.test(c.waId || '') ? `+${c.waId}` : c?.waId || '');
const inboxName = c => c?.displayName || inboxContact(c);
const inboxConversation = id => state.conversations.find(c => c.id === id) || null;
const inboxAssets = () => state.me?.tenant.channels || [];
const hasWhatsAppChannel = () => inboxAssets().some(a => a.platform === 'WHATSAPP');
const inboxIcon = name => `<span class="inbox-ico">${INBOX_ICONS[name]}</span>`;
const nearBottom = el => el.scrollHeight - el.scrollTop - el.clientHeight < 80;
const fmtUnit = (n, unit) => new Intl.NumberFormat(uiLocale(), { style: 'unit', unit, unitDisplay: 'narrow' }).format(n);
// Rendered HTML is cached on the element so a poll that changes nothing doesn't reset focus or scroll.
const setInboxHTML = (el, html) => { if (el && el._html !== html) { el._html = html; el.innerHTML = html; } };

function inboxInitials(c) {
  const name = (c?.displayName || '').trim();
  if (!name) return (c?.waId || '').replace(/\D/g, '').slice(-2) || '?';
  return name.split(/\s+/).slice(0, 2).map(w => w[0]).join('').toUpperCase();
}
/** `autoReplyPausedBy` of a conversation the bot handed to the team (Conversation.BOT_HANDOFF on the server). */
const BOT_HANDOFF = 'bot:handoff';
function inboxAgentLabel(userId, name) {
  if (name === BOT_HANDOFF) return STR.inboxPausedByBot;
  if ((userId && userId === state.me?.user?.id) || (name && name === state.me?.user?.email)) return STR.inboxYou;
  return name ? name.split('@')[0] : STR.inboxTeam;
}
function fmtTimeLeft(ms) {
  const minutes = Math.max(1, Math.ceil(ms / 60000));
  const h = Math.floor(minutes / 60), m = minutes % 60;
  if (!h) return fmtUnit(m, 'minute');
  return m ? `${fmtUnit(h, 'hour')} ${fmtUnit(m, 'minute')}` : fmtUnit(h, 'hour');
}
function inboxWindow(c) {
  if (c?.channel !== 'WHATSAPP') return { state: 'none' };
  const left = (c.windowExpiresAt ? Date.parse(c.windowExpiresAt) : NaN) - Date.now();
  if (!(left > 0)) return { state: 'closed', never: !c.lastInboundAt };
  return { state: left < INBOX_CLOSING_SOON_MS ? 'closing' : 'open', left };
}
function inboxMatches(c, filter) {
  if (filter === 'needs') return !!c.waiting;
  if (filter === 'unread') return (c.unreadCount || 0) > 0;
  if (filter === 'paused') return c.autoReplyEnabled === false;
  return true;
}
// The i18n proxy answers a missing key with its own path; unknown keys fall back to Meta's own words.
function inboxErrorText(key, detail) {
  const k = `inboxErr_${key || 'send_failed'}`;
  if (key && key !== 'send_failed' && STR[k] !== `app.${k}`) return STR[k];
  return detail ? `${STR.inboxErr_send_failed} (${detail})` : STR.inboxErr_send_failed;
}
/**
 * Chat text with WhatsApp's own formatting (*bold*, _italic_, ~strike~), the AI's **bold**, and http(s)
 * links. Links are swapped for placeholders first so the formatting rules never reach inside a URL.
 */
function renderWhatsAppText(s = '') {
  const links = [];
  const text = String(s).replace(/https?:\/\/[^\s<>"']+/g, url => {
    const clean = url.replace(/[.,!?;:)\]]+$/, '');
    links.push(clean);
    return `\u0000${links.length - 1}\u0000${url.slice(clean.length)}`;
  });
  return escapeHTML(text)
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/(^|[\s(])\*(?=\S)([^*\n]*?\S)\*(?=$|[\s).,!?:;])/gm, '$1<strong>$2</strong>')
    .replace(/(^|[\s(])_(?=\S)([^_\n]*?\S)_(?=$|[\s).,!?:;])/gm, '$1<em>$2</em>')
    .replace(/(^|[\s(])~(?=\S)([^~\n]*?\S)~(?=$|[\s).,!?:;])/gm, '$1<s>$2</s>')
    .replace(/\u0000(\d+)\u0000/g, (all, i) => {
      const url = escapeHTML(links[Number(i)]);
      return `<a href="${url}" target="_blank" rel="noopener noreferrer">${url}</a>`;
    });
}

// Customer media comes through the authenticated media route as blobs (an <img src> can't send the
// bearer token). Anything not shown inline is re-wrapped as a download, so a customer's file never
// renders inside the dashboard's origin.
const inboxMedia = new Map();
const inboxMediaQueue = [];
let inboxMediaActive = 0;
function clearInboxMedia() {
  for (const entry of inboxMedia.values()) if (entry.url) URL.revokeObjectURL(entry.url);
  inboxMedia.clear();
  inboxMediaQueue.length = 0;
}
function mediaBoxHtml(m) {
  const cached = inboxMedia.get(m.id);
  const label = STR[`inboxMedia_${m.kind}`];
  if (!cached || cached.loading) return `<span class="bubble__media-status" aria-busy="true">${inboxIcon(m.kind === 'document' ? 'file' : 'clock')}<span>${escapeHTML(label)}</span></span>`;
  if (cached.error) return `<span class="bubble__media-status is-error">${inboxIcon('alert')}<span>${escapeHTML(inboxErrorText(cached.error))}</span></span>`;
  if (cached.inline && m.kind === 'image') return `<button type="button" class="bubble__image-btn" data-media-expand aria-label="${escapeHTML(STR.inboxMediaExpand)}"><img class="bubble__image" src="${cached.url}" alt="${escapeHTML(label)}" /></button>`;
  if (cached.inline && m.kind === 'audio') return `<audio class="bubble__audio" controls preload="metadata" src="${cached.url}"></audio>`;
  if (cached.inline && m.kind === 'video') return `<video class="bubble__video" controls preload="metadata" src="${cached.url}"></video>`;
  const name = m.fileName || label;
  return `<a class="bubble__file" href="${cached.url}" download="${escapeHTML(name)}">${inboxIcon('file')}<span>${escapeHTML(name)}</span><span class="bubble__file-action">${escapeHTML(STR.inboxMediaDownload)}</span></a>`;
}
function loadInboxMedia(root) {
  const conversationId = state.selectedConversation;
  $$('[data-media]', root).forEach(box => {
    const id = box.dataset.media;
    if (inboxMedia.has(id)) return;
    inboxMedia.set(id, { loading: true });
    inboxMediaQueue.push({ id, kind: box.dataset.kind, conversationId });
  });
  const next = () => {
    if (inboxMediaActive >= 3 || !inboxMediaQueue.length) return;
    const job = inboxMediaQueue.shift();
    inboxMediaActive += 1;
    apiBlob(`/app/api/conversations/${encodeURIComponent(job.conversationId)}/media/${encodeURIComponent(job.id)}`)
      .then(blob => {
        const inline = job.kind === 'image' ? /^image\/(jpeg|png|webp|gif)$/.test(blob.type)
          : job.kind === 'audio' ? blob.type.startsWith('audio/')
          : job.kind === 'video' ? blob.type.startsWith('video/') : false;
        const stored = inline ? blob : new Blob([blob], { type: 'application/octet-stream' });
        return { url: URL.createObjectURL(stored), inline };
      })
      .catch(e => ({ error: e.code || 'media_unavailable' }))
      .then(entry => {
        inboxMediaActive -= 1;
        if (state.inbox.threadFor !== job.conversationId) {
          if (entry.url) URL.revokeObjectURL(entry.url);
        } else {
          inboxMedia.set(job.id, entry);
          const log = $('#inbox-log', root);
          const box = log?.querySelector(`[data-media="${CSS.escape(job.id)}"]`);
          const message = state.threadMessages.find(m => m.id === job.id);
          if (box && message) {
            const stick = nearBottom(log);
            box.innerHTML = mediaBoxHtml(message);
            const settle = () => { if (stick) log.scrollTop = log.scrollHeight; };
            settle();
            box.querySelector('img')?.addEventListener('load', settle, { once: true });
            box.querySelector('video')?.addEventListener('loadedmetadata', settle, { once: true });
          }
        }
        next();
      });
    next();
  };
  next();
}
function inboxTick(m) {
  const tick = (icon, label, tone) => `<span class="tick tick--${tone}" role="img" aria-label="${escapeHTML(label)}" title="${escapeHTML(label)}">${INBOX_ICONS[icon]}</span>`;
  if (m.pending) return tick('clock', STR.inboxStatusSending, 'pending');
  if (m.status === 'FAILED') return tick('alert', STR.inboxStatusFailed, 'failed');
  if (!m.tracked) return '';
  if (m.status === 'SENT') return tick('check', STR.inboxStatusSent, 'sent');
  if (m.status === 'DELIVERED') return tick('checks', STR.inboxStatusDelivered, 'delivered');
  if (m.status === 'READ') return tick('checks', STR.inboxStatusRead, 'read');
  return '';
}
function inboxSelectedAsset() {
  const assets = inboxAssets();
  if (!state.selectedAsset) state.selectedAsset = rememberedAsset();
  if (!assets.some(a => a.externalId === state.selectedAsset)) state.selectedAsset = assets[0]?.externalId || '';
  return assets.find(a => a.externalId === state.selectedAsset) || null;
}
function inboxVisible() {
  const asset = inboxSelectedAsset();
  const q = state.search.trim().toLowerCase();
  const inAsset = state.conversations.filter(c => !asset || c.channel === asset.platform);
  const searched = q ? inAsset.filter(c => `${c.displayName || ''} ${c.waId} ${c.lastPreview || ''}`.toLowerCase().includes(q)) : inAsset;
  return { asset, inAsset, searched, rows: searched.filter(c => inboxMatches(c, state.inbox.filter)) };
}
function upsertConversation(c) {
  if (!c?.id) return;
  const i = state.conversations.findIndex(x => x.id === c.id);
  if (i >= 0) state.conversations[i] = c; else state.conversations.unshift(c);
}

function renderConversations(root) {
  const mounted = $('.inbox', root);
  if (mounted && mounted.dataset.asset === (inboxSelectedAsset()?.externalId || '') && mounted.dataset.locale === uiLocale()) {
    renderInboxRows(root);
    return;
  }
  mountInbox(root);
}

function mountInbox(root) {
  const { asset, inAsset, rows } = inboxVisible();
  if (state.selectedConversation && !inAsset.some(c => c.id === state.selectedConversation)) state.selectedConversation = null;
  if (!state.selectedConversation && !isNarrowInbox()) state.selectedConversation = rows[0]?.id || null;
  state.inbox.threadFor = null;
  const current = inboxConversation(state.selectedConversation);
  const assets = inboxAssets();
  const picker = assets.length > 1
    ? `<select class="sel inbox__asset" id="conversation-asset" aria-label="${escapeHTML(STR.inboxAssetAria)}">${assets.map(a => `<option value="${escapeHTML(a.externalId)}" ${a.externalId === state.selectedAsset ? 'selected' : ''}>${escapeHTML(assetLabel(a))}</option>`).join('')}</select>`
    : '';
  const newButton = hasWhatsAppChannel() ? `<button class="btn btn--primary btn--sm inbox__new" id="inbox-new" type="button">${inboxIcon('plus')}<span>${escapeHTML(STR.inboxNew)}</span></button>` : '';
  root.innerHTML = `${hero(labels.conversations, STR.conversationsDesc)}
    <div class="inbox" data-asset="${escapeHTML(asset?.externalId || '')}" data-locale="${escapeHTML(uiLocale())}" data-thread-open="${current ? 'true' : 'false'}">
      <aside class="inbox__list" aria-label="${escapeHTML(STR.inboxListAria)}">
        <div class="inbox__list-head">
          ${picker || newButton ? `<div class="inbox__list-top">${picker}${newButton}</div>` : ''}
          <div class="inbox__filters" id="inbox-filters" role="group" aria-label="${escapeHTML(STR.inboxFilterAria)}"></div>
        </div>
        <ul class="inbox__rows" id="inbox-rows" role="list"></ul>
      </aside>
      <section class="inbox__thread" id="inbox-thread" aria-label="${escapeHTML(STR.threadTitle)}"></section>
      <p class="visually-hidden" id="inbox-announce" aria-live="polite"></p>
    </div>`;
  renderInboxRows(root);
  const inbox = $('.inbox', root);
  $('#conversation-asset', root)?.addEventListener('change', e => {
    rememberAsset(e.target.value);
    state.selectedConversation = null;
    mountInbox(root);
  });
  $('#inbox-new', root)?.addEventListener('click', () => openTemplateDrawer(null));
  $('#inbox-rows', root).addEventListener('click', e => {
    const row = e.target.closest('[data-conversation]');
    if (row) return openInboxThread(row.dataset.conversation, root, { focus: true });
    if (e.target.closest('[data-inbox-settings]')) return openChannelSettings();
    const filter = e.target.closest('[data-inbox-filter]');
    if (filter) { state.inbox.filter = filter.dataset.inboxFilter; renderInboxRows(root); }
  });
  $('#inbox-filters', root).addEventListener('click', e => {
    const chip = e.target.closest('[data-inbox-filter]');
    if (!chip) return;
    state.inbox.filter = chip.dataset.inboxFilter;
    renderInboxRows(root);
  });
  // Alt+↑/↓ steps through the visible conversations; text fields keep their own Option+arrow behavior.
  inbox.addEventListener('keydown', e => {
    if (!e.altKey || (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') || e.target.closest('textarea, input, select')) return;
    const visible = inboxVisible().rows;
    if (!visible.length) return;
    e.preventDefault();
    const index = visible.findIndex(c => c.id === state.selectedConversation);
    const next = visible[Math.max(0, Math.min(visible.length - 1, index + (e.key === 'ArrowDown' ? 1 : -1)))];
    if (!next || next.id === state.selectedConversation) return;
    openInboxThread(next.id, root);
    $(`[data-conversation="${CSS.escape(next.id)}"]`, root)?.focus();
  });
  bindThreadPane(root);
  if (current) openInboxThread(current.id, root);
  else renderThreadPlaceholder(root);
  startInboxPolling(root);
}

function inboxRow(c) {
  const author = c.lastAuthor || (c.lastRole === 'USER' ? 'customer' : c.lastRole ? 'ai' : '');
  const mediaLabel = INBOX_MEDIA_KINDS.includes(c.lastKind) ? STR[`inboxMedia_${c.lastKind}`] : '';
  const preview = c.lastPreview || mediaLabel || conversationStateLabel(c.state);
  const unread = c.unreadCount || 0;
  const active = state.selectedConversation === c.id;
  const tick = author && author !== 'customer' && c.lastTracked ? inboxTick({ status: c.lastStatus, tracked: true }) : '';
  const prefix = { ai: STR.inboxAiPrefix, automation: STR.inboxAutomationPrefix }[author];
  const who = prefix ? `<span class="inbox-row__who">${escapeHTML(prefix)}</span>` : '';
  const flags = `${c.autoReplyEnabled === false ? `<span class="inbox-row__flag" title="${escapeHTML(STR.inboxAiPaused)}">${escapeHTML(STR.inboxAiOffShort)}</span>` : ''}${unread ? `<span class="inbox-row__badge">${unread > 99 ? '99+' : unread}</span>` : ''}`;
  const label = [inboxName(c), unread ? tApp('inboxUnreadAria', { n: unread }) : '', c.waiting ? STR.inboxFilter_needs : '', c.autoReplyEnabled === false ? STR.inboxAiPaused : '', preview].filter(Boolean).join(' · ');
  return `<li><button type="button" class="inbox-row${active ? ' is-active' : ''}${unread ? ' is-unread' : ''}${c.waiting ? ' is-waiting' : ''}" data-conversation="${escapeHTML(c.id)}" aria-current="${active ? 'true' : 'false'}" aria-label="${escapeHTML(label)}">
    <span class="inbox-avatar" aria-hidden="true">${escapeHTML(inboxInitials(c))}</span>
    <span class="inbox-row__main" aria-hidden="true">
      <span class="inbox-row__top"><span class="inbox-row__name">${escapeHTML(inboxName(c))}</span><time class="inbox-row__time" datetime="${escapeHTML(c.lastMessageAt)}">${escapeHTML(relTime(c.lastMessageAt))}</time></span>
      <span class="inbox-row__bottom"><span class="inbox-row__preview">${tick}${who}${escapeHTML(preview)}</span>${flags}</span>
    </span>
  </button></li>`;
}

function inboxListEmpty(inAsset, searched) {
  const empty = (title, desc = '', action = '') => `<div class="empty"><p class="empty__title">${escapeHTML(title)}</p>${desc ? `<p class="empty__desc">${escapeHTML(desc)}</p>` : ''}${action}</div>`;
  if (!inboxAssets().length) return empty(STR.noMessagingAssets, STR.inboxConnectDesc, `<button class="btn btn--sm" type="button" data-inbox-settings>${escapeHTML(STR.inboxOpenSettings)}</button>`);
  if (!inAsset.length) return empty(STR.inboxEmptyTitle, STR.inboxEmptyDesc);
  if (!searched.length) return empty(STR.inboxSearchEmpty);
  return empty(STR[`inboxFilterEmpty_${state.inbox.filter}`], '', `<button class="btn btn--sm btn--ghost" type="button" data-inbox-filter="all">${escapeHTML(STR.inboxShowAll)}</button>`);
}

function renderInboxRows(root) {
  const list = $('#inbox-rows', root);
  if (!list) return;
  const { inAsset, searched, rows } = inboxVisible();
  setInboxHTML($('#inbox-filters', root), INBOX_FILTERS.map(f => {
    const n = searched.filter(c => inboxMatches(c, f)).length;
    const on = state.inbox.filter === f;
    return `<button type="button" class="chip${on ? ' is-on' : ''}" data-inbox-filter="${f}" aria-pressed="${on}">${escapeHTML(STR[`inboxFilter_${f}`])}${f !== 'all' && n ? `<span class="chip__count">${n}</span>` : ''}</button>`;
  }).join(''));
  const focused = document.activeElement?.closest?.('[data-conversation]')?.dataset.conversation;
  setInboxHTML(list, rows.length ? rows.map(inboxRow).join('') : `<li class="inbox__empty">${inboxListEmpty(inAsset, searched)}</li>`);
  if (focused) $(`[data-conversation="${CSS.escape(focused)}"]`, list)?.focus();
}

function openChannelSettings() {
  state.settingsSection = 'channels';
  setActive('settings');
}

function renderThreadPlaceholder(root) {
  const pane = $('#inbox-thread', root);
  if (!pane) return;
  pane.innerHTML = `<div class="thread-empty empty"><p class="empty__title">${escapeHTML(STR.noThread)}</p><p class="empty__desc">${escapeHTML(STR.noThreadDesc)}</p></div>`;
}

// One set of listeners on the thread pane survives every re-render of its parts.
function bindThreadPane(root) {
  const pane = $('#inbox-thread', root);
  if (!pane) return;
  pane.addEventListener('click', e => {
    const target = e.target.closest('button, a');
    if (!target) return;
    if (target.id === 'inbox-back') return closeInboxThread(root);
    if (target.id === 'inbox-jump') {
      const log = $('#inbox-log', root);
      log.scrollTop = log.scrollHeight;
      target.hidden = true;
      return;
    }
    if (target.hasAttribute('data-media-expand')) return target.closest('.bubble__media-box')?.classList.toggle('is-expanded');
    if (target.dataset.aiToggle) return toggleInboxAi(root, target.dataset.aiToggle === 'on', target);
    if (target.hasAttribute('data-open-template')) return openTemplateDrawer(inboxConversation(state.selectedConversation));
    if (target.dataset.retry) return retryInboxMessage(root, target.dataset.retry, target);
    if (target.hasAttribute('data-thread-retry')) return openInboxThread(state.selectedConversation, root, { reload: true });
    if (target.hasAttribute('data-inbox-settings')) return openChannelSettings();
  });
  pane.addEventListener('submit', e => {
    if (e.target.id !== 'inbox-form') return;
    e.preventDefault();
    sendInboxText(root);
  });
  pane.addEventListener('input', e => {
    if (e.target.id !== 'inbox-input') return;
    state.inbox.drafts[state.selectedConversation] = e.target.value;
    autosizeComposer(e.target);
    updateComposerState(root);
    $('#inbox-error', root)?.setAttribute('hidden', '');
  });
  pane.addEventListener('keydown', e => {
    if (e.target.id !== 'inbox-input' || e.key !== 'Enter' || e.shiftKey || e.isComposing) return;
    e.preventDefault();
    sendInboxText(root);
  });
}

async function openInboxThread(id, root, opts = {}) {
  const conversation = inboxConversation(id);
  if (!conversation) return;
  const switching = opts.reload || state.inbox.threadFor !== id;
  state.selectedConversation = id;
  $('.inbox', root)?.setAttribute('data-thread-open', 'true');
  $$('[data-conversation]', root).forEach(b => {
    const on = b.dataset.conversation === id;
    b.classList.toggle('is-active', on);
    b.setAttribute('aria-current', on ? 'true' : 'false');
  });
  if (switching) {
    clearInboxMedia();
    state.inbox.threadFor = id;
    state.threadMessages = [];
    state.inbox.cursor = null;
    mountThread(root, conversation);
  }
  if (opts.focus && isNarrowInbox()) $('#inbox-thread-name', root)?.focus();
  try {
    await refreshThread(root, { initial: switching });
  } catch {
    if (switching && state.selectedConversation === id) renderThreadLog(root, { error: true });
  }
}

function closeInboxThread(root) {
  const id = state.selectedConversation;
  state.selectedConversation = null;
  state.inbox.threadFor = null;
  $('.inbox', root)?.setAttribute('data-thread-open', 'false');
  renderThreadPlaceholder(root);
  renderInboxRows(root);
  if (id) $(`[data-conversation="${CSS.escape(id)}"]`, root)?.focus();
}

function mountThread(root, c) {
  const pane = $('#inbox-thread', root);
  if (!pane) return;
  pane.innerHTML = `
    <header class="thread-head" id="inbox-head"></header>
    <div class="thread-banners" id="inbox-banner"></div>
    <div class="thread-log" id="inbox-log" role="log" aria-live="off" tabindex="0" aria-label="${escapeHTML(tApp('inboxLogAria', { name: inboxName(c) }))}"></div>
    <button type="button" class="thread-jump" id="inbox-jump" hidden>${inboxIcon('down')}<span>${escapeHTML(STR.inboxJumpNew)}</span></button>
    <footer class="composer" id="inbox-composer"></footer>`;
  renderThreadChrome(root);
  renderThreadLog(root, { loading: true });
  $('#inbox-log', root).addEventListener('scroll', e => { if (nearBottom(e.target)) $('#inbox-jump', root).hidden = true; }, { passive: true });
}

function threadHeadHtml(c) {
  const win = inboxWindow(c);
  const help = escapeHTML(STR.inboxWindowHelp);
  const windowPill = win.state === 'open' ? `<span class="pill pill--ok" title="${help}">${escapeHTML(tApp('inboxWindowOpen', { left: fmtTimeLeft(win.left) }))}</span>`
    : win.state === 'closing' ? `<span class="pill pill--warn" title="${help}">${escapeHTML(tApp('inboxWindowClosing', { left: fmtTimeLeft(win.left) }))}</span>`
    : win.state === 'closed' ? `<span class="pill" title="${help}">${escapeHTML(STR.inboxWindowClosed)}</span>` : '';
  const ai = c.channel === 'WEB' ? ''
    : c.autoReplyEnabled !== false
      ? `<span class="pill pill--ok">${escapeHTML(STR.inboxAiOn)}</span><button type="button" class="btn btn--sm btn--ghost" data-ai-toggle="off">${escapeHTML(STR.inboxAiPause)}</button>`
      : `<span class="pill pill--warn">${escapeHTML(STR.inboxAiPaused)}</span>`;
  const sub = [c.displayName ? `<span class="mono">${escapeHTML(inboxContact(c))}</span>` : '', escapeHTML(channelName(c.channel))].filter(Boolean).join(' · ');
  return `<button type="button" class="iconbtn thread-head__back" id="inbox-back" aria-label="${escapeHTML(STR.inboxBack)}">${inboxIcon('back')}</button>
    <span class="inbox-avatar inbox-avatar--lg" aria-hidden="true">${escapeHTML(inboxInitials(c))}</span>
    <div class="thread-head__who"><h2 class="thread-head__name" id="inbox-thread-name" tabindex="-1">${escapeHTML(inboxName(c))}</h2><span class="thread-head__sub">${sub}</span></div>
    <div class="thread-head__tools">${windowPill}${ai}</div>`;
}

function threadBannerHtml(c) {
  if (c.channel === 'WEB') return `<div class="thread-banner" role="note"><span>${escapeHTML(STR.webConversationReadOnly)}</span></div>`;
  if (c.autoReplyEnabled !== false) return '';
  const handedOff = c.autoReplyPausedBy === BOT_HANDOFF;
  const by = c.autoReplyPausedBy && !handedOff ? tApp('inboxPausedBy', { who: inboxAgentLabel(null, c.autoReplyPausedBy) }) : '';
  const meta = [by, c.autoReplyPausedAt ? relTime(c.autoReplyPausedAt) : ''].filter(Boolean).join(' · ');
  return `<div class="thread-banner thread-banner--warn" role="status">
    <div class="thread-banner__text"><strong>${escapeHTML(handedOff ? STR.inboxHandoffTitle : STR.inboxAiPausedTitle)}</strong><span>${escapeHTML(handedOff ? STR.inboxHandoffDesc : STR.inboxAiPausedDesc)}</span>${meta ? `<span class="thread-banner__meta">${escapeHTML(meta)}</span>` : ''}</div>
    <button type="button" class="btn btn--sm" data-ai-toggle="on">${escapeHTML(STR.inboxAiResume)}</button>
  </div>`;
}

function composerMode(c) {
  if (c.channel === 'WEB') return 'web';
  if (!inboxAssets().some(a => a.platform === c.channel)) return 'nochannel';
  if (c.channel !== 'WHATSAPP') return 'text';
  const win = inboxWindow(c);
  if (win.state !== 'closed') return 'text-wa';
  return win.never ? 'never' : 'closed';
}

function composerHtml(c, mode) {
  if (mode === 'web') return '';
  if (mode === 'nochannel') return `<div class="composer__notice"><p>${escapeHTML(STR.inboxNoChannel)}</p><button type="button" class="btn btn--sm" data-inbox-settings>${escapeHTML(STR.inboxOpenSettings)}</button></div>`;
  if (mode === 'closed' || mode === 'never') {
    const never = mode === 'never';
    return `<div class="composer__notice">
      <div class="composer__notice-text"><strong>${escapeHTML(never ? STR.inboxNeverWroteTitle : STR.inboxWindowClosedTitle)}</strong><p>${escapeHTML(never ? STR.inboxNeverWroteDesc : STR.inboxWindowClosedDesc)}</p></div>
      <button type="button" class="btn btn--primary" data-open-template>${inboxIcon('template')}<span>${escapeHTML(STR.inboxSendTemplate)}</span></button>
    </div>`;
  }
  const templateButton = mode === 'text-wa'
    ? `<button type="button" class="btn btn--ghost composer__template" data-open-template title="${escapeHTML(STR.inboxTemplateButtonTitle)}">${inboxIcon('template')}<span>${escapeHTML(STR.inboxTemplateButton)}</span></button>`
    : '';
  return `<form class="composer__form" id="inbox-form" novalidate>
      <label class="visually-hidden" for="inbox-input">${escapeHTML(STR.inboxComposerAria)}</label>
      <textarea class="inp composer__input" id="inbox-input" rows="1" placeholder="${escapeHTML(tApp('inboxComposerPlaceholder', { name: inboxName(c) }))}" aria-describedby="inbox-hint inbox-counter">${escapeHTML(state.inbox.drafts[c.id] || '')}</textarea>
      <div class="composer__actions">${templateButton}<button type="submit" class="btn btn--primary composer__send" id="inbox-send">${inboxIcon('send')}<span>${escapeHTML(STR.inboxSend)}</span></button></div>
    </form>
    <div class="composer__foot"><span class="hint" id="inbox-hint"></span><span class="hint composer__counter" id="inbox-counter"></span></div>
    <p class="hint hint--bad composer__error" id="inbox-error" role="alert" hidden></p>`;
}

function renderThreadChrome(root) {
  const c = inboxConversation(state.selectedConversation);
  if (!c) return;
  setInboxHTML($('#inbox-head', root), threadHeadHtml(c));
  setInboxHTML($('#inbox-banner', root), threadBannerHtml(c));
  const composer = $('#inbox-composer', root);
  const mode = composerMode(c);
  if (composer && composer.dataset.mode !== mode) {
    composer.dataset.mode = mode;
    composer.innerHTML = composerHtml(c, mode);
    const input = $('#inbox-input', root);
    if (input) autosizeComposer(input);
  }
  updateComposerState(root);
}

function autosizeComposer(input) {
  input.style.height = 'auto';
  input.style.height = `${Math.min(input.scrollHeight, 180)}px`;
}

function updateComposerState(root) {
  const c = inboxConversation(state.selectedConversation);
  const input = $('#inbox-input', root);
  if (!c || !input) return;
  const text = input.value.trim();
  const left = INBOX_MAX_TEXT - text.length;
  const counter = $('#inbox-counter', root);
  counter.textContent = left < 500 ? (left >= 0 ? tApp('inboxCharsLeft', { n: left }) : tApp('inboxCharsOver', { n: -left })) : '';
  counter.classList.toggle('hint--bad', left < 0);
  const win = inboxWindow(c);
  const hint = $('#inbox-hint', root);
  const closing = win.state === 'closing';
  hint.textContent = closing ? tApp('inboxClosingSoon', { left: fmtTimeLeft(win.left) }) : (window.matchMedia('(pointer: fine)').matches ? STR.inboxComposerHint : '');
  hint.classList.toggle('hint--warn', closing);
  $('#inbox-send', root).disabled = !text || left < 0 || state.inbox.sending;
}

function showComposerError(root, message) {
  const el = $('#inbox-error', root);
  if (el) { el.textContent = message; el.hidden = false; }
  else toast(message);
}

function inboxDayLabel(day) {
  const relative = relativeDayLabel(day);
  if (relative) return relative;
  const sameYear = day.slice(0, 4) === todayKey().slice(0, 4);
  return capFirst(fmtDayKey(day, sameYear ? { weekday: 'long', day: 'numeric', month: 'long' } : { day: 'numeric', month: 'long', year: 'numeric' }));
}

function inboxBubble(m) {
  const author = inboxAuthor(m);
  const out = author !== 'customer';
  const who = author === 'ai' ? STR.inboxAuthorAi
    : author === 'agent' ? inboxAgentLabel(m.agentUserId, m.agentName)
    : author === 'automation' ? m.agentName || '' : '';
  const label = [who, m.kind === 'template' ? tApp('inboxTemplateLabel', { name: m.templateName || '' }) : ''].filter(Boolean).join(' · ');
  // An agent from the Agents module or a chat flow sent it, not a person or the receptionist AI.
  const tag = author === 'automation' ? `<span class="bubble__tag">${escapeHTML(STR.inboxAutomation)}</span>` : '';
  const media = INBOX_MEDIA_KINDS.includes(m.kind) && !m.pending
    ? `<div class="bubble__media-box" data-media="${escapeHTML(m.id)}" data-kind="${escapeHTML(m.kind)}">${mediaBoxHtml(m)}</div>` : '';
  const text = m.text ? `<div class="bubble__text">${renderWhatsAppText(m.text)}</div>` : '';
  const failed = m.status === 'FAILED';
  const retry = failed && author === 'agent' && m.kind === 'text' ? `<button type="button" class="btn btn--sm btn--ghost" data-retry="${escapeHTML(m.id)}">${escapeHTML(STR.inboxRetry)}</button>` : '';
  return `<article class="bubble ${out ? 'bubble--out' : 'bubble--in'} bubble--${author}${failed ? ' is-failed' : ''}${m.pending ? ' is-pending' : ''}" data-message="${escapeHTML(m.id)}">
    ${out ? `<div class="bubble__author">${tag}${escapeHTML(label)}</div>` : ''}${media}${text}
    <div class="bubble__meta"><time datetime="${escapeHTML(m.createdAt)}" title="${escapeHTML(fmtDate(m.createdAt))}">${escapeHTML(fmtTime(m.createdAt))}</time>${out ? inboxTick(m) : ''}</div>
    ${failed ? `<div class="bubble__error"><span>${escapeHTML(inboxErrorText(m.errorKey, m.errorText))}</span>${retry}</div>` : ''}
  </article>`;
}

function renderThreadLog(root, opts = {}) {
  const log = $('#inbox-log', root);
  if (!log) return;
  if (opts.loading || opts.error) {
    log._sig = '';
    log.innerHTML = opts.loading
      ? '<div class="thread-skeleton" aria-hidden="true"><span></span><span></span><span></span></div>'
      : `<div class="empty"><p class="empty__title">${escapeHTML(STR.inboxThreadFailed)}</p><button type="button" class="btn btn--sm" data-thread-retry>${escapeHTML(STR.inboxTryAgain)}</button></div>`;
    return;
  }
  const msgs = state.threadMessages;
  const sig = `${uiLocale()}|${msgs.map(m => `${m.id}:${m.status}:${m.pending ? 1 : 0}`).join('|')}`;
  if (log._sig === sig && !opts.force) return;
  const first = !log._sig;
  const atBottom = first || nearBottom(log);
  const previousTop = log.scrollTop;
  log._sig = sig;
  if (!msgs.length) {
    log.innerHTML = `<div class="empty"><p class="empty__title">${escapeHTML(STR.inboxNoMessagesTitle)}</p><p class="empty__desc">${escapeHTML(STR.inboxNoMessagesDesc)}</p></div>`;
    return;
  }
  let html = '';
  let lastDay = '';
  for (const m of msgs) {
    const day = localDay(m.createdAt);
    if (day !== lastDay) {
      html += `<div class="thread-day" role="separator"><span>${escapeHTML(inboxDayLabel(day))}</span></div>`;
      lastDay = day;
    }
    html += inboxBubble(m);
  }
  log.innerHTML = html;
  loadInboxMedia(root);
  if (opts.stick || atBottom) {
    log.scrollTop = log.scrollHeight;
  } else {
    log.scrollTop = previousTop;
    if (opts.arrived) $('#inbox-jump', root).hidden = false;
  }
}

/** Merges fetched messages by id; optimistic messages still being sent stay until their request settles. */
function mergeThreadMessages(incoming, replace = false) {
  const known = new Set(state.threadMessages.map(m => m.id));
  const pending = state.threadMessages.filter(m => m.pending);
  const byId = new Map((replace ? [] : state.threadMessages.filter(m => !m.pending)).map(m => [m.id, m]));
  const arrived = [];
  for (const m of incoming) {
    if (!known.has(m.id)) arrived.push(m);
    byId.set(m.id, m);
  }
  state.threadMessages = [...byId.values(), ...pending].sort((a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt));
  return arrived;
}

async function refreshThread(root, { initial = false } = {}) {
  const id = state.selectedConversation;
  if (!id || state.inbox.threadFor !== id) return;
  const since = initial ? '' : (state.inbox.cursor || '');
  const data = await api(`/app/api/conversations/${encodeURIComponent(id)}/updates${since ? `?since=${encodeURIComponent(since)}` : ''}`);
  if (state.selectedConversation !== id || state.inbox.threadFor !== id) return;
  state.inbox.cursor = data.cursor;
  const arrived = mergeThreadMessages(data.messages, initial);
  upsertConversation(data.conversation);
  renderThreadChrome(root);
  renderThreadLog(root, { arrived: arrived.length > 0 });
  renderInboxRows(root);
  if (!initial && arrived.some(m => inboxAuthor(m) === 'customer')) {
    const announcer = $('#inbox-announce', root);
    if (announcer) announcer.textContent = tApp('inboxNewFrom', { name: inboxName(data.conversation) });
  }
  if ((data.conversation.unreadCount || 0) > 0 && document.visibilityState === 'visible') markConversationRead(id, root);
}

async function refreshInboxList(root) {
  const list = await api('/app/api/conversations');
  if (state.active !== 'conversations') return;
  state.conversations = list;
  state.fetched.conversations = true;
  renderInboxRows(root);
  renderNav();
}

async function markConversationRead(id, root) {
  const c = inboxConversation(id);
  if (!c || !(c.unreadCount > 0) || state.inbox.reading === id) return;
  state.inbox.reading = id;
  c.unreadCount = 0;
  renderInboxRows(root);
  try { upsertConversation(await api(`/app/api/conversations/${encodeURIComponent(id)}/read`, { method: 'POST' })); }
  catch { /* the next list refresh shows the real count */ }
  finally { state.inbox.reading = null; }
}

async function sendInboxText(root) {
  const c = inboxConversation(state.selectedConversation);
  const input = $('#inbox-input', root);
  if (!c || !input || state.inbox.sending) return;
  const text = input.value.trim();
  if (!text || text.length > INBOX_MAX_TEXT) return updateComposerState(root);
  $('#inbox-error', root)?.setAttribute('hidden', '');
  const tempId = `tmp-${Date.now()}`;
  state.threadMessages.push({
    id: tempId, role: 'ASSISTANT', text, status: 'SENT', createdAt: new Date().toISOString(), author: 'agent', kind: 'text',
    tracked: false, agentUserId: state.me?.user?.id || null, agentName: state.me?.user?.email || null, pending: true,
  });
  input.value = '';
  state.inbox.drafts[c.id] = '';
  autosizeComposer(input);
  state.inbox.sending = true;
  updateComposerState(root);
  renderThreadLog(root, { stick: true });
  const aiWasOn = c.autoReplyEnabled !== false;
  try {
    const sent = await api(`/app/api/conversations/${encodeURIComponent(c.id)}/messages`, { method: 'POST', body: JSON.stringify({ text }) });
    state.threadMessages = state.threadMessages.filter(m => m.id !== tempId);
    mergeThreadMessages([sent]);
    renderThreadLog(root, { stick: true });
    toast(aiWasOn ? STR.inboxSentAiPaused : STR.inboxSent);
    await refreshThread(root).catch(() => {});
    refreshInboxList(root).catch(() => {});
  } catch (e) {
    state.threadMessages = state.threadMessages.filter(m => m.id !== tempId);
    renderThreadLog(root, { force: true });
    state.inbox.drafts[c.id] = text;
    const field = $('#inbox-input', root);
    if (field && !field.value) { field.value = text; autosizeComposer(field); }
    showComposerError(root, inboxErrorText(e.code, e.detail));
    // The refresh swaps the form for the template notice, so the error also goes to a toast.
    if (e.code === 'window_closed') {
      toast(inboxErrorText(e.code));
      await refreshThread(root).catch(() => {});
    }
  } finally {
    state.inbox.sending = false;
    updateComposerState(root);
    $('#inbox-input', root)?.focus();
  }
}

async function retryInboxMessage(root, messageId, button) {
  const c = inboxConversation(state.selectedConversation);
  if (!c) return;
  button.disabled = true;
  try {
    const message = await api(`/app/api/conversations/${encodeURIComponent(c.id)}/messages/${encodeURIComponent(messageId)}/retry`, { method: 'POST' });
    mergeThreadMessages([message]);
    renderThreadLog(root, { force: true });
    toast(STR.inboxRetried);
  } catch (e) {
    toast(inboxErrorText(e.code, e.detail));
    button.disabled = false;
  }
}

async function toggleInboxAi(root, enabled, button) {
  const c = inboxConversation(state.selectedConversation);
  if (!c) return;
  button.disabled = true;
  try {
    upsertConversation(await api(`/app/api/conversations/${encodeURIComponent(c.id)}/auto-reply`, { method: 'PATCH', body: JSON.stringify({ enabled }) }));
    renderThreadChrome(root);
    renderInboxRows(root);
    renderNav();
    toast(enabled ? STR.inboxAiResumedToast : STR.inboxAiPausedToast);
  } catch {
    toast(STR.autoReplyToggleFailed);
    button.disabled = false;
  }
}

function stopInboxPolling() {
  clearTimeout(inboxThreadTimer);
  clearTimeout(inboxListTimer);
  inboxThreadTimer = null;
  inboxListTimer = null;
}

function startInboxPolling(root) {
  stopInboxPolling();
  const later = base => base * 2 ** Math.min(inboxFailures, 3);
  const threadTick = async () => {
    if (state.active !== 'conversations') return stopInboxPolling();
    if (document.visibilityState === 'visible' && state.inbox.threadFor && !state.inbox.sending) {
      try { await refreshThread(root); inboxFailures = 0; } catch { inboxFailures += 1; }
    }
    inboxThreadTimer = setTimeout(threadTick, later(INBOX_THREAD_POLL_MS));
  };
  const listTick = async () => {
    if (state.active !== 'conversations') return stopInboxPolling();
    if (document.visibilityState === 'visible') {
      try { await refreshInboxList(root); } catch { /* retried on the next tick */ }
    }
    inboxListTimer = setTimeout(listTick, later(INBOX_LIST_POLL_MS));
  };
  inboxThreadTimer = setTimeout(threadTick, INBOX_THREAD_POLL_MS);
  inboxListTimer = setTimeout(listTick, INBOX_LIST_POLL_MS);
}

// ── Template drawer: reply after the 24 h window, or start a new WhatsApp conversation ───────────
async function loadInboxTemplates(refresh = false) {
  const cached = state.inbox.templates;
  if (!refresh && cached && Date.now() - cached.at < 60000) return cached.list;
  const list = await api(`/app/api/whatsapp/templates${refresh ? '?refresh=1' : ''}`);
  state.inbox.templates = { at: Date.now(), list };
  return list;
}

function templateCategoryLabel(category) {
  const key = `inboxTemplateCategory_${category || ''}`;
  return category && STR[key] !== `app.${key}` ? STR[key] : (category || '');
}

function renderTemplateText(t, values) {
  const fill = s => escapeHTML(s).replace(/\{\{\s*([A-Za-z0-9_]+)\s*\}\}/g, (all, key) => {
    const value = (values[key] || '').trim();
    return value ? escapeHTML(value) : `<span class="wa-var">{{${escapeHTML(key)}}}</span>`;
  });
  return [t.header ? `<strong>${escapeHTML(t.header)}</strong>` : '', fill(t.body || ''), t.footer ? `<span class="wa-preview__footer">${escapeHTML(t.footer)}</span>` : ''].filter(Boolean).join('\n\n');
}

async function openTemplateDrawer(conversation) {
  const body = document.createElement('div');
  body.className = 'wa-send';
  body.innerHTML = '<div class="thread-skeleton" aria-hidden="true"><span></span><span></span></div>';
  const title = conversation ? tApp('inboxTemplateTitleTo', { name: inboxName(conversation) }) : STR.inboxNewTitle;
  const gen = openDrawer(title, body, false, { eyebrow: channelName('WHATSAPP') });
  const load = async refresh => {
    try {
      const templates = await loadInboxTemplates(refresh);
      if (drawerGen === gen) renderTemplateForm(body, templates, conversation, load);
    } catch (e) {
      if (drawerGen === gen) renderTemplateDrawerMessage(body, STR.inboxTemplatesFailedTitle, inboxErrorText(e.code, e.detail), load);
    }
  };
  await load(false);
}

function renderTemplateDrawerMessage(body, title, desc, load, offerCreate = false) {
  body.innerHTML = `<div class="empty"><p class="empty__title">${escapeHTML(title)}</p><p class="empty__desc">${escapeHTML(desc)}</p></div>
    <div class="drawer__foot">
      <button type="button" class="btn btn--ghost" data-close>${escapeHTML(STR.cancel)}</button>
      <button type="button" class="btn${offerCreate ? '' : ' btn--accent'}" data-wa-tpl-reload>${escapeHTML(offerCreate ? STR.inboxTemplatesRefresh : STR.inboxTryAgain)}</button>
      ${offerCreate ? `<button type="button" class="btn btn--accent" data-wa-tpl-create>${escapeHTML(STR.tplNew)}</button>` : ''}
    </div>`;
  bindDrawerClose(body);
  $('[data-wa-tpl-reload]', body).addEventListener('click', () => {
    body.innerHTML = '<div class="thread-skeleton" aria-hidden="true"><span></span><span></span></div>';
    load(true);
  });
  $('[data-wa-tpl-create]', body)?.addEventListener('click', openTemplateEditor);
  ($('[data-wa-tpl-create]', body) || $('[data-wa-tpl-reload]', body)).focus();
}

function renderTemplateForm(body, templates, conversation, load) {
  if (!templates.length) return renderTemplateDrawerMessage(body, STR.inboxTemplatesEmptyTitle, STR.inboxTemplatesEmptyDesc, load, true);
  const isNew = !conversation;
  const ordered = [...templates].sort((a, b) => Number(b.sendable) - Number(a.sendable) || a.name.localeCompare(b.name));
  const firstSendable = ordered.findIndex(t => t.sendable);
  const options = ordered.map((t, i) => `<option value="${i}" ${i === firstSendable ? 'selected' : ''} ${t.sendable ? '' : 'disabled'}>${escapeHTML(`${t.name} · ${t.language}${t.sendable ? '' : ` — ${STR.inboxTemplateUnsupportedShort}`}`)}</option>`).join('');
  body.innerHTML = `<form class="form" id="wa-send-form" novalidate>
      <div class="form__grid">
        ${isNew ? `<div class="form__row form__row--full">
          <label class="lbl" for="wa-send-phone">${escapeHTML(STR.inboxPhoneLabel)} <span class="req">*</span></label>
          <input class="inp inp--mono" id="wa-send-phone" type="tel" inputmode="tel" autocomplete="tel" required placeholder="${escapeHTML(STR.inboxPhonePlaceholder)}" aria-describedby="wa-send-phone-hint" />
          <p class="hint" id="wa-send-phone-hint">${escapeHTML(STR.inboxPhoneHint)}</p>
        </div>` : ''}
        <div class="form__row form__row--full">
          <label class="lbl" for="wa-send-template">${escapeHTML(STR.inboxTemplateField)}</label>
          <select class="sel" id="wa-send-template" aria-describedby="wa-send-meta">${options}</select>
          <p class="hint" id="wa-send-meta"></p>
        </div>
        <div class="form__row form__row--full wa-params" id="wa-send-params"></div>
        <div class="form__row form__row--full">
          <span class="lbl">${escapeHTML(STR.inboxTemplatePreview)}</span>
          <div class="wa-preview" id="wa-send-preview"></div>
        </div>
      </div>
      <p class="hint">${escapeHTML(isNew ? STR.inboxTemplateHintNew : STR.inboxTemplateHint)}</p>
      <p class="hint hint--bad" id="wa-send-error" role="alert" hidden></p>
    </form>
    <div class="drawer__foot">
      <button type="button" class="btn btn--ghost wa-send__manage" data-wa-tpl-manage>${escapeHTML(STR.inboxTemplatesManage)}</button>
      <button type="button" class="btn btn--ghost" data-close>${escapeHTML(STR.cancel)}</button>
      <button type="submit" form="wa-send-form" class="btn btn--accent" id="wa-send-submit" ${firstSendable < 0 ? 'disabled' : ''}>${escapeHTML(STR.inboxSendTemplate)}</button>
    </div>`;
  bindDrawerClose(body);
  $('[data-wa-tpl-manage]', body).addEventListener('click', openTemplateSettings);
  const values = {};
  const select = $('#wa-send-template', body);
  const selected = () => ordered[Number(select.value)];
  const paintPreview = () => {
    const t = selected();
    $('#wa-send-preview', body).innerHTML = `<div class="bubble bubble--out bubble--agent"><div class="bubble__text">${renderTemplateText(t, values)}</div>${t.buttons.length ? `<div class="wa-preview__buttons">${t.buttons.map(b => `<span>${escapeHTML(b)}</span>`).join('')}</div>` : ''}</div>`;
  };
  const paint = () => {
    const t = selected();
    $('#wa-send-meta', body).textContent = [templateCategoryLabel(t.category), t.language].filter(Boolean).join(' · ');
    $('#wa-send-params', body).innerHTML = t.params.length
      ? t.params.map(p => `<div class="wa-param"><label class="lbl" for="wa-send-p-${escapeHTML(p)}">${escapeHTML(tApp('inboxTemplateVar', { name: p }))} <span class="req">*</span></label><input class="inp" id="wa-send-p-${escapeHTML(p)}" data-param="${escapeHTML(p)}" maxlength="1024" required value="${escapeHTML(values[p] || '')}" /></div>`).join('')
      : `<p class="hint">${escapeHTML(STR.inboxTemplateNoVars)}</p>`;
    paintPreview();
  };
  const error = message => { const el = $('#wa-send-error', body); el.textContent = message; el.hidden = !message; };
  select.addEventListener('change', () => { error(''); paint(); });
  $('#wa-send-params', body).addEventListener('input', e => {
    if (e.target.dataset.param == null) return;
    values[e.target.dataset.param] = e.target.value;
    paintPreview();
  });
  $('#wa-send-form', body).addEventListener('submit', async e => {
    e.preventDefault();
    error('');
    const t = selected();
    const phoneInput = $('#wa-send-phone', body);
    const phone = phoneInput ? phoneInput.value.trim() : '';
    if (isNew && phone.replace(/\D/g, '').length < 8) { error(STR.inboxErr_invalid_phone); phoneInput.focus(); return; }
    const missing = t.params.find(p => !(values[p] || '').trim());
    if (missing !== undefined) { error(STR.inboxErr_template_params); $(`#wa-send-p-${CSS.escape(missing)}`, body)?.focus(); return; }
    const send = $('#wa-send-submit', body);
    send.disabled = true;
    send.textContent = STR.inboxSending;
    const payload = { name: t.name, language: t.language, params: Object.fromEntries(t.params.map(p => [p, values[p].trim()])) };
    try {
      if (isNew) {
        const started = await api('/app/api/conversations/start', { method: 'POST', body: JSON.stringify({ phone, ...payload }) });
        closeDrawer({ dismissed: true });
        upsertConversation(started.conversation);
        const whatsApp = inboxAssets().find(a => a.platform === 'WHATSAPP');
        if (whatsApp) rememberAsset(whatsApp.externalId);
        state.inbox.filter = 'all';
        state.search = '';
        $('#search').value = '';
        state.selectedConversation = started.conversation.id;
        toast(STR.inboxTemplateSent);
        if (state.active === 'conversations') mountInbox($('#view'));
        else await setActive('conversations');
      } else {
        const message = await api(`/app/api/conversations/${encodeURIComponent(conversation.id)}/template`, { method: 'POST', body: JSON.stringify(payload) });
        closeDrawer({ dismissed: true });
        toast(STR.inboxTemplateSent);
        const root = $('#view');
        if (state.active === 'conversations' && state.inbox.threadFor === conversation.id) {
          mergeThreadMessages([message]);
          renderThreadLog(root, { stick: true });
          refreshThread(root).catch(() => {});
        }
      }
    } catch (err) {
      error(inboxErrorText(err.code, err.detail));
      send.disabled = false;
      send.textContent = STR.inboxSendTemplate;
    }
  });
  paint();
  ($('#wa-send-phone', body) || select).focus();
}

function openTemplateSettings() {
  closeDrawer({ dismissed: true });
  state.settingsSection = 'templates';
  setActive('settings');
}

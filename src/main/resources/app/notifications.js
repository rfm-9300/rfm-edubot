/* Notifications: the top-bar bell. Every signed-in user has one, whatever modules the company has. The
   server keeps the sentence's parts (kind + params); the text is written here in the reader's language. */
const NOTIFY = I18N.section('app.notifications');
const NOTIFICATIONS_POLL_MS = 60000;
const NOTIFICATION_TONES = {
  agent_approval: 'warn', agent_failed: 'bad', agent_paused: 'warn', agent_task: 'info', agent_notice: 'accent', integration_reconnect: 'bad',
  service_submitted: 'warn', time_device_enrolled: 'info', time_missed_clock_out: 'warn', conversation_handoff: 'warn',
  change_order_decided: n => (n.params?.decision === 'REJECTED' ? 'warn' : 'ok'),
};
const notificationTone = n => {
  const tone = NOTIFICATION_TONES[n.kind];
  return (typeof tone === 'function' ? tone(n) : tone) || 'neutral';
};
let notifications = { items: [], unread: 0 };
let notificationsAt = 0;
let notificationsTimer = null;
// Bumped on sign-out so a poll that was in flight doesn't bring the last user's list back.
let notificationsGen = 0;
const notificationsShown = { drawer: -1, key: '' };

function renderBell() {
  const btn = $('#btn-notifications');
  if (!btn) return;
  const n = notifications.unread;
  const label = n ? I18N.t('app.notifications.ariaUnread', { n }) : NOTIFY.aria;
  btn.setAttribute('aria-label', label);
  btn.title = label;
  const count = $('.iconbtn__count', btn);
  count.hidden = !n;
  count.textContent = n > 9 ? '9+' : String(n);
}

async function loadNotifications() {
  const gen = notificationsGen;
  const data = await api('/app/api/notifications');
  if (gen !== notificationsGen) return;
  notifications = { items: data.items || [], unread: Number(data.unread) || 0 };
  notificationsAt = Date.now();
  renderBell();
  refreshNotificationsDrawer();
}

function startNotifications() {
  stopNotifications();
  const gen = notificationsGen;
  $('#btn-notifications').hidden = false;
  renderBell();
  const tick = async () => {
    if (gen !== notificationsGen) return;
    if (document.visibilityState === 'visible') await loadNotifications().catch(() => {});
    if (gen === notificationsGen) notificationsTimer = setTimeout(tick, NOTIFICATIONS_POLL_MS);
  };
  tick();
}

function stopNotifications() {
  notificationsGen += 1;
  clearTimeout(notificationsTimer);
  notificationsTimer = null;
  notifications = { items: [], unread: 0 };
  notificationsAt = 0;
  $('#btn-notifications').hidden = true;
  renderBell();
}

function notificationText(n) {
  const p = n.params || {};
  const kind = key => I18N.t(`app.notifications.kinds.${key}`, p);
  const from = p.agent ? I18N.t('app.notifications.fromAgent', { agent: p.agent }) : '';
  const line = (...parts) => parts.filter(Boolean).join(' · ');
  switch (n.kind) {
    case 'agent_approval': return { title: kind(Number(p.count) > 1 ? 'agent_approvalMany' : 'agent_approval'), detail: p.subject };
    case 'agent_failed': return { title: kind('agent_failed'), detail: line(p.subject, p.error ? window.AgentsUI?.reasonText(p.error) : '') };
    case 'agent_paused': return { title: kind('agent_paused'), detail: NOTIFY.pausedDetail };
    case 'agent_task': return { title: kind('agent_task'), detail: line(p.subject, from) };
    case 'agent_notice': return { title: n.body || kind('agent_notice'), detail: line(from, p.subject) };
    case 'integration_reconnect': {
      const key = `app.agents.integrations.${p.integration}`;
      const name = I18N.t(key) === key ? p.integration || '' : I18N.t(key);
      return { title: I18N.t('app.notifications.kinds.integration_reconnect', { name }), detail: line(p.account, NOTIFY.reconnectDetail) };
    }
    case 'service_submitted': return { title: kind('service_submitted'), detail: line(p.service, p.client) };
    case 'time_device_enrolled': return { title: kind('time_device_enrolled'), detail: p.device || '' };
    case 'time_missed_clock_out': return { title: kind('time_missed_clock_out'), detail: line(p.day ? fmtDay(p.day) : '', p.end ? `${TIME.overdueEnd} ${p.end}` : '') };
    case 'conversation_handoff': return { title: kind('conversation_handoff'), detail: line(p.reason, p.channel ? channelName(p.channel) : '') };
    case 'change_order_decided': return { title: kind(p.decision === 'REJECTED' ? 'change_order_rejected' : 'change_order_approved'), detail: line(p.title, p.project) };
    default: return { title: n.body || NOTIFY.title, detail: '' };
  }
}

function notificationRow(n) {
  const { title, detail } = notificationText(n);
  return `<li><button class="worklist__item" type="button" data-notification="${escapeHTML(n.id)}" data-tone="${n.read ? 'muted' : notificationTone(n)}">
    <span class="worklist__dot" aria-hidden="true"></span>
    <span class="worklist__main"><span class="worklist__title">${escapeHTML(title)}</span>${detail ? `<span class="worklist__detail">${escapeHTML(detail)}</span>` : ''}</span>
    <span class="worklist__side"><span class="worklist__when">${escapeHTML(relTime(n.createdAt))}</span></span>
  </button></li>`;
}

function notificationsHtml() {
  const { items, unread } = notifications;
  if (!items.length) return `<div class="empty"><p class="empty__title">${escapeHTML(NOTIFY.empty)}</p><p class="empty__desc">${escapeHTML(NOTIFY.emptyDesc)}</p></div>`;
  const fresh = items.filter(n => !n.read);
  const earlier = items.filter(n => n.read);
  const panel = (title, rows, tools = '') => `<section class="panel"><header class="panel__head"><h2 class="panel__title">${title}</h2>${tools}</header>
    <ul class="worklist worklist--wrap">${rows.map(notificationRow).join('')}</ul></section>`;
  return [
    fresh.length ? panel(`${escapeHTML(NOTIFY.unreadGroup)} <span class="tag">${Math.max(unread, fresh.length)}</span>`, fresh,
      `<div class="panel__tools"><button class="btn btn--ghost btn--sm" type="button" data-notifications-read-all>${escapeHTML(NOTIFY.markAllRead)}</button></div>`) : '',
    earlier.length ? panel(escapeHTML(NOTIFY.earlierGroup), earlier) : '',
  ].join('');
}

const notificationsKey = () => `${notifications.unread}|${notifications.items.map(n => `${n.id}:${n.read ? 1 : 0}`).join(',')}`;

function fillNotifications(body) {
  const focused = document.activeElement?.closest?.('[data-notification]')?.dataset.notification;
  notificationsShown.key = notificationsKey();
  body.innerHTML = notificationsHtml();
  $$('[data-notification]', body).forEach(b => b.addEventListener('click', () => {
    const n = notifications.items.find(x => x.id === b.dataset.notification);
    if (n) openNotification(n);
  }));
  $('[data-notifications-read-all]', body)?.addEventListener('click', e => markAllNotificationsRead(e.currentTarget));
  if (focused) $(`[data-notification="${CSS.escape(focused)}"]`, body)?.focus();
}

// Redraws the list in place when a poll or a click changed it and it's still the open drawer.
function refreshNotificationsDrawer() {
  if ($('#drawer').hidden || drawerGen !== notificationsShown.drawer || notificationsKey() === notificationsShown.key) return;
  const body = $('#drawer-body > .record');
  if (body) fillNotifications(body);
}

async function openNotifications() {
  if (!notificationsAt) {
    try { await loadNotifications(); }
    catch (err) { if (err.message !== 'unauthorized') toast(NOTIFY.loadFailed); return; }
  } else loadNotifications().catch(() => {});
  const body = document.createElement('div');
  body.className = 'record';
  fillNotifications(body);
  notificationsShown.drawer = openDrawer(NOTIFY.title, body, false, { eyebrow: state.me?.tenant?.name });
}

function markNotificationRead(n) {
  if (n.read) return;
  n.read = true;
  notifications.unread = Math.max(0, notifications.unread - 1);
  renderBell();
  api(`/app/api/notifications/${encodeURIComponent(n.id)}/read`, { method: 'POST' }).catch(() => {});
}

async function markAllNotificationsRead(btn) {
  btn.disabled = true;
  try {
    await api('/app/api/notifications/read-all', { method: 'POST' });
    notifications.items.forEach(n => { n.read = true; });
    notifications.unread = 0;
    renderBell();
    refreshNotificationsDrawer();
  } catch (err) {
    btn.disabled = false;
    if (err.message !== 'unauthorized') toast(NOTIFY.markAllFailed);
  }
}

// Opens what the notification is about, with the way back to the list: the approval, task, run or
// agent it points at, else its record, else its page.
function openNotification(n) {
  markNotificationRead(n);
  const back = { key: 'notifications', label: NOTIFY.title, open: () => openNotifications() };
  if (n.ref?.startsWith('submission:')) {
    if (submissionsOn()) openFrom(back, () => openSubmissionDetail(n.ref.slice('submission:'.length)));
    return;
  }
  if (n.ref?.startsWith('shift:')) {
    if (hasModule('timesheets')) openFrom(back, () => window.TimesheetsUI.openShift(n.ref.slice('shift:'.length)));
    return;
  }
  if (n.ref?.startsWith('employee:')) {
    if (hasModule('employees')) openFrom(back, () => openPayeeDrawer('employee', n.ref.slice('employee:'.length)));
    return;
  }
  if (n.ref && hasModule('agents') && window.AgentsUI) {
    if (n.ref !== 'inbox') return window.AgentsUI.openRef(n.ref, back);
    closeDrawer({ dismissed: true });
    window.AgentsUI.focusRef(n.ref);
    return setActive('agents');
  }
  if (canOpenAgentSubject(n.subject)) return openAgentSubject(n.subject, back);
  if (n.link && hasModule(n.link)) {
    closeDrawer({ dismissed: true });
    if (n.kind === 'integration_reconnect') state.settingsSection = 'channels';
    return setActive(n.link);
  }
  refreshNotificationsDrawer();
}

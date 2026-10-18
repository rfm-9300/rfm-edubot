/* Backups (/backoffice → Backups): the server's archives, and a manual backup the host's runner picks up
   within a minute. The page polls while one is waiting or running and says how it ended. */

const BACKUP_POLL_MS = 4000;
const BACKUP_STATE_PILLS = { requested: 'pill--warn', running: 'pill--info', succeeded: 'pill--ok', failed: 'pill--bad', stalled: 'pill--bad' };
let backupPoll = null;
// requestedAt of a run seen waiting or running on this page, so its outcome is announced once.
let watchedBackup = null;

function stopBackupPolling() {
  clearTimeout(backupPoll);
  backupPoll = null;
}

async function renderBackups() {
  stopBackupPolling();
  const B = T.backups;
  let data;
  try {
    data = await api('/admin/api/backups');
  } catch (e) {
    if (currentView === 'backups') $('#view').innerHTML = loadErrorHtml(e);
    return;
  }
  if (currentView !== 'backups') return;
  const run = data.current;
  const busy = !!run && (run.state === 'requested' || run.state === 'running');
  announceBackupOutcome(run);
  const hero = `<div class="view__hero"><div><h1 class="view__title">${escapeHTML(B.title)}</h1><p class="view__desc">${escapeHTML(B.desc)}</p></div>
    ${data.available ? `<div class="actions"><button class="btn btn--primary" type="button" id="backup-now" ${busy ? 'disabled' : ''}>${escapeHTML(busy ? B.inProgress : B.backupNow)}</button></div>` : ''}</div>`;
  if (!data.available) {
    $('#view').innerHTML = `${hero}<div class="panel"><div class="empty"><p class="empty__title">${escapeHTML(B.unavailableTitle)}</p><p class="empty__desc">${escapeHTML(B.unavailableDesc)}</p></div></div>`;
    return;
  }
  $('#view').innerHTML = `${hero}
    <div class="settings-stack">
      ${runnerNoticeHtml(data.runner)}
      ${run ? backupRunHtml(run) : ''}
      <div class="panel">
        <div class="panel__head"><h2 class="panel__title">${escapeHTML(B.listTitle)} <span class="tag">${data.archives.length}</span></h2></div>
        <div class="tbl-wrap"><table class="tbl tbl--stack"><thead><tr>
          <th>${escapeHTML(B.thDate)}</th><th>${escapeHTML(B.thFile)}</th><th class="right">${escapeHTML(B.thSize)}</th><th>${escapeHTML(B.thOffsite)}</th>
        </tr></thead><tbody>
        ${data.archives.length ? data.archives.map(backupArchiveRowHtml).join('')
          : `<tr><td colspan="4"><div class="empty"><p class="empty__title">${escapeHTML(B.emptyTitle)}</p><p class="empty__desc">${escapeHTML(B.emptyDesc)}</p></div></td></tr>`}
        </tbody></table></div>
      </div>
    </div>`;
  $('#backup-now')?.addEventListener('click', requestBackup);
  if (busy) backupPoll = setTimeout(() => { if (currentView === 'backups') renderBackups(); }, BACKUP_POLL_MS);
}

function announceBackupOutcome(run) {
  if (run && (run.state === 'requested' || run.state === 'running')) {
    watchedBackup = run.requestedAt;
    return;
  }
  if (watchedBackup && run?.requestedAt === watchedBackup && (run.state === 'succeeded' || run.state === 'failed')) {
    toast(run.state === 'succeeded' ? T.backups.doneToast : T.backups.failedToast);
  }
  watchedBackup = null;
}

function runnerNoticeHtml(runner) {
  if (runner?.online) return '';
  return warnNoticeHtml(runner?.lastSeenAt ? T.backups.runnerOffline({ at: fmtDate(runner.lastSeenAt) }) : T.backups.runnerMissing);
}

function backupRunHtml(run) {
  const B = T.backups;
  const by = run.requestedBy || '';
  const lines = {
    requested: () => B.requestedLine({ at: fmtDate(run.requestedAt), by }),
    running: () => B.runningLine({ at: fmtDate(run.startedAt), by }),
    succeeded: () => B.succeededLine({ at: fmtDate(run.finishedAt), archive: run.archive || '—' }),
    failed: () => B.failedLine({ at: fmtDate(run.finishedAt) }),
    stalled: () => B.stalledLine({ at: fmtDate(run.startedAt) }),
  };
  return `<div class="panel">
    <div class="panel__head"><h2 class="panel__title">${escapeHTML(B.lastTitle)}</h2><span class="pill ${BACKUP_STATE_PILLS[run.state] || 'pill--info'}">${escapeHTML(B.state[run.state] || run.state)}</span></div>
    <div class="panel__body">
      <p class="hint">${escapeHTML(lines[run.state]?.() || '')}</p>
      ${run.state === 'failed' && run.log ? `<pre class="log-tail">${escapeHTML(run.log)}</pre>` : ''}
    </div>
  </div>`;
}

function backupArchiveRowHtml(a) {
  const B = T.backups;
  return `<tr>
    <td>${escapeHTML(fmtDate(a.createdAt))}</td>
    <td class="id" ${dataLabel(B.thFile)}>${escapeHTML(a.name)}</td>
    <td class="num" ${dataLabel(B.thSize)}>${escapeHTML(formatBytes(a.sizeBytes))}</td>
    <td ${dataLabel(B.thOffsite)}>${a.uploaded ? `<span class="pill pill--ok">${escapeHTML(B.copied)}</span>` : `<span class="muted">${escapeHTML(B.notCopied)}</span>`}</td>
  </tr>`;
}

function formatBytes(bytes) {
  const units = ['byte', 'kilobyte', 'megabyte', 'gigabyte'];
  let value = Number(bytes) || 0;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
  return new Intl.NumberFormat(I18N.locale(), { style: 'unit', unit: units[unit], unitDisplay: 'short', maximumFractionDigits: unit === 0 ? 0 : 1 }).format(value);
}

async function requestBackup() {
  const B = T.backups;
  const button = $('#backup-now');
  if (button) button.disabled = true;
  try {
    await api('/admin/api/backups', { method: 'POST' });
    toast(B.requestedToast);
  } catch (e) {
    toast(e.code === 'backup_in_progress' ? B.busy : e.code === 'backups_unavailable' ? B.unavailableTitle : T.error({ msg: e.message }));
  }
  renderBackups();
}

/* Admins (/backoffice → Admins): who may sign in with Google, the server's ADMIN_EMAILS plus the ones added
   here. Env emails can't be removed here, and nobody can remove themselves. */

async function renderAdmins() {
  const A = T.admins;
  let data;
  try {
    data = await api('/admin/api/admins');
  } catch (e) {
    if (currentView === 'admins') $('#view').innerHTML = loadErrorHtml(e);
    return;
  }
  if (currentView !== 'admins') return;
  $('#view').innerHTML = `
    <div class="view__hero"><div><h1 class="view__title">${escapeHTML(A.title)}</h1><p class="view__desc">${escapeHTML(A.desc)}</p></div></div>
    <div class="settings-stack">
      ${data.googleReady ? '' : warnNoticeHtml(A.googleNotReady)}
      <div class="panel">
        <div class="panel__head"><h2 class="panel__title">${escapeHTML(A.listTitle)} <span class="tag">${data.admins.length}</span></h2></div>
        <div class="tbl-wrap"><table class="tbl tbl--stack tbl--flow"><thead><tr>
          <th>${escapeHTML(A.thEmail)}</th><th>${escapeHTML(A.thSource)}</th><th>${escapeHTML(A.thAdded)}</th><th class="right">${escapeHTML(T.thActions)}</th>
        </tr></thead><tbody>${data.admins.map(a => adminRowHtml(a, data.me)).join('')}</tbody></table></div>
      </div>
      <div class="panel">
        <div class="panel__head"><h2 class="panel__title">${escapeHTML(A.addTitle)}</h2></div>
        <div class="panel__body">
          <form class="form" id="admin-form" novalidate>
            <div class="form__row"><label class="lbl" for="admin-email">${escapeHTML(A.emailLabel)}</label><input class="inp" id="admin-email" type="email" autocomplete="off" spellcheck="false" placeholder="${escapeHTML(A.emailPlaceholder)}" /><div class="hint">${escapeHTML(A.addHint)}</div></div>
            <div class="actions"><button class="btn btn--primary" type="submit">${escapeHTML(A.add)}</button></div>
          </form>
        </div>
      </div>
    </div>`;
  bindAdmins();
}

function adminRowHtml(a, me) {
  const A = T.admins;
  const self = !!me && a.email === me.toLowerCase();
  const fromEnv = a.source === 'env';
  const action = fromEnv ? `<span class="muted">${escapeHTML(A.envLocked)}</span>`
    : self ? '' : `<button class="btn btn--sm btn--ghost" type="button" data-remove-admin="${escapeHTML(a.email)}">${escapeHTML(A.remove)}</button>`;
  return `<tr>
    <td class="name">${escapeHTML(a.email)}${self ? `<span class="muted"> · ${escapeHTML(A.you)}</span>` : ''}</td>
    <td ${dataLabel(A.thSource)}><span class="pill ${fromEnv ? 'pill--info' : 'pill--accent'}">${escapeHTML(fromEnv ? A.sourceEnv : A.sourceBackoffice)}</span></td>
    <td class="muted" ${dataLabel(A.thAdded)}>${a.addedAt ? escapeHTML(A.addedBy({ date: fmtDate(a.addedAt), by: a.addedBy })) : '—'}</td>
    <td class="right">${action}</td>
  </tr>`;
}

function adminErrorText(err) {
  const A = T.admins;
  const messages = { invalid_email: A.invalidEmail, already_allowed: A.alreadyAllowed, from_env: A.fromEnv, cannot_remove_self: A.cannotRemoveSelf, not_found: A.notFound };
  return messages[err.code] || T.error({ msg: err.message });
}

function bindAdmins() {
  const A = T.admins;
  const form = $('#admin-form');
  form?.addEventListener('submit', async e => {
    e.preventDefault();
    const input = $('#admin-email', form);
    const email = input.value.trim().toLowerCase();
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) { toast(A.invalidEmail); input.focus(); return; }
    if (!await confirmDialog({ title: A.addConfirmTitle, body: A.addConfirmBody({ email }), okLabel: A.add, danger: false })) return;
    const button = $('button[type=submit]', form);
    button.disabled = true;
    try {
      await api('/admin/api/admins', { method: 'POST', body: JSON.stringify({ email }) });
      toast(A.added({ email }));
      await renderAdmins();
      $('#admin-email')?.focus();
    } catch (err) {
      toast(adminErrorText(err));
      button.disabled = false;
    }
  });
  $$('[data-remove-admin]').forEach(b => b.addEventListener('click', async () => {
    const email = b.dataset.removeAdmin;
    if (!await confirmDialog({ title: A.removeConfirmTitle, body: A.removeConfirmBody({ email }), okLabel: A.remove })) return;
    try {
      await api(`/admin/api/admins/${encodeURIComponent(email)}`, { method: 'DELETE' });
      toast(A.removed({ email }));
    } catch (err) {
      toast(adminErrorText(err));
    }
    renderAdmins();
  }));
}

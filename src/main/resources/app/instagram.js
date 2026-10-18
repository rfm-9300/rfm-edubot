/* Instagram (/app → Instagram): the connected account's posts and the comments waiting for a reply,
   answered from a post's drawer. Connecting the account is in Settings (integrations.js). */

function igThumb(url) {
  if (url) return `<img class="ig-thumb" src="${escapeHTML(url)}" alt="" />`;
  return `<div class="ig-thumb ig-thumb--empty" aria-hidden="true">◇</div>`;
}

function captionPreview(text) {
  const value = (text || '').replace(/\s+/g, ' ').trim();
  if (!value) return STR.instagramNoCaption;
  return value.length > 80 ? `${value.slice(0, 77)}…` : value;
}

function renderInstagram(root) {
  const data = state.instagram || { connected: false, comments: [], media: [], unrepliedCount: 0 };
  if (!data.connected) {
    root.innerHTML = hero(labels.instagram, STR.instagramDesc) +
      `<div class="panel"><div class="empty"><p class="empty__title">${escapeHTML(STR.instagramNotConnected)}</p><p class="empty__desc">${escapeHTML(STR.instagramNotConnectedDesc)}</p><button type="button" class="btn btn--primary" data-ig-settings>${escapeHTML(STR.instagramGoSettings)}</button></div></div>`;
    $('[data-ig-settings]', root)?.addEventListener('click', async () => {
      state.settingsSection = 'channels';
      await setActive('settings');
    });
    return;
  }
  const filter = state.instagramFilter || 'needs';
  const unreplied = (data.comments || []).filter(c => c.needsReply);
  const banner = data.needsReconnect
    ? `<div class="panel" style="padding:16px;margin-bottom:16px"><p class="view__desc" style="margin:0 0 10px">${escapeHTML(STR.instagramReconnectBanner)}</p><button type="button" class="btn btn--sm btn--primary" data-ig-settings>${escapeHTML(STR.igReconnect)}</button></div>`
    : '';
  const tools = `<div class="panel__tools">
      <button type="button" class="chip ${filter === 'needs' ? 'is-on' : ''}" data-ig-filter="needs">${escapeHTML(STR.instagramNeedsReply)}</button>
      <button type="button" class="chip ${filter === 'posts' ? 'is-on' : ''}" data-ig-filter="posts">${escapeHTML(STR.instagramPosts)}</button>
    </div>`;
  const stats = statCards([
    { label: STR.instagramNeedsReply, value: data.unrepliedCount || unreplied.length },
    { label: STR.instagramPosts, value: (data.media || []).length },
  ]);
  let body;
  if (filter === 'posts') {
    const rows = (data.media || []).map(m => `<tr data-ig-media="${escapeHTML(m.id)}">
      <td>${igThumb(m.thumbnailUrl)}</td>
      <td class="name">${escapeHTML(captionPreview(m.caption))}</td>
      <td class="mono">${m.commentsCount ?? '—'}</td>
      <td>${m.unrepliedCount ? `<span class="pill pill--warn">${escapeHTML(String(m.unrepliedCount))}</span>` : '—'}</td>
      <td class="mono muted">${escapeHTML(fmtDate(m.publishedAt))}</td>
    </tr>`).join('');
    body = crmPanel({
      title: STR.instagramPosts,
      tag: (data.media || []).length,
      tools,
      head: `<tr><th></th><th>${STR.instagramThPost}</th><th>${STR.instagramThComment}</th><th>${STR.instagramUnreplied}</th><th>${STR.instagramThWhen}</th></tr>`,
      rows,
      empty: STR.instagramEmptyPosts,
      emptyDesc: STR.instagramEmptyPostsDesc,
    });
  } else {
    const rows = unreplied.map(c => `<tr data-ig-comment="${escapeHTML(c.id)}" data-ig-media="${escapeHTML(c.mediaId)}">
      <td>${igThumb(c.thumbnailUrl)}</td>
      <td class="name">${escapeHTML(c.fromUsername ? '@' + c.fromUsername : '—')}</td>
      <td>${escapeHTML(c.text || '')}</td>
      <td class="muted">${escapeHTML(captionPreview(c.caption))}</td>
      <td class="mono muted">${escapeHTML(fmtDate(c.createdAt))}</td>
    </tr>`).join('');
    body = crmPanel({
      title: STR.instagramNeedsReply,
      tag: unreplied.length,
      tools,
      head: `<tr><th></th><th>${STR.instagramThFrom}</th><th>${STR.instagramThComment}</th><th>${STR.instagramThPost}</th><th>${STR.instagramThWhen}</th></tr>`,
      rows,
      empty: STR.instagramEmptyComments,
      emptyDesc: STR.instagramEmptyCommentsDesc,
    });
  }
  root.innerHTML = hero(labels.instagram, STR.instagramDesc, stats) + banner + body;
  $$('[data-ig-filter]', root).forEach(b => b.addEventListener('click', () => {
    state.instagramFilter = b.dataset.igFilter;
    renderInstagram(root);
  }));
  $('[data-ig-settings]', root)?.addEventListener('click', async () => {
    state.settingsSection = 'channels';
    await setActive('settings');
  });
  $$('[data-ig-media]', root).forEach(el => el.addEventListener('click', () => openInstagramPost(el.dataset.igMedia, el.dataset.igComment)));
}

async function openInstagramPost(mediaId, commentId) {
  let detail;
  try { detail = await api(`/app/api/instagram/media/${encodeURIComponent(mediaId)}`); }
  catch { toast(STR.instagramSyncFailed); return; }
  const media = detail.media || {};
  const comments = detail.comments || [];
  const permalink = media.permalink
    ? `<a class="btn btn--sm btn--ghost" href="${escapeHTML(media.permalink)}" target="_blank" rel="noopener">${escapeHTML(STR.instagramOpenPost)}</a>`
    : '';
  const items = comments.map(c => `<div class="ig-comment${c.id === commentId ? ' is-target' : ''}${c.fromAccount ? ' ig-comment--own' : ''}">
      <strong>${escapeHTML(c.fromUsername ? '@' + c.fromUsername : (c.fromAccount ? (state.instagram?.username ? '@' + state.instagram.username : '—') : '—'))}</strong>
      ${c.needsReply ? `<span class="pill pill--warn">${escapeHTML(STR.instagramUnreplied)}</span>` : ''}
      <p>${escapeHTML(c.text || '')}</p>
      <span class="muted">${escapeHTML(fmtDate(c.createdAt))}</span>
      ${c.needsReply ? `<button type="button" class="btn btn--sm" data-ig-reply="${escapeHTML(c.id)}">${escapeHTML(STR.instagramReply)}</button>` : ''}
    </div>`).join('');
  const body = document.createElement('div');
  body.innerHTML = `<div class="ig-post">
      ${igThumb(media.thumbnailUrl)}
      <div><strong>${escapeHTML(captionPreview(media.caption))}</strong>${permalink}</div>
    </div>
    <div class="ig-comments">${items || `<p class="muted">${escapeHTML(STR.instagramEmptyComments)}</p>`}</div>
    <form class="form" id="ig-reply-form" hidden>
      <input type="hidden" name="commentId" />
      <div class="form__row"><label class="lbl">${escapeHTML(STR.instagramReply)}</label><textarea class="inp" name="message" rows="3" required placeholder="${escapeHTML(STR.instagramReplyPlaceholder)}"></textarea></div>
      <button class="btn btn--primary" type="submit">${escapeHTML(STR.instagramReply)}</button>
    </form>`;
  const form = $('#ig-reply-form', body);
  const showReply = id => {
    form.hidden = false;
    form.commentId.value = id;
    form.message.focus();
  };
  $$('[data-ig-reply]', body).forEach(b => b.addEventListener('click', () => showReply(b.dataset.igReply)));
  if (commentId && comments.some(c => c.id === commentId && c.needsReply)) showReply(commentId);
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const id = form.commentId.value;
    const message = form.message.value.trim();
    if (!id || !message) return;
    try {
      await api(`/app/api/instagram/comments/${encodeURIComponent(id)}/replies`, { method: 'POST', body: JSON.stringify({ message }) });
      toast(STR.instagramReplied);
      closeDrawer();
      await loadModule('instagram');
      render();
    } catch { toast(STR.instagramReplyFailed); }
  });
  openDrawer(labels.instagram, body, true);
}

/* Settings → WhatsApp templates: the company's message templates with Meta's review status, the editor
   that submits a new one for review, and deleting one. Sending a template is the inbox's template drawer. */

const TEMPLATE_LANGUAGES = ['pt_PT', 'pt_BR', 'en_US', 'en_GB', 'es_ES', 'es_MX', 'fr_FR', 'de_DE', 'it_IT'];
const TEMPLATE_STATUS_TONES = { APPROVED: 'ok', PENDING: 'warn', IN_APPEAL: 'info', PAUSED: 'warn', REJECTED: 'bad', DISABLED: 'bad' };
const TRASH_ICON = '<svg width="13" height="13" viewBox="0 0 16 16" aria-hidden="true" focusable="false"><path d="M3 5 L13 5 M6 5 L6 3 L10 3 L10 5 M5 5 L6 13 L10 13 L11 5" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></svg>';

function templateLanguageLabel(code) {
  try { return capFirst(new Intl.DisplayNames([uiLocale()], { type: 'language' }).of(String(code).replace('_', '-')) || code); }
  catch { return code; }
}
function templateStatusLabel(status) {
  const key = `tplStatus_${status}`;
  return STR[key] !== `app.${key}` ? STR[key] : status;
}
function defaultTemplateLanguage() {
  const locale = String(state.me?.tenant?.locale || uiLocale()).replace('-', '_');
  if (TEMPLATE_LANGUAGES.includes(locale)) return locale;
  return TEMPLATE_LANGUAGES.find(code => code.startsWith(locale.slice(0, 2))) || 'en_US';
}
const templateBodyVars = body => [...new Set([...String(body).matchAll(/\{\{\s*([A-Za-z0-9_]+)\s*\}\}/g)].map(m => m[1]))];

/** The same rules the server checks, so the form can say what's wrong before Meta is asked. */
function templateDraftProblem(d) {
  if (!/^[a-z0-9_]{1,512}$/.test(d.name)) return 'template_name_invalid';
  const placeholder = /\{\{\s*[A-Za-z0-9_]+\s*\}\}/;
  if (d.header && (d.header.length > 60 || placeholder.test(d.header))) return 'template_header_invalid';
  if (d.footer && (d.footer.length > 60 || placeholder.test(d.footer))) return 'template_footer_invalid';
  const text = d.body.trim();
  if (!text || d.body.length > 1024) return 'template_body_invalid';
  const vars = templateBodyVars(text);
  const numbers = vars.map(Number).sort((a, b) => a - b);
  if (vars.some(v => !/^\d+$/.test(v)) || numbers.some((n, i) => n !== i + 1)) return 'template_variables_invalid';
  if (vars.length && (text.startsWith('{{') || text.endsWith('}}'))) return 'template_variables_invalid';
  if (d.examples.length !== vars.length || d.examples.some(e => !e.trim())) return 'template_examples_missing';
  if (d.quickReplies.length > 3 || d.quickReplies.some(b => !b || b.length > 25) || new Set(d.quickReplies).size !== d.quickReplies.length) return 'template_buttons_invalid';
  return '';
}

function waTemplatesPanel() {
  const data = state.waTemplates;
  const head = `<div class="panel__head">
      <h2 class="panel__title">${escapeHTML(STR.tplTitle)}${data?.list ? ` <span class="tag">${data.list.length}</span>` : ''}</h2>
      <div class="panel__tools">
        <button type="button" class="btn btn--sm btn--ghost" data-wa-tpl-refresh ${data?.loading ? 'disabled' : ''}>${escapeHTML(STR.inboxTemplatesRefresh)}</button>
        <button type="button" class="btn btn--sm btn--primary" data-wa-tpl-new>${inboxIcon('plus')}<span>${escapeHTML(STR.tplNew)}</span></button>
      </div>
    </div>`;
  let content;
  if (!data || data.loading) {
    content = '<div class="thread-skeleton" aria-hidden="true"><span></span><span></span></div>';
  } else if (data.error) {
    content = `<div class="empty"><p class="empty__title">${escapeHTML(STR.inboxTemplatesFailedTitle)}</p><p class="empty__desc">${escapeHTML(inboxErrorText(data.error.code, data.error.detail))}</p><button type="button" class="btn btn--sm" data-wa-tpl-refresh>${escapeHTML(STR.inboxTryAgain)}</button></div>`;
  } else if (!data.list.length) {
    content = `<div class="empty"><p class="empty__title">${escapeHTML(STR.inboxTemplatesEmptyTitle)}</p><p class="empty__desc">${escapeHTML(STR.tplEmptyDesc)}</p><button type="button" class="btn btn--sm btn--primary" data-wa-tpl-new>${escapeHTML(STR.tplNew)}</button></div>`;
  } else {
    const rows = [...data.list].sort((a, b) => a.name.localeCompare(b.name) || a.language.localeCompare(b.language)).map(t => {
      const tone = TEMPLATE_STATUS_TONES[t.status];
      const excerpt = t.body.length > 90 ? `${t.body.slice(0, 87)}…` : t.body;
      const reason = t.status === 'REJECTED' && t.rejectedReason
        ? `<span class="wa-templates__reason">${escapeHTML(tApp('tplRejectedReason', { reason: t.rejectedReason.replace(/_/g, ' ').toLowerCase() }))}</span>` : '';
      return `<tr>
        <td class="name"><span class="mono">${escapeHTML(t.name)}</span><span class="wa-templates__excerpt">${escapeHTML(excerpt)}</span></td>
        <td>${escapeHTML(templateLanguageLabel(t.language))}</td>
        <td>${escapeHTML(templateCategoryLabel(t.category))}</td>
        <td><span class="pill ${tone ? `pill--${tone}` : ''}">${escapeHTML(templateStatusLabel(t.status))}</span>${reason}</td>
        <td class="actions"><button type="button" class="iconbtn iconbtn--danger" data-wa-tpl-delete="${escapeHTML(t.name)}" data-wa-tpl-id="${escapeHTML(t.id || '')}" data-wa-tpl-language="${escapeHTML(t.language)}" aria-label="${escapeHTML(tApp('tplDeleteAria', { name: t.name }))}" title="${escapeHTML(STR.tplDelete)}">${TRASH_ICON}</button></td>
      </tr>`;
    }).join('');
    content = `<div class="tbl-wrap"><table class="tbl wa-templates__table">
      <thead><tr><th>${escapeHTML(STR.tplColName)}</th><th>${escapeHTML(STR.tplColLanguage)}</th><th>${escapeHTML(STR.tplColCategory)}</th><th>${escapeHTML(STR.tplColStatus)}</th><th></th></tr></thead>
      <tbody>${rows}</tbody>
    </table></div>`;
  }
  return `<div class="panel wa-templates">${head}<div class="wa-templates__body"><p class="hint">${escapeHTML(STR.tplIntro)}</p>${content}</div></div>`;
}

function wireWaTemplatesPanel(root) {
  if (!state.waTemplates) loadWaTemplates();
  $$('[data-wa-tpl-refresh]', root).forEach(b => b.addEventListener('click', () => loadWaTemplates(true)));
  $$('[data-wa-tpl-new]', root).forEach(b => b.addEventListener('click', () => openTemplateEditor()));
  $$('[data-wa-tpl-delete]', root).forEach(b => b.addEventListener('click', () => deleteWaTemplate(b.dataset.waTplDelete, b.dataset.waTplId, b.dataset.waTplLanguage)));
}

async function loadWaTemplates(refresh = false) {
  const showing = () => state.active === 'settings' && state.settingsSection === 'templates';
  state.waTemplates = { loading: true };
  if (showing()) render();
  try { state.waTemplates = { list: await api(`/app/api/whatsapp/templates?all=1${refresh ? '&refresh=1' : ''}`) }; }
  catch (e) { state.waTemplates = { error: { code: e.code, detail: e.detail } }; }
  if (showing()) render();
}

async function deleteWaTemplate(name, id, language) {
  const ok = await confirmDialog({ title: STR.tplDeleteTitle, body: tApp('tplDeleteBody', { name, language: templateLanguageLabel(language) }), okLabel: STR.tplDelete });
  if (!ok) return;
  try {
    await api(`/app/api/whatsapp/templates/${encodeURIComponent(name)}${id ? `?id=${encodeURIComponent(id)}` : ''}`, { method: 'DELETE' });
    toast(STR.tplDeleted);
    state.inbox.templates = null;
    loadWaTemplates(true);
  } catch (e) {
    toast(inboxErrorText(e.code, e.detail));
  }
}

function openTemplateEditor() {
  const body = document.createElement('div');
  body.className = 'wa-editor';
  const languages = TEMPLATE_LANGUAGES.map(code => `<option value="${code}" ${code === defaultTemplateLanguage() ? 'selected' : ''}>${escapeHTML(`${templateLanguageLabel(code)} · ${code}`)}</option>`).join('');
  body.innerHTML = `<form class="form" id="wa-editor-form" novalidate>
      <div class="wa-editor__grid">
        <div class="form__grid">
          <div class="form__row form__row--full">
            <label class="lbl" for="wa-editor-name">${escapeHTML(STR.tplFieldName)} <span class="req">*</span></label>
            <input class="inp inp--mono" id="wa-editor-name" maxlength="512" autocomplete="off" spellcheck="false" required placeholder="${escapeHTML(STR.tplNamePlaceholder)}" aria-describedby="wa-editor-name-hint" />
            <p class="hint" id="wa-editor-name-hint">${escapeHTML(STR.tplNameHint)}</p>
          </div>
          <div class="form__row">
            <label class="lbl" for="wa-editor-category">${escapeHTML(STR.tplFieldCategory)}</label>
            <select class="sel" id="wa-editor-category" aria-describedby="wa-editor-category-hint">
              <option value="UTILITY">${escapeHTML(templateCategoryLabel('UTILITY'))}</option>
              <option value="MARKETING">${escapeHTML(templateCategoryLabel('MARKETING'))}</option>
            </select>
            <p class="hint" id="wa-editor-category-hint"></p>
          </div>
          <div class="form__row">
            <label class="lbl" for="wa-editor-language">${escapeHTML(STR.tplFieldLanguage)}</label>
            <select class="sel" id="wa-editor-language">${languages}</select>
          </div>
          <div class="form__row form__row--full">
            <label class="lbl" for="wa-editor-header">${escapeHTML(STR.tplFieldHeader)} <span class="opt">${escapeHTML(STR.tplOptional)}</span></label>
            <input class="inp" id="wa-editor-header" maxlength="60" autocomplete="off" />
          </div>
          <div class="form__row form__row--full">
            <label class="lbl" for="wa-editor-body">${escapeHTML(STR.tplFieldBody)} <span class="req">*</span></label>
            <textarea class="txt" id="wa-editor-body" rows="5" maxlength="1024" required aria-describedby="wa-editor-body-hint"></textarea>
            <div class="wa-editor__tools"><button type="button" class="btn btn--sm btn--ghost" id="wa-editor-add-var">${inboxIcon('plus')}<span>${escapeHTML(STR.tplAddVariable)}</span></button><span class="hint composer__counter" id="wa-editor-count"></span></div>
            <p class="hint" id="wa-editor-body-hint">${escapeHTML(STR.tplBodyHint)}</p>
          </div>
          <div class="form__row form__row--full wa-params" id="wa-editor-examples"></div>
          <div class="form__row form__row--full">
            <label class="lbl" for="wa-editor-footer">${escapeHTML(STR.tplFieldFooter)} <span class="opt">${escapeHTML(STR.tplOptional)}</span></label>
            <input class="inp" id="wa-editor-footer" maxlength="60" autocomplete="off" />
          </div>
          <div class="form__row form__row--full">
            <span class="lbl">${escapeHTML(STR.tplFieldButtons)} <span class="opt">${escapeHTML(STR.tplOptional)}</span></span>
            <div class="wa-editor__buttons" id="wa-editor-buttons"></div>
            <button type="button" class="btn btn--sm btn--ghost wa-editor__add" id="wa-editor-add-button">${inboxIcon('plus')}<span>${escapeHTML(STR.tplAddButton)}</span></button>
          </div>
        </div>
        <div class="wa-editor__preview">
          <span class="lbl">${escapeHTML(STR.inboxTemplatePreview)}</span>
          <div class="wa-preview" id="wa-editor-preview"></div>
          <p class="hint">${escapeHTML(STR.tplReviewHint)}</p>
        </div>
      </div>
      <p class="hint hint--bad" id="wa-editor-error" role="alert" hidden></p>
    </form>
    <div class="drawer__foot">
      <button type="button" class="btn btn--ghost" data-close>${escapeHTML(STR.cancel)}</button>
      <button type="submit" form="wa-editor-form" class="btn btn--accent" id="wa-editor-submit">${escapeHTML(STR.tplSubmit)}</button>
    </div>`;
  openDrawer(STR.tplNewTitle, body, true, { eyebrow: channelName('WHATSAPP') });

  const field = id => $(`#${id}`, body);
  const examples = {};
  let quickReplies = [];
  const draft = () => {
    const vars = templateBodyVars(field('wa-editor-body').value).sort((a, b) => Number(a) - Number(b));
    return {
      name: field('wa-editor-name').value.trim(),
      category: field('wa-editor-category').value,
      language: field('wa-editor-language').value,
      header: field('wa-editor-header').value.trim(),
      body: field('wa-editor-body').value,
      examples: vars.map(v => (examples[v] || '').trim()),
      footer: field('wa-editor-footer').value.trim(),
      quickReplies: quickReplies.map(b => b.trim()).filter(Boolean),
    };
  };
  const paintPreview = () => {
    const d = draft();
    const values = Object.fromEntries(templateBodyVars(d.body).map(v => [v, examples[v] || '']));
    const buttons = d.quickReplies.length ? `<div class="wa-preview__buttons">${d.quickReplies.map(b => `<span>${escapeHTML(b)}</span>`).join('')}</div>` : '';
    field('wa-editor-preview').innerHTML = d.body.trim() || d.header
      ? `<div class="bubble bubble--out bubble--agent"><div class="bubble__text">${renderTemplateText({ header: d.header, body: d.body, footer: d.footer }, values)}</div>${buttons}</div>`
      : `<p class="hint">${escapeHTML(STR.tplPreviewEmpty)}</p>`;
    field('wa-editor-count').textContent = `${d.body.length}/1024`;
  };
  const paintExamples = () => {
    const vars = templateBodyVars(field('wa-editor-body').value).sort((a, b) => Number(a) - Number(b));
    const focused = document.activeElement?.dataset?.example;
    field('wa-editor-examples').innerHTML = vars.length
      ? vars.map(v => `<div class="wa-param"><label class="lbl" for="wa-editor-ex-${escapeHTML(v)}">${escapeHTML(tApp('tplExampleFor', { name: v }))} <span class="req">*</span></label><input class="inp" id="wa-editor-ex-${escapeHTML(v)}" data-example="${escapeHTML(v)}" maxlength="200" value="${escapeHTML(examples[v] || '')}" placeholder="${escapeHTML(STR.tplExamplePlaceholder)}" /></div>`).join('')
      : '';
    if (focused) field(`wa-editor-ex-${focused}`)?.focus();
  };
  const paintButtons = () => {
    field('wa-editor-buttons').innerHTML = quickReplies.map((b, i) => `<div class="wa-editor__button"><label class="visually-hidden" for="wa-editor-btn-${i}">${escapeHTML(tApp('tplButtonLabel', { n: i + 1 }))}</label><input class="inp" id="wa-editor-btn-${i}" data-button="${i}" maxlength="25" value="${escapeHTML(b)}" /><button type="button" class="iconbtn" data-remove-button="${i}" aria-label="${escapeHTML(STR.tplRemoveButton)}">×</button></div>`).join('');
    field('wa-editor-add-button').disabled = quickReplies.length >= 3;
  };
  const paintCategory = () => {
    field('wa-editor-category-hint').textContent = field('wa-editor-category').value === 'MARKETING' ? STR.tplCategoryMarketingHint : STR.tplCategoryUtilityHint;
  };
  const error = message => { const el = field('wa-editor-error'); el.textContent = message; el.hidden = !message; };

  field('wa-editor-name').addEventListener('input', e => {
    const normalized = e.target.value.toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/[\s-]+/g, '_').replace(/[^a-z0-9_]/g, '');
    if (normalized !== e.target.value) e.target.value = normalized;
    error('');
  });
  field('wa-editor-category').addEventListener('change', paintCategory);
  ['wa-editor-header', 'wa-editor-footer'].forEach(id => field(id).addEventListener('input', () => { error(''); paintPreview(); }));
  field('wa-editor-body').addEventListener('input', () => { error(''); paintExamples(); paintPreview(); });
  field('wa-editor-add-var').addEventListener('click', () => {
    const input = field('wa-editor-body');
    const next = Math.max(0, ...templateBodyVars(input.value).map(Number).filter(Number.isFinite)) + 1;
    const token = `{{${next}}}`;
    const start = input.selectionStart ?? input.value.length;
    const end = input.selectionEnd ?? input.value.length;
    input.value = `${input.value.slice(0, start)}${token}${input.value.slice(end)}`;
    input.focus();
    input.setSelectionRange(start + token.length, start + token.length);
    paintExamples();
    paintPreview();
  });
  field('wa-editor-examples').addEventListener('input', e => {
    if (e.target.dataset.example == null) return;
    examples[e.target.dataset.example] = e.target.value;
    error('');
    paintPreview();
  });
  field('wa-editor-add-button').addEventListener('click', () => {
    if (quickReplies.length >= 3) return;
    quickReplies.push('');
    paintButtons();
    field(`wa-editor-btn-${quickReplies.length - 1}`)?.focus();
  });
  field('wa-editor-buttons').addEventListener('input', e => {
    if (e.target.dataset.button == null) return;
    quickReplies[Number(e.target.dataset.button)] = e.target.value;
    error('');
    paintPreview();
  });
  field('wa-editor-buttons').addEventListener('click', e => {
    const remove = e.target.closest('[data-remove-button]');
    if (!remove) return;
    quickReplies.splice(Number(remove.dataset.removeButton), 1);
    paintButtons();
    paintPreview();
  });
  field('wa-editor-form').addEventListener('submit', async e => {
    e.preventDefault();
    const d = draft();
    const problem = templateDraftProblem({ ...d, header: d.header || null, footer: d.footer || null });
    if (problem) { error(inboxErrorText(problem)); return; }
    const submit = field('wa-editor-submit');
    submit.disabled = true;
    submit.textContent = STR.inboxSending;
    try {
      const created = await api('/app/api/whatsapp/templates', {
        method: 'POST',
        body: JSON.stringify({ ...d, header: d.header || null, footer: d.footer || null }),
      });
      closeDrawer({ dismissed: true });
      toast(created.status === 'APPROVED' ? STR.tplApprovedToast : STR.tplSubmittedToast);
      state.inbox.templates = null;
      state.waTemplates = null;
      if (state.active === 'settings' && state.settingsSection === 'templates') loadWaTemplates(true);
    } catch (err) {
      error(inboxErrorText(err.code, err.detail));
      submit.disabled = false;
      submit.textContent = STR.tplSubmit;
    }
  });
  paintCategory();
  paintButtons();
  paintPreview();
}

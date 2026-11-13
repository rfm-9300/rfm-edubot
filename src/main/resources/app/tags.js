/* Tags (/app): free-text labels staff put on any record, kept apart from the record by the server
   (`record_tags`, one API under each module). Every list gets the same three things from here: a Tags column,
   a filter beside its search and, in a record's drawer, a strip to add and remove them (openDrawer's `tags`
   option). The server answers every tagged record of a kind as `id → tags`; that map is `state.tags[kind]`. */

const TAGS = I18N.section('app.tags');
// What the server allows (RecordTags): at most this many tags on a record, each this long.
const TAG_LIMIT = 10;
const TAG_MAX_LENGTH = 30;
// Each taggable list, by its table key, and the API path its tags are under.
const TAG_PATHS = {
  clients: '/app/api/crm/clients',
  suppliers: '/app/api/crm/suppliers',
  employees: '/app/api/crm/employees',
  services: '/app/api/crm/services',
  catalog: '/app/api/crm/standard-items',
  quotes: '/app/api/crm/quotes',
  invoices: '/app/api/crm/invoices',
  payments: '/app/api/crm/payments',
  projects: '/app/api/building/projects',
};

const tagsOf = (kind, id) => state.tags[kind]?.[id] || [];
const tagsText = (kind, id) => tagsOf(kind, id).join(' ');

// Loads (or reloads) the tags of `kind`. A company without that module answers 403, which just means none.
async function loadTags(kind) {
  state.tags[kind] = await api(`${TAG_PATHS[kind]}/tags`).catch(() => state.tags[kind] || {});
  return state.tags[kind];
}

// Every tag in use on `kinds`, once each ignoring case (the first spelling wins), in alphabetical order.
function tagVocabulary(...kinds) {
  const seen = new Map();
  for (const kind of kinds) {
    for (const tags of Object.values(state.tags[kind] || {})) {
      for (const tag of tags) if (!seen.has(tag.toLowerCase())) seen.set(tag.toLowerCase(), tag);
    }
  }
  return [...seen.values()].sort((a, b) => a.localeCompare(b, uiLocale(), { sensitivity: 'base' }));
}

const tagPills = tags => (tags.length ? `<div class="tags">${tags.map(t => `<span class="pill pill--tag">${escapeHTML(t)}</span>`).join('')}</div>` : '');

// The Tags column of a list. It is on by default once the company has tagged something in that list, so a
// company that never uses tags never sees an empty column.
function tagsColumn(kind) {
  return {
    id: 'tags', label: TAGS.title, default: Object.keys(state.tags[kind] || {}).length > 0,
    cell: row => `<td class="long">${tagPills(tagsOf(kind, row.id))}</td>`,
  };
}

// For a list that always shows every column (it has no column picker): the Tags column, or nothing until the company
// has tagged something in it. Spread it into the list's columns.
const tagsColumnIfUsed = kind => (Object.keys(state.tags[kind] || {}).length ? [tagsColumn(kind)] : []);

// ── Filtering a list by tag ────────────────────────────────────────────────────
// The choice is `t:<tag>` (compared ignoring case) or `none`; one that no record has any more counts as unset.
function tagFilterOf(kind) {
  const value = state.tagFilter[kind] || '';
  if (value.startsWith('t:') && !tagVocabulary(kind).some(t => t.toLowerCase() === value.slice(2).toLowerCase())) return '';
  return value;
}
const matchesTagFilter = (kind, id) => {
  const filter = tagFilterOf(kind);
  if (!filter) return true;
  const tags = tagsOf(kind, id);
  if (filter === 'none') return tags.length === 0;
  return tags.some(t => t.toLowerCase() === filter.slice(2).toLowerCase());
};
// A compact select for a panel's tools; nothing until the list has a tag to filter by.
function tagFilterHtml(kind) {
  const vocabulary = tagVocabulary(kind);
  if (!vocabulary.length) return '';
  const current = tagFilterOf(kind);
  const option = (value, label) => `<option value="${escapeHTML(value)}"${value === current ? ' selected' : ''}>${escapeHTML(label)}</option>`;
  return `<select class="sel" data-tag-filter="${kind}" aria-label="${escapeHTML(TAGS.filterAria)}">${option('', TAGS.filterAll)}${vocabulary.map(t => option(`t:${t}`, t)).join('')}${option('none', TAGS.filterNone)}</select>`;
}
function wireTagFilter(root, kind) {
  $(`[data-tag-filter="${kind}"]`, root)?.addEventListener('change', e => {
    state.tagFilter[kind] = e.target.value;
    render();
  });
}

// ── The strip in a record's drawer ─────────────────────────────────────────────
const tagErrorText = err => (TAGS.errors && typeof TAGS.errors === 'object' && err?.code && TAGS.errors[err.code]) || TAGS.saveFailed;

// Pills with a remove button, and a button that opens a field to add one. It keeps its own state, saves each change
// (the whole list goes to the server) and puts the list back when a save fails. The page behind the drawer is
// redrawn after each change so its column and filter follow.
function tagStrip(kind, id) {
  const el = document.createElement('div');
  el.className = 'tags-strip';
  el.hidden = true;
  let adding = false;
  let saving = Promise.resolve();

  const suggestions = () => {
    const have = new Set(tagsOf(kind, id).map(t => t.toLowerCase()));
    return tagVocabulary(...Object.keys(state.tags)).filter(t => !have.has(t.toLowerCase()));
  };

  function paint() {
    const tags = tagsOf(kind, id);
    const field = adding
      ? `<input class="inp tags-strip__input" type="text" list="tags-suggest" maxlength="${TAG_MAX_LENGTH}" placeholder="${escapeHTML(TAGS.newPlaceholder)}" aria-label="${escapeHTML(TAGS.addAria)}" autocomplete="off" spellcheck="false" />
         <datalist id="tags-suggest">${suggestions().map(t => `<option value="${escapeHTML(t)}"></option>`).join('')}</datalist>`
      : (tags.length < TAG_LIMIT ? `<button class="btn btn--sm btn--ghost" type="button" data-tag-add><span class="btn__plus" aria-hidden="true">+</span> ${escapeHTML(TAGS.add)}</button>` : '');
    el.innerHTML = `<span class="tags-strip__label">${escapeHTML(TAGS.title)}</span>
      ${tags.map(t => `<span class="pill pill--tag">${escapeHTML(t)}<button class="pill__x" type="button" data-tag-remove="${escapeHTML(t)}" aria-label="${escapeHTML(TAGS.remove({ tag: t }))}">×</button></span>`).join('')}
      ${field}`;
    $('[data-tag-add]', el)?.addEventListener('click', () => { adding = true; paint(); $('.tags-strip__input', el)?.focus(); });
    $$('[data-tag-remove]', el).forEach(b => b.addEventListener('click', () => save(tagsOf(kind, id).filter(t => t !== b.dataset.tagRemove))));
    const input = $('.tags-strip__input', el);
    if (!input) return;
    const commit = () => {
      const tag = input.value.trim().replace(/\s+/g, ' ');
      adding = false;
      if (!tag) return paint();
      const tags = tagsOf(kind, id);
      if (tags.some(t => t.toLowerCase() === tag.toLowerCase())) return paint();
      if (tags.length >= TAG_LIMIT) { toast(TAGS.errors.too_many_tags); return paint(); }
      save([...tags, tag]);
    };
    input.addEventListener('keydown', e => {
      // The drawer closes on Escape and moves focus on Tab; inside this field they only end it.
      if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); adding = false; paint(); $('[data-tag-add]', el)?.focus(); }
      if (e.key === 'Enter' || e.key === ',') { e.preventDefault(); e.stopPropagation(); commit(); }
    });
    input.addEventListener('blur', () => { if (adding) commit(); });
  }

  function save(next) {
    const previous = tagsOf(kind, id);
    const apply = tags => {
      const all = { ...(state.tags[kind] || {}) };
      if (tags.length) all[id] = tags; else delete all[id];
      state.tags[kind] = all;
    };
    apply(next);
    paint();
    saving = saving.then(async () => {
      try {
        const saved = await api(`${TAG_PATHS[kind]}/${encodeURIComponent(id)}/tags`, { method: 'PUT', body: JSON.stringify({ tags: next }) });
        apply(saved.tags);
      } catch (err) {
        apply(previous);
        toast(tagErrorText(err));
      }
      paint();
      render();
    });
  }

  // What is loaded shows at once; the server's answer follows, because another person may have tagged since.
  if (state.tags[kind]) { el.hidden = false; paint(); }
  loadTags(kind).then(() => { el.hidden = false; paint(); });
  return el;
}

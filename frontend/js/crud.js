/* Generic list / search / add / edit / delete screen driven by a small config object. */
window.initCrud = async function (cfg) {
  const root = document.getElementById('crud-root');
  const filters = cfg.filters || [];
  root.innerHTML = `
    <div class="page-header">
      <div><h1>${esc(cfg.plural)}</h1><p>${esc(cfg.subtitle || '')}</p></div>
      <button class="btn btn-primary" id="btnAdd"><i class="bi bi-plus-lg me-1"></i>Add ${esc(cfg.entity.toLowerCase())}</button>
    </div>
    <div class="card"><div class="card-body">
      <div class="row g-2 mb-3" id="filterBar">
        <div class="col-md-4"><div class="input-group"><span class="input-group-text bg-white"><i class="bi bi-search"></i></span>
          <input type="search" class="form-control" id="fq" placeholder="${esc(cfg.searchPlaceholder || 'Search…')}" aria-label="Search"></div></div>
        ${filters.map(f => `<div class="col-md-3 col-6"><select class="form-select" data-filter="${f.key}" aria-label="${esc(f.label)}"></select></div>`).join('')}
      </div>
      <div id="tableWrap">${loadingHtml()}</div>
    </div></div>`;

  // ----- filter dropdowns -----
  for (const f of filters) {
    const sel = root.querySelector(`[data-filter="${f.key}"]`);
    if (f.optionsFrom) await fillSelect(sel, f.optionsFrom, f.valueKey, f.labelKey, f.label + ': all');
    else sel.innerHTML = `<option value="">${esc(f.label)}: all</option>` + f.options.map(o => `<option value="${esc(o[0])}">${esc(o[1])}</option>`).join('');
  }

  // ----- load + draw the table -----
  const wrap = document.getElementById('tableWrap');
  async function load() {
    const params = { q: document.getElementById('fq').value };
    filters.forEach(f => params[f.key] = root.querySelector(`[data-filter="${f.key}"]`).value);
    try {
      const rows = await api(cfg.endpoint + qs(params));
      const hasFilter = Object.values(params).some(v => v);
      renderTable(wrap, cfg.columns, rows, r => {
        const extra = (cfg.rowActions || []).map((a, i) =>
          `<button class="btn btn-outline-secondary" data-act="${i}" data-id="${r[cfg.idKey]}" title="${esc(a.label)}"><i class="bi ${a.icon}"></i></button>`).join(' ');
        return `${extra} <button class="btn btn-outline-primary" data-edit="${r[cfg.idKey]}" title="Edit"><i class="bi bi-pencil"></i></button>
                <button class="btn btn-outline-danger" data-del="${r[cfg.idKey]}" title="Delete"><i class="bi bi-trash"></i></button>`;
      }, hasFilter ? 'No records match your search.' : 'No records yet. Use the Add button to create the first one.');
      wrap.querySelectorAll('[data-edit]').forEach(b => b.onclick = () => openForm(rows.find(r => r[cfg.idKey] == b.dataset.edit)));
      wrap.querySelectorAll('[data-del]').forEach(b => b.onclick = () => remove(rows.find(r => r[cfg.idKey] == b.dataset.del)));
      wrap.querySelectorAll('[data-act]').forEach(b => b.onclick = () => cfg.rowActions[b.dataset.act].run(rows.find(r => r[cfg.idKey] == b.dataset.id)));
    } catch (e) { wrap.innerHTML = errorHtml(e.message); }
  }
  document.getElementById('fq').addEventListener('input', debounce(load));
  root.querySelectorAll('[data-filter]').forEach(s => s.addEventListener('change', load));

  // ----- add / edit form -----
  const optionCache = {};
  async function optionsFor(f) {
    if (!f.optionsFrom) return f.options || [];
    if (!optionCache[f.optionsFrom]) optionCache[f.optionsFrom] = (await api(f.optionsFrom)).map(r => [r[f.valueKey], r[f.labelKey]]);
    return optionCache[f.optionsFrom];
  }
  async function openForm(row) {
    const editing = !!row;
    const fields = cfg.fields.filter(f => !(editing && f.createOnly));
    const inputs = [];
    for (const f of fields) {
      const v = editing ? row[f.key] : (f.default !== undefined ? f.default : '');
      const id = 'f_' + f.key;
      let control;
      if (f.type === 'select') {
        const opts = await optionsFor(f);
        control = `<select class="form-select" id="${id}" ${f.required ? 'required' : ''}>` +
          `<option value="">${f.required ? 'Select…' : '— none —'}</option>` +
          opts.map(o => `<option value="${esc(o[0])}" ${String(o[0]) === String(v ?? '') ? 'selected' : ''}>${esc(o[1])}</option>`).join('') + '</select>';
      } else if (f.type === 'checkbox') {
        control = `<div class="form-check form-switch pt-2"><input class="form-check-input" type="checkbox" id="${id}" ${v ? 'checked' : ''}><label class="form-check-label" for="${id}">${esc(f.hint || 'Yes')}</label></div>`;
      } else if (f.type === 'textarea') {
        control = `<textarea class="form-control" id="${id}" rows="2" maxlength="${f.maxlength || 500}">${esc(v ?? '')}</textarea>`;
      } else {
        control = `<input class="form-control" id="${id}" type="${f.type || 'text'}" value="${esc(v ?? '')}" ${f.required ? 'required' : ''}
          ${f.maxlength ? `maxlength="${f.maxlength}"` : ''} ${f.min !== undefined ? `min="${f.min}"` : ''} ${f.max !== undefined ? `max="${f.max}"` : ''} ${f.step ? `step="${f.step}"` : ''} ${f.pattern ? `pattern="${f.pattern}"` : ''}>`;
      }
      inputs.push(`<div class="${f.wide ? 'col-12' : 'col-md-6'}"><label class="form-label" for="${id}">${esc(f.label)}${f.required ? ' <span class="text-danger">*</span>' : ''}</label>${control}</div>`);
    }
    const m = showModal((editing ? 'Edit ' : 'Add ') + cfg.entity.toLowerCase(),
      `<form id="crudForm" novalidate><div id="formError"></div><div class="row g-3">${inputs.join('')}</div></form>`,
      { size: 'modal-lg', footer: `<button class="btn btn-light" data-bs-dismiss="modal">Cancel</button><button class="btn btn-primary" id="btnSave">${editing ? 'Save changes' : 'Add ' + esc(cfg.entity.toLowerCase())}</button>` });
    m.el.querySelector('#btnSave').onclick = async () => {
      const form = m.el.querySelector('#crudForm');
      if (!form.reportValidity()) return;
      const body = {};
      for (const f of fields) {
        const el = form.querySelector('#f_' + f.key);
        if (f.type === 'checkbox') body[f.key] = el.checked;
        else if (el.value === '') body[f.key] = null;
        else body[f.key] = (f.type === 'number' || f.type === 'select' && f.numeric) ? Number(el.value) : el.value;
      }
      const btn = m.el.querySelector('#btnSave'); btn.disabled = true;
      try {
        await api(editing ? `${cfg.endpoint}/${row[cfg.idKey]}` : cfg.endpoint, { method: editing ? 'PUT' : 'POST', body });
        m.modal.hide();
        toast(`${cfg.entity} ${editing ? 'updated' : 'added'}`);
        load();
      } catch (e) {
        m.el.querySelector('#formError').innerHTML = `<div class="alert alert-danger">${esc(e.message)}</div>`;
        btn.disabled = false;
      }
    };
  }
  document.getElementById('btnAdd').onclick = () => openForm(null);

  // ----- delete with confirmation -----
  async function remove(row) {
    const ok = await confirmDialog(`Delete ${esc(cfg.entity.toLowerCase())} <b>${esc(row[cfg.nameKey])}</b>? This cannot be undone.`);
    if (!ok) return;
    try { await api(`${cfg.endpoint}/${row[cfg.idKey]}`, { method: 'DELETE' }); toast(cfg.entity + ' deleted'); load(); }
    catch (e) { infoDialog(cfg.entity + ' cannot be deleted', e.message); }
  }
  load();
};

/* Stationary Shop - shared helpers: page shell, API calls, toasts, dialogs, printing. */
(function () {
  const NAV = [
    ['index.html', 'Dashboard', 'bi-speedometer2'], ['products.html', 'Products', 'bi-box-seam'],
    ['categories.html', 'Categories', 'bi-tags'], ['suppliers.html', 'Suppliers', 'bi-truck'],
    ['customers.html', 'Customers', 'bi-people'], ['employees.html', 'Employees', 'bi-person-badge'],
    ['inventory.html', 'Inventory', 'bi-clipboard-data'], ['orders.html', 'Sales / Orders', 'bi-receipt'],
    ['purchase-orders.html', 'Purchase Orders', 'bi-cart-plus'], ['reports.html', 'Reports', 'bi-bar-chart-line']
  ];

  // ---------- formatting ----------
  window.esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  window.money = n => '₹' + Number(n || 0).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  window.fmtNum = n => Number(n || 0).toLocaleString('en-IN');
  window.qs = obj => {
    const p = new URLSearchParams();
    Object.entries(obj).forEach(([k, v]) => { if (v !== '' && v !== null && v !== undefined) p.append(k, v); });
    const s = p.toString();
    return s ? '?' + s : '';
  };
  window.badge = (text) => {
    const map = { 'In Stock': 'st-green', 'Low Stock': 'st-yellow', 'Out of Stock': 'st-red', 'Inactive': 'st-grey',
      'Completed': 'st-green', 'Pending': 'st-yellow', 'Cancelled': 'st-red', 'Received': 'st-green', 'Ordered': 'st-blue',
      'Active': 'st-green', 'Sale': 'st-blue', 'Restock': 'st-green', 'Sale Cancelled': 'st-yellow', 'Opening Stock': 'st-grey' };
    return `<span class="status-badge ${map[text] || 'st-grey'}">${esc(text)}</span>`;
  };
  window.loadingHtml = (msg = 'Loading…') => `<div class="loading"><div class="spinner-border spinner-border-sm text-primary me-2"></div>${esc(msg)}</div>`;
  window.emptyHtml = (msg = 'Nothing to show yet.') => `<div class="empty"><i class="bi bi-inbox"></i>${esc(msg)}</div>`;
  window.errorHtml = (msg) => `<div class="alert alert-danger m-3"><i class="bi bi-exclamation-triangle me-1"></i>${esc(msg)}</div>`;

  // ---------- API ----------
  window.api = async function (path, opts = {}) {
    let res;
    try {
      res = await fetch((window.API_BASE || '') + path, {
        method: opts.method || 'GET',
        headers: opts.body !== undefined ? { 'Content-Type': 'application/json' } : {},
        body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined
      });
    } catch (e) {
      throw new Error('Cannot reach the Stationary Shop server. Is the backend running at ' + location.origin + '?');
    }
    const text = await res.text();
    let data = null;
    if (text) { try { data = JSON.parse(text); } catch (e) { data = text; } }
    if (!res.ok) throw new Error((data && data.message) || ('Request failed (' + res.status + ')'));
    return data;
  };

  // ---------- toast ----------
  window.toast = function (msg, type = 'ok', ms = 3500) {
    let area = document.querySelector('.toast-area');
    if (!area) { area = document.createElement('div'); area.className = 'toast-area'; document.body.appendChild(area); }
    const t = document.createElement('div');
    t.className = 'toast-msg' + (type === 'error' ? ' error' : '');
    t.setAttribute('role', 'status');
    t.textContent = msg;
    area.appendChild(t);
    setTimeout(() => t.remove(), type === 'error' ? Math.max(ms, 6000) : ms);
  };

  // ---------- modal helpers ----------
  window.showModal = function (title, bodyHtml, opts = {}) {
    const el = document.createElement('div');
    el.className = 'modal fade';
    el.tabIndex = -1;
    el.innerHTML = `<div class="modal-dialog ${opts.size || ''} modal-dialog-scrollable"><div class="modal-content">
      <div class="modal-header"><h5 class="modal-title">${esc(title)}</h5><button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close"></button></div>
      <div class="modal-body">${bodyHtml}</div>${opts.footer ? `<div class="modal-footer">${opts.footer}</div>` : ''}</div></div>`;
    document.body.appendChild(el);
    const modal = new bootstrap.Modal(el);
    el.addEventListener('hidden.bs.modal', () => { modal.dispose(); el.remove(); });
    modal.show();
    return { el, modal, body: el.querySelector('.modal-body') };
  };
  window.confirmDialog = function (message, okLabel = 'Delete', okClass = 'btn-danger') {
    return new Promise(resolve => {
      let answer = false;
      const m = showModal('Please confirm', `<p class="mb-0">${message}</p>`, {
        footer: `<button class="btn btn-light" data-bs-dismiss="modal">Cancel</button><button class="btn ${okClass}" id="dlgOk">${esc(okLabel)}</button>`
      });
      m.el.querySelector('#dlgOk').onclick = () => { answer = true; m.modal.hide(); };
      m.el.addEventListener('hidden.bs.modal', () => resolve(answer));
    });
  };
  window.infoDialog = (title, message) => showModal(title, `<p class="mb-0">${esc(message)}</p>`,
    { footer: '<button class="btn btn-primary" data-bs-dismiss="modal">OK</button>' });

  // ---------- generic table with client-side sorting ----------
  // columns: [{key,label,num,render(row),sortable}]  actions(row) -> html
  window.renderTable = function (el, columns, rows, actions, emptyMsg) {
    if (!rows.length) { el.innerHTML = emptyHtml(emptyMsg); return; }
    let sortKey = null, dir = 1;
    const draw = () => {
      const data = rows.slice();
      if (sortKey) data.sort((a, b) => {
        const x = a[sortKey], y = b[sortKey];
        if (x == null) return 1; if (y == null) return -1;
        const nx = Number(x), ny = Number(y);
        return (isNaN(nx) || isNaN(ny) ? String(x).localeCompare(String(y)) : nx - ny) * dir;
      });
      el.innerHTML = `<div class="table-wrap"><table class="table table-hover"><thead><tr>` +
        columns.map(c => `<th class="${c.num ? 'num' : ''} ${c.sortable === false ? '' : 'sortable'}" data-k="${c.key}">${esc(c.label)}${sortKey === c.key ? (dir > 0 ? ' ▴' : ' ▾') : ''}</th>`).join('') +
        (actions ? '<th></th>' : '') + `</tr></thead><tbody>` +
        data.map((r, i) => `<tr data-i="${rows.indexOf(r)}">` + columns.map(c =>
          `<td class="${c.num ? 'num' : ''}">${c.render ? c.render(r) : esc(r[c.key] ?? '')}</td>`).join('') +
          (actions ? `<td class="actions">${actions(r)}</td>` : '') + '</tr>').join('') + '</tbody></table></div>';
      el.querySelectorAll('th.sortable').forEach(th => th.onclick = () => {
        const k = th.dataset.k; if (sortKey === k) dir = -dir; else { sortKey = k; dir = 1; } draw();
      });
    };
    draw();
  };

  // ---------- printing: copies HTML into #print-root and prints only that ----------
  window.printHtml = function (html) {
    let root = document.getElementById('print-root');
    if (!root) { root = document.createElement('div'); root.id = 'print-root'; document.body.appendChild(root); }
    root.innerHTML = html;
    document.body.classList.add('printing');
    const done = () => { document.body.classList.remove('printing'); root.innerHTML = ''; window.removeEventListener('afterprint', done); };
    window.addEventListener('afterprint', done);
    window.print();
  };

  // ---------- fill <select> from an endpoint ----------
  window.fillSelect = async function (sel, url, valueKey, labelKey, firstLabel) {
    const rows = await api(url);
    sel.innerHTML = (firstLabel !== undefined ? `<option value="">${esc(firstLabel)}</option>` : '') +
      rows.map(r => `<option value="${esc(r[valueKey])}">${esc(typeof labelKey === 'function' ? labelKey(r) : r[labelKey])}</option>`).join('');
    return rows;
  };
  window.debounce = (fn, ms = 250) => { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; };
  window.newId = () => (crypto.randomUUID ? crypto.randomUUID() : 'r' + Date.now() + Math.random().toString(16).slice(2));

  // ---------- page shell: sidebar + top bar around <main> ----------
  const page = document.body.dataset.page;
  const main = document.querySelector('main');
  const title = document.body.dataset.title || 'Stationary Shop';
  const shell = document.createElement('div');
  shell.innerHTML = `
    <aside class="sidebar" id="sidebar">
      <a class="brand" href="index.html"><img src="assets/logo.svg" alt=""><span><b>AZAIN</b><small>The Stationary Shop</small></span></a>
      <nav aria-label="Main">${NAV.map(n => `<a class="nav-item-link ${n[0] === page ? 'active' : ''}" href="${n[0]}"><i class="bi ${n[2]}"></i>${n[1]}</a>`).join('')}</nav>
      <div class="sidebar-foot">Stationary Shop Management System</div>
    </aside>
    <div class="content-area">
      <header class="topbar">
        <button class="btn btn-light menu-btn" id="menuBtn" aria-label="Open menu"><i class="bi bi-list"></i></button>
        <h2>${esc(title)}</h2><span class="date">${new Date().toLocaleDateString('en-IN', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })}</span>
      </header>
      <div class="page-body" id="pageBody"></div>
    </div>`;
  document.body.prepend(...shell.children);
  document.getElementById('pageBody').appendChild(main);
  document.getElementById('menuBtn').onclick = () => document.getElementById('sidebar').classList.toggle('open');
  document.getElementById('pageBody').addEventListener('click', () => document.getElementById('sidebar').classList.remove('open'));
})();

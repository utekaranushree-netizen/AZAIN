(async function () {
  const $ = id => document.getElementById(id);

  async function load() {
    $('poTable').innerHTML = loadingHtml();
    try {
      const rows = await api('/api/purchase-orders' + qs({ status: $('pstatus').value }));
      renderTable($('poTable'), [
        { key: 'po_id', label: 'PO no.' }, { key: 'po_date', label: 'Date' }, { key: 'supplier_name', label: 'Supplier' },
        { key: 'item_count', label: 'Lines', num: true }, { key: 'total_cost', label: 'Total cost', num: true, render: r => money(r.total_cost) },
        { key: 'status', label: 'Status', render: r => badge(r.status) }
      ], rows, r => `<button class="btn btn-outline-secondary" data-v="${r.po_id}" title="View items"><i class="bi bi-eye"></i></button>` +
        (r.status === 'Ordered' ? ` <button class="btn btn-outline-success" data-rcv="${r.po_id}" title="Receive goods"><i class="bi bi-box-arrow-in-down"></i> Receive</button>
          <button class="btn btn-outline-danger" data-cx="${r.po_id}" title="Cancel"><i class="bi bi-x-circle"></i></button>` : ''),
      'No purchase orders yet.');
      const t = $('poTable');
      t.querySelectorAll('[data-v]').forEach(b => b.onclick = () => view(b.dataset.v));
      t.querySelectorAll('[data-rcv]').forEach(b => b.onclick = async () => {
        if (!await confirmDialog(`Receive all goods on purchase order <b>#${b.dataset.rcv}</b>? Stock will increase now. This can only be done once.`, 'Receive goods', 'btn-success')) return;
        try { await api('/api/purchase-orders/' + b.dataset.rcv + '/receive', { method: 'POST' }); toast('Goods received. Stock updated.'); load(); }
        catch (e) { infoDialog('Cannot receive goods', e.message); load(); }
      });
      t.querySelectorAll('[data-cx]').forEach(b => b.onclick = async () => {
        if (!await confirmDialog(`Cancel purchase order <b>#${b.dataset.cx}</b>? No stock changes.`, 'Cancel order')) return;
        try { await api('/api/purchase-orders/' + b.dataset.cx + '/cancel', { method: 'POST' }); toast('Purchase order cancelled'); load(); }
        catch (e) { infoDialog('Cannot cancel', e.message); load(); }
      });
    } catch (e) { $('poTable').innerHTML = errorHtml(e.message); }
  }

  async function view(id) {
    try {
      const po = await api('/api/purchase-orders/' + id);
      const total = po.items.reduce((s, i) => s + Number(i.line_total), 0);
      const m = showModal('Purchase order #' + id, `<p class="mb-2"><b>${esc(po.supplier_name)}</b> · ${esc(po.po_date)} · ${badge(po.status)}</p><div id="pi"></div><p class="text-end mt-2 mb-0"><b>Total: ${money(total)}</b></p>`, { size: 'modal-lg' });
      renderTable(m.el.querySelector('#pi'), [
        { key: 'product_name', label: 'Product' }, { key: 'quantity', label: 'Quantity', num: true },
        { key: 'unit_cost', label: 'Unit cost', num: true, render: r => money(r.unit_cost) }, { key: 'line_total', label: 'Line total', num: true, render: r => money(r.line_total) }
      ], po.items, null, 'No items.');
    } catch (e) { toast(e.message, 'error'); }
  }

  // ---------- new purchase order ----------
  $('newPo').onclick = async () => {
    let suppliers, products;
    try { [suppliers, products] = await Promise.all([api('/api/suppliers'), api('/api/products')]); } catch (e) { return toast(e.message, 'error'); }
    const lines = [];
    const m = showModal('New purchase order', `
      <div id="poErr"></div>
      <div class="mb-3"><label class="form-label" for="poSup">Supplier <span class="text-danger">*</span></label>
        <select id="poSup" class="form-select"><option value="">Select supplier…</option>${suppliers.map(s => `<option value="${s.supplier_id}">${esc(s.supplier_name)}</option>`).join('')}</select></div>
      <div class="row g-2 align-items-end mb-2">
        <div class="col-md-5"><label class="form-label mb-0 small" for="poProd">Product</label><select id="poProd" class="form-select"></select></div>
        <div class="col-4 col-md-2"><label class="form-label mb-0 small" for="poQty">Quantity</label><input type="number" id="poQty" class="form-control" min="1" step="1" value="10"></div>
        <div class="col-4 col-md-3"><label class="form-label mb-0 small" for="poCost">Unit cost (₹)</label><input type="number" id="poCost" class="form-control" min="0" step="0.01"></div>
        <div class="col-4 col-md-2"><button class="btn btn-outline-primary w-100" id="poAdd">Add</button></div></div>
      <div id="poLines"></div>`, { size: 'modal-lg', footer: '<button class="btn btn-light" data-bs-dismiss="modal">Cancel</button><button class="btn btn-primary" id="poSave">Create purchase order</button>' });
    const $m = sel => m.el.querySelector(sel);
    const fillProducts = () => {
      const sup = $m('#poSup').value;
      const list = products.filter(p => p.is_active && (!sup || String(p.supplier_id) === sup));
      $m('#poProd').innerHTML = list.map(p => `<option value="${p.product_id}" data-cost="${p.cost_price}">${esc(p.product_name)} (stock ${p.stock_qty})</option>`).join('') || '<option value="">No products for this supplier</option>';
      setCost();
    };
    const setCost = () => { const o = $m('#poProd').selectedOptions[0]; $m('#poCost').value = o && o.dataset.cost ? o.dataset.cost : ''; };
    const draw = () => {
      if (!lines.length) { $m('#poLines').innerHTML = emptyHtml('Add products to this purchase order.'); return; }
      const total = lines.reduce((s, l) => s + l.quantity * l.unit_cost, 0);
      $m('#poLines').innerHTML = `<table class="table table-sm"><thead><tr><th>Product</th><th class="num">Qty</th><th class="num">Unit cost</th><th class="num">Total</th><th></th></tr></thead><tbody>` +
        lines.map((l, i) => `<tr><td>${esc(l.name)}</td><td class="num">${l.quantity}</td><td class="num">${money(l.unit_cost)}</td><td class="num">${money(l.quantity * l.unit_cost)}</td>
        <td class="actions"><button class="btn btn-outline-danger" data-x="${i}"><i class="bi bi-x-lg"></i></button></td></tr>`).join('') +
        `</tbody><tfoot><tr><td colspan="3" class="text-end"><b>Total</b></td><td class="num"><b>${money(total)}</b></td><td></td></tr></tfoot></table>`;
      $m('#poLines').querySelectorAll('[data-x]').forEach(b => b.onclick = () => { lines.splice(b.dataset.x, 1); draw(); });
    };
    $m('#poSup').onchange = fillProducts; $m('#poProd').onchange = setCost;
    $m('#poAdd').onclick = () => {
      const pid = Number($m('#poProd').value), qty = Math.floor(Number($m('#poQty').value)), cost = Number($m('#poCost').value);
      if (!pid) return toast('Choose a product', 'error');
      if (!(qty >= 1)) return toast('Quantity must be at least 1', 'error');
      if (!(cost >= 0) || $m('#poCost').value === '') return toast('Enter a valid unit cost', 'error');
      const ex = lines.find(l => l.product_id === pid);
      if (ex) { ex.quantity += qty; ex.unit_cost = cost; } else lines.push({ product_id: pid, name: products.find(p => p.product_id === pid).product_name, quantity: qty, unit_cost: cost });
      draw();
    };
    $m('#poSave').onclick = async () => {
      if (!$m('#poSup').value) return $m('#poErr').innerHTML = '<div class="alert alert-danger">Select a supplier.</div>';
      if (!lines.length) return $m('#poErr').innerHTML = '<div class="alert alert-danger">Add at least one product.</div>';
      const btn = $m('#poSave'); btn.disabled = true;
      try {
        const r = await api('/api/purchase-orders', { method: 'POST', body: { supplier_id: Number($m('#poSup').value), items: lines.map(l => ({ product_id: l.product_id, quantity: l.quantity, unit_cost: l.unit_cost })) } });
        m.modal.hide(); toast('Purchase order #' + r.po_id + ' created. Stock will change when it is received.'); load();
      } catch (e) { $m('#poErr').innerHTML = `<div class="alert alert-danger">${esc(e.message)}</div>`; btn.disabled = false; }
    };
    fillProducts(); draw();
  };

  $('pstatus').onchange = load;
  load();
})();

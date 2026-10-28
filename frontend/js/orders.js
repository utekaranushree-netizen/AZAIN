(async function () {
  const $ = id => document.getElementById(id);
  let products = [], cart = [], requestId = newId();

  // ---------- setup ----------
  async function loadProducts() {
    products = await api('/api/products?status=active');
    drawProducts();
  }
  function drawProducts() {
    const q = $('pq').value.trim().toLowerCase();
    const list = products.filter(p => !q || p.product_name.toLowerCase().includes(q) || (p.brand || '').toLowerCase().includes(q));
    $('prodGrid').innerHTML = list.length ? list.map(p => `
      <button class="product-tile" data-id="${p.product_id}" ${p.stock_qty <= 0 ? 'disabled' : ''}>
        <div class="n">${esc(p.product_name)}</div><div class="b">${esc(p.brand || '')}</div>
        <div class="d-flex justify-content-between align-items-end mt-1"><span class="p">${money(p.price)}</span>
        <span class="status-badge ${p.stock_qty <= 0 ? 'st-red' : p.stock_qty <= p.reorder_level ? 'st-yellow' : 'st-green'}">${p.stock_qty <= 0 ? 'Out of stock' : p.stock_qty + ' in stock'}</span></div>
      </button>`).join('') : emptyHtml('No products match.');
    $('prodGrid').querySelectorAll('[data-id]').forEach(b => b.onclick = () => addToCart(Number(b.dataset.id)));
  }
  try {
    await Promise.all([
      loadProducts(),
      fillSelect($('custSel'), '/api/customers', 'customer_id', r => r.customer_name + (r.phone ? ' (' + r.phone + ')' : ''), 'Walk-in customer'),
      api('/api/employees').then(emps => {
        $('empSel').innerHTML = '<option value="">Select employee…</option>' + emps.map(e => `<option value="${e.emp_id}">${esc(e.emp_name)} – ${esc(e.role)}</option>`).join('');
        const cashier = emps.find(e => e.role === 'Cashier'); if (cashier) $('empSel').value = cashier.emp_id;
      })
    ]);
  } catch (e) { $('saleError').innerHTML = errorHtml(e.message); }

  // ---------- cart ----------
  function addToCart(id) {
    const p = products.find(x => x.product_id === id);
    const line = cart.find(l => l.product_id === id);
    if (line) { if (line.qty < p.stock_qty) line.qty++; else toast('Only ' + p.stock_qty + ' in stock', 'error'); }
    else cart.push({ product_id: id, name: p.product_name, price: Number(p.price), stock: p.stock_qty, qty: 1 });
    drawCart();
  }
  function totals() {
    const sub = cart.reduce((s, l) => s + l.price * l.qty, 0);
    const d = Math.min(Math.max(Number($('disc').value) || 0, 0), 50);
    const total = Math.round(sub * (1 - d / 100) * 100) / 100;
    return { sub, discAmt: Math.round((sub - total) * 100) / 100, total };
  }
  function drawCart() {
    if (!cart.length) $('cartBox').innerHTML = emptyHtml('The cart is empty. Click a product to add it.');
    else {
      $('cartBox').innerHTML = `<div class="table-wrap"><table class="table table-sm cart-table"><thead><tr><th>Product</th><th>Qty</th><th class="num">Line total</th><th></th></tr></thead><tbody>` +
        cart.map((l, i) => `<tr><td>${esc(l.name)}<div class="small text-muted">${money(l.price)} · ${l.stock} available</div></td>
          <td><input type="number" class="form-control form-control-sm" min="1" max="${l.stock}" value="${l.qty}" data-q="${i}" aria-label="Quantity"></td>
          <td class="num">${money(l.price * l.qty)}</td><td><button class="btn btn-sm btn-outline-danger" data-r="${i}" title="Remove"><i class="bi bi-x-lg"></i></button></td></tr>`).join('') + '</tbody></table></div>';
      $('cartBox').querySelectorAll('[data-q]').forEach(inp => inp.onchange = () => {
        const l = cart[inp.dataset.q]; let v = Math.floor(Number(inp.value)) || 1;
        if (v > l.stock) { toast('Only ' + l.stock + ' in stock', 'error'); v = l.stock; }
        l.qty = Math.max(1, v); drawCart();
      });
      $('cartBox').querySelectorAll('[data-r]').forEach(b => b.onclick = () => { cart.splice(b.dataset.r, 1); drawCart(); });
    }
    const t = totals();
    $('subTxt').textContent = money(t.sub); $('discTxt').textContent = '- ' + money(t.discAmt); $('totalTxt').textContent = money(t.total);
  }
  $('pq').addEventListener('input', drawProducts);
  $('disc').addEventListener('input', drawCart);
  $('clearBtn').onclick = () => { cart = []; drawCart(); };
  drawCart();

  // ---------- confirm sale ----------
  $('confirmBtn').onclick = async () => {
    $('saleError').innerHTML = '';
    const fail = m => { $('saleError').innerHTML = `<div class="alert alert-danger">${esc(m)}</div>`; };
    if (!cart.length) return fail('Add at least one product to the cart.');
    if (!$('empSel').value) return fail('Select the employee who served this customer.');
    const d = Number($('disc').value) || 0;
    if (d < 0 || d > 50) return fail('Discount must be between 0 and 50 percent.');
    const btn = $('confirmBtn'); btn.disabled = true;     // blocks double clicks; requestId also protects against repeats
    try {
      const res = await api('/api/orders', { method: 'POST', body: {
        customer_id: $('custSel').value ? Number($('custSel').value) : null, emp_id: Number($('empSel').value), discount_percent: d,
        payment_method: document.querySelector('input[name=pm]:checked').value, request_id: requestId,
        items: cart.map(l => ({ product_id: l.product_id, quantity: l.qty })) } });
      toast(res.duplicate ? 'This sale was already saved earlier (order #' + res.order_id + '); no second order was created' : 'Sale saved: order #' + res.order_id);
      cart = []; $('disc').value = 0; requestId = newId(); drawCart();
      await Promise.all([loadProducts(), loadOrders()]);
      showReceipt(res.order_id);
    } catch (e) { fail(e.message); loadProducts().catch(() => {}); }
    finally { btn.disabled = false; }
  };

  // ---------- receipt ----------
  async function showReceipt(id) {
    try {
      const r = await api('/api/orders/' + id + '/receipt');
      const html = `<div class="receipt">
        <h3>${esc(r.shop_name)}</h3><div class="sub">Receipt</div>
        <div>Order no: <b>#${r.order_id}</b></div><div>Date: ${esc(r.order_date)}</div>
        <div>Customer: ${esc(r.customer)}</div><div>Served by: ${esc(r.served_by)}</div><hr>
        <table><thead><tr><th align="left">Item</th><th class="r">Qty</th><th class="r">Price</th><th class="r">Total</th></tr></thead><tbody>
        ${r.items.map(i => `<tr><td>${esc(i.product_name)}</td><td class="r">${i.quantity}</td><td class="r">${Number(i.unit_price).toFixed(2)}</td><td class="r">${Number(i.line_total).toFixed(2)}</td></tr>`).join('')}
        </tbody></table><hr>
        <table><tr><td>Subtotal</td><td class="r">${money(r.subtotal)}</td></tr>
        <tr><td>Discount (${Number(r.discount_percent)}%)</td><td class="r">- ${money(r.discount_amount)}</td></tr>
        <tr><td><b>TOTAL</b></td><td class="r"><b>${money(r.total)}</b></td></tr>
        <tr><td>Payment</td><td class="r">${esc(r.payment_method || 'Not paid yet')}</td></tr>
        <tr><td>Status</td><td class="r">${esc(r.status)}</td></tr></table><hr>
        <div class="sub">Thank you for shopping at ${esc(r.shop_name)}!</div></div>`;
      const m = showModal('Receipt – order #' + id, html, { footer: '<button class="btn btn-light" data-bs-dismiss="modal">Close</button><button class="btn btn-primary" id="prt"><i class="bi bi-printer me-1"></i>Print receipt</button>' });
      m.el.querySelector('#prt').onclick = () => printHtml(html);
    } catch (e) { toast(e.message, 'error'); }
  }

  // ---------- orders list ----------
  async function loadOrders() {
    $('orderTable').innerHTML = loadingHtml();
    try {
      const rows = await api('/api/orders' + qs({ from: $('ofrom').value, to: $('oto').value, status: $('ostatus').value }));
      renderTable($('orderTable'), [
        { key: 'order_id', label: 'Order' }, { key: 'order_date', label: 'Date' }, { key: 'customer', label: 'Customer' }, { key: 'served_by', label: 'Served by' },
        { key: 'gross_amount', label: 'Gross', num: true, render: r => money(r.gross_amount) }, { key: 'discount_percent', label: 'Disc %', num: true },
        { key: 'net_amount', label: 'Net', num: true, render: r => money(r.net_amount) }, { key: 'payment_method', label: 'Payment' },
        { key: 'status', label: 'Status', render: r => badge(r.status) }
      ], rows, r => `<button class="btn btn-outline-primary" data-rc="${r.order_id}" title="Receipt"><i class="bi bi-printer"></i></button>` +
        (r.status === 'Pending' ? ` <button class="btn btn-outline-success" data-pay="${r.order_id}" title="Record payment"><i class="bi bi-cash-coin"></i></button>` : '') +
        (r.status !== 'Cancelled' ? ` <button class="btn btn-outline-danger" data-cx="${r.order_id}" title="Cancel order"><i class="bi bi-x-circle"></i></button>` : ''),
      'No orders match these filters.');
      const t = $('orderTable');
      t.querySelectorAll('[data-rc]').forEach(b => b.onclick = () => showReceipt(b.dataset.rc));
      t.querySelectorAll('[data-cx]').forEach(b => b.onclick = async () => {
        if (!await confirmDialog(`Cancel order <b>#${b.dataset.cx}</b>? Its stock is returned to the shelf and loyalty points are reversed. This cannot be undone.`, 'Cancel order')) return;
        try { await api('/api/orders/' + b.dataset.cx + '/cancel', { method: 'PUT' }); toast('Order cancelled and stock restored'); loadOrders(); loadProducts(); }
        catch (e) { infoDialog('Cannot cancel order', e.message); }
      });
      t.querySelectorAll('[data-pay]').forEach(b => b.onclick = async () => {
        const m = showModal('Record payment – order #' + b.dataset.pay, `<label class="form-label" for="pm2">Payment method</label><select id="pm2" class="form-select"><option>Cash</option><option>Card</option><option>UPI</option></select>`,
          { footer: '<button class="btn btn-light" data-bs-dismiss="modal">Cancel</button><button class="btn btn-primary" id="payOk">Record payment</button>' });
        m.el.querySelector('#payOk').onclick = async () => {
          m.el.querySelector('#payOk').disabled = true;     // no double submit (the server also locks the order)
          try { await api('/api/orders/' + b.dataset.pay + '/pay', { method: 'POST', body: { payment_method: m.el.querySelector('#pm2').value } }); m.modal.hide(); toast('Payment recorded'); loadOrders(); }
          catch (e) { m.modal.hide(); infoDialog('Payment not recorded', e.message); loadOrders(); }
        };
      });
    } catch (e) { $('orderTable').innerHTML = errorHtml(e.message); }
  }
  ['ofrom', 'oto', 'ostatus'].forEach(id => $(id).onchange = loadOrders);
  loadOrders();
})();

(async function () {
  const $ = id => document.getElementById(id);
  await fillSelect($('icat'), '/api/categories', 'category_id', 'category_name', 'Category: all');
  await fillSelect($('logProduct'), '/api/products', 'product_id', 'product_name', 'All products');

  async function loadLevels() {
    $('invTable').innerHTML = loadingHtml();
    try {
      const rows = await api('/api/inventory' + qs({ q: $('iq').value, category_id: $('icat').value, status: $('istatus').value }));
      renderTable($('invTable'), [
        { key: 'product_name', label: 'Product' }, { key: 'category_name', label: 'Category' },
        { key: 'stock_qty', label: 'Current stock', num: true }, { key: 'reorder_level', label: 'Reorder level', num: true },
        { key: 'stock_status', label: 'Stock status', render: r => badge(r.stock_status) },
        { key: 'supplier_name', label: 'Supplier' },
        { key: 'on_order_qty', label: 'On order', num: true, render: r => r.on_order_qty > 0 ? `<span class="text-primary">${r.on_order_qty}</span>` : '0' }
      ], rows, r => `<button class="btn btn-outline-secondary" data-log="${r.product_id}" title="Stock history"><i class="bi bi-clock-history"></i></button>`,
      'No products match these filters.');
      $('invTable').querySelectorAll('[data-log]').forEach(b => b.onclick = () => {
        $('logProduct').value = b.dataset.log; loadLog();
        bootstrap.Tab.getOrCreateInstance(document.querySelector('[data-bs-target="#tab-log"]')).show();
      });
    } catch (e) { $('invTable').innerHTML = errorHtml(e.message); }
  }
  async function loadLog() {
    $('logTable').innerHTML = loadingHtml();
    try {
      const rows = await api('/api/inventory/stock-log' + qs({ product_id: $('logProduct').value }));
      renderTable($('logTable'), [
        { key: 'logged_at', label: 'When' }, { key: 'product_name', label: 'Product' },
        { key: 'change_qty', label: 'Change', num: true, render: r => `<b class="${r.change_qty < 0 ? 'text-danger' : 'text-success'}">${r.change_qty > 0 ? '+' : ''}${r.change_qty}</b>` },
        { key: 'reason', label: 'Reason', render: r => badge(r.reason) }
      ], rows, null, 'No stock movements recorded.');
    } catch (e) { $('logTable').innerHTML = errorHtml(e.message); }
  }
  $('iq').addEventListener('input', debounce(loadLevels));
  $('icat').onchange = $('istatus').onchange = loadLevels;
  $('logProduct').onchange = loadLog;
  loadLevels(); loadLog();
})();

(async function () {
  const boxes = ['lowBox', 'recentBox', 'topBox'].map(id => document.getElementById(id));
  boxes.forEach(b => b.innerHTML = loadingHtml());
  try {
    const d = await api('/api/dashboard');
    document.querySelectorAll('[data-k]').forEach(el => {
      const v = d[el.dataset.k];
      el.textContent = el.dataset.money ? money(v) : fmtNum(v);
    });
    renderTable(document.getElementById('lowBox'), [
      { key: 'product_name', label: 'Product' }, { key: 'stock_qty', label: 'Stock', num: true, render: r => `<b class="text-danger">${r.stock_qty}</b>` },
      { key: 'reorder_level', label: 'Reorder at', num: true }
    ], d.low_stock, null, 'All products are above their reorder level.');
    renderTable(document.getElementById('recentBox'), [
      { key: 'order_id', label: 'Order' }, { key: 'order_date', label: 'Date' }, { key: 'customer', label: 'Customer' },
      { key: 'net_amount', label: 'Amount', num: true, render: r => money(r.net_amount) }, { key: 'status', label: 'Status', render: r => badge(r.status) }
    ], d.recent_orders, null, 'No orders yet.');
    renderTable(document.getElementById('topBox'), [
      { key: 'product_name', label: 'Product' }, { key: 'units_sold', label: 'Units sold', num: true },
      { key: 'revenue', label: 'Revenue', num: true, render: r => money(r.revenue) }
    ], d.top_selling, null, 'No sales yet.');

    const box = document.getElementById('chartBox');
    if (!d.monthly_sales.length) { box.innerHTML = emptyHtml('No completed sales yet.'); return; }
    new Chart(document.getElementById('salesChart'), {
      type: 'bar',
      data: { labels: d.monthly_sales.map(m => m.label),
        datasets: [{ label: 'Total sales (₹)', data: d.monthly_sales.map(m => Number(m.total_sales)), backgroundColor: '#4d7fa8', borderRadius: 6, maxBarThickness: 56 }] },
      options: { maintainAspectRatio: false, plugins: { legend: { display: false }, tooltip: { callbacks: {
        label: c => ' ' + money(c.parsed.y) + ' · ' + d.monthly_sales[c.dataIndex].total_orders + ' orders' } } },
        scales: { y: { beginAtZero: true, grid: { color: '#e4ebf0' }, ticks: { callback: v => '₹' + fmtNum(v) } }, x: { grid: { display: false } } } }
    });
  } catch (e) {
    document.getElementById('dashError').innerHTML = `<div class="alert alert-danger d-flex justify-content-between align-items-center"><span><i class="bi bi-exclamation-triangle me-1"></i>${esc(e.message)}</span><button class="btn btn-sm btn-outline-danger" onclick="location.reload()">Retry</button></div>`;
    boxes.forEach(b => b.innerHTML = '');
  }
})();

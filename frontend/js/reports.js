(function () {
  const $ = id => document.getElementById(id);
  const stamp = () => 'Generated on ' + new Date().toLocaleString('en-IN');
  let chart = null;

  // ================= Report 1: Sales and revenue =================
  async function runSales() {
    const box = $('salesReport');
    if ($('sfrom').value && $('sto').value && $('sfrom').value > $('sto').value) { box.innerHTML = errorHtml("The 'from' date cannot be after the 'to' date."); return; }
    box.innerHTML = loadingHtml();
    try {
      const d = await api('/api/reports/sales' + qs({ from: $('sfrom').value, to: $('sto').value }));
      const range = (d.from || d.to) ? `${d.from || 'start'} to ${d.to || 'today'}` : 'All dates';
      box.innerHTML = `
        <div class="report-print-title"><h2>Stationary Shop – Sales and Revenue Report</h2><p>Period: ${esc(range)} · ${esc(stamp())}</p></div>
        <div class="row g-3 mb-3">
          <div class="col-md-4"><div class="card stat"><div class="ico ico-yellow"><i class="bi bi-currency-rupee"></i></div><div><div class="val">${money(d.totals.total_sales)}</div><div class="lbl">Total completed sales</div></div></div></div>
          <div class="col-md-4"><div class="card stat"><div class="ico ico-teal"><i class="bi bi-bag-check"></i></div><div><div class="val">${fmtNum(d.totals.completed_orders)}</div><div class="lbl">Completed orders</div></div></div></div>
          <div class="col-md-4"><div class="card stat"><div class="ico ico-indigo"><i class="bi bi-receipt"></i></div><div><div class="val">${fmtNum(d.totals.total_orders)}</div><div class="lbl">All orders in period (any status)</div></div></div></div>
        </div>
        <div class="row g-3 mb-3">
          <div class="col-lg-7"><div class="card h-100"><div class="card-header">Monthly sales summary</div><div class="card-body">
            ${d.monthly.length ? '<div style="height:240px" class="no-print"><canvas id="mChart"></canvas></div>' : ''}<div id="mTable" class="mt-2"></div></div></div></div>
          <div class="col-lg-5"><div class="card h-100"><div class="card-header">How to read this</div><div class="card-body small text-muted">
            Gross amount is the sum of the order lines. Net amount is what the customer paid after the percentage discount (<code>get_order_total()</code>).
            Totals count only <b>Completed</b> orders; Pending and Cancelled orders are listed but not counted as revenue.</div></div></div>
        </div>
        <div class="card"><div class="card-header">Orders</div><div id="oTable"></div></div>`;
      renderTable($('mTable'), [
        { key: 'month', label: 'Month' }, { key: 'total_orders', label: 'Orders', num: true }, { key: 'total_sales', label: 'Sales', num: true, render: r => money(r.total_sales) }
      ], d.monthly, null, 'No completed sales in this period.');
      renderTable($('oTable'), [
        { key: 'order_id', label: 'Order ID' }, { key: 'order_date', label: 'Order date' }, { key: 'customer', label: 'Customer' }, { key: 'employee', label: 'Employee' },
        { key: 'gross_amount', label: 'Gross', num: true, render: r => money(r.gross_amount) },
        { key: 'discount_amount', label: 'Discount', num: true, render: r => money(r.discount_amount) },
        { key: 'net_amount', label: 'Net', num: true, render: r => money(r.net_amount) }, { key: 'status', label: 'Status', render: r => badge(r.status) }
      ], d.rows, null, 'No orders in this period.');
      if (chart) { chart.destroy(); chart = null; }
      if (d.monthly.length) chart = new Chart($('mChart'), { type: 'bar',
        data: { labels: d.monthly.map(m => m.month), datasets: [{ label: 'Sales (₹)', data: d.monthly.map(m => Number(m.total_sales)), backgroundColor: '#4d7fa8', borderRadius: 6, maxBarThickness: 50 }] },
        options: { maintainAspectRatio: false, plugins: { legend: { display: false } }, scales: { y: { beginAtZero: true, ticks: { callback: v => '₹' + fmtNum(v) } }, x: { grid: { display: false } } } } });
    } catch (e) { box.innerHTML = errorHtml(e.message); }
  }
  $('sRun').onclick = runSales;
  $('sClear').onclick = () => { $('sfrom').value = ''; $('sto').value = ''; runSales(); };
  $('sPrint').onclick = () => {
    const clone = $('salesReport').cloneNode(true);
    clone.querySelectorAll('canvas').forEach(c => c.parentElement.remove());   // canvases do not clone; the tables carry the data
    printHtml(clone.innerHTML);
  };

  // ================= Report 2: Inventory and low stock =================
  async function runInv() {
    const box = $('invReport'); box.innerHTML = loadingHtml();
    try {
      const rows = await api('/api/reports/low-stock' + qs({ category_id: $('icat').value, status: $('istatus').value }));
      const count = s => rows.filter(r => r.stock_status === s).length;
      const catName = $('icat').selectedOptions[0].textContent, stName = $('istatus').selectedOptions[0].textContent;
      box.innerHTML = `
        <div class="report-print-title"><h2>Stationary Shop – Inventory and Low-Stock Report</h2><p>Category: ${esc(catName)} · Status: ${esc(stName)} · ${esc(stamp())}</p></div>
        <div class="row g-3 mb-3">
          <div class="col-6 col-md-3"><div class="card stat"><div class="ico ico-indigo"><i class="bi bi-box-seam"></i></div><div><div class="val">${rows.length}</div><div class="lbl">Products listed</div></div></div></div>
          <div class="col-6 col-md-3"><div class="card stat"><div class="ico ico-yellow"><i class="bi bi-exclamation-triangle"></i></div><div><div class="val">${count('Low Stock')}</div><div class="lbl">Low stock</div></div></div></div>
          <div class="col-6 col-md-3"><div class="card stat"><div class="ico ico-pink"><i class="bi bi-x-octagon"></i></div><div><div class="val">${count('Out of Stock')}</div><div class="lbl">Out of stock</div></div></div></div>
          <div class="col-6 col-md-3"><div class="card stat"><div class="ico ico-teal"><i class="bi bi-cash-stack"></i></div><div><div class="val">${money(rows.filter(r => r.is_active).reduce((s, r) => s + r.stock_qty * Number(r.price), 0))}</div><div class="lbl">Stock value at selling price</div></div></div></div>
        </div>
        <div class="card"><div id="iTable"></div></div>`;
      renderTable($('iTable'), [
        { key: 'product_name', label: 'Product' }, { key: 'category_name', label: 'Category' }, { key: 'supplier_name', label: 'Supplier' },
        { key: 'stock_qty', label: 'Stock', num: true }, { key: 'reorder_level', label: 'Reorder level', num: true },
        { key: 'price', label: 'Selling price', num: true, render: r => money(r.price) }, { key: 'stock_status', label: 'Status', render: r => badge(r.stock_status) }
      ], rows, null, 'No products match these filters.');
    } catch (e) { box.innerHTML = errorHtml(e.message); }
  }
  $('iRun').onclick = runInv;
  $('iPrint').onclick = () => printHtml($('invReport').innerHTML);

  // ================= extra: top products, customer spending =================
  async function runMore() {
    try {
      const [top, cust] = await Promise.all([api('/api/reports/top-selling'), api('/api/reports/customer-spending')]);
      renderTable($('topReport'), [{ key: 'product_name', label: 'Product' }, { key: 'units_sold', label: 'Units sold', num: true },
        { key: 'revenue', label: 'Revenue', num: true, render: r => money(r.revenue) }], top, null, 'No sales yet.');
      renderTable($('custReport'), [{ key: 'customer_name', label: 'Customer' }, { key: 'orders_placed', label: 'Orders', num: true },
        { key: 'total_spent', label: 'Spent', num: true, render: r => money(r.total_spent) }, { key: 'loyalty_points', label: 'Points', num: true }], cust, null, 'No customers yet.');
    } catch (e) { $('topReport').innerHTML = errorHtml(e.message); }
  }

  fillSelect($('icat'), '/api/categories', 'category_id', 'category_name', 'All categories').then(runInv).catch(e => $('invReport').innerHTML = errorHtml(e.message));
  runSales(); runMore();
})();

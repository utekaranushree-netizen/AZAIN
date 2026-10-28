const { JSDOM, VirtualConsole } = require('jsdom');
/* Browser smoke test: loads every page in jsdom against JSON fixtures taken from the database.
   Setup:  cd tests/frontend && npm install     Run:  node smoke.js */
const http = require('http');
const path = require('path');
const srv = http.createServer((req, res) => { const f = require('path').join(path.join(__dirname, '..', '..', 'frontend'), req.url.split('?')[0]); require('fs').readFile(f, (e, b) => { if (e) { res.statusCode = 404; return res.end(); } res.setHeader('Content-Type', f.endsWith('.js') ? 'text/javascript' : f.endsWith('.css') ? 'text/css' : 'text/html'); res.end(b); }); }).listen(8099);
const fs = require('fs');
const F = JSON.parse(fs.readFileSync('fixtures.json'));
const ROOT = path.join(__dirname, '..', '..', 'frontend');
const calls = [];
function route(url, method) {
  const u = url.split('?')[0];
  calls.push(method + ' ' + url);
  if (method !== 'GET') return { status: 200, body: { message: 'ok', order_id: 99, po_id: 99, duplicate: false } };
  const map = { '/api/categories': F.categories, '/api/suppliers': F.suppliers, '/api/customers': F.customers, '/api/employees': F.employees,
    '/api/products': F.products, '/api/inventory': F.inventory, '/api/inventory/stock-log': F.stocklog, '/api/orders': F.orders,
    '/api/purchase-orders': F.pos, '/api/dashboard': F.dash, '/api/reports/sales': F.sales, '/api/reports/low-stock': F.inventory,
    '/api/reports/top-selling': F.topsell, '/api/reports/customer-spending': F.cust };
  if (map[u]) return { status: 200, body: map[u] };
  if (/\/api\/orders\/\d+\/receipt/.test(u)) return { status: 200, body: F.receipt };
  if (/\/api\/purchase-orders\/\d+$/.test(u)) return { status: 200, body: F.po };
  if (/\/api\/(suppliers|customers)\/\d+\/(products|orders)/.test(u)) return { status: 200, body: [] };
  return { status: 404, body: { message: 'no route ' + u } };
}
async function run(page) {
  const errors = [];
  const vc = new VirtualConsole(); vc.on('jsdomError', e => errors.push(String(e.message || e)));
  const dom = await JSDOM.fromURL('http://localhost:8099/' + page, { runScripts: 'dangerously', resources: 'usable', pretendToBeVisual: true, virtualConsole: vc,
    beforeParse(w) {
      w.fetch = async (url, o = {}) => { const r = route(url, o.method || 'GET'); return { ok: r.status < 400, status: r.status, text: async () => JSON.stringify(r.body) }; };
      w.Chart = function () { this.destroy = () => {}; };
      w.print = () => {}; w.matchMedia = w.matchMedia || (() => ({ matches: false, addListener() {}, removeListener() {} }));
    } });
  await new Promise(r => setTimeout(r, 1500));
  const d = dom.window.document;
  const info = { page, errors, rows: d.querySelectorAll('tbody tr').length, nav: !!d.querySelector('.sidebar .active'), alerts: [...d.querySelectorAll('.alert-danger')].map(a => a.textContent.trim()) };
  return { dom, info };
}
(async () => {
  for (const p of ['index.html', 'products.html', 'categories.html', 'suppliers.html', 'customers.html', 'employees.html', 'inventory.html', 'orders.html', 'purchase-orders.html', 'reports.html']) {
    const { dom, info } = await run(p);
    console.log(JSON.stringify(info));
    if (p === 'orders.html') {  // exercise the POS flow
      const d = dom.window.document, w = dom.window;
      d.querySelector('#prodGrid [data-id]').click(); d.querySelector('#prodGrid [data-id]').click();
      console.log('cart rows', d.querySelectorAll('#cartBox tbody tr').length, 'payable', d.getElementById('totalTxt').textContent);
      calls.length = 0; d.getElementById('confirmBtn').click(); await new Promise(r => setTimeout(r, 800));
      console.log('POST made:', calls.filter(c => c.startsWith('POST')).join(' | '), '| receipt modal:', !!d.querySelector('.receipt'));
    }
    if (p === 'products.html') {
      const d = dom.window.document; d.getElementById('btnAdd').click(); await new Promise(r => setTimeout(r, 600));
      console.log('product form fields:', d.querySelectorAll('#crudForm [id^=f_]').length);
    }
    if (p === 'purchase-orders.html') {
      const d = dom.window.document; d.getElementById('newPo').click(); await new Promise(r => setTimeout(r, 600));
      console.log('PO form open:', !!d.getElementById('poSup'));
    }
    dom.window.close();
  }
  srv.close(); process.exit(0);
})();

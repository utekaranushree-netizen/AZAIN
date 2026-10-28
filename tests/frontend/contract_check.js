/*
 * Frontend <-> backend CONTRACT CHECK (needs only Node.js, no npm packages, no database).
 *   node tests/frontend/contract_check.js
 * 1. every api('/api/...') call in the JavaScript/HTML maps to an existing Spring controller route and HTTP method
 * 2. every CRUD form field is a property of the matching Java entity
 * 3. the sale / purchase-order / payment request bodies only use fields that exist in the Java request records
 */
const fs = require('fs'), path = require('path');
const ROOT = path.join(__dirname, '..', '..');
const read = f => fs.readFileSync(f, 'utf8');
let failures = 0;
const fail = m => { failures++; console.log('  FAIL: ' + m); };
const norm = p => p.replace(/\?.*$/, '').replace(/\{[^}]*\}/g, '{}').replace(/\/+$/, '');
const snake = s => s.replace(/[A-Z]/g, c => '_' + c.toLowerCase());

// ---------- backend routes from the controller sources ----------
const ctrlDir = path.join(ROOT, 'backend/src/main/java/com/stationaryshop/controller');
const routes = new Set();
for (const f of fs.readdirSync(ctrlDir)) {
  const src = read(path.join(ctrlDir, f));
  const base = (src.match(/@RequestMapping\("([^"]*)"\)/) || [, ''])[1];
  for (const m of src.matchAll(/@(Get|Post|Put|Delete)Mapping(?:\("([^"]*)"\))?/g))
    routes.add(m[1].toUpperCase() + ' ' + norm(base + (m[2] || '')));
}
console.log(`Backend routes found: ${routes.size}`);

// ---------- frontend calls ----------
const files = [];
for (const f of fs.readdirSync(path.join(ROOT, 'frontend/js'))) files.push(path.join(ROOT, 'frontend/js', f));
for (const f of fs.readdirSync(path.join(ROOT, 'frontend'))) if (f.endsWith('.html')) files.push(path.join(ROOT, 'frontend', f));

function splitTop(s, sep) {            // split on a separator outside quotes/brackets
  const out = []; let depth = 0, q = null, cur = '';
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (q) { cur += c; if (c === '\\') { cur += s[++i]; } else if (c === q) q = null; continue; }
    if (c === "'" || c === '"' || c === '`') { q = c; cur += c; continue; }
    if ('([{'.includes(c)) depth++; if (')]}'.includes(c)) depth--;
    if (c === sep && depth === 0) { out.push(cur); cur = ''; } else cur += c;
  }
  out.push(cur); return out;
}
function callArgs(src, idx) {          // text between the parentheses starting at src[idx] === '('
  let depth = 0, q = null, i = idx;
  for (; i < src.length; i++) {
    const c = src[i];
    if (q) { if (c === '\\') i++; else if (c === q) q = null; continue; }
    if (c === "'" || c === '"' || c === '`') { q = c; continue; }
    if (c === '(') depth++; if (c === ')') { depth--; if (depth === 0) break; }
  }
  return src.slice(idx + 1, i);
}
function pathOf(expr) {                // '/api/orders/' + id + '/cancel'  ->  /api/orders/{}/cancel
  let out = '';
  for (let part of splitTop(expr.trim(), '+')) {
    part = part.trim();
    if (/^qs\(/.test(part)) continue;                                  // query string helper
    const lit = part.match(/^(['"`])([\s\S]*)\1$/);
    out += lit ? lit[2].replace(/\$\{[^}]*\}/g, '{}') : '{}';
  }
  return out;
}
const calls = [];                      // {method, path, where}
for (const f of files) {
  const src = read(f), name = path.relative(ROOT, f);
  for (const m of src.matchAll(/\bapi\(/g)) {
    const args = splitTop(callArgs(src, m.index + 3), ',');
    const p = pathOf(args[0]);
    if (p.startsWith('{}')) continue;     // generic helper (app.js / crud.js): the real URLs are checked through each page's config
    const method = ((args.slice(1).join(',').match(/method:\s*'(\w+)'/) || [, 'GET'])[1]).toUpperCase();
    calls.push({ method, path: p, where: name });
  }
  for (const m of src.matchAll(/fillSelect\(\s*[^,]+,\s*('(?:[^']*)')/g)) calls.push({ method: 'GET', path: pathOf(m[1]), where: name });
}
// CRUD pages: endpoint -> list/create/update/delete
const entities = { categories: 'Category', suppliers: 'Supplier', customers: 'Customer', employees: 'Employee', products: 'Product' };
for (const f of files.filter(f => f.endsWith('.html'))) {
  const src = read(f), name = path.relative(ROOT, f);
  const ep = (src.match(/initCrud\(\{\s*endpoint:\s*'(\/api\/[a-z-]+)'/) || [])[1];
  if (!ep) continue;
  for (const [m, p] of [['GET', ep], ['POST', ep], ['PUT', ep + '/{}'], ['DELETE', ep + '/{}']]) calls.push({ method: m, path: p, where: name + ' (crud)' });
  // form fields must exist on the entity
  const entity = entities[ep.split('/').pop()];
  const ents = read(path.join(ROOT, 'backend/src/main/java/com/stationaryshop/entity', entity + '.java'));
  const props = new Set([...ents.matchAll(/public\s+[\w<>]+\s+(\w+);/g)].map(m => snake(m[1])));
  const fi = src.indexOf('fields: [');
  let depth = 0, end = fi + 'fields: ['.length - 1;           // bracket-match the fields array exactly
  for (let i = end; i < src.length; i++) { if (src[i] === '[') depth++; if (src[i] === ']') { depth--; if (depth === 0) { end = i; break; } } }
  const fieldsBlock = src.slice(fi, end + 1);
  for (const o of src.matchAll(/optionsFrom:\s*'(\/api\/[^']*)'/g)) calls.push({ method: 'GET', path: o[1], where: name + ' (select options)' });
  for (const k of fieldsBlock.matchAll(/\{\s*key:\s*'(\w+)'/g)) if (!props.has(k[1])) fail(`${name}: form field "${k[1]}" is not a property of ${entity}`);
}
console.log(`Frontend API calls found: ${calls.length}`);
const used = new Set();
for (const c of calls) {
  const key = c.method + ' ' + norm(c.path);
  used.add(key);
  if (!routes.has(key)) fail(`${c.where}: ${key} has no matching controller route`);
}

// ---------- request bodies vs Java records ----------
function recordFields(file) {
  const src = read(path.join(ROOT, 'backend/src/main/java/com/stationaryshop/dto', file));
  // component names: the identifier directly before , or ) at depth 0 of each record header, annotations ignored
  const names = new Set();
  for (const m of src.matchAll(/(?:Integer|String|BigDecimal|List<Item>)\s+(\w+)\s*[,)]/g)) names.add(snake(m[1]));
  return names;
}
function bodyKeys(file, anchor) {
  const src = read(path.join(ROOT, 'frontend/js', file));
  const i = src.indexOf(anchor); if (i < 0) { fail(`${file}: could not find "${anchor}"`); return []; }
  const block = callArgs(src, src.indexOf('(', i));
  return [...block.matchAll(/(?:^|[{,]\s*)([a-z_]+):/g)].map(m => m[1]).filter(k => k !== 'method' && k !== 'body');
}
const checks = [
  ['orders.js', "api('/api/orders', { method: 'POST'", ['OrderRequest.java']],
  ['orders.js', "api('/api/orders/' + b.dataset.pay + '/pay'", ['PaymentRequest.java']],
  ['purchase-orders.js', "api('/api/purchase-orders', { method: 'POST'", ['PurchaseOrderRequest.java']]
];
for (const [file, anchor, dtos] of checks) {
  const allowed = new Set(dtos.flatMap(d => [...recordFields(d)]));
  for (const k of bodyKeys(file, anchor)) if (!allowed.has(k)) fail(`${file}: request field "${k}" is not in ${dtos.join(', ')}`);
}
const unused = [...routes].filter(r => !used.has(r));
console.log(`Routes not used by the UI (informational): ${unused.join('; ') || 'none'}`);
console.log(failures ? `\nCONTRACT CHECK FAILED (${failures})` : '\nCONTRACT CHECK PASSED');
process.exit(failures ? 1 : 0);

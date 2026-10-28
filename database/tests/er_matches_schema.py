"""
Checks that the ER diagram description (er_diagram/make_er.py) matches the real PostgreSQL schema:
same tables, same columns, same primary keys, foreign keys and unique keys.

Run (needs psql on the PATH and a database loaded from stationary_shop.sql):
    set PGUSER=postgres & set PGPASSWORD=... & set PGDATABASE=stationary_shop_test
    python database/tests/er_matches_schema.py
"""
import os, subprocess, sys, runpy

here = os.path.dirname(os.path.abspath(__file__))
ns = runpy.run_path(os.path.join(here, '..', 'er_diagram', 'make_er.py'))   # also rewrites er_diagram.dot (harmless)
E, R = ns['E'], ns['R']
TABLE = {'CATEGORY': 'categories', 'SUPPLIER': 'suppliers', 'PRODUCT': 'products', 'CUSTOMER': 'customers', 'EMPLOYEE': 'employees',
         'ORDER': 'orders', 'PAYMENT': 'payments', 'PURCHASE_ORDER': 'purchase_orders', 'STOCK_LOG': 'stock_log'}
ASSOC = {'Contains': 'order_items', 'Includes': 'purchase_order_items'}      # M:N relationships become tables

def q(sql):
    args = ['psql', '-At', '-F', '|', '-c', sql]
    if os.environ.get('PGHOST') is None and os.environ.get('PGDATABASE') is None:
        sys.exit('Set PGDATABASE (and PGUSER / PGPASSWORD) first.')
    r = subprocess.run(args, capture_output=True, text=True)
    if r.returncode: sys.exit(r.stderr)
    return [l.split('|') for l in r.stdout.strip().splitlines() if l]

cols = {}
for t, c in q("select table_name, column_name from information_schema.columns where table_schema='public' and table_name in (select table_name from information_schema.tables where table_type='BASE TABLE')"):
    cols.setdefault(t, set()).add(c)
pk = {(t, c) for t, c in q("select tc.table_name, kcu.column_name from information_schema.table_constraints tc join information_schema.key_column_usage kcu using (constraint_name, table_schema) where tc.constraint_type='PRIMARY KEY' and tc.table_schema='public'")}
fk = {(t, c) for t, c in q("select tc.table_name, kcu.column_name from information_schema.table_constraints tc join information_schema.key_column_usage kcu using (constraint_name, table_schema) where tc.constraint_type='FOREIGN KEY' and tc.table_schema='public'")}
uq_rows = q("select tc.constraint_name, tc.table_name, kcu.column_name from information_schema.table_constraints tc join information_schema.key_column_usage kcu using (constraint_name, table_schema) where tc.constraint_type='UNIQUE' and tc.table_schema='public'")
count = {}
for n, t, c in uq_rows: count[n] = count.get(n, 0) + 1
uq = {(t, c) for n, t, c in uq_rows if count[n] == 1}

problems = []
def check(cond, msg):
    if not cond: problems.append(msg)

for ent, attrs in E.items():
    t = TABLE[ent]
    check(t in cols, f'table {t} missing in the database')
    er_cols = {a for a, _ in attrs}
    check(er_cols == cols.get(t, set()), f'{t}: ER columns {sorted(er_cols ^ cols.get(t, set()))} differ from the database')
    for a, f in attrs:
        check(((t, a) in pk) == (f == 'PK'), f'{t}.{a}: primary-key flag differs')
        check(((t, a) in fk) == ('FK' in f), f'{t}.{a}: foreign-key flag differs')
        check(((t, a) in uq) == ('UK' in f), f'{t}.{a}: unique-key flag differs')
for name, l, r, *_rest in R:
    if name in ASSOC:
        t = ASSOC[name]
        attrs = {a.replace(' (PK)', '') for a in _rest[-1]}
        fks = {c for (tt, c) in fk if tt == t}
        check(cols.get(t) == attrs | fks, f'{t}: columns differ from relationship attributes + foreign keys ({sorted(cols.get(t, set()) ^ (attrs | fks))})')
        check(len(fks) == 2, f'{t}: expected exactly two foreign keys')
mapped = set(TABLE.values()) | set(ASSOC.values())
check(mapped == set(cols), f'tables not drawn in the ER diagram: {sorted(set(cols) - mapped)}')
if problems:
    print('ER DIAGRAM DOES NOT MATCH THE SCHEMA:'); [print(' -', p) for p in problems]; sys.exit(1)
print(f'ER diagram matches the schema: {len(mapped)} tables, all columns, primary keys, foreign keys and unique keys agree.')

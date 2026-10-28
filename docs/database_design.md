# Database Design and Implementation — Stationary Shop

## 1. Files
| File | Purpose |
|---|---|
| `database/stationary_shop.sql` | **DESTRUCTIVE RESET** script: drops and recreates every table, view, function and trigger, then loads sample data. One transaction (all or nothing). Refuses to run unless the database name starts with `stationary_shop` |
| `database/migrations/001_integrity_hardening.sql` | Non-destructive upgrade for a database made with version 1 of the script (keeps your data). Safe to run twice |
| `database/dbms_queries.sql` | Labelled demonstration of DDL, DML, SELECT, WHERE, ORDER BY, aggregates, GROUP BY, HAVING, joins, subqueries, views, indexes, triggers, transactions, payment rules, idempotency key and discount rule. Rolls back all its changes |
| `database/dbms_queries_sample_output.txt` | Output captured from one real run of the file above |
| `database/tests/db_tests.sql` | 75 self-checking tests of triggers, constraints, money/stock integrity and views (rolls back) |
| `database/tests/er_matches_schema.py` | Checks that the ER diagram description equals the real schema |
| `database/er_diagram/` | ER diagram (PNG, SVG, PDF) and editable `.dot` source + `make_er.py` |

The reset script was deliberately **not split** into schema and seed files: one file works in `psql` and in pgAdmin,
cannot get out of step with itself, and the sample data depends on the triggers defined just above it.

## 2. Database setup (use a separate development database)
```
psql -U postgres -c "CREATE DATABASE stationary_shop;"
psql -U postgres -d stationary_shop -f database/stationary_shop.sql      (DELETES existing project data)
```
Keeping existing data from version 1: `psql -U postgres -d stationary_shop -f database/migrations/001_integrity_hardening.sql`
(back up first: `pg_dump -U postgres stationary_shop > backup.sql`).

## 3. Business rules enforced by the database
| Rule | Where |
|---|---|
| Selling price >= cost price; prices and quantities are not negative | `CHECK` constraints on `products` |
| **Discount between 0 and 50 percent** (same rule in Java and the browser) | `CHECK` on `orders.discount_percent` |
| Stock is deducted only by the sale trigger; selling more than the stock, selling an inactive product, or adding items to a cancelled order is refused | `trg_before_order_item` (locks the product row, so two simultaneous sales cannot oversell) |
| Order items are permanent; an order is undone by cancelling it | `trg_order_item_immutable` |
| Cancelling restores stock once, logs it and reverses loyalty points; a cancelled order cannot be re-opened | `trg_order_status_change` |
| **One payment per order**, amount **equal to the order total**, none for a cancelled order; the order row is locked while checking | `uq_payments_order` + `trg_before_payment` |
| Payments are permanent | `trg_payment_immutable` |
| Loyalty points: 1 per Rs 100, awarded only here | `trg_after_payment` |
| Purchase order stock rises only on `Ordered → Received`, exactly once; `Received`/`Cancelled` are final; items only change while `Ordered`; a received order cannot be deleted | `trg_po_status_change`, `trg_po_item_guard`, `trg_po_delete_guard` |
| Stock log is append-only (only opening-stock rows may be removed with a never-traded new product) | `trg_stock_log_guard` |
| Repeated sale requests create one order | `orders.request_key` `UNIQUE` |

Triggers (10): `before_order_item_insert`, `order_item_no_change`, `order_status_update`, `po_item_guard`, `po_delete_guard`,
`po_status_update`, `before_payment_insert`, `payment_no_change`, `stock_log_no_change`, `after_payment_insert`.
Views (5): `order_summary`, `low_stock_products`, `top_selling_products`, `monthly_sales`, `customer_spending`.
Function: `get_order_total(order_id)`.

The Java backend therefore never updates `products.stock_qty` after creation and never touches `customers.loyalty_points`.

## 4. Changes made to the original script
Version 1 corrections (earlier): database name; purchase-order trigger now acts on receiving, not on item insert; cancellation
restores stock; sales trigger rejects inactive products; order items locked; views count only completed orders; extra checks and indexes;
sample data expanded to the college minimums; demo queries moved to `dbms_queries.sql`.

Version 2 (this review): destructive-script warning, database-name guard and single transaction; discount rule 0–50;
`orders.request_key`; one payment per order with amount/cancelled-order guard; payments and stock log permanent;
purchase-order items and received orders protected against edits and deletes. Details and evidence: `PROJECT_REVIEW.md`.

Known limitation: `idx_low_stock` is a partial index with a fixed threshold (10); it demonstrates partial indexes. The views
use each product's own `reorder_level`.

## 5. Sample data
| Table | Rows | Table | Rows |
|---|---|---|---|
| categories | 5 | orders | 14 |
| suppliers | 6 | order_items | 43 |
| products | 14 | payments | 12 |
| customers | 10 | purchase_orders | 10 |
| employees | 6 | purchase_order_items | 16 |
| | | stock_log | 57 (written by triggers) |

Order dates are relative to the load time, so one order is always dated "today" (re-run the script on demo day).

## 6. How the screens reach the database
```
Browser (HTML/JS) --fetch JSON--> Spring Boot controller --> service --> PostgreSQL
                                   (REST /api/...)         (JPA / JdbcTemplate, transactions)
```
* Products, categories, suppliers, customers and employees are written through Spring Data JPA entities
  (Hibernate in `validate` mode; it never changes the schema).
* Lists, reports, dashboard, sales and purchase orders use SQL through `JdbcTemplate`, reusing the views and `get_order_total()`.
* A sale (order + items + payment) is **one transaction**. `pay()` and `cancel()` lock the order row (`SELECT … FOR UPDATE`)
  and re-check the status while holding the lock. Sales carry an idempotency key stored in `orders.request_key`.
* Deleting a record that is still referenced is refused with an explanation; products with history are deactivated instead.

## 7. The two required reports
**Report 1 — Sales and Revenue** (`GET /api/reports/sales?from=&to=`): rows from the view `order_summary`; totals use
`SUM(net_amount) FILTER (WHERE status='Completed')`; the monthly summary groups completed orders by month.
**Report 2 — Inventory and Low Stock** (`GET /api/reports/low-stock?category_id=&status=`): products with a computed status
(`In Stock`, `Low Stock`, `Out of Stock`, `Inactive`) using the same rule as the view `low_stock_products` (`stock_qty <= reorder_level`).

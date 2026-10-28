# Problem Statement — Stationary Shop Management System

## 1. Project title
**Stationary Shop Management System** (application name: *Stationary Shop*; database: `stationary_shop`)

## 2. Background of the problem
A neighbourhood stationery shop sells hundreds of small, low-priced items: pens, notebooks, paper, art
supplies, office and geometry items. Stock comes from several suppliers (Navneet, Camlin, Pilot and others),
and customers range from walk-in students to schools that place bulk orders. Several staff members (manager,
cashiers, storekeepers) work at the counter. Every day the shop must answer questions such as:
*What is in stock? What is running out? Who supplies it? How much did we sell this month? Which customer
spends the most? Did the goods we ordered arrive?*

## 3. Existing problem or limitation
Most small stationery shops still run on paper bills, notebooks or loose spreadsheets. This causes:

| Limitation | Consequence |
|---|---|
| Stock counted by hand | Items run out unnoticed; customers are turned away, or money is tied up in over-stocking |
| No link between a sale and stock | Stock figures are wrong within days |
| Supplier details and purchase orders kept separately | Re-ordering is slow; it is unclear whether goods were received (or received twice) |
| Customer details not recorded | No order history, no loyalty rewards, no way to bill schools reliably |
| Sales totals added up manually | Arithmetic mistakes; discounts applied inconsistently; month-end reports take hours |
| Duplicate or inconsistent records (the same supplier or customer entered twice with different spellings) | Unreliable reports |
| No audit trail | Nobody can say who sold what, or why stock changed |

## 4. Proposed database solution
A relational database in **PostgreSQL**, with a web application on top of it (Spring Boot REST backend,
HTML/CSS/JavaScript frontend), that computerises the whole shop:

* **Master data** — categories, suppliers, products, customers and employees, protected by primary keys,
  foreign keys, `UNIQUE`, `NOT NULL`, `CHECK` and `DEFAULT` constraints, so duplicate or invalid data is rejected
  by the database itself.
* **Sales** — orders, order items and payments are saved in one transaction. Database triggers reduce the
  stock automatically, write a stock-movement log and award loyalty points (1 point per Rs 100). The function
  `get_order_total()` calculates the payable amount after discount, so every screen and report agrees.
* **Purchasing** — purchase orders are raised to suppliers; stock increases *only when the goods are received*
  and never twice.
* **Inventory control** — the reorder level of each product drives low-stock alerts; every stock change is
  recorded in `stock_log`.
* **Reports** — sales and revenue report (with date filter and monthly summary) and an inventory/low-stock
  report, produced by SQL views and queries.
* **Frontend** — dashboard, add/update/delete/search screens for every master table, a point-of-sale screen
  with printable receipt, purchase-order screen and printable reports.

This replaces guesswork with a single, consistent, queryable source of truth for the shop.

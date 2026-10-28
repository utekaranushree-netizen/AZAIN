# Stationary Shop — Management System

A DBMS micro project: **PostgreSQL** database + **Spring Boot** REST backend + **HTML/CSS/JavaScript** frontend
(Bootstrap 5, Chart.js, all bundled — no internet needed to run). It manages products, categories, suppliers, customers,
employees, stock, sales with printable receipts, purchase orders and reports.

> **What is and is not verified** (details: [`docs/testing.md`](docs/testing.md), [`PROJECT_REVIEW.md`](PROJECT_REVIEW.md))
> * **Verified by running it:** the SQL script, triggers, constraints, upgrade script, 75 database tests, concurrency tests,
>   the demo-query file, the ER-diagram-vs-schema check, JavaScript syntax, the frontend/API contract check and a browser-simulation smoke test.
> * **Written but NOT compiled or run** (no access to Maven Central where it was produced): the Java backend and its 7 test classes.
>   Follow section 8; if Maven prints an error, send me the full text and I will fix it.

## 1. Problem statement (short)
Small stationery shops track stock, suppliers, customers and sales on paper or loose spreadsheets, so stock figures are wrong,
re-ordering is slow, and loyalty points and reports are manual. This project replaces that with one consistent database and an
easy web interface. Full text: [`docs/problem_statement.md`](docs/problem_statement.md).

## 2. Features
Dashboard · Products / Categories / Suppliers / Customers / Employees (add, search/filter, edit, delete with safe handling of
linked records) · Inventory with stock history · Point of sale with printable receipt, cancel and pay-pending · Purchase orders
(receive goods once) · Printable reports: **Sales and Revenue**, **Inventory and Low Stock** (+ top products, customer spending).

## 3. Technology
Java 17+, Spring Boot 3.3.5 (Web, Data JPA/Hibernate, Validation), Maven, PostgreSQL 14+ (JDBC driver), HTML5, CSS3, vanilla JavaScript.

## 4. Folder structure
```
stationary-shop/
├── backend/        Spring Boot project: pom.xml, src/main/java/com/stationaryshop/{config,controller,dto,entity,exception,repository,service}, src/test, start-backend.cmd
├── frontend/       10 pages, css/, js/ (config.js = API address), assets/, vendor/
├── database/       stationary_shop.sql (RESET script) · migrations/ · dbms_queries.sql · tests/ · er_diagram/
├── tests/frontend/ contract_check.js, smoke.js (Node.js)
├── docs/           problem_statement.md, er_to_relational_mapping.md, database_design.md, testing.md
├── PROJECT_REVIEW.md   findings, fixes, evidence, limitations
├── .env.example · .gitignore · README.md
```

## 5. Prerequisites (Windows)
1. **JDK 17 or newer** — `java -version`.
2. **Apache Maven 3.9+** — `mvn -version` (add Maven's `bin` folder to PATH).
3. **PostgreSQL 14+** — during installation remember the `postgres` password. Make sure the service is running
   (Windows: *Services* → "postgresql-x64-NN" → Start; or `pg_ctl`). Check with `psql --version`
   (add `C:\Program Files\PostgreSQL\<version>\bin` to PATH if needed).
4. Optional: Node.js 18+ for the frontend tests.

## 6. Create the database and load the data

> ### ⚠ WARNING — `database\stationary_shop.sql` DELETES DATA
> It drops and recreates every Stationary Shop table and reloads the sample data. Use a **separate development database**
> (never one that holds data you care about). It refuses to run unless the database name starts with `stationary_shop`.
> Already loaded version 1 and want to keep your data? Run `database\migrations\001_integrity_hardening.sql` instead
> (back up first with `pg_dump`).

Command Prompt or PowerShell, in the `stationary-shop` folder:
```
psql -U postgres -c "CREATE DATABASE stationary_shop;"
psql -U postgres -d stationary_shop -f database\stationary_shop.sql
```
(Enter the password when asked. Repeat the second command any time to reset to the sample data. Do it on the day of your demo so
"Today's sales" has an order dated today.) A separate database for tests: `CREATE DATABASE stationary_shop_test;`.

Optional — see every DBMS concept run (changes are rolled back): `psql -U postgres -d stationary_shop -f database\dbms_queries.sql`
Optional — database self-test: `psql -U postgres -d stationary_shop_test -f database\tests\db_tests.sql`

## 7. Configure the database connection (environment variables — never put a password in a file)
PowerShell:
```
$env:DB_URL = "jdbc:postgresql://localhost:5432/stationary_shop"
$env:DB_USERNAME = "postgres"
$env:DB_PASSWORD = "your_local_password"
```
Command Prompt:
```
set DB_URL=jdbc:postgresql://localhost:5432/stationary_shop
set DB_USERNAME=postgres
set DB_PASSWORD=your_local_password
```
Defaults if unset: the URL above, user `postgres`, empty password. See `.env.example`. Hibernate runs with `ddl-auto=validate`: it only
checks that the Java entities match the SQL schema and never changes it.

## 8. Build, test and run the backend
```
cd backend
mvn clean test          (19 unit tests; the database tests are skipped unless TEST_DB_URL is set - see docs/testing.md)
mvn package
mvn spring-boot:run
```
Wait for `Started StationaryShopApplication`, then open **http://localhost:8080/**. (Or double-click `backend\start-backend.cmd`.)
The backend also serves the `frontend` folder, so nothing else needs to start. Run it from the `backend` folder; otherwise set
`FRONTEND_DIR` to the frontend path **with forward slashes and a trailing slash**, e.g. `set FRONTEND_DIR=C:/projects/stationary-shop/frontend/`.
Change the port with `set PORT=8081`.

### Frontend served from somewhere else (optional)
If you open the pages from another address (for example VS Code Live Server, `http://localhost:5500`), edit
`frontend/js/config.js` → `window.API_BASE = 'http://localhost:8080';`. The backend allows `localhost:5500`, `127.0.0.1:5500` and
`localhost:3000` by default; change with `set CORS_ALLOWED_ORIGINS=http://localhost:5173`. Do not just double-click the `.html` files.

## 9. Working API endpoints (base `http://localhost:8080`)
| Area | Endpoints |
|---|---|
| Dashboard | `GET /api/dashboard` |
| Categories | `GET /api/categories?q=` · `GET/PUT/DELETE /api/categories/{id}` · `POST /api/categories` |
| Suppliers | `GET /api/suppliers?q=` · `POST` · `GET/PUT/DELETE /api/suppliers/{id}` · `GET /api/suppliers/{id}/products` |
| Customers | `GET /api/customers?q=` · `POST` · `GET/PUT/DELETE /api/customers/{id}` · `GET /api/customers/{id}/orders` |
| Employees | `GET /api/employees?q=&role=` · `POST` · `GET/PUT/DELETE /api/employees/{id}` |
| Products | `GET /api/products?q=&category_id=&supplier_id=&status=active\|inactive` · `POST` · `GET/PUT/DELETE /api/products/{id}` |
| Orders | `GET /api/orders?from=&to=&status=` · `POST /api/orders` · `GET /api/orders/{id}/receipt` · `POST /api/orders/{id}/pay` · `PUT /api/orders/{id}/cancel` |
| Purchase orders | `GET /api/purchase-orders?status=` · `GET /{id}` · `POST` · `POST /{id}/receive` · `POST /{id}/cancel` |
| Inventory | `GET /api/inventory?q=&category_id=&supplier_id=&status=` · `GET /api/inventory/low-stock` · `GET /api/inventory/stock-log?product_id=` |
| Reports | `GET /api/reports/sales?from=&to=` · `GET /api/reports/low-stock?category_id=&status=` · `GET /api/reports/top-selling` · `GET /api/reports/customer-spending` |

JSON uses `snake_case` names equal to the database columns. Errors always look like
`{ "status": 409, "error": "Conflict", "message": "…", "field_errors": { "price": "…" } }` and never contain stack traces or SQL.
Status codes: 200/201 success · 400 invalid input · 404 not found · 405/415 wrong method / body type · 409 conflict (duplicate, still referenced,
insufficient stock, already paid/received/cancelled, busy — try again) · 500 unexpected.

### Sale request, repeated requests and payment rules
`POST /api/orders` body: `{ "customer_id": null|id, "emp_id": 2, "discount_percent": 0-50, "payment_method": "Cash|Card|UPI", "request_id": "8-64 chars A-Za-z0-9_-", "items": [{ "product_id": 1, "quantity": 2 }] }`.
* The server ignores any price or total from the browser: prices come from the catalogue, the total from `get_order_total()`.
* **Repeating a request with the same `request_id`** (double click, retry, even after a restart) returns the **same order** with HTTP 200 and
  `"duplicate": true` — no second order, no second stock deduction. A new `request_id` is a new sale. Different payloads with the same id return the first order.
* **Discount:** 0 to 50 percent (database, Java and browser agree).
* **Payment states:** `Pending → Completed` by paying (exactly one payment, amount = order total, loyalty points once);
  `Pending/Completed → Cancelled` (stock returns, points reversed); `Completed` and `Cancelled` orders cannot be paid; a cancelled order cannot be re-opened.
  Two simultaneous payments: the order row is locked, one succeeds, the other gets 409 "already been paid".

## 10. Manual test steps
See `docs/testing.md` section C (M1–M14): insert, update, delete, search, **sale (M6–M8)**, **payment (M9)**, **cancellation and stock (M10)**,
purchase-order receiving (M11) and **reports (M12)**. Quick API checks (PowerShell): `Invoke-RestMethod http://localhost:8080/api/dashboard`.

## 11. Troubleshooting
| Problem | Fix |
|---|---|
| `Connection refused` / `Connection to localhost:5432 refused` | PostgreSQL is not running: start the Windows service "postgresql-x64-NN"; check the port (default 5432) and that `DB_URL` matches |
| `password authentication failed for user "postgres"` | `DB_PASSWORD` is wrong or not set in the same terminal window as `mvn` |
| `FATAL: database "stationary_shop" does not exist` | Run the `CREATE DATABASE` command (section 6) |
| `Schema-validation: missing table …` / `relation … does not exist` | The SQL script was not loaded into that database; repeat section 6 |
| `Refusing to run: connected to database "postgres"` | Add `-d stationary_shop` to the `psql` command — the guard protects other databases |
| `column "request_key" … does not exist` (or payments rejected) | Database made with version 1: run `database\migrations\001_integrity_hardening.sql` |
| `Port 8080 was already in use` | Close the other program or `set PORT=8081` and use that port |
| `mvn` / `java` / `psql` is not recognized | Install it and add its `bin` folder to PATH; open a **new** terminal |
| `release version 17 not supported` / `UnsupportedClassVersionError` | Install JDK 17 or newer and make `JAVA_HOME` point to it |
| `Could not resolve dependencies` / `Connection timed out` while building | Maven needs internet the first time (downloads libraries); check proxy/firewall |
| Page shows 404 at `/` or has no styling | Start from the `backend` folder, or set `FRONTEND_DIR` (forward slashes, trailing slash) |
| "Cannot reach the Stationary Shop server" in the page | Use http://localhost:8080/ (not a double-clicked file); backend must be running; for another address see `config.js` / CORS above |
| Dashboard "Today's sales" is 0 | No completed order is dated today: make a sale or reload the SQL script |
| Tests: `Could not reset the test database` | `TEST_DB_URL` must point to a database named like `stationary_shop_test` and the user/password must be correct |

## 12. Known limitations (honest list)
No login/roles (run locally only) · no pagination · the Java backend/tests and `start-backend.cmd` are untested on a real machine ·
the POS "Payable (estimate)" is calculated in JavaScript — the receipt (from the database) is authoritative ·
integration tests reset their database · charts and printing were not checked in a real browser. See `PROJECT_REVIEW.md` section 3.

## 13. Documents for submission
[`docs/problem_statement.md`](docs/problem_statement.md) · ER diagram [`database/er_diagram/er_diagram.png`](database/er_diagram/er_diagram.png) ·
[`docs/er_to_relational_mapping.md`](docs/er_to_relational_mapping.md) · [`docs/database_design.md`](docs/database_design.md) ·
[`database/dbms_queries.sql`](database/dbms_queries.sql) (+ sample output) · [`docs/testing.md`](docs/testing.md) · [`PROJECT_REVIEW.md`](PROJECT_REVIEW.md)

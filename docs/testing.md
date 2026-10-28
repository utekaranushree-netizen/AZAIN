# Testing — Stationary Shop (version 2)

Honesty rule: only things that were **actually run** are marked as executed. The review environment had PostgreSQL 16,
Java 21 JDK and Node 22 but **no access to Maven Central**, so the Spring Boot application could not be built or started there.

## A. Executed in the review environment (results observed)

| ID | What was run | Result |
|---|---|---|
| E1 | `stationary_shop.sql` loaded into an empty `stationary_shop_test` with `ON_ERROR_STOP` | OK; 5 categories, 6 suppliers, 14 products, 10 customers, 6 employees, 14 orders, 43 order items, 12 payments, 10 purchase orders, 16 PO items, 57 stock-log rows |
| E2 | Same script run while connected to the `postgres` database | **Refused** ("Refusing to run…"), nothing created or deleted |
| E3 | `database/tests/db_tests.sql` (75 checks: schema, constraints, sale/stock once, insufficient stock, inactive product, payment once/exact/not on cancelled, points once, cancel restores once and reverses points, no re-open, purchase order once, history protected, append-only logs, report consistency) | **75 / 75 passed** on a fresh database **and** on a version-1 database after the migration |
| E4 | Same suite against the old version-1 schema (mutation check) | **Fails** at the first missing protection, so the suite really detects the problems |
| E5 | `001_integrity_hardening.sql` applied to a version-1 database, twice | OK both times; data kept (14 orders, 12 payments); 10 triggers afterwards |
| E6 | Concurrency, 2 real PostgreSQL sessions each: (1) payment race using the service's lock-and-recheck steps, (2) payment race without any application lock, (3) same idempotency key at once, (4) two sales for the last 2 units | (1) one payment, points 7→8 once, 2nd session rejected; (2) unique rule stopped the 2nd; (3) one order, stock −1 once; (4) 2nd sale "Insufficient stock… available 0" |
| E7 | `dbms_queries.sql` run end to end on a fresh database | 0 errors; data unchanged afterwards; output saved in `dbms_queries_sample_output.txt` |
| E8 | Every SQL statement the Java services use, run by hand in `psql` (before and after the changes) | All worked |
| E9 | `database/tests/er_matches_schema.py` | Diagram = schema (11 tables, all columns/PK/FK/UK). Fails correctly on two deliberately wrong diagrams |
| E10 | `node --check` on all 8 JavaScript files and the inline scripts of the 5 CRUD pages | No syntax errors |
| E11 | `node tests/frontend/contract_check.js` | PASSED: 51 frontend API calls ↔ 45 controller routes; all form fields exist on the entities; request bodies match the DTOs. Fails correctly when a URL is broken on purpose |
| E12 | `node tests/frontend/smoke.js` (jsdom, JSON fixtures taken from the database): loads all 10 pages; opens the product form and the purchase-order form; adds a product to the POS cart and confirms a sale | All pages render data and highlight the menu; the sale sends one `POST /api/orders` and shows the receipt. jsdom cannot draw canvas charts (reported as "not implemented"); charts not checked visually |
| E13 | `javac` over all main and test sources (without Spring on the classpath) | No syntax errors; every unresolved symbol is a Spring/JPA/JUnit/MockMvc class. It found one real mistake in the tests (a helper named `status` hiding MockMvc's), now fixed. **This is not a compilation** |

## B. Added but NOT executed (need your computer: Maven + internet)

| Test class | Needs DB? | Covers (required item) |
|---|---|---|
| `GlobalExceptionHandlerTest` (9 tests) | no | D error handling, constraint → HTTP mapping, no leakage of SQL/secrets |
| `RequestValidationTest` (10 tests) | no | B/C/D validation: discount 0–50, quantities, payment method, key format, product/employee/supplier rules |
| `ApplicationContextIntegrationTest` | `TEST_DB_URL` | A context starts and Hibernate `validate` accepts the schema; O schema script on a clean database |
| `MasterDataApiIntegrationTest` | `TEST_DB_URL` | B product CRUD + validation; C category/supplier/customer/employee CRUD; D not found / invalid; delete protection |
| `SalesFlowIntegrationTest` | `TEST_DB_URL` | E valid sale, F insufficient stock, G stock once, H cancel restores once, I payment once/duplicate rejected, J failed payment consistent, idempotent repeat, **two-thread payment race**, **two-thread same-key race** |
| `PurchaseOrderIntegrationTest` | `TEST_DB_URL` | K receive once, L repeated receive/cancel rejected, invalid PO leaves nothing |
| `ReportIntegrationTest` | `TEST_DB_URL` | M reports and dashboard equal database values; date-range validation |

N (frontend ↔ API contract) is covered by E11 (Node script, executed).
Without `TEST_DB_URL` the database tests are reported as **skipped**, and `mvn clean test` runs only the first two classes.

### How to run them
```
cd backend
mvn clean test                                  (runs the 19 tests that need no database)
```
With a separate test database (**it is reset by the tests**):
```
psql -U postgres -c "CREATE DATABASE stationary_shop_test;"
set TEST_DB_URL=jdbc:postgresql://localhost:5432/stationary_shop_test
set TEST_DB_USERNAME=postgres
set TEST_DB_PASSWORD=your_password
mvn clean test                                  (now all 7 classes run)
mvn package
```
Database-only tests, no Java needed:
```
psql -U postgres -d stationary_shop_test -f database\stationary_shop.sql
psql -U postgres -d stationary_shop_test -f database\tests\db_tests.sql      (expect: ALL DATABASE TESTS PASSED (75 checks))
```
Frontend tests (Node.js): `node tests\frontend\contract_check.js` · for the smoke test `cd tests\frontend`, `npm install`, `node smoke.js`.

## C. Manual test steps (browser) — please perform and tick

| ID | Steps | Expected | Result |
|---|---|---|---|
| M1 | Start the backend, open http://localhost:8080/ | Dashboard: 8 cards with numbers, chart, recent orders, low-stock alerts, best sellers. "Today's sales" is non-zero only when an order is dated today | |
| M2 | Products → Add: "Test Pen", cost 5, price 9, stock 20 | Added; Inventory history shows "Opening Stock +20" | |
| M3 | Add a product with price 3, cost 5 | Error message about selling price below cost | |
| M4 | Edit "Test Pen": price 12, untick Active; search "pen"; filter by category | Updated; stock unchanged; filters work | |
| M5 | Delete "Test Pen"; try to delete "Ball Pen Blue", category "Writing", customer "Aarav Shah", employee "Pooja Desai", supplier "Camlin Supplies" | First deleted; the rest refused with an explanation | |
| M6 | **Sale**: Sales → New sale → add 2 × Gel Pen Black, customer Diya Mehta, a cashier, discount 10, UPI → Confirm | Receipt with subtotal, discount, total, method; Gel Pen stock −2 (once); order in the Orders tab | |
| M7 | Discount field: type 60 | Browser stops at 50; server message "between 0 and 50 percent" if forced | |
| M8 | Double-click *Confirm sale* quickly | One order only | |
| M9 | **Payment**: find the Pending order (#14) → Record payment (Cash) | Becomes Completed; customer Rohan Patil gets +1 point; paying it again is refused ("already been paid") | |
| M10 | **Cancellation**: cancel a Completed order | Stock returns (Inventory history "Sale Cancelled"); cannot be cancelled twice; cannot be paid | |
| M11 | **Purchase order**: Office Mart, Steel Ruler × 40 → Receive | Stock +40 once; Receive button disappears | |
| M12 | **Reports**: Sales report with a date range and Print; Inventory report with category/status filters and Print | Totals follow the filters; print preview shows only the report; "from" after "to" gives a message | |
| M13 | Stop PostgreSQL and reload a page | A red error message (no blank page, no technical details) | |
| M14 | Phone width (browser dev tools, 390 px): open the menu button | Sidebar slides in; tables scroll sideways | |

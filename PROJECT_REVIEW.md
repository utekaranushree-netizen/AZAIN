# PROJECT REVIEW — Stationary Shop (version 2)

Scope: every file of the delivered project was inspected: `pom.xml`, 36 Java main files, tests, `application.properties`,
10 HTML pages, 8 JavaScript files, CSS, the SQL scripts, the ER diagram and the documents.
Every finding below states what was actually done and how it was checked. "Verified" means a command was run and its output
observed in the review environment (PostgreSQL 16, Java 21, Node 22). **Maven Central was not reachable from that environment, so the
Spring Boot application was never compiled, started or tested there.** Everything that depends on that is marked *not verified*.

## 1. Defects found and fixed

| ID | Sev. | Where | Finding / evidence | Fix | Verification |
|---|---|---|---|---|---|
| F1 | High | `OrderService.pay()` | Read the status and inserted the payment without a lock: two simultaneous requests could both see `Pending` and both pay (double loyalty points). | `pay()` and `cancel()` run in a transaction, lock the row with `SELECT … FOR UPDATE`, re-check the status under the lock. The database adds a second line of defence (F3). | **Verified at database level** with two real PostgreSQL sessions running the same SQL steps (second session waited, saw `Completed`, was rejected; 1 payment; points 7→8 once). The Java code itself: *not verified* (tests written: `SalesFlowIntegrationTest`). |
| F2 | High | `OrderService` (idempotency) | Duplicate-submit protection was an in-memory `ConcurrentHashMap`: lost on restart, ignored by a second server. | New column `orders.request_key VARCHAR(64) UNIQUE` (format-checked). `create()` looks the key up, and a simultaneous duplicate hits the unique index and returns the winner's order (HTTP 200, `duplicate:true`). Migration provided. | **Verified at database level**: two sessions with one key → one order, stock −1 once. Java: *not verified*. |
| F3 | High | database | A payment could be inserted for a cancelled order, for any amount, several times; payments and the stock log could be edited or deleted. | `uq_payments_order` (UNIQUE), trigger `before_payment_insert` (locks order, rejects cancelled order and wrong amount), `payment_no_change`, `stock_log_no_change`. | **Verified**: `db_tests.sql` D2–D9, E7, G1–G3 pass (75/75); same suite fails on the old v1 schema. Race without any app lock: unique rule stopped the 2nd payment. |
| F4 | Med | Java / DB / JS | Discount rule differed: DB allowed 0–100, Java rejected ≥100, browser allowed 99.99. A 100 % order cannot be paid (`amount > 0`). | One rule everywhere: **0–50 %**. DB `CHECK`, `OrderRequest` bean validation + service check, POS input `max=50` and JS check. | DB: verified (B6, B7, B16). Java/JS: validation unit tests written (*not run*); JS syntax checked. |
| F5 | Med | `GlobalExceptionHandler` | Catch-all `@ExceptionHandler(Exception)` turned unknown URLs (404) and wrong methods (405) into 500 and printed stack traces. | Explicit handlers for 404/405/415/400, `ConstraintViolation`; SLF4J logging; response text never contains SQL or exception text. | *Not verified* (unit tests written: `GlobalExceptionHandlerTest`). |
| F6 | Med | database | Items of a received purchase order could still be deleted; a received PO could be deleted. | `po_item_guard` now also fires on DELETE; `po_delete_guard`. | **Verified** (F6–F9, F13 in `db_tests.sql`). |
| F7 | Med | `OrderService` | Two sales with the same products in different order could deadlock. | Lines are sorted by product id (`TreeMap`) so product rows are always locked in the same order; SQLSTATE 40P01/40001/55P03 return a friendly "try again" 409. | Ordering logic *not verified* (reasoning only); handler mapping has a unit test (*not run*). |
| F8 | Med | `stationary_shop.sql` | Reset script could be run on any database (e.g. `postgres`) and was not atomic. | Prominent DESTRUCTIVE banner, database-name guard (`stationary_shop%`), whole script in one transaction. | **Verified**: run on `postgres` it refused and changed nothing; run on a test DB loads cleanly. |
| F9 | Low | config | No CORS and no API base: the frontend only worked when served by Spring. | `js/config.js` (`API_BASE`), `WebConfig` CORS for `/api/**` (origins from `CORS_ALLOWED_ORIGINS`). | *Not verified* in a browser/server. |
| F10 | Low | validation | Missing: future hire date, reversed report dates, maximum quantity/money size, key format, mixed-case e-mails bypassing `UNIQUE`. | `@PastOrPresent`, `Util.checkRange` (400), `@Max/@Digits`, `@Pattern`, e-mails stored lower-case; matching `max` attributes in the forms. | DB-level parts verified; Java parts *not run*; JS syntax checked. |
| F11 | Low | `orders.js` | Payment dialog could be submitted twice; the "Payable" figure is computed in JavaScript. | Button disabled after the first click (server is safe anyway); label says "estimate"; the receipt always shows database-computed amounts. | jsdom smoke test passes; behaviour in a real browser *not verified*. |
| F12 | Low | `make_er.py` | Wrote its output to the current directory; ER diagram lacked `request_key` and the 1:1 payment relation. | Writes next to itself, only when run directly; diagram regenerated. | **Verified** by `er_matches_schema.py` (passes; fails on two deliberate mistakes). |
| F13 | — | tests | While writing tests, `javac` showed a helper named `status()` shadowed MockMvc's `status()`. | Renamed to `orderStatus()`. | `javac` structure check re-run: no remaining non-library errors. |

## 2. Checked and found correct (no change needed)
* All SQL in the services is parameterised; dynamic parts are fixed text (no SQL injection path).
* Every API call in the frontend has a controller route, and every form field exists on the Java entity (`tests/frontend/contract_check.js`: 51 calls, 45 routes — passes).
* Reports, dashboard and receipts take their numbers from SQL/views; no hard-coded statistics. Totals persisted by a sale come from `get_order_total()` — the browser's total is display only.
* Deleting referenced categories, suppliers, customers, employees and products is refused with an explanation; historical sales are never cascaded away.
* Output encoding: all user text shown in the pages goes through `esc()`.

## 3. Not fixed / known limitations
| ID | Item | Why / advice |
|---|---|---|
| L1 | **No login or roles.** Anyone who can reach port 8080 can change data. | Out of scope for the micro project. Run locally only; do not expose the port. |
| L2 | No pagination (lists are unbounded; stock history shows the latest 300). | Fine for a small shop; would be needed for large data. |
| L3 | An order can still be inserted as `Completed` without a payment by hand-written SQL (the application never does this). | A trigger cannot demand a *later* payment row. |
| L4 | The Java backend, the Java tests and the Windows helper `start-backend.cmd` were never run. | See `docs/testing.md` section B for exact commands. |
| L5 | Integration tests **reset** the database named in `TEST_DB_URL`. | Use `stationary_shop_test` only. |
| L6 | The reset script was not split into schema + seed (decision recorded in `docs/database_design.md`). | Avoids drift; pgAdmin compatibility. |

## 4. Files changed
`database/stationary_shop.sql` (rules, guards, request_key, transaction, banner) · `database/migrations/001_integrity_hardening.sql` (new) ·
`database/dbms_queries.sql` (+3 demo sections) · `database/dbms_queries_sample_output.txt` · `database/tests/*` (new) · `database/er_diagram/*` ·
`backend/.../service/OrderService.java` (rewritten) · `Util.java`, `SupplierService`, `CustomerService`, `ReportService` · `exception/GlobalExceptionHandler.java` ·
`config/WebConfig.java` (new) · DTOs `OrderRequest`, `PaymentRequest`, `PurchaseOrderRequest` · entities `Employee`, `Product` ·
`application.properties` · `src/test/**` (replaced one placeholder test with 2 unit-test and 5 integration-test classes) ·
`frontend/js/{config,app,orders,reports,crud}.js`, `orders.html`, `employees.html`, `products.html`, all pages (config.js tag) ·
`tests/frontend/*` (new) · `docs/*`, `README.md`, `PROJECT_REVIEW.md`, `.env.example`, `backend/start-backend.cmd`, `.gitignore`.
No feature was removed: the same 10 screens and all 45 API routes remain (checked by comparing with the version-1 archive).

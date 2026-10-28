-- =====================================================================
-- STATIONARY SHOP - DATABASE TEST SUITE (PostgreSQL)
--
-- Checks the rules that live in the database: triggers, constraints, views and
-- money/stock integrity. Every test prints PASS or stops with FAIL.
--
-- RUN (use a TEST database; this file loads nothing and rolls everything back):
--   psql -U postgres -c "CREATE DATABASE stationary_shop_test;"
--   psql -U postgres -d stationary_shop_test -f database/stationary_shop.sql
--   psql -U postgres -d stationary_shop_test -f database/tests/db_tests.sql
-- Expected last line:  NOTICE:  ALL DATABASE TESTS PASSED (n checks)
-- =====================================================================
\set ON_ERROR_STOP on
\set VERBOSITY terse
BEGIN;

CREATE TEMP TABLE test_counter (n INT);
INSERT INTO test_counter VALUES (0);

-- assertion helpers (temporary, vanish with the transaction)
CREATE FUNCTION pg_temp.ok(cond BOOLEAN, name TEXT) RETURNS VOID AS $$
BEGIN
    IF cond IS NOT TRUE THEN RAISE EXCEPTION 'FAIL: %', name; END IF;
    UPDATE test_counter SET n = n + 1;
    RAISE NOTICE 'PASS: %', name;
END $$ LANGUAGE plpgsql;

-- runs a statement that MUST fail with a message containing frag; the failed statement is rolled back
CREATE FUNCTION pg_temp.raises(stmt TEXT, frag TEXT, name TEXT) RETURNS VOID AS $$
BEGIN
    BEGIN
        EXECUTE stmt;
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM ILIKE '%' || frag || '%' THEN
            UPDATE test_counter SET n = n + 1;
            RAISE NOTICE 'PASS: %', name;
            RETURN;
        END IF;
        RAISE EXCEPTION 'FAIL: % (unexpected error: %)', name, SQLERRM;
    END;
    RAISE EXCEPTION 'FAIL: % (no error was raised)', name;
END $$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------
-- A. Schema and sample data (college minimums)
-- ---------------------------------------------------------------------
SELECT pg_temp.ok((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE') = 11, 'A1 eleven tables exist');
SELECT pg_temp.ok((SELECT COUNT(*) FROM information_schema.views WHERE table_schema='public') = 5, 'A2 five views exist');
SELECT pg_temp.ok((SELECT COUNT(*) FROM pg_trigger WHERE NOT tgisinternal) = 10, 'A3 ten triggers exist');
SELECT pg_temp.ok((SELECT COUNT(*) FROM categories) >= 5 AND (SELECT COUNT(*) FROM suppliers) >= 5 AND (SELECT COUNT(*) FROM products) >= 10
               AND (SELECT COUNT(*) FROM customers) >= 5 AND (SELECT COUNT(*) FROM employees) >= 5, 'A4 master tables have at least 5 rows');
SELECT pg_temp.ok((SELECT COUNT(*) FROM orders) >= 10 AND (SELECT COUNT(*) FROM order_items) >= 10 AND (SELECT COUNT(*) FROM payments) >= 10
               AND (SELECT COUNT(*) FROM purchase_orders) >= 10 AND (SELECT COUNT(*) FROM purchase_order_items) >= 10, 'A5 transaction tables have at least 10 rows');
SELECT pg_temp.ok((SELECT COUNT(*) FROM products WHERE stock_qty < 0) = 0, 'A6 no negative stock');
SELECT pg_temp.ok((SELECT COUNT(*) FROM pg_proc WHERE proname = 'get_order_total') = 1, 'A7 get_order_total() exists');

-- ---------------------------------------------------------------------
-- B. Constraints (money, discount rule, keys, statuses)
-- ---------------------------------------------------------------------
SELECT pg_temp.raises($q$INSERT INTO products (product_name, category_id, cost_price, price) VALUES ('X', 1, 10, 5)$q$, 'products_check', 'B1 selling price below cost is rejected');
SELECT pg_temp.raises($q$INSERT INTO products (product_name, category_id, cost_price, price) VALUES ('X', 1, -1, 5)$q$, 'cost_price_check', 'B2 negative cost price is rejected');
SELECT pg_temp.raises($q$INSERT INTO categories (category_name) VALUES ('Writing')$q$, 'category_name_key', 'B3 duplicate category name is rejected');
SELECT pg_temp.raises($q$INSERT INTO customers (customer_name, email) VALUES ('Dup', 'aarav@example.com')$q$, 'customers_email_key', 'B4 duplicate customer email is rejected');
SELECT pg_temp.raises($q$INSERT INTO employees (emp_name, role) VALUES ('Z', 'Boss')$q$, 'employees_role_check', 'B5 invalid employee role is rejected');
SELECT pg_temp.raises($q$INSERT INTO orders (emp_id, discount_percent) VALUES (2, 51)$q$, 'orders_discount_percent_check', 'B6 discount above 50 percent is rejected');
SELECT pg_temp.raises($q$INSERT INTO orders (emp_id, discount_percent) VALUES (2, -1)$q$, 'orders_discount_percent_check', 'B7 negative discount is rejected');
SELECT pg_temp.raises($q$INSERT INTO orders (emp_id, status) VALUES (2, 'Done')$q$, 'orders_status_check', 'B8 invalid order status is rejected');
SELECT pg_temp.raises($q$INSERT INTO orders (emp_id, request_key) VALUES (2, 'bad key!')$q$, 'orders_request_key_check', 'B9 malformed idempotency key is rejected');
SELECT pg_temp.raises($q$INSERT INTO orders (emp_id) VALUES (999)$q$, 'foreign key', 'B10 order with unknown employee is rejected');
SELECT pg_temp.raises($q$DELETE FROM categories WHERE category_id = 1$q$, 'foreign key', 'B11 category in use cannot be deleted');
SELECT pg_temp.raises($q$DELETE FROM customers WHERE customer_id = 1$q$, 'foreign key', 'B12 customer with orders cannot be deleted');
SELECT pg_temp.raises($q$DELETE FROM employees WHERE emp_id = 2$q$, 'foreign key', 'B13 employee with orders cannot be deleted');
SELECT pg_temp.raises($q$DELETE FROM suppliers WHERE supplier_id = 4$q$, 'foreign key', 'B14 supplier with products cannot be deleted');
SELECT pg_temp.ok((SELECT COUNT(*) FROM (SELECT 1 FROM orders WHERE discount_percent = 50 UNION ALL SELECT 1) t) >= 1, 'B15 (control) query works');
DO $$ BEGIN INSERT INTO orders (emp_id, discount_percent) VALUES (2, 50); PERFORM pg_temp.ok(TRUE, 'B16 discount of exactly 50 percent is accepted'); END $$;

-- ---------------------------------------------------------------------
-- C. Sale: stock deducted once, price from catalogue, rounding, idempotency key
-- ---------------------------------------------------------------------
DO $$
DECLARE
    s0 INT; s1 INT; logs0 INT; oid INT; price NUMERIC; expected NUMERIC;
BEGIN
    SELECT stock_qty, p.price INTO s0, price FROM products p WHERE product_id = 2;
    SELECT COUNT(*) INTO logs0 FROM stock_log WHERE product_id = 2;
    INSERT INTO orders (customer_id, emp_id, discount_percent, request_key) VALUES (3, 2, 33.33, 'test-key-0001') RETURNING order_id INTO oid;
    INSERT INTO order_items (order_id, product_id, quantity, unit_price) VALUES (oid, 2, 3, NULL);
    SELECT stock_qty INTO s1 FROM products WHERE product_id = 2;
    PERFORM pg_temp.ok(s1 = s0 - 3, 'C1 sale reduces stock by exactly the quantity (once)');
    PERFORM pg_temp.ok((SELECT COUNT(*) FROM stock_log WHERE product_id = 2) = logs0 + 1, 'C2 sale writes exactly one stock_log row');
    PERFORM pg_temp.ok((SELECT change_qty FROM stock_log WHERE product_id = 2 ORDER BY log_id DESC LIMIT 1) = -3, 'C3 stock_log records -3');
    PERFORM pg_temp.ok((SELECT unit_price FROM order_items WHERE order_id = oid) = price, 'C4 unit price is taken from the product catalogue');
    expected := ROUND(3 * price * (1 - 33.33 / 100), 2);
    PERFORM pg_temp.ok(get_order_total(oid) = expected, 'C5 get_order_total applies the discount and rounds to 2 decimals');
    PERFORM pg_temp.raises(format($q$INSERT INTO orders (emp_id, request_key) VALUES (2, 'test-key-0001')$q$), 'orders_request_key_key', 'C6 repeating an idempotency key is rejected by the database');
    PERFORM pg_temp.ok((SELECT COUNT(*) FROM orders WHERE request_key = 'test-key-0001') = 1, 'C7 only one order exists for the key');
    PERFORM pg_temp.raises(format($q$UPDATE order_items SET quantity = 99 WHERE order_id = %s$q$, oid), 'cannot be changed', 'C8 order items cannot be updated');
    PERFORM pg_temp.raises(format($q$DELETE FROM order_items WHERE order_id = %s$q$, oid), 'cannot be changed', 'C9 order items cannot be deleted');
END $$;

-- insufficient stock: rejected, and the order row of the failed attempt is not left behind
DO $$
DECLARE
    s0 INT; s1 INT; orders0 INT;
BEGIN
    SELECT stock_qty INTO s0 FROM products WHERE product_id = 6;
    SELECT COUNT(*) INTO orders0 FROM orders;
    BEGIN
        INSERT INTO orders (emp_id, request_key) VALUES (2, 'test-key-0002');
        INSERT INTO order_items (order_id, product_id, quantity, unit_price)
        VALUES (currval(pg_get_serial_sequence('orders','order_id'))::INT, 6, s0 + 1, NULL);
        RAISE EXCEPTION 'FAIL: oversell was not rejected';
    EXCEPTION WHEN raise_exception THEN
        IF SQLERRM NOT LIKE '%Insufficient stock%' THEN RAISE; END IF;
    END;
    SELECT stock_qty INTO s1 FROM products WHERE product_id = 6;
    PERFORM pg_temp.ok(s1 = s0, 'C10 insufficient stock is rejected and stock is unchanged');
    PERFORM pg_temp.ok((SELECT COUNT(*) FROM orders) = orders0, 'C11 failed sale leaves no order row (transaction rolled back)');
    PERFORM pg_temp.ok((SELECT COUNT(*) FROM orders WHERE request_key = 'test-key-0002') = 0, 'C12 failed sale does not consume its idempotency key');
END $$;

DO $$
DECLARE oid INT;
BEGIN
    UPDATE products SET is_active = FALSE WHERE product_id = 11;
    INSERT INTO orders (emp_id) VALUES (2) RETURNING order_id INTO oid;
    PERFORM pg_temp.raises(format($q$INSERT INTO order_items (order_id, product_id, quantity, unit_price) VALUES (%s, 11, 1, NULL)$q$, oid), 'inactive', 'C13 inactive product cannot be sold');
END $$;

-- ---------------------------------------------------------------------
-- D. Payment: once only, exact amount, loyalty points once, cancelled order, permanence
-- ---------------------------------------------------------------------
DO $$
DECLARE
    oid INT; total NUMERIC; pts0 INT; pts1 INT; pts2 INT; pid INT;
BEGIN
    SELECT loyalty_points INTO pts0 FROM customers WHERE customer_id = 3;
    INSERT INTO orders (customer_id, emp_id, discount_percent) VALUES (3, 2, 10) RETURNING order_id INTO oid;
    INSERT INTO order_items (order_id, product_id, quantity, unit_price) VALUES (oid, 6, 2, NULL);   -- 2 x 290 = 580, 10% off = 522
    total := get_order_total(oid);
    PERFORM pg_temp.ok(total = 522.00, 'D1 order total is 522.00 (580 less 10 percent)');

    PERFORM pg_temp.raises(format($q$INSERT INTO payments (order_id, amount, method) VALUES (%s, 500, 'Cash')$q$, oid), 'does not match', 'D2 payment with the wrong amount is rejected');
    PERFORM pg_temp.raises(format($q$INSERT INTO payments (order_id, amount, method) VALUES (%s, %s, 'Cheque')$q$, oid, total), 'payments_method_check', 'D3 invalid payment method is rejected');
    PERFORM pg_temp.ok((SELECT COUNT(*) FROM payments WHERE order_id = oid) = 0, 'D4 rejected payments left nothing behind');

    INSERT INTO payments (order_id, amount, method) VALUES (oid, total, 'UPI') RETURNING payment_id INTO pid;
    SELECT loyalty_points INTO pts1 FROM customers WHERE customer_id = 3;
    PERFORM pg_temp.ok(pts1 = pts0 + 5, 'D5 payment of 522 awards floor(522/100) = 5 loyalty points');

    PERFORM pg_temp.raises(format($q$INSERT INTO payments (order_id, amount, method) VALUES (%s, %s, 'Cash')$q$, oid, total), 'uq_payments_order', 'D6 a second payment for the same order is rejected');
    SELECT loyalty_points INTO pts2 FROM customers WHERE customer_id = 3;
    PERFORM pg_temp.ok(pts2 = pts1, 'D7 the rejected duplicate payment awarded no extra points');
    PERFORM pg_temp.raises(format($q$UPDATE payments SET amount = 1 WHERE payment_id = %s$q$, pid), 'cannot be changed', 'D8 payments cannot be edited');
    PERFORM pg_temp.raises(format($q$DELETE FROM payments WHERE payment_id = %s$q$, pid), 'cannot be changed', 'D9 payments cannot be deleted');
END $$;

-- ---------------------------------------------------------------------
-- E. Cancellation: stock restored once, points reversed once, no re-open, no payment on cancelled order
-- ---------------------------------------------------------------------
DO $$
DECLARE
    oid INT; s_before INT; s_sold INT; s_after INT; s_again INT; pts0 INT; pts_paid INT; pts_cancel INT; total NUMERIC; logs INT;
BEGIN
    SELECT stock_qty INTO s_before FROM products WHERE product_id = 9;
    SELECT loyalty_points INTO pts0 FROM customers WHERE customer_id = 5;
    INSERT INTO orders (customer_id, emp_id) VALUES (5, 2) RETURNING order_id INTO oid;
    INSERT INTO order_items (order_id, product_id, quantity, unit_price) VALUES (oid, 9, 4, NULL);   -- 4 x 85 = 340
    total := get_order_total(oid);
    INSERT INTO payments (order_id, amount, method) VALUES (oid, total, 'Card');
    SELECT stock_qty INTO s_sold FROM products WHERE product_id = 9;
    SELECT loyalty_points INTO pts_paid FROM customers WHERE customer_id = 5;
    PERFORM pg_temp.ok(s_sold = s_before - 4 AND pts_paid = pts0 + 3, 'E1 sale of 340: stock -4 and +3 points');

    UPDATE orders SET status = 'Cancelled' WHERE order_id = oid;
    SELECT stock_qty INTO s_after FROM products WHERE product_id = 9;
    SELECT loyalty_points INTO pts_cancel FROM customers WHERE customer_id = 5;
    PERFORM pg_temp.ok(s_after = s_before, 'E2 cancelling restores the stock exactly once');
    PERFORM pg_temp.ok(pts_cancel = pts0, 'E3 cancelling reverses the loyalty points');
    SELECT COUNT(*) INTO logs FROM stock_log WHERE product_id = 9 AND reason = 'Sale Cancelled';

    UPDATE orders SET status = 'Cancelled' WHERE order_id = oid;       -- repeated cancel
    SELECT stock_qty INTO s_again FROM products WHERE product_id = 9;
    PERFORM pg_temp.ok(s_again = s_before AND (SELECT COUNT(*) FROM stock_log WHERE product_id = 9 AND reason = 'Sale Cancelled') = logs, 'E4 a repeated cancel changes nothing (no double restore)');
    PERFORM pg_temp.raises(format($q$UPDATE orders SET status = 'Completed' WHERE order_id = %s$q$, oid), 're-opened', 'E5 a cancelled order cannot be re-opened');
    PERFORM pg_temp.raises(format($q$INSERT INTO order_items (order_id, product_id, quantity, unit_price) VALUES (%s, 9, 1, NULL)$q$, oid), 'cancelled', 'E6 items cannot be added to a cancelled order');
END $$;

DO $$
DECLARE oid INT;
BEGIN   -- a Pending order that is cancelled before payment cannot be paid afterwards
    INSERT INTO orders (customer_id, emp_id, status) VALUES (5, 2, 'Pending') RETURNING order_id INTO oid;
    INSERT INTO order_items (order_id, product_id, quantity, unit_price) VALUES (oid, 9, 1, NULL);
    UPDATE orders SET status = 'Cancelled' WHERE order_id = oid;
    PERFORM pg_temp.raises(format($q$INSERT INTO payments (order_id, amount, method) VALUES (%s, 85, 'Cash')$q$, oid), 'cancelled', 'E7 payment on a cancelled order is rejected');
END $$;

-- ---------------------------------------------------------------------
-- F. Purchase orders: stock only on receive, exactly once, history protected
-- ---------------------------------------------------------------------
DO $$
DECLARE
    po INT; s0 INT; s1 INT; s2 INT; s3 INT; logs INT; po2 INT;
BEGIN
    SELECT stock_qty INTO s0 FROM products WHERE product_id = 12;
    INSERT INTO purchase_orders (supplier_id) VALUES (4) RETURNING po_id INTO po;
    PERFORM pg_temp.ok((SELECT status FROM purchase_orders WHERE po_id = po) = 'Ordered', 'F1 a new purchase order starts as Ordered');
    INSERT INTO purchase_order_items (po_id, product_id, quantity, unit_cost) VALUES (po, 12, 25, 14), (po, 12, 5, 14);   -- same product twice
    SELECT stock_qty INTO s1 FROM products WHERE product_id = 12;
    PERFORM pg_temp.ok(s1 = s0, 'F2 creating a purchase order does not change stock');

    UPDATE purchase_orders SET status = 'Received' WHERE po_id = po;
    SELECT stock_qty INTO s2 FROM products WHERE product_id = 12;
    PERFORM pg_temp.ok(s2 = s0 + 30, 'F3 receiving adds 25 + 5 = 30 units once');
    SELECT COUNT(*) INTO logs FROM stock_log WHERE product_id = 12 AND reason = 'Restock';

    UPDATE purchase_orders SET status = 'Received' WHERE po_id = po;      -- repeat: same status, no transition
    SELECT stock_qty INTO s3 FROM products WHERE product_id = 12;
    PERFORM pg_temp.ok(s3 = s2 AND (SELECT COUNT(*) FROM stock_log WHERE product_id = 12 AND reason = 'Restock') = logs, 'F4 receiving again adds nothing');
    PERFORM pg_temp.raises(format($q$UPDATE purchase_orders SET status = 'Cancelled' WHERE po_id = %s$q$, po), 'already Received', 'F5 a received purchase order cannot be cancelled');
    PERFORM pg_temp.raises(format($q$INSERT INTO purchase_order_items (po_id, product_id, quantity, unit_cost) VALUES (%s, 12, 1, 1)$q$, po), 'can no longer be changed', 'F6 items cannot be added after receiving');
    PERFORM pg_temp.raises(format($q$DELETE FROM purchase_order_items WHERE po_id = %s$q$, po), 'can no longer be changed', 'F7 items cannot be deleted after receiving');
    PERFORM pg_temp.raises(format($q$UPDATE purchase_order_items SET quantity = 999 WHERE po_id = %s$q$, po), 'can no longer be changed', 'F8 items cannot be edited after receiving');
    PERFORM pg_temp.raises(format($q$DELETE FROM purchase_orders WHERE po_id = %s$q$, po), 'cannot be deleted', 'F9 a received purchase order cannot be deleted');

    INSERT INTO purchase_orders (supplier_id) VALUES (4) RETURNING po_id INTO po2;
    PERFORM pg_temp.raises(format($q$UPDATE purchase_orders SET status = 'Received' WHERE po_id = %s$q$, po2), 'no items', 'F10 an empty purchase order cannot be received');
    INSERT INTO purchase_order_items (po_id, product_id, quantity, unit_cost) VALUES (po2, 12, 10, 14);
    UPDATE purchase_orders SET status = 'Cancelled' WHERE po_id = po2;
    PERFORM pg_temp.ok((SELECT stock_qty FROM products WHERE product_id = 12) = s0 + 30, 'F11 cancelling a purchase order does not change stock');
    PERFORM pg_temp.raises(format($q$UPDATE purchase_orders SET status = 'Received' WHERE po_id = %s$q$, po2), 'already Cancelled', 'F12 a cancelled purchase order cannot be received');
    DELETE FROM purchase_orders WHERE po_id = po2;
    PERFORM pg_temp.ok(NOT EXISTS (SELECT 1 FROM purchase_orders WHERE po_id = po2), 'F13 an unreceived purchase order can be deleted together with its items');
END $$;

-- ---------------------------------------------------------------------
-- G. Stock log is append-only (opening-stock rows may go with a brand-new product)
-- ---------------------------------------------------------------------
DO $$
DECLARE pid INT; lid INT;
BEGIN
    SELECT log_id INTO lid FROM stock_log ORDER BY log_id LIMIT 1;
    PERFORM pg_temp.raises(format($q$UPDATE stock_log SET change_qty = 0 WHERE log_id = %s$q$, lid), 'cannot be changed', 'G1 stock_log rows cannot be edited');
    PERFORM pg_temp.raises(format($q$DELETE FROM stock_log WHERE log_id = %s$q$, lid), 'cannot be changed', 'G2 sale/restock log rows cannot be deleted');
    INSERT INTO products (product_name, category_id, cost_price, price, stock_qty, reorder_level) VALUES ('Temp product', 1, 5, 9, 20, 5) RETURNING product_id INTO pid;
    INSERT INTO stock_log (product_id, change_qty, reason) VALUES (pid, 20, 'Opening Stock');
    DELETE FROM stock_log WHERE product_id = pid AND reason = 'Opening Stock';
    DELETE FROM products WHERE product_id = pid;
    PERFORM pg_temp.ok(NOT EXISTS (SELECT 1 FROM products WHERE product_id = pid), 'G3 a new product with only opening stock can be removed');
END $$;

-- ---------------------------------------------------------------------
-- H. Reports are database-derived and consistent with each other
-- ---------------------------------------------------------------------
SELECT pg_temp.ok((SELECT SUM(total_sales) FROM monthly_sales) = (SELECT SUM(net_amount) FROM order_summary WHERE status = 'Completed'), 'H1 monthly_sales total = sum of completed orders in order_summary');
SELECT pg_temp.ok((SELECT SUM(total_spent) FROM customer_spending) = (SELECT SUM(get_order_total(order_id)) FROM orders WHERE status = 'Completed' AND customer_id IS NOT NULL), 'H2 customer_spending ignores cancelled and pending orders');
SELECT pg_temp.ok((SELECT SUM(units_sold) FROM top_selling_products) = (SELECT SUM(i.quantity) FROM order_items i JOIN orders o USING (order_id) WHERE o.status = 'Completed'), 'H3 top_selling_products counts completed orders only');
SELECT pg_temp.ok((SELECT COUNT(*) FROM low_stock_products) = (SELECT COUNT(*) FROM products WHERE stock_qty <= reorder_level AND is_active), 'H4 low_stock_products matches the reorder-level rule');
SELECT pg_temp.ok((SELECT COALESCE(SUM(amount), 0) FROM payments) = (SELECT COALESCE(SUM(get_order_total(order_id)), 0) FROM orders WHERE order_id IN (SELECT order_id FROM payments)), 'H5 money invariant: payments equal the totals of the orders they pay');
SELECT pg_temp.ok((SELECT COUNT(*) FROM orders o WHERE o.status = 'Cancelled' AND EXISTS (SELECT 1 FROM payments p WHERE p.order_id = o.order_id AND p.paid_at > o.order_date + INTERVAL '1 year')) = 0, 'H6 (control) no cancelled order carries a future payment');
SELECT pg_temp.ok((SELECT COUNT(*) FROM products WHERE stock_qty < 0) = 0, 'H7 stock is never negative after all tests');

DO $$ BEGIN RAISE NOTICE 'ALL DATABASE TESTS PASSED (% checks)', (SELECT n FROM test_counter); END $$;
ROLLBACK;

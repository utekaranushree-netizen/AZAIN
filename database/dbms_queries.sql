-- =====================================================================
-- STATIONARY SHOP  -  DBMS CONCEPTS AND QUERIES  (PostgreSQL)
-- Run AFTER database/stationary_shop.sql has been loaded:
--     psql -U postgres -d stationary_shop -f database/dbms_queries.sql
--
-- Every statement that CHANGES data is wrapped in BEGIN ... ROLLBACK, so the
-- sample data is left untouched and this file can be run any number of times.
-- (Remove the ROLLBACK / use COMMIT if you want to keep a change.)
--
-- CONTENTS
--   1. DDL   : CREATE DATABASE, CREATE TABLE, ALTER TABLE, constraints
--   2. DML   : INSERT, UPDATE, DELETE
--   3. SELECT, WHERE, ORDER BY
--   4. Aggregates, GROUP BY, HAVING
--   5. Joins
--   6. Nested queries and subqueries
--   7. Practical business queries
--   8. Functions, views, indexes, triggers, transactions, payment rules, idempotency key, discount rule
-- =====================================================================

\set ON_ERROR_STOP off
\pset null '(null)'

-- =====================================================================
-- 1. DDL (Data Definition Language)
-- =====================================================================

-- 1.1 CREATE DATABASE (run once, connected to the default "postgres" database;
--     left commented because the database already exists)
-- CREATE DATABASE stationary_shop;

-- 1.2 CREATE TABLE with PRIMARY KEY, FOREIGN KEY, UNIQUE, NOT NULL, CHECK, DEFAULT.
--     The real tables are in stationary_shop.sql; here is a small extra table
--     for product feedback, created inside a transaction and rolled back.
BEGIN;

CREATE TABLE product_feedback (
    feedback_id  INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,           -- primary key
    product_id   INT NOT NULL REFERENCES products(product_id),           -- foreign key
    customer_id  INT REFERENCES customers(customer_id),                  -- nullable FK
    rating       INT NOT NULL CHECK (rating BETWEEN 1 AND 5),            -- check
    comment      VARCHAR(200),
    given_on     DATE NOT NULL DEFAULT CURRENT_DATE,                     -- default
    UNIQUE (product_id, customer_id)                                     -- unique
);

-- 1.3 ALTER TABLE (safe, documented schema changes on the demo table)
ALTER TABLE product_feedback ADD COLUMN is_public BOOLEAN NOT NULL DEFAULT TRUE;          -- add column
ALTER TABLE product_feedback ALTER COLUMN comment TYPE VARCHAR(500);                      -- change type
ALTER TABLE product_feedback ADD CONSTRAINT chk_comment_len CHECK (LENGTH(comment) > 2);  -- add constraint
ALTER TABLE product_feedback DROP CONSTRAINT chk_comment_len;                             -- drop constraint
ALTER TABLE product_feedback DROP COLUMN is_public;                                       -- drop column
ALTER TABLE product_feedback RENAME COLUMN comment TO feedback_text;                      -- rename column

SELECT column_name, data_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_name = 'product_feedback' ORDER BY ordinal_position;

DROP TABLE product_feedback;
ROLLBACK;

-- 1.4 Constraints that exist in the real schema (listed from the catalogue)
SELECT conrelid::regclass AS table_name, conname AS constraint_name,
       CASE contype WHEN 'p' THEN 'PRIMARY KEY' WHEN 'f' THEN 'FOREIGN KEY'
                    WHEN 'u' THEN 'UNIQUE'      WHEN 'c' THEN 'CHECK' END AS type,
       pg_get_constraintdef(oid) AS definition
FROM pg_constraint
WHERE connamespace = 'public'::regnamespace
ORDER BY conrelid::regclass::text, contype;

-- =====================================================================
-- 2. DML (Data Manipulation Language)  -  all rolled back
-- =====================================================================
BEGIN;

-- 2.1 INSERT: a new category and product
INSERT INTO categories (category_name, description) VALUES ('Craft Paper', 'Coloured chart and craft paper');

INSERT INTO products (product_name, brand, category_id, supplier_id, cost_price, price, stock_qty, reorder_level)
VALUES ('Chart Paper (Pack of 10)', 'Navneet',
        (SELECT category_id FROM categories WHERE category_name = 'Craft Paper'), 1, 60.00, 95.00, 40, 10);

-- 2.2 UPDATE: 5% price rise on all Paper products, then one product's stock threshold
UPDATE products SET price = ROUND(price * 1.05, 2)
WHERE category_id = (SELECT category_id FROM categories WHERE category_name = 'Paper');

UPDATE products SET reorder_level = 25 WHERE product_name = 'Gel Pen Black';

-- 2.3 DELETE: remove records that nothing else refers to
DELETE FROM customers WHERE customer_id = 10;      -- Neha Gupta has no orders
DELETE FROM employees WHERE emp_id = 6;            -- Vijay More has handled no orders
DELETE FROM suppliers WHERE supplier_id = 6;       -- Kangaro Industries has no products/POs

SELECT 'products' AS tbl, COUNT(*) FROM products UNION ALL
SELECT 'customers', COUNT(*) FROM customers UNION ALL
SELECT 'employees', COUNT(*) FROM employees UNION ALL
SELECT 'suppliers', COUNT(*) FROM suppliers;
ROLLBACK;

-- 2.4 Foreign-key protection: this DELETE is refused because orders refer to the customer
DO $$
BEGIN
    DELETE FROM customers WHERE customer_id = 1;
EXCEPTION WHEN foreign_key_violation THEN
    RAISE NOTICE 'DELETE blocked by foreign key: %', SQLERRM;
END $$;

-- =====================================================================
-- 3. SELECT, WHERE, ORDER BY
-- =====================================================================

-- 3.1 All products, cheapest first
SELECT product_id, product_name, brand, price, stock_qty FROM products ORDER BY price ASC;

-- 3.2 WHERE with several conditions: active Camlin products priced above Rs 50
SELECT product_name, price FROM products
WHERE brand = 'Camlin' AND price > 50 AND is_active ORDER BY price DESC;

-- 3.3 Pattern matching, range and IN list
SELECT product_name, price FROM products WHERE product_name ILIKE '%pen%';
SELECT product_name, price FROM products WHERE price BETWEEN 50 AND 150 ORDER BY product_name;
SELECT customer_name, city FROM customers WHERE city IN ('Pune', 'Thane');

-- 3.4 Walk-in customers: orders whose customer_id IS NULL
SELECT order_id, order_date, status FROM orders WHERE customer_id IS NULL;

-- =====================================================================
-- 4. AGGREGATES, GROUP BY, HAVING
-- =====================================================================

-- 4.1 COUNT, SUM, AVG, MIN, MAX over the product table
SELECT COUNT(*)            AS total_products,
       SUM(stock_qty)      AS total_units,
       ROUND(AVG(price),2) AS avg_price,
       MIN(price)          AS cheapest,
       MAX(price)          AS dearest
FROM products;

-- 4.2 Products and stock per category (GROUP BY)
SELECT c.category_name, COUNT(*) AS products, SUM(p.stock_qty) AS units_in_stock
FROM products p JOIN categories c ON c.category_id = p.category_id
GROUP BY c.category_name ORDER BY units_in_stock DESC;

-- 4.3 HAVING: categories whose average selling price exceeds Rs 60
SELECT c.category_name, ROUND(AVG(p.price),2) AS avg_price
FROM products p JOIN categories c ON c.category_id = p.category_id
GROUP BY c.category_name HAVING AVG(p.price) > 60 ORDER BY avg_price DESC;

-- 4.4 HAVING: customers who placed 2 or more completed orders
SELECT customer_id, COUNT(*) AS orders_placed
FROM orders WHERE status = 'Completed' AND customer_id IS NOT NULL
GROUP BY customer_id HAVING COUNT(*) >= 2;

-- 4.5 Orders per status
SELECT status, COUNT(*) AS orders FROM orders GROUP BY status ORDER BY orders DESC;

-- =====================================================================
-- 5. JOINS
-- =====================================================================

-- 5.1 INNER JOIN (3 tables): products with their category and supplier name
SELECT p.product_id, p.product_name, c.category_name, s.supplier_name
FROM products p
INNER JOIN categories c ON c.category_id = p.category_id
INNER JOIN suppliers  s ON s.supplier_id = p.supplier_id
ORDER BY c.category_name, p.product_name;

-- 5.2 LEFT JOIN: every customer, with their number of orders (0 if none)
SELECT c.customer_name, COUNT(o.order_id) AS orders
FROM customers c LEFT JOIN orders o ON o.customer_id = c.customer_id
GROUP BY c.customer_name ORDER BY orders DESC, c.customer_name;

-- 5.3 LEFT JOIN: orders with customer name; walk-ins show as 'Walk-in'
SELECT o.order_id, COALESCE(c.customer_name, 'Walk-in') AS customer, e.emp_name AS served_by
FROM orders o
LEFT JOIN customers c ON c.customer_id = o.customer_id
JOIN employees e      ON e.emp_id = o.emp_id
ORDER BY o.order_id;

-- 5.4 Full receipt of order 3: order -> items -> products (-> payment)
SELECT o.order_id, p.product_name, oi.quantity, oi.unit_price, oi.line_total,
       o.discount_percent, get_order_total(o.order_id) AS payable, pay.method
FROM orders o
JOIN order_items oi ON oi.order_id = o.order_id
JOIN products p     ON p.product_id = oi.product_id
LEFT JOIN payments pay ON pay.order_id = o.order_id
WHERE o.order_id = 3;

-- 5.5 Products supplied by each supplier (LEFT JOIN keeps suppliers with no products)
SELECT s.supplier_name, p.product_name
FROM suppliers s LEFT JOIN products p ON p.supplier_id = s.supplier_id
ORDER BY s.supplier_name, p.product_name;

-- =====================================================================
-- 6. NESTED QUERIES AND SUBQUERIES
-- =====================================================================

-- 6.1 Scalar subquery: products priced above the average price
SELECT product_name, price FROM products
WHERE price > (SELECT AVG(price) FROM products) ORDER BY price DESC;

-- 6.2 IN subquery: customers who bought Sketch Book A3
SELECT customer_name FROM customers
WHERE customer_id IN (SELECT o.customer_id FROM orders o
                      JOIN order_items oi ON oi.order_id = o.order_id
                      JOIN products p ON p.product_id = oi.product_id
                      WHERE p.product_name = 'Sketch Book A3');

-- 6.3 NOT EXISTS: products that have never been sold
SELECT product_name FROM products p
WHERE NOT EXISTS (SELECT 1 FROM order_items oi WHERE oi.product_id = p.product_id);

-- 6.4 Correlated subquery: each product's stock compared with the average of its category
SELECT p.product_name, p.stock_qty,
       (SELECT ROUND(AVG(stock_qty),1) FROM products x WHERE x.category_id = p.category_id) AS category_avg
FROM products p ORDER BY p.product_name;

-- 6.5 Subquery in HAVING: employees handling more completed orders than the average employee
SELECT emp_id, COUNT(*) AS orders_handled
FROM orders WHERE status = 'Completed' GROUP BY emp_id
HAVING COUNT(*) > (SELECT COUNT(*)::NUMERIC / COUNT(DISTINCT emp_id) FROM orders WHERE status = 'Completed');

-- 6.6 Subquery in FROM: the highest-value completed order
SELECT * FROM (SELECT order_id, get_order_total(order_id) AS payable
               FROM orders WHERE status = 'Completed') t
ORDER BY payable DESC LIMIT 1;

-- =====================================================================
-- 7. PRACTICAL BUSINESS QUERIES
-- =====================================================================

-- 7.1 Products below (or at) their reorder level
SELECT product_name, stock_qty, reorder_level, reorder_level - stock_qty AS shortfall
FROM products WHERE stock_qty <= reorder_level AND is_active ORDER BY shortfall DESC;

-- 7.2 Total sales for each month (completed orders)
SELECT TO_CHAR(order_date, 'YYYY-MM') AS month, COUNT(*) AS orders,
       SUM(get_order_total(order_id)) AS total_sales
FROM orders WHERE status = 'Completed' GROUP BY 1 ORDER BY 1;

-- 7.3 Revenue by category (before order-level discount)
SELECT c.category_name, SUM(oi.line_total) AS revenue
FROM order_items oi
JOIN orders o     ON o.order_id = oi.order_id AND o.status = 'Completed'
JOIN products p   ON p.product_id = oi.product_id
JOIN categories c ON c.category_id = p.category_id
GROUP BY c.category_name ORDER BY revenue DESC;

-- 7.4 Customers with the highest spending
SELECT customer_name, orders_placed, total_spent, loyalty_points
FROM customer_spending ORDER BY total_spent DESC LIMIT 5;

-- 7.5 Employees who handled the most orders
SELECT e.emp_name, e.role, COUNT(o.order_id) AS orders_handled,
       COALESCE(SUM(get_order_total(o.order_id)), 0) AS sales_value
FROM employees e LEFT JOIN orders o ON o.emp_id = e.emp_id AND o.status = 'Completed'
GROUP BY e.emp_id, e.emp_name, e.role ORDER BY orders_handled DESC, sales_value DESC;

-- 7.6 Total inventory value, at cost and at selling price
SELECT SUM(stock_qty * cost_price) AS value_at_cost, SUM(stock_qty * price) AS value_at_selling_price
FROM products;

-- 7.7 Profit per product sold (completed orders)
SELECT p.product_name, SUM(oi.quantity) AS units_sold,
       SUM(oi.quantity * (oi.unit_price - p.cost_price)) AS profit
FROM order_items oi
JOIN orders o   ON o.order_id = oi.order_id AND o.status = 'Completed'
JOIN products p ON p.product_id = oi.product_id
GROUP BY p.product_name ORDER BY profit DESC;

-- 7.8 Payments by method
SELECT method, COUNT(*) AS payments, SUM(amount) AS total FROM payments GROUP BY method ORDER BY total DESC;

-- 7.9 Outstanding purchase orders and their value
SELECT po.po_id, s.supplier_name, po.po_date, po.status,
       SUM(i.quantity * i.unit_cost) AS po_value
FROM purchase_orders po
JOIN suppliers s ON s.supplier_id = po.supplier_id
JOIN purchase_order_items i ON i.po_id = po.po_id
GROUP BY po.po_id, s.supplier_name, po.po_date, po.status ORDER BY po.po_id;

-- =====================================================================
-- 8. FUNCTIONS, VIEWS, INDEXES, TRIGGERS, TRANSACTIONS
-- =====================================================================

-- 8.1 Function: get_order_total(order_id) = sum of line totals less discount
SELECT order_id, discount_percent, get_order_total(order_id) AS payable FROM orders ORDER BY order_id;

-- 8.2 Views
SELECT * FROM order_summary ORDER BY order_id;
SELECT * FROM low_stock_products;
SELECT * FROM top_selling_products LIMIT 5;
SELECT * FROM monthly_sales;
SELECT * FROM customer_spending ORDER BY total_spent DESC;

-- 8.3 Indexes: list them, then show one being used
SELECT tablename, indexname, indexdef FROM pg_indexes
WHERE schemaname = 'public' ORDER BY tablename, indexname;
EXPLAIN SELECT * FROM orders WHERE customer_id = 3;

-- 8.4 Trigger: SALE reduces stock automatically and writes stock_log
BEGIN;
SELECT product_id, stock_qty FROM products WHERE product_id = 4;                   -- before
INSERT INTO orders (customer_id, emp_id) VALUES (3, 2);
INSERT INTO order_items (order_id, product_id, quantity, unit_price)
VALUES (currval(pg_get_serial_sequence('orders','order_id')), 4, 3, NULL);          -- price filled by trigger
SELECT product_id, stock_qty FROM products WHERE product_id = 4;                   -- after (3 less)
SELECT * FROM stock_log WHERE product_id = 4 ORDER BY log_id DESC LIMIT 1;
ROLLBACK;

-- 8.5 Trigger: insufficient stock is rejected and nothing is saved
DO $$
BEGIN
    INSERT INTO orders (customer_id, emp_id) VALUES (3, 2);
    INSERT INTO order_items (order_id, product_id, quantity, unit_price)
    VALUES (currval(pg_get_serial_sequence('orders','order_id')), 6, 9999, NULL);
EXCEPTION WHEN raise_exception THEN
    RAISE NOTICE 'Sale rejected: %', SQLERRM;   -- the whole block (including the order row) is rolled back
END $$;

-- 8.6 Trigger: PAYMENT awards loyalty points (1 per Rs 100)
BEGIN;
SELECT loyalty_points FROM customers WHERE customer_id = 3;                         -- before
INSERT INTO orders (customer_id, emp_id) VALUES (3, 2);
INSERT INTO order_items (order_id, product_id, quantity, unit_price)
VALUES (currval(pg_get_serial_sequence('orders','order_id')), 9, 2, NULL);          -- 2 x Rs 85 = 170
INSERT INTO payments (order_id, amount, method)
VALUES (currval(pg_get_serial_sequence('orders','order_id')),
        get_order_total(currval(pg_get_serial_sequence('orders','order_id'))::INT), 'Card');
SELECT loyalty_points FROM customers WHERE customer_id = 3;                         -- after (+1)
ROLLBACK;

-- 8.7 Trigger: CANCELLING an order restores stock and reverses points
BEGIN;
SELECT product_id, stock_qty FROM products WHERE product_id IN (2, 5) ORDER BY 1;  -- before
UPDATE orders SET status = 'Cancelled' WHERE order_id = 13;                         -- order 13 sold products 2, 5, 12, 3
SELECT product_id, stock_qty FROM products WHERE product_id IN (2, 5) ORDER BY 1;  -- stock returned
SELECT * FROM stock_log WHERE reason = 'Sale Cancelled' ORDER BY log_id DESC LIMIT 4;
ROLLBACK;

-- 8.8 Trigger: PURCHASE ORDER increases stock only when it is RECEIVED, and only once
BEGIN;
INSERT INTO purchase_orders (supplier_id) VALUES (4);                               -- status defaults to 'Ordered'
INSERT INTO purchase_order_items (po_id, product_id, quantity, unit_cost)
VALUES (currval(pg_get_serial_sequence('purchase_orders','po_id')), 12, 25, 14.00);
SELECT stock_qty AS stock_after_creating_po FROM products WHERE product_id = 12;   -- unchanged (2)
UPDATE purchase_orders SET status = 'Received'
WHERE po_id = currval(pg_get_serial_sequence('purchase_orders','po_id'));
SELECT stock_qty AS stock_after_receiving FROM products WHERE product_id = 12;     -- 2 + 25 = 27
-- a second receive attempt is refused:
DO $$
BEGIN
    UPDATE purchase_orders SET status = 'Received'
    WHERE po_id = currval(pg_get_serial_sequence('purchase_orders','po_id'));
    UPDATE purchase_orders SET status = 'Cancelled'
    WHERE po_id = currval(pg_get_serial_sequence('purchase_orders','po_id'));
EXCEPTION WHEN raise_exception THEN
    RAISE NOTICE 'Refused: %', SQLERRM;
END $$;
SELECT stock_qty AS stock_still FROM products WHERE product_id = 12;               -- still 27
ROLLBACK;

-- 8.9 Transaction: place a complete order atomically (order + items + payment)
BEGIN;
INSERT INTO orders (customer_id, emp_id, discount_percent) VALUES (3, 2, 5);
INSERT INTO order_items (order_id, product_id, quantity, unit_price)
VALUES (currval(pg_get_serial_sequence('orders','order_id')), 4, 3, NULL),
       (currval(pg_get_serial_sequence('orders','order_id')), 9, 1, NULL);
INSERT INTO payments (order_id, amount, method)
VALUES (currval(pg_get_serial_sequence('orders','order_id')),
        get_order_total(currval(pg_get_serial_sequence('orders','order_id'))::INT), 'UPI');
SELECT * FROM order_summary WHERE order_id = currval(pg_get_serial_sequence('orders','order_id'));
ROLLBACK;     -- ROLLBACK cancels everything; use COMMIT to keep the order

-- 8.10 Safe alternative to DELETE for products with history: deactivate instead
BEGIN;
UPDATE products SET is_active = FALSE WHERE product_name = 'Steel Ruler 30 cm';
SELECT product_name, is_active FROM products WHERE product_name = 'Steel Ruler 30 cm';
SELECT COUNT(*) AS low_stock_after_deactivation FROM low_stock_products;           -- inactive products drop out of the alert list
ROLLBACK;

-- 8.11 Payment rules: exact amount, one payment per order, no payment on a cancelled order
BEGIN;
INSERT INTO orders (customer_id, emp_id) VALUES (3, 2);
INSERT INTO order_items (order_id, product_id, quantity, unit_price)
VALUES (currval(pg_get_serial_sequence('orders','order_id'))::INT, 9, 1, NULL);          -- 1 x Rs 85
DO $$
DECLARE oid INT := currval(pg_get_serial_sequence('orders','order_id'));
BEGIN
    BEGIN
        INSERT INTO payments (order_id, amount, method) VALUES (oid, 1, 'Cash');         -- wrong amount
    EXCEPTION WHEN raise_exception THEN
        RAISE NOTICE 'Wrong amount rejected: %', SQLERRM;
    END;
    INSERT INTO payments (order_id, amount, method) VALUES (oid, get_order_total(oid), 'Cash');   -- correct payment
    BEGIN
        INSERT INTO payments (order_id, amount, method) VALUES (oid, get_order_total(oid), 'Card'); -- second payment
    EXCEPTION WHEN unique_violation THEN
        RAISE NOTICE 'Second payment for the same order rejected: %', SQLERRM;
    END;
    UPDATE orders SET status = 'Cancelled' WHERE order_id = oid;
    RAISE NOTICE 'Order % cancelled; its stock was returned and its payment stays on record', oid;
END $$;
ROLLBACK;

-- 8.12 Idempotency key: the same request can never create two orders
BEGIN;
INSERT INTO orders (emp_id, request_key) VALUES (2, 'demo-request-0001');
DO $$
BEGIN
    INSERT INTO orders (emp_id, request_key) VALUES (2, 'demo-request-0001');
EXCEPTION WHEN unique_violation THEN
    RAISE NOTICE 'Repeated request key rejected: %', SQLERRM;
END $$;
SELECT order_id, request_key FROM orders WHERE request_key = 'demo-request-0001';        -- exactly one row
ROLLBACK;

-- 8.13 Business rule enforced by CHECK constraints: discount between 0 and 50 percent
DO $$
BEGIN
    INSERT INTO orders (emp_id, discount_percent) VALUES (2, 60);
EXCEPTION WHEN check_violation THEN
    RAISE NOTICE 'Discount above 50 percent rejected: %', SQLERRM;
END $$;
SELECT MAX(discount_percent) AS highest_discount_in_data FROM orders;

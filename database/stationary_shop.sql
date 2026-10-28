-- =====================================================================
-- STATIONARY SHOP DATABASE  (PostgreSQL 14+)
--
-- !!! WARNING - DESTRUCTIVE RESET SCRIPT !!!
-- This script DROPS and RECREATES every Stationary Shop table, view and function
-- and then loads the sample data. ALL EXISTING DATA IN THESE TABLES IS DELETED.
-- Use a separate development database. For safety it refuses to run unless the
-- database name starts with "stationary_shop" (stationary_shop, stationary_shop_dev,
-- stationary_shop_test ...). The whole script runs in ONE transaction: if anything
-- fails, nothing is changed.
--
-- Already have a database from version 1 and want to KEEP its data?
-- Do not run this file; run database/migrations/001_integrity_hardening.sql instead.
--
-- HOW TO RUN (see README.md):
--   1. CREATE DATABASE stationary_shop;
--   2. psql -U postgres -d stationary_shop -f database/stationary_shop.sql
--
-- SUMMARY OF CORRECTIONS (all marked with "FIX" below)
--  FIX 1  Database name is stationary_shop (was stationery_shop).
--  FIX 2  Purchase-order stock trigger: stock now increases ONLY when a
--         purchase order changes Ordered -> Received, exactly once.
--         Default PO status changed from 'Received' to 'Ordered'.
--  FIX 3  Order cancellation now restores stock and reverses loyalty points
--         (previously cancelling an order left stock deducted).
--  FIX 4  Sales trigger also rejects inactive products and cancelled orders,
--         and stock_log.logged_at uses the order's own date.
--  FIX 5  Order items are immutable (stock would go out of sync otherwise);
--         an order is undone by cancelling it.
--  FIX 6  Views top_selling_products / customer_spending now ignore
--         Cancelled and Pending orders (revenue was overstated).
--  FIX 7  reorder_level gets CHECK (>= 0); extra foreign-key indexes added.
--  FIX 9  (v2) Payments: one full payment per order (UNIQUE), amount must equal the
--         order total, no payment on a cancelled order, payments are permanent.
--  FIX 10 (v2) Sales idempotency key orders.request_key (UNIQUE) so a repeated
--         request can never create a second order, even after a server restart.
--  FIX 11 (v2) Discount rule aligned in database, Java and frontend: 0 - 50 percent.
--  FIX 12 (v2) Integrity guards: items of a received purchase order cannot be changed
--         or deleted, a received purchase order cannot be deleted, stock_log is
--         append-only (opening-stock rows may be removed with their product).
--  FIX 8  Sample data expanded to meet the college minimums; the example
--         transaction and the demo queries moved to dbms_queries.sql so
--         that loading this script leaves exactly the intended data.
-- =====================================================================

BEGIN;

-- Safety guard: never wipe a database that is not a Stationary Shop database.
DO $$
BEGIN
    IF current_database() NOT LIKE 'stationary\_shop%' THEN
        RAISE EXCEPTION 'Refusing to run: connected to database "%". This script deletes data; run it only in a database whose name starts with stationary_shop.', current_database();
    END IF;
END $$;

-- ---------------------------------------------------------------------
-- 0. CLEAN UP (safe to re-run)
-- ---------------------------------------------------------------------
DROP VIEW  IF EXISTS order_summary, low_stock_products, top_selling_products,
                     monthly_sales, customer_spending CASCADE;
DROP TABLE IF EXISTS stock_log, payments, order_items, orders,
                     purchase_order_items, purchase_orders,
                     products, categories, suppliers, customers, employees CASCADE;
DROP FUNCTION IF EXISTS get_order_total(INT), trg_before_order_item(),
                        trg_after_po_item(), trg_after_payment(),
                        trg_po_status_change(), trg_po_item_guard(),
                        trg_order_status_change(), trg_order_item_immutable(),
                        trg_before_payment(), trg_payment_immutable(),
                        trg_stock_log_guard(), trg_po_delete_guard(), trg_po_item_guard() CASCADE;

-- ---------------------------------------------------------------------
-- 1. TABLES
-- ---------------------------------------------------------------------
CREATE TABLE categories (
    category_id   INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    category_name VARCHAR(50) NOT NULL UNIQUE,
    description   VARCHAR(200)
);

CREATE TABLE suppliers (
    supplier_id    INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    supplier_name  VARCHAR(80) NOT NULL,
    contact_person VARCHAR(60),
    phone          VARCHAR(15),
    email          VARCHAR(80) UNIQUE,
    city           VARCHAR(40)
);

CREATE TABLE products (
    product_id    INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_name  VARCHAR(80) NOT NULL,
    brand         VARCHAR(40),
    category_id   INT NOT NULL REFERENCES categories(category_id),
    supplier_id   INT REFERENCES suppliers(supplier_id),
    cost_price    NUMERIC(10,2) NOT NULL CHECK (cost_price >= 0),
    price         NUMERIC(10,2) NOT NULL CHECK (price >= 0),
    stock_qty     INT NOT NULL DEFAULT 0 CHECK (stock_qty >= 0),
    reorder_level INT NOT NULL DEFAULT 10 CHECK (reorder_level >= 0),   -- FIX 7
    is_active     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (price >= cost_price)
);

CREATE TABLE customers (
    customer_id    INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_name  VARCHAR(60) NOT NULL,
    phone          VARCHAR(15) UNIQUE,
    email          VARCHAR(80) UNIQUE,
    city           VARCHAR(40),
    loyalty_points INT NOT NULL DEFAULT 0 CHECK (loyalty_points >= 0),
    joined_on      DATE NOT NULL DEFAULT CURRENT_DATE
);

CREATE TABLE employees (
    emp_id    INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    emp_name  VARCHAR(60) NOT NULL,
    role      VARCHAR(30) NOT NULL CHECK (role IN ('Manager','Cashier','Storekeeper')),
    phone     VARCHAR(15),
    salary    NUMERIC(10,2) CHECK (salary > 0),
    hired_on  DATE NOT NULL DEFAULT CURRENT_DATE
);

CREATE TABLE orders (
    order_id         INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id      INT REFERENCES customers(customer_id),   -- NULL = walk-in
    emp_id           INT NOT NULL REFERENCES employees(emp_id),
    order_date       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    discount_percent NUMERIC(5,2) NOT NULL DEFAULT 0
                     CHECK (discount_percent BETWEEN 0 AND 50),            -- FIX 11 (business rule: max 50 %)
    status           VARCHAR(15) NOT NULL DEFAULT 'Completed'
                     CHECK (status IN ('Pending','Completed','Cancelled')),
    request_key      VARCHAR(64) UNIQUE                                    -- FIX 10 (idempotency key, NULL for old/manual orders)
                     CHECK (request_key ~ '^[A-Za-z0-9_-]{8,64}$')
);

CREATE TABLE order_items (
    item_id    INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id   INT NOT NULL REFERENCES orders(order_id) ON DELETE CASCADE,
    product_id INT NOT NULL REFERENCES products(product_id),
    quantity   INT NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(10,2) NOT NULL,
    line_total NUMERIC(12,2) GENERATED ALWAYS AS (quantity * unit_price) STORED
);

CREATE TABLE payments (
    payment_id INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id   INT NOT NULL REFERENCES orders(order_id) ON DELETE CASCADE,
    amount     NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    method     VARCHAR(10) NOT NULL CHECK (method IN ('Cash','Card','UPI')),
    paid_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_payments_order UNIQUE (order_id)      -- FIX 9: one full payment per order
);

CREATE TABLE purchase_orders (
    po_id       INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    supplier_id INT NOT NULL REFERENCES suppliers(supplier_id),
    po_date     DATE NOT NULL DEFAULT CURRENT_DATE,
    status      VARCHAR(15) NOT NULL DEFAULT 'Ordered'               -- FIX 2 (was 'Received')
                CHECK (status IN ('Ordered','Received','Cancelled'))
);

CREATE TABLE purchase_order_items (
    po_item_id INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    po_id      INT NOT NULL REFERENCES purchase_orders(po_id) ON DELETE CASCADE,
    product_id INT NOT NULL REFERENCES products(product_id),
    quantity   INT NOT NULL CHECK (quantity > 0),
    unit_cost  NUMERIC(10,2) NOT NULL CHECK (unit_cost >= 0)
);

CREATE TABLE stock_log (
    log_id      INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id  INT NOT NULL REFERENCES products(product_id),
    change_qty  INT NOT NULL,                 -- negative = sold, positive = restocked
    reason      VARCHAR(30) NOT NULL,         -- Sale / Sale Cancelled / Restock
    logged_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------------------------------------------------------------------
-- 2. INDEXES
-- ---------------------------------------------------------------------
CREATE INDEX idx_products_category  ON products (category_id);
CREATE INDEX idx_products_name      ON products (product_name);
CREATE INDEX idx_orders_customer    ON orders (customer_id);
CREATE INDEX idx_orders_date        ON orders (order_date);
CREATE INDEX idx_items_order        ON order_items (order_id);
CREATE INDEX idx_items_product      ON order_items (product_id);
CREATE INDEX idx_low_stock          ON products (stock_qty) WHERE stock_qty <= 10;  -- partial index
-- FIX 7: indexes on foreign keys that were not indexed
CREATE INDEX idx_products_supplier  ON products (supplier_id);
CREATE INDEX idx_payments_order     ON payments (order_id);
CREATE INDEX idx_po_supplier        ON purchase_orders (supplier_id);
CREATE INDEX idx_po_items_po        ON purchase_order_items (po_id);
CREATE INDEX idx_stock_log_product  ON stock_log (product_id, logged_at);

-- ---------------------------------------------------------------------
-- 3. FUNCTIONS AND TRIGGERS
-- ---------------------------------------------------------------------

-- 3a. Total payable amount of an order (after discount)   [unchanged]
CREATE OR REPLACE FUNCTION get_order_total(p_order_id INT)
RETURNS NUMERIC AS $$
DECLARE
    v_sum  NUMERIC;
    v_disc NUMERIC;
BEGIN
    SELECT COALESCE(SUM(line_total), 0) INTO v_sum
    FROM order_items WHERE order_id = p_order_id;

    SELECT discount_percent INTO v_disc FROM orders WHERE order_id = p_order_id;

    RETURN ROUND(v_sum * (1 - COALESCE(v_disc, 0) / 100), 2);
END;
$$ LANGUAGE plpgsql;

-- 3b. Before an item is sold: check stock, fill price, reduce stock, log it.
--     THIS is the only place where stock is deducted (the backend must not).
--     FIX 4: rejects inactive products and cancelled orders; the log uses the
--            order's date.
CREATE OR REPLACE FUNCTION trg_before_order_item()
RETURNS TRIGGER AS $$
DECLARE
    v_price  NUMERIC;
    v_stock  INT;
    v_active BOOLEAN;
    v_status VARCHAR;
    v_date   TIMESTAMP;
BEGIN
    SELECT status, order_date INTO v_status, v_date FROM orders WHERE order_id = NEW.order_id;
    IF v_status = 'Cancelled' THEN
        RAISE EXCEPTION 'Order % is cancelled; items cannot be added', NEW.order_id;
    END IF;

    SELECT price, stock_qty, is_active INTO v_price, v_stock, v_active
    FROM products WHERE product_id = NEW.product_id FOR UPDATE;

    IF NOT v_active THEN
        RAISE EXCEPTION 'Product % is inactive and cannot be sold', NEW.product_id;
    END IF;

    IF v_stock < NEW.quantity THEN
        RAISE EXCEPTION 'Insufficient stock for product %: available %, requested %',
                        NEW.product_id, v_stock, NEW.quantity;
    END IF;

    IF NEW.unit_price IS NULL THEN
        NEW.unit_price := v_price;          -- default to current selling price
    END IF;

    UPDATE products SET stock_qty = stock_qty - NEW.quantity
    WHERE product_id = NEW.product_id;

    INSERT INTO stock_log (product_id, change_qty, reason, logged_at)
    VALUES (NEW.product_id, -NEW.quantity, 'Sale', COALESCE(v_date, CURRENT_TIMESTAMP));

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER before_order_item_insert
BEFORE INSERT ON order_items
FOR EACH ROW EXECUTE FUNCTION trg_before_order_item();

-- 3b-2. FIX 5: order items cannot be edited or deleted (stock would go out of sync).
CREATE OR REPLACE FUNCTION trg_order_item_immutable()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Order items cannot be changed or deleted; cancel the order instead';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER order_item_no_change
BEFORE UPDATE OR DELETE ON order_items
FOR EACH ROW EXECUTE FUNCTION trg_order_item_immutable();

-- 3c. FIX 3: cancelling an order restores stock and reverses loyalty points.
--     A cancelled order can never be re-opened.
CREATE OR REPLACE FUNCTION trg_order_status_change()
RETURNS TRIGGER AS $$
DECLARE
    v_points INT;
BEGIN
    IF OLD.status = 'Cancelled' AND NEW.status <> 'Cancelled' THEN
        RAISE EXCEPTION 'Order % is cancelled and cannot be re-opened', OLD.order_id;
    END IF;

    IF OLD.status <> 'Cancelled' AND NEW.status = 'Cancelled' THEN
        UPDATE products p
        SET stock_qty = p.stock_qty + i.qty
        FROM (SELECT product_id, SUM(quantity) AS qty
              FROM order_items WHERE order_id = OLD.order_id GROUP BY product_id) i
        WHERE p.product_id = i.product_id;

        INSERT INTO stock_log (product_id, change_qty, reason)
        SELECT product_id, quantity, 'Sale Cancelled'
        FROM order_items WHERE order_id = OLD.order_id;

        IF OLD.customer_id IS NOT NULL THEN
            SELECT COALESCE(SUM(FLOOR(amount / 100))::INT, 0) INTO v_points
            FROM payments WHERE order_id = OLD.order_id;
            UPDATE customers
            SET loyalty_points = GREATEST(0, loyalty_points - v_points)
            WHERE customer_id = OLD.customer_id;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER order_status_update
AFTER UPDATE OF status ON orders
FOR EACH ROW EXECUTE FUNCTION trg_order_status_change();

-- 3d. FIX 2: purchase orders.
--     (i) Items may only be added / changed / deleted while the PO is 'Ordered'.
--         (When a whole PO is deleted the parent row is already gone, so the cascade is allowed.)
CREATE OR REPLACE FUNCTION trg_po_item_guard()
RETURNS TRIGGER AS $$
DECLARE
    v_status VARCHAR;
    v_po     INT;
BEGIN
    v_po := CASE WHEN TG_OP = 'DELETE' THEN OLD.po_id ELSE NEW.po_id END;
    SELECT status INTO v_status FROM purchase_orders WHERE po_id = v_po;
    IF v_status IS NOT NULL AND v_status <> 'Ordered' THEN
        RAISE EXCEPTION 'Purchase order % is %; items can no longer be changed', v_po, v_status;
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER po_item_guard
BEFORE INSERT OR UPDATE OR DELETE ON purchase_order_items
FOR EACH ROW EXECUTE FUNCTION trg_po_item_guard();

-- A received purchase order is history and cannot be deleted.
CREATE OR REPLACE FUNCTION trg_po_delete_guard()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.status = 'Received' THEN
        RAISE EXCEPTION 'Purchase order % was received and cannot be deleted', OLD.po_id;
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER po_delete_guard
BEFORE DELETE ON purchase_orders
FOR EACH ROW EXECUTE FUNCTION trg_po_delete_guard();

--     (ii) Stock increases ONLY on the status change Ordered -> Received.
--          Received / Cancelled are final, so goods can never be received twice.
CREATE OR REPLACE FUNCTION trg_po_status_change()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.status <> OLD.status AND OLD.status <> 'Ordered' THEN
        RAISE EXCEPTION 'Purchase order % is already % and its status cannot change',
                        OLD.po_id, OLD.status;
    END IF;

    IF OLD.status = 'Ordered' AND NEW.status = 'Received' THEN
        IF NOT EXISTS (SELECT 1 FROM purchase_order_items WHERE po_id = OLD.po_id) THEN
            RAISE EXCEPTION 'Purchase order % has no items to receive', OLD.po_id;
        END IF;

        UPDATE products p
        SET stock_qty = p.stock_qty + i.qty
        FROM (SELECT product_id, SUM(quantity) AS qty
              FROM purchase_order_items WHERE po_id = OLD.po_id GROUP BY product_id) i
        WHERE p.product_id = i.product_id;

        INSERT INTO stock_log (product_id, change_qty, reason)
        SELECT product_id, quantity, 'Restock'
        FROM purchase_order_items WHERE po_id = OLD.po_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER po_status_update
BEFORE UPDATE OF status ON purchase_orders
FOR EACH ROW EXECUTE FUNCTION trg_po_status_change();

-- 3d-2. FIX 9: a payment is accepted only for a non-cancelled order and only for the exact
--       order total. The order row is locked first, so two simultaneous payments for the same
--       order are processed one after the other (the second one then hits the UNIQUE rule).
CREATE OR REPLACE FUNCTION trg_before_payment()
RETURNS TRIGGER AS $$
DECLARE
    v_status VARCHAR;
    v_total  NUMERIC;
BEGIN
    SELECT status INTO v_status FROM orders WHERE order_id = NEW.order_id FOR UPDATE;
    IF v_status = 'Cancelled' THEN
        RAISE EXCEPTION 'Order % is cancelled and cannot be paid', NEW.order_id;
    END IF;
    v_total := get_order_total(NEW.order_id);
    IF NEW.amount <> v_total THEN
        RAISE EXCEPTION 'Payment amount % does not match the order total %', NEW.amount, v_total;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER before_payment_insert
BEFORE INSERT ON payments
FOR EACH ROW EXECUTE FUNCTION trg_before_payment();

-- Payments are permanent financial records.
CREATE OR REPLACE FUNCTION trg_payment_immutable()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Payments cannot be changed or deleted';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER payment_no_change
BEFORE UPDATE OR DELETE ON payments
FOR EACH ROW EXECUTE FUNCTION trg_payment_immutable();

-- FIX 12: the stock log is append-only. Only 'Opening Stock' rows may be deleted
-- (the application removes them together with a brand-new product that was never traded).
CREATE OR REPLACE FUNCTION trg_stock_log_guard()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' AND OLD.reason = 'Opening Stock' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'Stock log entries cannot be changed or deleted';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER stock_log_no_change
BEFORE UPDATE OR DELETE ON stock_log
FOR EACH ROW EXECUTE FUNCTION trg_stock_log_guard();

-- 3e. After payment: 1 loyalty point per Rs 100 spent  [unchanged]
--     THIS is the only place where points are awarded (the backend must not).
CREATE OR REPLACE FUNCTION trg_after_payment()
RETURNS TRIGGER AS $$
BEGIN
    UPDATE customers
    SET loyalty_points = loyalty_points + FLOOR(NEW.amount / 100)::INT
    WHERE customer_id = (SELECT customer_id FROM orders WHERE order_id = NEW.order_id);

    RAISE NOTICE 'Payment of % received for order %', NEW.amount, NEW.order_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER after_payment_insert
AFTER INSERT ON payments
FOR EACH ROW EXECUTE FUNCTION trg_after_payment();

-- ---------------------------------------------------------------------
-- 4. SAMPLE DATA
--    Masters: 5 categories, 6 suppliers, 14 products, 10 customers, 6 employees
--    Transactions: 14 orders, 12 payments, 10 purchase orders (stock_log is automatic)
-- ---------------------------------------------------------------------
INSERT INTO categories (category_name, description) VALUES
('Writing',      'Pens, pencils, markers'),
('Paper',        'Notebooks, registers, sheets'),
('Art & Craft',  'Colours, brushes, sketch books'),
('Office',       'Files, staplers, tapes'),
('Geometry',     'Compass, rulers, protractors');

INSERT INTO suppliers (supplier_name, contact_person, phone, email, city) VALUES
('Navneet Distributors',  'Rakesh Shah',   '9820011111', 'rakesh@navneet.example',    'Mumbai'),
('Camlin Supplies',       'Anita Rao',     '9820022222', 'anita@camlin.example',      'Mumbai'),
('Pilot Traders',         'Imran Qureshi', '9820033333', 'imran@pilot.example',       'Pune'),
('Office Mart',           'Sunita Verma',  '9820044444', 'sunita@officemart.example', 'Surat'),
('Classmate Wholesale',   'Ravi Nair',     '9820055555', 'ravi@classmate.example',    'Thane'),
('Kangaro Industries',    'Meera Joshi',   '9820066666', 'meera@kangaro.example',     'Nashik');

INSERT INTO products (product_name, brand, category_id, supplier_id, cost_price, price, stock_qty, reorder_level) VALUES
('Ball Pen Blue (Pack of 10)', 'Pilot',   1, 3,  60.00,  90.00, 100, 20),
('Gel Pen Black',              'Pilot',   1, 3,  12.00,  20.00, 150, 30),
('HB Pencil (Box of 10)',      'Camlin',  1, 2,  40.00,  60.00,  80, 15),
('Permanent Marker',           'Camlin',  1, 2,  25.00,  40.00,  60, 15),
('Notebook 200 Pages',         'Navneet', 2, 1,  35.00,  55.00, 200, 40),
('A4 Paper Ream (500 sheets)', 'JK',      2, 1, 220.00, 290.00,  40, 10),
('Sketch Book A3',             'Camlin',  3, 2,  70.00, 110.00,  30, 10),
('Water Colour Set (24)',      'Camlin',  3, 2, 120.00, 180.00,  25, 10),
('Stapler Medium',             'Kangaro', 4, 4,  55.00,  85.00,  35, 10),
('Transparent File Folder',    'Solo',    4, 4,   8.00,  15.00, 300, 50),
('Geometry Box',               'Camlin',  5, 2,  90.00, 140.00,  45, 10),
('Steel Ruler 30 cm',          'Kangaro', 5, 4,  14.00,  25.00,   8, 15),
('Highlighter Set (4 colours)','Camlin',  1, 2,  45.00,  70.00,  55, 15),
('Sticky Notes Pad',           'Solo',    4, 5,  18.00,  30.00,  12, 20);

INSERT INTO customers (customer_name, phone, email, city) VALUES
('Aarav Shah',        '9890000001', 'aarav@example.com',        'Mumbai'),
('Diya Mehta',        '9890000002', 'diya@example.com',         'Mumbai'),
('Rohan Patil',       '9890000003', 'rohan@example.com',        'Pune'),
('St. Xavier School', '9890000004', 'office@xaviers.example',   'Mumbai'),
('Meera Iyer',        '9890000005', 'meera.iyer@example.com',   'Thane'),
('Kabir Singh',       '9890000006', 'kabir@example.com',        'Navi Mumbai'),
('Sneha Kulkarni',    '9890000007', 'sneha@example.com',        'Pune'),
('Greenfield Academy','9890000008', 'admin@greenfield.example', 'Thane'),
('Vikram Joshi',      '9890000009', 'vikram@example.com',       'Mumbai'),
('Neha Gupta',        '9890000010', 'neha@example.com',         'Mumbai');

INSERT INTO employees (emp_name, role, phone, salary, hired_on) VALUES
('Suresh Kulkarni', 'Manager',     '9870000001', 45000, '2022-04-01'),
('Pooja Desai',     'Cashier',     '9870000002', 22000, '2023-06-15'),
('Mahesh Naik',     'Storekeeper', '9870000003', 20000, '2023-09-10'),
('Rahul Pawar',     'Cashier',     '9870000004', 21000, '2024-01-08'),
('Anjali Sharma',   'Cashier',     '9870000005', 23000, '2024-07-20'),
('Vijay More',      'Storekeeper', '9870000006', 19500, '2025-02-03');

-- Orders (customer NULL = walk-in). Dates are relative to the load time so the
-- dashboard always has recent data. Order 11 is cancelled below; order 14 stays Pending.
INSERT INTO orders (customer_id, emp_id, order_date, discount_percent, status) VALUES
(1,    2, CURRENT_TIMESTAMP - INTERVAL '75 days',  0, 'Completed'),   -- 1
(2,    2, CURRENT_TIMESTAMP - INTERVAL '62 days',  5, 'Completed'),   -- 2
(8,    1, CURRENT_TIMESTAMP - INTERVAL '48 days', 10, 'Completed'),   -- 3 bulk (school)
(NULL, 4, CURRENT_TIMESTAMP - INTERVAL '40 days',  0, 'Completed'),   -- 4 walk-in
(3,    4, CURRENT_TIMESTAMP - INTERVAL '33 days',  0, 'Completed'),   -- 5
(5,    5, CURRENT_TIMESTAMP - INTERVAL '25 days',  5, 'Completed'),   -- 6
(4,    1, CURRENT_TIMESTAMP - INTERVAL '18 days', 10, 'Completed'),   -- 7 bulk (school)
(6,    2, CURRENT_TIMESTAMP - INTERVAL '10 days',  0, 'Completed'),   -- 8
(NULL, 5, CURRENT_TIMESTAMP - INTERVAL '5 days',   0, 'Completed'),   -- 9 walk-in
(7,    4, CURRENT_TIMESTAMP - INTERVAL '3 days',   0, 'Completed'),   -- 10
(9,    4, CURRENT_TIMESTAMP - INTERVAL '2 days',   0, 'Pending'),     -- 11 (cancelled later)
(2,    5, CURRENT_TIMESTAMP - INTERVAL '1 day',    0, 'Completed'),   -- 12
(NULL, 2, CURRENT_TIMESTAMP,                       0, 'Completed'),   -- 13 today, walk-in
(3,    4, CURRENT_TIMESTAMP,                       0, 'Pending');     -- 14 today, not yet paid

-- Order items: unit_price NULL -> trigger fills the current price and reduces stock
INSERT INTO order_items (order_id, product_id, quantity, unit_price) VALUES
(1, 1, 2, NULL),  (1, 5, 3, NULL),  (1, 11, 1, NULL),
(2, 7, 1, NULL),  (2, 8, 1, NULL),  (2, 2, 5, NULL),
(3, 5, 40, NULL), (3, 6, 10, NULL), (3, 10, 100, NULL), (3, 3, 20, NULL),
(4, 2, 2, NULL),  (4, 12, 1, NULL),
(5, 9, 2, NULL),  (5, 6, 1, NULL),  (5, 3, 4, NULL),
(6, 1, 3, NULL),  (6, 2, 10, NULL), (6, 5, 5, NULL),
(7, 5, 50, NULL), (7, 10, 120, NULL), (7, 2, 40, NULL), (7, 3, 30, NULL), (7, 11, 10, NULL),
(8, 7, 2, NULL),  (8, 8, 2, NULL),  (8, 13, 4, NULL),
(9, 10, 10, NULL),(9, 2, 3, NULL),  (9, 12, 2, NULL),
(10, 6, 2, NULL), (10, 9, 1, NULL), (10, 11, 2, NULL),
(11, 1, 5, NULL), (11, 5, 5, NULL),
(12, 8, 1, NULL), (12, 13, 2, NULL),(12, 1, 1, NULL),
(13, 2, 4, NULL), (13, 5, 2, NULL), (13, 12, 3, NULL), (13, 3, 10, NULL),
(14, 10, 5, NULL),(14, 3, 2, NULL);

-- Cancel order 11 (trigger restores its stock)
UPDATE orders SET status = 'Cancelled' WHERE order_id = 11;

-- Payments for every Completed order (amount computed with get_order_total;
-- the trigger awards the loyalty points)
INSERT INTO payments (order_id, amount, method, paid_at)
SELECT order_id, get_order_total(order_id),
       (ARRAY['Cash','Card','UPI'])[1 + order_id % 3], order_date
FROM orders
WHERE status = 'Completed'
ORDER BY order_id;

-- Purchase orders: created as 'Ordered', items added, then received (trigger adds stock)
INSERT INTO purchase_orders (supplier_id, po_date) VALUES
(4, CURRENT_DATE - 60), (1, CURRENT_DATE - 45), (2, CURRENT_DATE - 40),
(3, CURRENT_DATE - 30), (2, CURRENT_DATE - 22), (4, CURRENT_DATE - 15),
(2, CURRENT_DATE - 9),  (4, CURRENT_DATE - 4),  (1, CURRENT_DATE - 2),
(3, CURRENT_DATE - 1);

INSERT INTO purchase_order_items (po_id, product_id, quantity, unit_cost) VALUES
(1, 9, 20, 55.00),  (1, 10, 50, 8.00),
(2, 5, 100, 35.00), (2, 6, 20, 220.00),
(3, 7, 15, 70.00),
(4, 1, 40, 60.00),  (4, 2, 60, 12.00),
(5, 8, 10, 120.00), (5, 4, 20, 25.00),
(6, 11, 20, 90.00),
(7, 13, 30, 45.00), (7, 7, 10, 70.00),
(8, 12, 40, 14.00), (8, 14, 40, 18.00),   -- still 'Ordered': shelves are low, goods not yet received
(9, 5, 80, 35.00),                         -- still 'Ordered'
(10, 1, 50, 60.00);                        -- will be cancelled

UPDATE purchase_orders SET status = 'Received'  WHERE po_id BETWEEN 1 AND 7;
UPDATE purchase_orders SET status = 'Cancelled' WHERE po_id = 10;

-- ---------------------------------------------------------------------
-- 5. VIEWS
-- ---------------------------------------------------------------------
CREATE VIEW order_summary AS
SELECT o.order_id,
       o.order_date::DATE                     AS order_date,
       COALESCE(c.customer_name, 'Walk-in')   AS customer,
       e.emp_name                             AS served_by,
       SUM(oi.line_total)                     AS gross_amount,
       o.discount_percent,
       get_order_total(o.order_id)            AS net_amount,
       o.status
FROM orders o
JOIN employees e        ON e.emp_id = o.emp_id
LEFT JOIN customers c   ON c.customer_id = o.customer_id
JOIN order_items oi     ON oi.order_id = o.order_id
GROUP BY o.order_id, c.customer_name, e.emp_name;

CREATE VIEW low_stock_products AS
SELECT p.product_id, p.product_name, c.category_name, p.stock_qty, p.reorder_level,
       s.supplier_name, s.phone AS supplier_phone
FROM products p
JOIN categories c      ON c.category_id = p.category_id
LEFT JOIN suppliers s  ON s.supplier_id = p.supplier_id
WHERE p.stock_qty <= p.reorder_level AND p.is_active;

-- FIX 6: only Completed orders count as sales
CREATE VIEW top_selling_products AS
SELECT p.product_id, p.product_name,
       SUM(oi.quantity)   AS units_sold,
       SUM(oi.line_total) AS revenue
FROM order_items oi
JOIN orders o   ON o.order_id = oi.order_id AND o.status = 'Completed'
JOIN products p ON p.product_id = oi.product_id
GROUP BY p.product_id, p.product_name
ORDER BY units_sold DESC;

CREATE VIEW monthly_sales AS
SELECT DATE_TRUNC('month', order_date)::DATE AS month,
       COUNT(*)                              AS total_orders,
       SUM(get_order_total(order_id))        AS total_sales
FROM orders
WHERE status = 'Completed'
GROUP BY DATE_TRUNC('month', order_date)
ORDER BY month;

-- FIX 6: only Completed orders count towards spending
CREATE VIEW customer_spending AS
SELECT c.customer_id, c.customer_name, c.loyalty_points,
       COUNT(o.order_id)                          AS orders_placed,
       COALESCE(SUM(get_order_total(o.order_id)), 0) AS total_spent
FROM customers c
LEFT JOIN orders o ON o.customer_id = c.customer_id AND o.status = 'Completed'
GROUP BY c.customer_id, c.customer_name, c.loyalty_points;

COMMIT;

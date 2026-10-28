-- =====================================================================
-- MIGRATION 001 - INTEGRITY HARDENING  (version 1 -> version 2 database)
--
-- Use this ONLY if you already loaded the version 1 script and want to KEEP your data.
-- (A fresh start? Run database/stationary_shop.sql instead - it already contains all of this.)
--
-- What it does (all inside ONE transaction; any problem = nothing is changed):
--   1. Discount rule 0-50 percent           (refuses to continue if an old order is above 50)
--   2. orders.request_key  (idempotency key, UNIQUE, format-checked)
--   3. payments: one payment per order      (refuses to continue if duplicates exist)
--   4. Payment guard, payment/stock_log permanence, purchase-order item/delete guards
--
-- It is safe to run twice. Back up first:  pg_dump -U postgres stationary_shop > backup.sql
-- Run:  psql -U postgres -d stationary_shop -f database/migrations/001_integrity_hardening.sql
-- =====================================================================
BEGIN;

DO $$
BEGIN
    IF current_database() NOT LIKE 'stationary\_shop%' THEN
        RAISE EXCEPTION 'Refusing to run: connected to database "%" (expected a database starting with stationary_shop)', current_database();
    END IF;
    IF EXISTS (SELECT 1 FROM orders WHERE discount_percent > 50) THEN
        RAISE EXCEPTION 'Cannot apply the 0-50 percent discount rule: some orders already have a discount above 50. Review them first: SELECT order_id, discount_percent FROM orders WHERE discount_percent > 50;';
    END IF;
    IF EXISTS (SELECT 1 FROM payments GROUP BY order_id HAVING COUNT(*) > 1) THEN
        RAISE EXCEPTION 'Cannot enforce one payment per order: some orders have several payments. Review them first: SELECT order_id, COUNT(*) FROM payments GROUP BY order_id HAVING COUNT(*) > 1;';
    END IF;
END $$;

-- 1. discount rule
ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_discount_percent_check;
ALTER TABLE orders ADD CONSTRAINT orders_discount_percent_check CHECK (discount_percent BETWEEN 0 AND 50);

-- 2. idempotency key
ALTER TABLE orders ADD COLUMN IF NOT EXISTS request_key VARCHAR(64);
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'orders_request_key_key') THEN
        ALTER TABLE orders ADD CONSTRAINT orders_request_key_key UNIQUE (request_key);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'orders_request_key_check') THEN
        ALTER TABLE orders ADD CONSTRAINT orders_request_key_check CHECK (request_key ~ '^[A-Za-z0-9_-]{8,64}$');
    END IF;
END $$;

-- 3. one payment per order
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_payments_order') THEN
        ALTER TABLE payments ADD CONSTRAINT uq_payments_order UNIQUE (order_id);
    END IF;
END $$;

-- 4. guards (identical to stationary_shop.sql)
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

DROP TRIGGER IF EXISTS po_item_guard ON purchase_order_items;
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

DROP TRIGGER IF EXISTS po_delete_guard ON purchase_orders;
CREATE TRIGGER po_delete_guard
BEFORE DELETE ON purchase_orders
FOR EACH ROW EXECUTE FUNCTION trg_po_delete_guard();


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

DROP TRIGGER IF EXISTS before_payment_insert ON payments;
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

DROP TRIGGER IF EXISTS payment_no_change ON payments;
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

DROP TRIGGER IF EXISTS stock_log_no_change ON stock_log;
CREATE TRIGGER stock_log_no_change
BEFORE UPDATE OR DELETE ON stock_log
FOR EACH ROW EXECUTE FUNCTION trg_stock_log_guard();


COMMIT;

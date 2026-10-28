package com.stationaryshop.service;

import com.stationaryshop.dto.OrderRequest;
import com.stationaryshop.exception.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sales.
 *
 * A sale = one order row + order_item rows + one payment row, saved in ONE transaction.
 * The database triggers deduct the stock (order_items), check the payment (amount, cancelled order),
 * and award the loyalty points (payments); this class must not do any of that itself.
 *
 * PAYMENT-STATE RULES
 *   Pending   --pay-->     Completed   (exactly one payment, amount = order total, points awarded once)
 *   Pending   --cancel-->  Cancelled   (stock returned)
 *   Completed --cancel-->  Cancelled   (stock returned, loyalty points reversed)
 *   Completed / Cancelled orders cannot be paid; a Cancelled order can never be re-opened.
 *   pay() and cancel() lock the order row (SELECT ... FOR UPDATE) and re-check the status while holding
 *   the lock, so two simultaneous requests are handled one after the other; the UNIQUE rule on
 *   payments(order_id) and the payment trigger are the database's second line of defence.
 *
 * IDEMPOTENCY
 *   POST /api/orders may carry request_id. The key is stored in orders.request_key (UNIQUE), so a repeated
 *   or simultaneous request with the same key returns the SAME order (HTTP 200, duplicate = true) and never
 *   creates a second sale - even after a server restart.
 */
@Service
public class OrderService {
    private static final Set<String> METHODS = Set.of("Cash", "Card", "UPI");
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    /** Business rule: a discount of 0 to 50 percent (also enforced by the database CHECK and by the browser). */
    public static final BigDecimal MAX_DISCOUNT = new BigDecimal("50");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public OrderService(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    // ------------------------------------------------------------------ list
    public List<Map<String, Object>> list(LocalDate from, LocalDate to, String status) {
        Util.checkRange(from, to);
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
            "SELECT s.order_id, s.order_date::text AS order_date, s.customer, s.served_by, s.gross_amount, " +
            "       s.discount_percent, s.net_amount, s.status, " +
            "       (SELECT p.method FROM payments p WHERE p.order_id = s.order_id ORDER BY p.payment_id LIMIT 1) AS payment_method " +
            "FROM order_summary s WHERE TRUE ");
        if (from != null) { sql.append("AND s.order_date >= ? "); args.add(Date.valueOf(from)); }
        if (to != null)   { sql.append("AND s.order_date <= ? "); args.add(Date.valueOf(to)); }
        if (Util.has(status)) { sql.append("AND s.status = ? "); args.add(status); }
        sql.append("ORDER BY s.order_id DESC");
        return jdbc.queryForList(sql.toString(), Util.arr(args));
    }

    // ------------------------------------------------------------------ create (idempotent)
    public Map<String, Object> create(OrderRequest r) {
        String key = Util.blank(r.requestId());
        if (key != null && !KEY.matcher(key).matches())
            throw ApiException.badRequest("request_id must be 8-64 characters: letters, digits, _ or -");

        if (key != null) {                                   // 1. a retry of a finished sale
            Integer existing = findByKey(key);
            if (existing != null) return result(existing, true);
        }
        try {
            return result(placeOrder(r, key), false);        // 2. a new sale
        } catch (DuplicateKeyException e) {
            // 3. a simultaneous request with the same key won the race (PostgreSQL waited for it to commit,
            //    then rejected ours with a unique violation): return the winner's order.
            Integer existing = key == null ? null : findByKey(key);
            if (existing != null) return result(existing, true);
            throw e;
        }
    }

    private Map<String, Object> result(Integer orderId, boolean duplicate) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("order_id", orderId);
        out.put("duplicate", duplicate);
        return out;
    }

    private Integer findByKey(String key) {
        List<Integer> ids = jdbc.queryForList("SELECT order_id FROM orders WHERE request_key = ?", Integer.class, key);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private Integer placeOrder(OrderRequest r, String key) {
        return tx.execute(status -> {
            BigDecimal discount = r.discountPercent() == null ? BigDecimal.ZERO : r.discountPercent();
            if (discount.compareTo(BigDecimal.ZERO) < 0 || discount.compareTo(MAX_DISCOUNT) > 0)
                throw ApiException.badRequest("Discount must be between 0 and 50 percent");
            discount = discount.setScale(2, RoundingMode.HALF_UP);

            if (!METHODS.contains(r.paymentMethod()))
                throw ApiException.badRequest("Payment method must be Cash, Card or UPI");

            Integer emp = jdbc.queryForObject("SELECT COUNT(*) FROM employees WHERE emp_id = ?", Integer.class, r.empId());
            if (emp == null || emp == 0) throw ApiException.badRequest("Selected employee does not exist");
            if (r.customerId() != null) {
                Integer cust = jdbc.queryForObject("SELECT COUNT(*) FROM customers WHERE customer_id = ?", Integer.class, r.customerId());
                if (cust == null || cust == 0) throw ApiException.badRequest("Selected customer does not exist");
            }

            // Merge repeated products into one line. A TreeMap sorts by product id, so every sale locks product
            // rows in the same order: two sales can never wait for each other (no deadlock).
            Map<Integer, Integer> lines = new TreeMap<>();
            for (OrderRequest.Item it : r.items()) lines.merge(it.productId(), it.quantity(), Integer::sum);
            for (Map.Entry<Integer, Integer> line : lines.entrySet()) {
                if (line.getValue() > 100000) throw ApiException.badRequest("Quantity cannot be more than 100000");
                List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT product_name, stock_qty, is_active FROM products WHERE product_id = ?", line.getKey());
                if (rows.isEmpty()) throw ApiException.badRequest("Product " + line.getKey() + " does not exist");
                Map<String, Object> p = rows.get(0);
                if (!(Boolean) p.get("is_active"))
                    throw ApiException.conflict("'" + p.get("product_name") + "' is inactive and cannot be sold");
                int stock = ((Number) p.get("stock_qty")).intValue();
                if (line.getValue() > stock)   // friendly early message; the trigger re-checks under a row lock
                    throw ApiException.conflict("Insufficient stock for '" + p.get("product_name") + "': available " +
                            stock + ", requested " + line.getValue());
            }

            Integer orderId = jdbc.queryForObject(
                "INSERT INTO orders (customer_id, emp_id, discount_percent, status, request_key) " +
                "VALUES (?, ?, ?, 'Completed', ?) RETURNING order_id",
                Integer.class, r.customerId(), r.empId(), discount, key);

            // unit_price is NULL on purpose: the trigger fills the current catalogue price and deducts the stock
            for (Map.Entry<Integer, Integer> line : lines.entrySet()) {
                jdbc.update("INSERT INTO order_items (order_id, product_id, quantity, unit_price) VALUES (?, ?, ?, NULL)",
                        orderId, line.getKey(), line.getValue());
            }

            // The persisted amount always comes from the database function, never from the browser.
            BigDecimal total = jdbc.queryForObject("SELECT get_order_total(?)", BigDecimal.class, orderId);
            if (total == null || total.compareTo(BigDecimal.ZERO) <= 0)
                throw ApiException.badRequest("The order total must be greater than zero");

            // The payment trigger checks the amount and awards the loyalty points.
            jdbc.update("INSERT INTO payments (order_id, amount, method) VALUES (?, ?, ?)", orderId, total, r.paymentMethod());
            return orderId;
        });
    }

    // ------------------------------------------------------------------ pay a Pending order
    public Map<String, Object> pay(Integer orderId, String method) {
        if (method == null || !METHODS.contains(method))
            throw ApiException.badRequest("Payment method must be Cash, Card or UPI");
        tx.executeWithoutResult(st -> {
            String status = lockAndGetStatus(orderId);            // row lock held until commit / rollback
            if ("Completed".equals(status)) throw ApiException.conflict("Order " + orderId + " has already been paid");
            if ("Cancelled".equals(status)) throw ApiException.conflict("Order " + orderId + " is cancelled and cannot be paid");
            BigDecimal total = jdbc.queryForObject("SELECT get_order_total(?)", BigDecimal.class, orderId);
            if (total == null || total.compareTo(BigDecimal.ZERO) <= 0)
                throw ApiException.badRequest("The order total must be greater than zero");
            // payment insert (+ loyalty points by trigger) and the status change succeed or fail together
            jdbc.update("INSERT INTO payments (order_id, amount, method) VALUES (?, ?, ?)", orderId, total, method);
            jdbc.update("UPDATE orders SET status = 'Completed' WHERE order_id = ?", orderId);
        });
        return Map.of("order_id", orderId, "status", "Completed");
    }

    // ------------------------------------------------------------------ cancel
    public Map<String, Object> cancel(Integer orderId) {
        tx.executeWithoutResult(st -> {
            String status = lockAndGetStatus(orderId);
            if ("Cancelled".equals(status)) throw ApiException.conflict("Order " + orderId + " is already cancelled");
            // the trigger restores the stock and reverses the loyalty points
            jdbc.update("UPDATE orders SET status = 'Cancelled' WHERE order_id = ?", orderId);
        });
        return Map.of("order_id", orderId, "status", "Cancelled");
    }

    /** Locks the order row for the rest of the transaction and returns its current status. */
    private String lockAndGetStatus(Integer orderId) {
        List<String> s = jdbc.queryForList("SELECT status FROM orders WHERE order_id = ? FOR UPDATE", String.class, orderId);
        if (s.isEmpty()) throw ApiException.notFound("Order " + orderId);
        return s.get(0);
    }

    // ------------------------------------------------------------------ receipt
    public Map<String, Object> receipt(Integer orderId) {
        List<Map<String, Object>> head = jdbc.queryForList(
            "SELECT o.order_id, to_char(o.order_date, 'YYYY-MM-DD HH24:MI') AS order_date, " +
            "       COALESCE(c.customer_name, 'Walk-in') AS customer, e.emp_name AS served_by, " +
            "       o.discount_percent, o.status " +
            "FROM orders o JOIN employees e ON e.emp_id = o.emp_id " +
            "LEFT JOIN customers c ON c.customer_id = o.customer_id WHERE o.order_id = ?", orderId);
        if (head.isEmpty()) throw ApiException.notFound("Order " + orderId);

        List<Map<String, Object>> items = jdbc.queryForList(
            "SELECT p.product_name, i.quantity, i.unit_price, i.line_total " +
            "FROM order_items i JOIN products p ON p.product_id = i.product_id " +
            "WHERE i.order_id = ? ORDER BY i.item_id", orderId);

        BigDecimal subtotal = BigDecimal.ZERO;
        for (Map<String, Object> it : items) subtotal = subtotal.add((BigDecimal) it.get("line_total"));
        BigDecimal total = jdbc.queryForObject("SELECT get_order_total(?)", BigDecimal.class, orderId);
        List<String> method = jdbc.queryForList(
            "SELECT method FROM payments WHERE order_id = ? ORDER BY payment_id LIMIT 1", String.class, orderId);

        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("shop_name", "Stationary Shop");
        receipt.putAll(head.get(0));
        receipt.put("items", items);
        receipt.put("subtotal", subtotal);
        receipt.put("discount_amount", subtotal.subtract(total));
        receipt.put("total", total);
        receipt.put("payment_method", method.isEmpty() ? null : method.get(0));
        return receipt;
    }
}

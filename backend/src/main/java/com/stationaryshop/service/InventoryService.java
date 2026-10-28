package com.stationaryshop.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Inventory screens and the inventory report. Read-only: stock is changed only by triggers. */
@Service
public class InventoryService {
    private final JdbcTemplate jdbc;

    public InventoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * stock_status rule (same as the view low_stock_products: stock_qty <= reorder_level):
     * Inactive, Out of Stock, Low Stock, In Stock.
     * status filter: needs_restock (Low + Out of Stock) or an exact status text.
     */
    public List<Map<String, Object>> list(String q, Integer categoryId, Integer supplierId, String status) {
        String like = Util.like(q);
        List<Object> args = new ArrayList<>(List.of(like, like));
        StringBuilder sql = new StringBuilder(
            "SELECT * FROM (" +
            " SELECT p.product_id, p.product_name, p.brand, p.category_id, c.category_name, p.supplier_id, s.supplier_name," +
            "        p.stock_qty, p.reorder_level, p.price, p.cost_price, p.is_active," +
            "        CASE WHEN NOT p.is_active THEN 'Inactive'" +
            "             WHEN p.stock_qty = 0 THEN 'Out of Stock'" +
            "             WHEN p.stock_qty <= p.reorder_level THEN 'Low Stock'" +
            "             ELSE 'In Stock' END AS stock_status," +
            "        COALESCE((SELECT SUM(i.quantity) FROM purchase_order_items i" +
            "                  JOIN purchase_orders po ON po.po_id = i.po_id" +
            "                  WHERE i.product_id = p.product_id AND po.status = 'Ordered'), 0) AS on_order_qty" +
            " FROM products p JOIN categories c ON c.category_id = p.category_id" +
            " LEFT JOIN suppliers s ON s.supplier_id = p.supplier_id" +
            " WHERE (p.product_name ILIKE ? OR COALESCE(p.brand,'') ILIKE ?)");
        if (categoryId != null) { sql.append(" AND p.category_id = ?"); args.add(categoryId); }
        if (supplierId != null) { sql.append(" AND p.supplier_id = ?"); args.add(supplierId); }
        sql.append(") t WHERE TRUE");
        if ("needs_restock".equalsIgnoreCase(status)) {
            sql.append(" AND t.stock_status IN ('Low Stock', 'Out of Stock')");
        } else if (Util.has(status)) {
            sql.append(" AND t.stock_status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY (t.stock_qty - t.reorder_level), t.product_name");
        return jdbc.queryForList(sql.toString(), Util.arr(args));
    }

    /** Uses the existing view low_stock_products. */
    public List<Map<String, Object>> lowStock() {
        return jdbc.queryForList(
            "SELECT product_id, product_name, category_name, stock_qty, reorder_level, supplier_name, supplier_phone " +
            "FROM low_stock_products ORDER BY (stock_qty - reorder_level), product_name");
    }

    public List<Map<String, Object>> stockLog(Integer productId) {
        StringBuilder sql = new StringBuilder(
            "SELECT l.log_id, l.product_id, p.product_name, l.change_qty, l.reason, " +
            "       to_char(l.logged_at, 'YYYY-MM-DD HH24:MI') AS logged_at " +
            "FROM stock_log l JOIN products p ON p.product_id = l.product_id ");
        Object[] args = new Object[0];
        if (productId != null) {
            sql.append("WHERE l.product_id = ? ");
            args = new Object[]{productId};
        }
        sql.append("ORDER BY l.logged_at DESC, l.log_id DESC LIMIT 300");
        return jdbc.queryForList(sql.toString(), args);
    }
}

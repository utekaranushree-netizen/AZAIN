package com.stationaryshop.service;

import com.stationaryshop.dto.PurchaseOrderRequest;
import com.stationaryshop.exception.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Purchase orders. Creating a PO never changes stock. Stock rises only when the PO status changes
 * Ordered -> Received; the trigger po_status_update does it, exactly once.
 */
@Service
public class PurchaseOrderService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public PurchaseOrderService(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public List<Map<String, Object>> list(String status) {
        StringBuilder sql = new StringBuilder(
            "SELECT po.po_id, po.po_date::text AS po_date, po.supplier_id, s.supplier_name, po.status, " +
            "       COUNT(i.po_item_id) AS item_count, COALESCE(SUM(i.quantity * i.unit_cost), 0) AS total_cost " +
            "FROM purchase_orders po JOIN suppliers s ON s.supplier_id = po.supplier_id " +
            "LEFT JOIN purchase_order_items i ON i.po_id = po.po_id ");
        Object[] args = new Object[0];
        if (Util.has(status)) {
            sql.append("WHERE po.status = ? ");
            args = new Object[]{status};
        }
        sql.append("GROUP BY po.po_id, s.supplier_name ORDER BY po.po_id DESC");
        return jdbc.queryForList(sql.toString(), args);
    }

    public Map<String, Object> get(Integer id) {
        List<Map<String, Object>> head = jdbc.queryForList(
            "SELECT po.po_id, po.po_date::text AS po_date, po.supplier_id, s.supplier_name, po.status " +
            "FROM purchase_orders po JOIN suppliers s ON s.supplier_id = po.supplier_id WHERE po.po_id = ?", id);
        if (head.isEmpty()) throw ApiException.notFound("Purchase order " + id);
        List<Map<String, Object>> items = jdbc.queryForList(
            "SELECT i.po_item_id, i.product_id, p.product_name, i.quantity, i.unit_cost, i.quantity * i.unit_cost AS line_total " +
            "FROM purchase_order_items i JOIN products p ON p.product_id = i.product_id " +
            "WHERE i.po_id = ? ORDER BY i.po_item_id", id);
        Map<String, Object> out = new LinkedHashMap<>(head.get(0));
        out.put("items", items);
        return out;
    }

    public Map<String, Object> create(PurchaseOrderRequest r) {
        Integer id = tx.execute(st -> {
            Integer sup = jdbc.queryForObject("SELECT COUNT(*) FROM suppliers WHERE supplier_id = ?", Integer.class, r.supplierId());
            if (sup == null || sup == 0) throw ApiException.badRequest("Selected supplier does not exist");

            Integer poId = jdbc.queryForObject(
                "INSERT INTO purchase_orders (supplier_id, status) VALUES (?, 'Ordered') RETURNING po_id",
                Integer.class, r.supplierId());
            for (PurchaseOrderRequest.Item it : r.items()) {
                Integer p = jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE product_id = ?", Integer.class, it.productId());
                if (p == null || p == 0) throw ApiException.badRequest("Product " + it.productId() + " does not exist");
                jdbc.update("INSERT INTO purchase_order_items (po_id, product_id, quantity, unit_cost) VALUES (?, ?, ?, ?)",
                        poId, it.productId(), it.quantity(), it.unitCost());
            }
            return poId;
        });
        return Map.of("po_id", id, "status", "Ordered");
    }

    /** Receives the goods. The WHERE status = 'Ordered' guard plus the trigger make double receiving impossible. */
    public Map<String, Object> receive(Integer id) {
        int rows = jdbc.update("UPDATE purchase_orders SET status = 'Received' WHERE po_id = ? AND status = 'Ordered'", id);
        if (rows == 0) explainWhyNotOrdered(id, "received");
        return Map.of("po_id", id, "status", "Received");
    }

    public Map<String, Object> cancel(Integer id) {
        int rows = jdbc.update("UPDATE purchase_orders SET status = 'Cancelled' WHERE po_id = ? AND status = 'Ordered'", id);
        if (rows == 0) explainWhyNotOrdered(id, "cancelled");
        return Map.of("po_id", id, "status", "Cancelled");
    }

    private void explainWhyNotOrdered(Integer id, String action) {
        List<String> s = jdbc.queryForList("SELECT status FROM purchase_orders WHERE po_id = ?", String.class, id);
        if (s.isEmpty()) throw ApiException.notFound("Purchase order " + id);
        throw ApiException.conflict("Purchase order " + id + " is already " + s.get(0) + " and cannot be " + action);
    }
}

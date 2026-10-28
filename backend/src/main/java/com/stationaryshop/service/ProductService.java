package com.stationaryshop.service;

import com.stationaryshop.entity.Product;
import com.stationaryshop.exception.ApiException;
import com.stationaryshop.repository.ProductRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {
    private final ProductRepository repo;
    private final JdbcTemplate jdbc;

    public ProductService(ProductRepository repo, JdbcTemplate jdbc) {
        this.repo = repo;
        this.jdbc = jdbc;
    }

    /** Search by name/brand and filter by category, supplier and status (active / inactive). */
    public List<Map<String, Object>> search(String q, Integer categoryId, Integer supplierId, String status) {
        String like = Util.like(q);
        List<Object> args = new ArrayList<>(List.of(like, like));
        StringBuilder sql = new StringBuilder(
            "SELECT p.product_id, p.product_name, p.brand, p.category_id, c.category_name, " +
            "       p.supplier_id, s.supplier_name, p.cost_price, p.price, p.stock_qty, p.reorder_level, p.is_active " +
            "FROM products p " +
            "JOIN categories c ON c.category_id = p.category_id " +
            "LEFT JOIN suppliers s ON s.supplier_id = p.supplier_id " +
            "WHERE (p.product_name ILIKE ? OR COALESCE(p.brand,'') ILIKE ?) ");
        if (categoryId != null) { sql.append("AND p.category_id = ? "); args.add(categoryId); }
        if (supplierId != null) { sql.append("AND p.supplier_id = ? ");  args.add(supplierId); }
        if ("active".equalsIgnoreCase(status))   sql.append("AND p.is_active ");
        if ("inactive".equalsIgnoreCase(status)) sql.append("AND NOT p.is_active ");
        sql.append("ORDER BY p.product_id");
        return jdbc.queryForList(sql.toString(), Util.arr(args));
    }

    public Product get(Integer id) {
        return repo.findById(id).orElseThrow(() -> ApiException.notFound("Product"));
    }

    @Transactional
    public Product create(Product in) {
        in.productId = null;
        check(in);
        if (in.stockQty == null) in.stockQty = 0;
        if (in.isActive == null) in.isActive = true;
        Product saved = repo.saveAndFlush(in);
        // Opening stock is recorded in stock_log so that no stock exists without a history entry.
        if (saved.stockQty > 0) {
            jdbc.update("INSERT INTO stock_log (product_id, change_qty, reason) VALUES (?, ?, 'Opening Stock')",
                    saved.productId, saved.stockQty);
        }
        return saved;
    }

    @Transactional
    public Product update(Integer id, Product in) {
        Product p = get(id);
        check(in);
        p.productName = in.productName.trim();
        p.brand = Util.blank(in.brand);
        p.categoryId = in.categoryId;
        p.supplierId = in.supplierId;
        p.costPrice = in.costPrice;
        p.price = in.price;
        p.reorderLevel = in.reorderLevel;
        if (in.isActive != null) p.isActive = in.isActive;
        // stockQty is deliberately NOT copied: stock changes only via sales / purchase receipts.
        return repo.saveAndFlush(p);
    }

    @Transactional
    public void delete(Integer id) {
        Product p = get(id);
        Integer sold = jdbc.queryForObject("SELECT COUNT(*) FROM order_items WHERE product_id = ?", Integer.class, id);
        Integer bought = jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order_items WHERE product_id = ?", Integer.class, id);
        Integer moves = jdbc.queryForObject(
            "SELECT COUNT(*) FROM stock_log WHERE product_id = ? AND reason <> 'Opening Stock'", Integer.class, id);
        if (sold > 0 || bought > 0 || moves > 0)
            throw ApiException.conflict("Product '" + p.productName + "' has sales, purchase or stock history (" + sold +
                    " sale line(s), " + bought + " purchase line(s)) and cannot be deleted without destroying those records. " +
                    "Deactivate it instead: edit the product and untick 'Active'.");
        jdbc.update("DELETE FROM stock_log WHERE product_id = ? AND reason = 'Opening Stock'", id);
        repo.delete(p);
        repo.flush();
    }

    private void check(Product p) {
        if (p.price.compareTo(p.costPrice) < 0)
            throw ApiException.badRequest("Selling price (" + p.price + ") cannot be lower than cost price (" + p.costPrice + ")");
        Integer cat = jdbc.queryForObject("SELECT COUNT(*) FROM categories WHERE category_id = ?", Integer.class, p.categoryId);
        if (cat == null || cat == 0) throw ApiException.badRequest("Selected category does not exist");
        if (p.supplierId != null) {
            Integer sup = jdbc.queryForObject("SELECT COUNT(*) FROM suppliers WHERE supplier_id = ?", Integer.class, p.supplierId);
            if (sup == null || sup == 0) throw ApiException.badRequest("Selected supplier does not exist");
        }
    }
}

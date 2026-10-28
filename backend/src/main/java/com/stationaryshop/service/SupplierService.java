package com.stationaryshop.service;

import com.stationaryshop.entity.Supplier;
import com.stationaryshop.exception.ApiException;
import com.stationaryshop.repository.SupplierRepository;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SupplierService {
    private final SupplierRepository repo;
    private final JdbcTemplate jdbc;

    public SupplierService(SupplierRepository repo, JdbcTemplate jdbc) {
        this.repo = repo;
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> search(String q) {
        String like = Util.like(q);
        return jdbc.queryForList(
            "SELECT s.supplier_id, s.supplier_name, s.contact_person, s.phone, s.email, s.city, " +
            "       (SELECT COUNT(*) FROM products p WHERE p.supplier_id = s.supplier_id) AS product_count " +
            "FROM suppliers s " +
            "WHERE s.supplier_name ILIKE ? OR COALESCE(s.contact_person,'') ILIKE ? " +
            "   OR COALESCE(s.email,'') ILIKE ? OR COALESCE(s.city,'') ILIKE ? OR COALESCE(s.phone,'') ILIKE ? " +
            "ORDER BY s.supplier_id", like, like, like, like, like);
    }

    public List<Map<String, Object>> products(Integer id) {
        get(id);
        return jdbc.queryForList(
            "SELECT product_id, product_name, brand, price, stock_qty, is_active " +
            "FROM products WHERE supplier_id = ? ORDER BY product_name", id);
    }

    public Supplier get(Integer id) {
        return repo.findById(id).orElseThrow(() -> ApiException.notFound("Supplier"));
    }

    public Supplier create(Supplier in) {
        in.supplierId = null;
        clean(in);
        return repo.saveAndFlush(in);
    }

    public Supplier update(Integer id, Supplier in) {
        Supplier s = get(id);
        clean(in);
        s.supplierName = in.supplierName;
        s.contactPerson = in.contactPerson;
        s.phone = in.phone;
        s.email = in.email;
        s.city = in.city;
        return repo.saveAndFlush(s);
    }

    public void delete(Integer id) {
        Supplier s = get(id);
        Integer products = jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE supplier_id = ?", Integer.class, id);
        Integer pos = jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders WHERE supplier_id = ?", Integer.class, id);
        if ((products != null && products > 0) || (pos != null && pos > 0))
            throw ApiException.conflict("Supplier '" + s.supplierName + "' supplies " + products + " product(s) and has " + pos +
                    " purchase order(s), so it cannot be deleted. Reassign its products first; purchase-order history is kept.");
        repo.delete(s);
        repo.flush();
    }

    private void clean(Supplier s) {
        s.supplierName = s.supplierName.trim();
        s.contactPerson = Util.blank(s.contactPerson);
        s.phone = Util.blank(s.phone);
        s.email = Util.lower(Util.blank(s.email));
        s.city = Util.blank(s.city);
    }
}

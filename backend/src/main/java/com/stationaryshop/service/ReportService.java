package com.stationaryshop.service;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Dashboard figures and the sales reports. Everything comes from SQL; nothing is hard-coded. */
@Service
public class ReportService {
    private final JdbcTemplate jdbc;

    public ReportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> dashboard() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("total_products",   jdbc.queryForObject("SELECT COUNT(*) FROM products", Long.class));
        d.put("total_categories", jdbc.queryForObject("SELECT COUNT(*) FROM categories", Long.class));
        d.put("total_suppliers",  jdbc.queryForObject("SELECT COUNT(*) FROM suppliers", Long.class));
        d.put("total_customers",  jdbc.queryForObject("SELECT COUNT(*) FROM customers", Long.class));
        d.put("total_employees",  jdbc.queryForObject("SELECT COUNT(*) FROM employees", Long.class));
        d.put("completed_orders", jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE status = 'Completed'", Long.class));
        d.put("low_stock_count",  jdbc.queryForObject("SELECT COUNT(*) FROM low_stock_products", Long.class));
        d.put("today_sales", jdbc.queryForObject(
            "SELECT COALESCE(SUM(get_order_total(order_id)), 0) FROM orders " +
            "WHERE status = 'Completed' AND order_date::date = CURRENT_DATE", BigDecimal.class));
        d.put("recent_orders", jdbc.queryForList(
            "SELECT order_id, order_date::text AS order_date, customer, served_by, net_amount, status " +
            "FROM order_summary ORDER BY order_id DESC LIMIT 6"));
        d.put("low_stock", jdbc.queryForList(
            "SELECT product_id, product_name, category_name, stock_qty, reorder_level " +
            "FROM low_stock_products ORDER BY (stock_qty - reorder_level) LIMIT 6"));
        d.put("top_selling", jdbc.queryForList(
            "SELECT product_id, product_name, units_sold, revenue FROM top_selling_products LIMIT 5"));
        d.put("monthly_sales", jdbc.queryForList(
            "SELECT to_char(month, 'Mon YYYY') AS label, total_orders, total_sales FROM monthly_sales ORDER BY month"));
        return d;
    }

    /** Report 1: Sales and Revenue. Rows come from the view order_summary. */
    public Map<String, Object> sales(LocalDate from, LocalDate to) {
        Util.checkRange(from, to);
        List<Object> args = new ArrayList<>();
        String where = " WHERE TRUE";
        if (from != null) { where += " AND order_date >= ?"; args.add(Date.valueOf(from)); }
        if (to != null)   { where += " AND order_date <= ?"; args.add(Date.valueOf(to)); }
        Object[] a = Util.arr(args);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from == null ? null : from.toString());
        out.put("to", to == null ? null : to.toString());
        out.put("rows", jdbc.queryForList(
            "SELECT order_id, order_date::text AS order_date, customer, served_by AS employee, gross_amount, " +
            "       discount_percent, (gross_amount - net_amount) AS discount_amount, net_amount, status " +
            "FROM order_summary" + where + " ORDER BY order_id DESC", a));
        out.put("totals", jdbc.queryForMap(
            "SELECT COALESCE(SUM(net_amount) FILTER (WHERE status = 'Completed'), 0) AS total_sales, " +
            "       COUNT(*) FILTER (WHERE status = 'Completed') AS completed_orders, " +
            "       COUNT(*) AS total_orders " +
            "FROM order_summary" + where, a));
        out.put("monthly", jdbc.queryForList(
            "SELECT to_char(order_date, 'YYYY-MM') AS month, COUNT(*) AS total_orders, SUM(net_amount) AS total_sales " +
            "FROM order_summary" + where + " AND status = 'Completed' GROUP BY 1 ORDER BY 1", a));
        return out;
    }

    public List<Map<String, Object>> topSelling() {
        return jdbc.queryForList("SELECT product_id, product_name, units_sold, revenue FROM top_selling_products LIMIT 10");
    }

    public List<Map<String, Object>> customerSpending() {
        return jdbc.queryForList(
            "SELECT customer_id, customer_name, orders_placed, total_spent, loyalty_points " +
            "FROM customer_spending ORDER BY total_spent DESC, customer_name");
    }
}

package com.stationaryshop.service;

import com.stationaryshop.entity.Customer;
import com.stationaryshop.exception.ApiException;
import com.stationaryshop.repository.CustomerRepository;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CustomerService {
    private final CustomerRepository repo;
    private final JdbcTemplate jdbc;

    public CustomerService(CustomerRepository repo, JdbcTemplate jdbc) {
        this.repo = repo;
        this.jdbc = jdbc;
    }

    /** Uses the view customer_spending for order count and total spent. */
    public List<Map<String, Object>> search(String q) {
        String like = Util.like(q);
        return jdbc.queryForList(
            "SELECT c.customer_id, c.customer_name, c.phone, c.email, c.city, c.loyalty_points, c.joined_on, " +
            "       cs.orders_placed, cs.total_spent " +
            "FROM customers c JOIN customer_spending cs ON cs.customer_id = c.customer_id " +
            "WHERE c.customer_name ILIKE ? OR COALESCE(c.phone,'') ILIKE ? OR COALESCE(c.email,'') ILIKE ? " +
            "   OR COALESCE(c.city,'') ILIKE ? " +
            "ORDER BY c.customer_id", like, like, like, like);
    }

    public List<Map<String, Object>> orderHistory(Integer id) {
        get(id);
        return jdbc.queryForList(
            "SELECT o.order_id, o.order_date::text AS order_date, o.status, o.discount_percent, " +
            "       get_order_total(o.order_id) AS net_amount, " +
            "       (SELECT COUNT(*) FROM order_items i WHERE i.order_id = o.order_id) AS item_count " +
            "FROM orders o WHERE o.customer_id = ? ORDER BY o.order_id DESC", id);
    }

    public Customer get(Integer id) {
        return repo.findById(id).orElseThrow(() -> ApiException.notFound("Customer"));
    }

    public Customer create(Customer in) {
        in.customerId = null;
        clean(in);
        return repo.saveAndFlush(in);
    }

    public Customer update(Integer id, Customer in) {
        Customer c = get(id);
        clean(in);
        c.customerName = in.customerName;
        c.phone = in.phone;
        c.email = in.email;
        c.city = in.city;
        return repo.saveAndFlush(c);
    }

    public void delete(Integer id) {
        Customer c = get(id);
        Integer orders = jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE customer_id = ?", Integer.class, id);
        if (orders != null && orders > 0)
            throw ApiException.conflict("Customer '" + c.customerName + "' has " + orders +
                    " order(s). Customers with order history cannot be deleted so that past sales records stay correct.");
        repo.delete(c);
        repo.flush();
    }

    private void clean(Customer c) {
        c.customerName = c.customerName.trim();
        c.phone = Util.blank(c.phone);
        c.email = Util.lower(Util.blank(c.email));
        c.city = Util.blank(c.city);
    }
}

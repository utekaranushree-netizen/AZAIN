package com.stationaryshop.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stationaryshop.service.OrderService;
import com.stationaryshop.service.PurchaseOrderService;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base class for tests that need a REAL PostgreSQL database (triggers, locks and constraints cannot be tested
 * with an in-memory database).
 *
 * These tests run only when the environment variable TEST_DB_URL is set, e.g.
 *     TEST_DB_URL=jdbc:postgresql://localhost:5432/stationary_shop_test
 *     TEST_DB_USERNAME=postgres        TEST_DB_PASSWORD=your_password
 * WARNING: the database is RESET (database/stationary_shop.sql is executed) once when the tests start.
 * Its name must contain "stationary_shop". Never point it at a database with data you want to keep.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
public abstract class AbstractDbIntegrationTest {

    static {
        String url = System.getenv("TEST_DB_URL");
        if (url != null && !url.isBlank()) resetDatabase(url);
    }

    private static String user() { return System.getenv().getOrDefault("TEST_DB_USERNAME", "postgres"); }
    private static String password() { return System.getenv().getOrDefault("TEST_DB_PASSWORD", ""); }

    private static void resetDatabase(String url) {
        if (!url.contains("stationary_shop"))
            throw new IllegalStateException("TEST_DB_URL must name a database that contains 'stationary_shop' (for example stationary_shop_test). The tests RESET that database.");
        try (Connection c = DriverManager.getConnection(url, user(), password()); Statement st = c.createStatement()) {
            st.setEscapeProcessing(false);
            st.execute(Files.readString(Path.of("..", "database", "stationary_shop.sql")));
        } catch (Exception e) {
            throw new IllegalStateException("Could not reset the test database: " + e.getMessage(), e);
        }
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TEST_DB_URL"));
        registry.add("spring.datasource.username", AbstractDbIntegrationTest::user);
        registry.add("spring.datasource.password", AbstractDbIntegrationTest::password);
    }

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected OrderService orderService;
    @Autowired protected PurchaseOrderService purchaseOrderService;

    // ---------------------------------------------------------------- helpers
    protected int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    /** Creates a fresh product so that a test never depends on the stock left behind by another test. */
    protected int newProduct(int stock, String price) {
        return jdbc.queryForObject(
            "INSERT INTO products (product_name, category_id, cost_price, price, stock_qty, reorder_level) " +
            "VALUES (?, 1, 0, ?, ?, 1) RETURNING product_id", Integer.class,
            "Test product " + UUID.randomUUID(), new BigDecimal(price), stock);
    }

    protected int newCustomer() {
        String id = UUID.randomUUID().toString().replace("-", "");
        return jdbc.queryForObject("INSERT INTO customers (customer_name, email) VALUES (?, ?) RETURNING customer_id",
                Integer.class, "Test customer " + id.substring(0, 6), id + "@test.example");
    }

    protected int stock(int productId) { return count("SELECT stock_qty FROM products WHERE product_id = ?", productId); }
    protected int points(int customerId) { return count("SELECT loyalty_points FROM customers WHERE customer_id = ?", customerId); }
    protected String orderStatus(int orderId) { return jdbc.queryForObject("SELECT status FROM orders WHERE order_id = ?", String.class, orderId); }

    protected String newKey() { return "k" + UUID.randomUUID().toString().replace("-", ""); }   // 33 characters, valid format

    protected String orderJson(Integer customerId, String discount, String method, String key, int productId, int qty) {
        return """
            {"customer_id": %s, "emp_id": 2, "discount_percent": %s, "payment_method": "%s", "request_id": %s,
             "items": [{"product_id": %d, "quantity": %d}]}"""
            .formatted(customerId == null ? "null" : customerId.toString(), discount, method,
                       key == null ? "null" : "\"" + key + "\"", productId, qty);
    }

    /** A Pending (unpaid) order, created directly in the database. The trigger deducts the stock. */
    protected int pendingOrder(Integer customerId, int productId, int qty) {
        int id = jdbc.queryForObject("INSERT INTO orders (customer_id, emp_id, status) VALUES (?, 2, 'Pending') RETURNING order_id",
                Integer.class, customerId);
        jdbc.update("INSERT INTO order_items (order_id, product_id, quantity, unit_price) VALUES (?, ?, ?, NULL)", id, productId, qty);
        return id;
    }
}

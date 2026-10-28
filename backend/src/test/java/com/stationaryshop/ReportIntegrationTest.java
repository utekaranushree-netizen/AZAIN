package com.stationaryshop;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stationaryshop.support.AbstractDbIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Test M: dashboard and reports return values derived from the database. */
class ReportIntegrationTest extends AbstractDbIntegrationTest {

    @Test
    void dashboardCountsComeFromTheDatabase() throws Exception {
        mvc.perform(get("/api/dashboard")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total_products").value(count("SELECT COUNT(*) FROM products")))
                .andExpect(jsonPath("$.total_categories").value(count("SELECT COUNT(*) FROM categories")))
                .andExpect(jsonPath("$.total_suppliers").value(count("SELECT COUNT(*) FROM suppliers")))
                .andExpect(jsonPath("$.total_customers").value(count("SELECT COUNT(*) FROM customers")))
                .andExpect(jsonPath("$.total_employees").value(count("SELECT COUNT(*) FROM employees")))
                .andExpect(jsonPath("$.completed_orders").value(count("SELECT COUNT(*) FROM orders WHERE status = 'Completed'")))
                .andExpect(jsonPath("$.low_stock_count").value(count("SELECT COUNT(*) FROM low_stock_products")));
    }

    @Test
    void salesReportTotalsEqualTheDatabaseTotals() throws Exception {
        BigDecimal expected = jdbc.queryForObject(
                "SELECT COALESCE(SUM(net_amount) FILTER (WHERE status = 'Completed'), 0) FROM order_summary", BigDecimal.class);
        mvc.perform(get("/api/reports/sales")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.total_sales").value(expected.doubleValue()))
                .andExpect(jsonPath("$.totals.completed_orders").value(count("SELECT COUNT(*) FROM order_summary WHERE status = 'Completed'")))
                .andExpect(jsonPath("$.rows.length()").value(count("SELECT COUNT(*) FROM order_summary")));
    }

    @Test
    void salesReportDateFilterAndRangeValidation() throws Exception {
        String tomorrow = LocalDate.now().plusDays(1).toString();
        mvc.perform(get("/api/reports/sales?from=" + tomorrow)).andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(0)).andExpect(jsonPath("$.totals.completed_orders").value(0));
        mvc.perform(get("/api/reports/sales?from=2026-12-31&to=2026-01-01")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/reports/sales?from=not-a-date")).andExpect(status().isBadRequest());
    }

    @Test
    void inventoryReportFiltersByStatusAndCategory() throws Exception {
        mvc.perform(get("/api/reports/low-stock?status=Low Stock")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].stock_status", everyItem(is("Low Stock"))));
        mvc.perform(get("/api/reports/low-stock?category_id=1")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].category_id", everyItem(is(1))));
        mvc.perform(get("/api/inventory/low-stock")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(count("SELECT COUNT(*) FROM low_stock_products")));
        assertEquals(true, count("SELECT COUNT(*) FROM products") > 0);
    }

    @Test
    void topSellingAndCustomerSpendingAreDatabaseViews() throws Exception {
        mvc.perform(get("/api/reports/top-selling")).andExpect(status().isOk());
        mvc.perform(get("/api/reports/customer-spending")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(count("SELECT COUNT(*) FROM customers")));
    }
}

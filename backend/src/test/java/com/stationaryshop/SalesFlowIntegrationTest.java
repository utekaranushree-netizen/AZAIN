package com.stationaryshop;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stationaryshop.dto.OrderRequest;
import com.stationaryshop.exception.ApiException;
import com.stationaryshop.support.AbstractDbIntegrationTest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Tests E-J: sales, stock, payment, cancellation, idempotency and concurrency against PostgreSQL. */
class SalesFlowIntegrationTest extends AbstractDbIntegrationTest {

    private int placeOrderViaApi(Integer customer, String discount, String key, int product, int qty) throws Exception {
        String body = mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson(customer, discount, "Cash", key, product, qty)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("order_id").asInt();
    }

    @Test
    void validSaleSavesOrderPaymentAndDeductsStockExactlyOnce() throws Exception {
        int product = newProduct(10, "50.00");
        int customer = newCustomer();
        int orderId = placeOrderViaApi(customer, "10", newKey(), product, 3);       // 3 x 50 = 150, less 10 % = 135.00

        assertEquals(7, stock(product), "stock must fall by 3, once");
        assertEquals("Completed", orderStatus(orderId));
        assertEquals(1, count("SELECT COUNT(*) FROM payments WHERE order_id = ?", orderId));
        assertEquals(0, new BigDecimal("135.00").compareTo(
                jdbc.queryForObject("SELECT amount FROM payments WHERE order_id = ?", BigDecimal.class, orderId)));
        assertEquals(1, points(customer), "floor(135 / 100) = 1 loyalty point, awarded once");
        assertEquals(1, count("SELECT COUNT(*) FROM stock_log WHERE product_id = ? AND reason = 'Sale'", product));

        mvc.perform(get("/api/orders/" + orderId + "/receipt")).andExpect(status().isOk())
                .andExpect(jsonPath("$.shop_name").value("Stationary Shop"))
                .andExpect(jsonPath("$.subtotal").value(150.0)).andExpect(jsonPath("$.total").value(135.0))
                .andExpect(jsonPath("$.discount_amount").value(15.0)).andExpect(jsonPath("$.payment_method").value("Cash"));
    }

    @Test
    void theServerUsesCataloguePricesNotBrowserValues() throws Exception {
        int product = newProduct(5, "40.00");
        // even if a client tries to smuggle in a price, unknown fields are ignored and the catalogue price is used
        String body = """
            {"emp_id": 2, "payment_method": "Cash", "items": [{"product_id": %d, "quantity": 2, "unit_price": 1}], "total": 1}""".formatted(product);
        String res = mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        int id = json.readTree(res).get("order_id").asInt();
        assertEquals(0, new BigDecimal("80.00").compareTo(
                jdbc.queryForObject("SELECT amount FROM payments WHERE order_id = ?", BigDecimal.class, id)));
    }

    @Test
    void insufficientStockIsRejectedAndNothingIsSaved() throws Exception {
        int product = newProduct(2, "10.00");
        int orders = count("SELECT COUNT(*) FROM orders");
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(orderJson(null, "0", "Cash", newKey(), product, 5)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message", containsString("Insufficient stock")));
        assertEquals(2, stock(product));
        assertEquals(orders, count("SELECT COUNT(*) FROM orders"));
    }

    @Test
    void invalidRequestsAreRejectedWithoutChangingTheDatabase() throws Exception {
        int product = newProduct(5, "10.00");
        int orders = count("SELECT COUNT(*) FROM orders");
        for (String discount : new String[]{"50.01", "60", "100", "-1"}) {
            mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(orderJson(null, discount, "Cash", null, product, 1)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(orderJson(null, "0", "Cheque", null, product, 1))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(orderJson(null, "0", "Cash", null, product, 0))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(orderJson(null, "0", "Cash", "bad key", product, 1))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(orderJson(null, "0", "Cash", null, 999999, 1))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("{\"emp_id\": 2, \"payment_method\": \"Cash\", \"items\": []}")).andExpect(status().isBadRequest());
        assertEquals(5, stock(product));
        assertEquals(orders, count("SELECT COUNT(*) FROM orders"));
    }

    @Test
    void sameIdempotencyKeyReturnsTheSameOrder() throws Exception {
        int product = newProduct(10, "20.00");
        String key = newKey();
        String body = orderJson(null, "0", "UPI", key, product, 2);
        String first = mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.duplicate").value(false)).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true)).andReturn().getResponse().getContentAsString();
        assertEquals(json.readTree(first).get("order_id").asInt(), json.readTree(second).get("order_id").asInt());
        assertEquals(8, stock(product), "the repeated request must not deduct stock again");
        assertEquals(1, count("SELECT COUNT(*) FROM orders WHERE request_key = ?", key));
    }

    @Test
    void differentKeysCreateDifferentSales() throws Exception {
        int product = newProduct(10, "20.00");
        int a = placeOrderViaApi(null, "0", newKey(), product, 1);
        int b = placeOrderViaApi(null, "0", newKey(), product, 1);
        assertTrue(a != b);
        assertEquals(8, stock(product));
    }

    @Test
    void simultaneousRequestsWithTheSameKeyCreateOneOrder() throws Exception {
        int product = newProduct(10, "20.00");
        String key = newKey();
        OrderRequest request = new OrderRequest(null, 2, BigDecimal.ZERO, "Cash", key, List.of(new OrderRequest.Item(product, 1)));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Map<String, Object>>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Callable<Map<String, Object>> task = () -> { go.await(); return orderService.create(request); };
            results.add(pool.submit(task));
        }
        go.countDown();
        Object id1 = results.get(0).get().get("order_id");
        Object id2 = results.get(1).get().get("order_id");
        pool.shutdown();
        assertEquals(id1, id2);
        assertEquals(1, count("SELECT COUNT(*) FROM orders WHERE request_key = ?", key));
        assertEquals(9, stock(product));
    }

    @Test
    void cancellingRestoresStockExactlyOnce() throws Exception {
        int product = newProduct(10, "30.00");
        int customer = newCustomer();
        int orderId = placeOrderViaApi(customer, "0", newKey(), product, 4);        // 120 -> 1 point
        assertEquals(6, stock(product));
        assertEquals(1, points(customer));

        mvc.perform(put("/api/orders/" + orderId + "/cancel")).andExpect(status().isOk());
        assertEquals(10, stock(product));
        assertEquals(0, points(customer), "points awarded for the sale are reversed");
        assertEquals("Cancelled", orderStatus(orderId));

        mvc.perform(put("/api/orders/" + orderId + "/cancel")).andExpect(status().isConflict());
        assertEquals(10, stock(product), "a second cancel must not restore stock again");
        assertEquals(1, count("SELECT COUNT(*) FROM stock_log WHERE product_id = ? AND reason = 'Sale Cancelled'", product));
        mvc.perform(put("/api/orders/999999/cancel")).andExpect(status().isNotFound());
    }

    @Test
    void aPendingOrderIsPaidOnceAndAnotherPaymentIsRejected() throws Exception {
        int product = newProduct(10, "150.00");
        int customer = newCustomer();
        int orderId = pendingOrder(customer, product, 2);                            // 300.00 -> 3 points
        String pay = "{\"payment_method\": \"Card\"}";

        mvc.perform(post("/api/orders/" + orderId + "/pay").contentType(MediaType.APPLICATION_JSON).content(pay)).andExpect(status().isOk());
        assertEquals("Completed", orderStatus(orderId));
        assertEquals(1, count("SELECT COUNT(*) FROM payments WHERE order_id = ?", orderId));
        assertEquals(3, points(customer));

        mvc.perform(post("/api/orders/" + orderId + "/pay").contentType(MediaType.APPLICATION_JSON).content(pay))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message", containsString("already been paid")));
        assertEquals(1, count("SELECT COUNT(*) FROM payments WHERE order_id = ?", orderId));
        assertEquals(3, points(customer), "retrying a payment must not award points twice");
    }

    @Test
    void cancelledOrdersCannotBePaidAndBadPaymentRequestsAreRejected() throws Exception {
        int product = newProduct(10, "150.00");
        int orderId = pendingOrder(null, product, 1);
        mvc.perform(put("/api/orders/" + orderId + "/cancel")).andExpect(status().isOk());
        String pay = "{\"payment_method\": \"Cash\"}";
        mvc.perform(post("/api/orders/" + orderId + "/pay").contentType(MediaType.APPLICATION_JSON).content(pay)).andExpect(status().isConflict());
        mvc.perform(post("/api/orders/999999/pay").contentType(MediaType.APPLICATION_JSON).content(pay)).andExpect(status().isNotFound());
        int other = pendingOrder(null, product, 1);
        mvc.perform(post("/api/orders/" + other + "/pay").contentType(MediaType.APPLICATION_JSON).content("{\"payment_method\": \"Bitcoin\"}")).andExpect(status().isBadRequest());
        assertEquals("Pending", orderStatus(other));
        assertEquals(0, count("SELECT COUNT(*) FROM payments WHERE order_id IN (?, ?)", orderId, other));
    }

    @Test
    void aFailedPaymentLeavesOrderPaymentAndPointsUntouched() throws Exception {
        int free = newProduct(5, "0.00");                                            // price 0 -> order total 0 -> payment impossible
        int customer = newCustomer();
        int orderId = pendingOrder(customer, free, 1);
        mvc.perform(post("/api/orders/" + orderId + "/pay").contentType(MediaType.APPLICATION_JSON).content("{\"payment_method\": \"Cash\"}"))
                .andExpect(status().isBadRequest());
        assertEquals("Pending", orderStatus(orderId));
        assertEquals(0, count("SELECT COUNT(*) FROM payments WHERE order_id = ?", orderId));
        assertEquals(0, points(customer));
    }

    @Test
    void twoSimultaneousPaymentsForOneOrderOnlyOneSucceeds() throws Exception {
        int product = newProduct(10, "250.00");
        int customer = newCustomer();
        int orderId = pendingOrder(customer, product, 1);                            // 250.00 -> 2 points
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Callable<Boolean> task = () -> {
                go.await();
                try { orderService.pay(orderId, "Cash"); return true; }
                catch (ApiException e) { return false; }
            };
            results.add(pool.submit(task));
        }
        go.countDown();
        int successes = 0;
        for (Future<Boolean> f : results) if (f.get()) successes++;
        pool.shutdown();
        assertEquals(1, successes, "exactly one of the two payment requests may succeed");
        assertEquals(1, count("SELECT COUNT(*) FROM payments WHERE order_id = ?", orderId));
        assertEquals(2, points(customer), "loyalty points are awarded once");
        assertEquals("Completed", orderStatus(orderId));
    }
}

package com.stationaryshop;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stationaryshop.support.AbstractDbIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Tests K and L: purchase orders raise stock only when received, exactly once. */
class PurchaseOrderIntegrationTest extends AbstractDbIntegrationTest {

    private int createPo(int product, int qty) throws Exception {
        String body = """
            {"supplier_id": 4, "items": [{"product_id": %d, "quantity": %d, "unit_cost": 14.50}]}""".formatted(product, qty);
        String res = mvc.perform(post("/api/purchase-orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("po_id").asInt();
    }

    @Test
    void creatingDoesNotChangeStockAndReceivingAddsItOnce() throws Exception {
        int product = newProduct(5, "20.00");
        int po = createPo(product, 25);
        assertEquals(5, stock(product), "stock must not change when a purchase order is created");
        assertEquals("Ordered", jdbc.queryForObject("SELECT status FROM purchase_orders WHERE po_id = ?", String.class, po));

        mvc.perform(post("/api/purchase-orders/" + po + "/receive")).andExpect(status().isOk());
        assertEquals(30, stock(product));
        assertEquals(1, count("SELECT COUNT(*) FROM stock_log WHERE product_id = ? AND reason = 'Restock'", product));

        mvc.perform(post("/api/purchase-orders/" + po + "/receive")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("already Received")));
        assertEquals(30, stock(product), "receiving twice must not add stock twice");
    }

    @Test
    void aReceivedOrderCannotBeCancelledAndACancelledOneCannotBeReceived() throws Exception {
        int product = newProduct(5, "20.00");
        int received = createPo(product, 10);
        mvc.perform(post("/api/purchase-orders/" + received + "/receive")).andExpect(status().isOk());
        mvc.perform(post("/api/purchase-orders/" + received + "/cancel")).andExpect(status().isConflict());

        int cancelled = createPo(product, 10);
        mvc.perform(post("/api/purchase-orders/" + cancelled + "/cancel")).andExpect(status().isOk());
        mvc.perform(post("/api/purchase-orders/" + cancelled + "/receive")).andExpect(status().isConflict());
        mvc.perform(post("/api/purchase-orders/" + cancelled + "/cancel")).andExpect(status().isConflict());
        assertEquals(15, stock(product), "only the received order added stock");
    }

    @Test
    void invalidPurchaseOrdersAreRejected() throws Exception {
        int before = count("SELECT COUNT(*) FROM purchase_orders");
        mvc.perform(post("/api/purchase-orders").contentType(MediaType.APPLICATION_JSON).content("{\"supplier_id\": 4, \"items\": []}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/purchase-orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"supplier_id\": 4, \"items\": [{\"product_id\": 1, \"quantity\": 0, \"unit_cost\": 5}]}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/purchase-orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"supplier_id\": 999999, \"items\": [{\"product_id\": 1, \"quantity\": 1, \"unit_cost\": 5}]}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/purchase-orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"supplier_id\": 4, \"items\": [{\"product_id\": 999999, \"quantity\": 1, \"unit_cost\": 5}]}")).andExpect(status().isBadRequest());
        assertEquals(before, count("SELECT COUNT(*) FROM purchase_orders"), "a failed request leaves no half-created purchase order");
        mvc.perform(post("/api/purchase-orders/999999/receive")).andExpect(status().isNotFound());
        mvc.perform(get("/api/purchase-orders/999999")).andExpect(status().isNotFound());
    }
}

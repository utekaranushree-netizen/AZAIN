package com.stationaryshop;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stationaryshop.support.AbstractDbIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Tests B, C, D: CRUD, validation, not-found and delete-protection for the master tables. */
class MasterDataApiIntegrationTest extends AbstractDbIntegrationTest {

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, String body) throws Exception {
        return mvc.perform(b.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String uniq() { return UUID.randomUUID().toString().replace("-", "").substring(0, 10); }

    private String productJson(String name, String cost, String price, int stock) {
        return """
            {"product_name": "%s", "brand": "T", "category_id": 1, "supplier_id": null,
             "cost_price": %s, "price": %s, "stock_qty": %d, "reorder_level": 5, "is_active": true}""".formatted(name, cost, price, stock);
    }

    @Test
    void productLifecycleInsertUpdateDelete() throws Exception {
        String body = send(post("/api/products"), productJson("Pen " + uniq(), "5", "9", 20)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.product_id").exists()).andReturn().getResponse().getContentAsString();
        int id = json.readTree(body).get("product_id").asInt();
        assertEquals(1, count("SELECT COUNT(*) FROM stock_log WHERE product_id = ? AND reason = 'Opening Stock'", id));

        mvc.perform(get("/api/products/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.price").value(9.0));

        // update changes the price but can NEVER change the stock (even if the request tries)
        send(put("/api/products/" + id), productJson("Pen renamed " + uniq(), "5", "12", 9999)).andExpect(status().isOk());
        assertEquals(0, new java.math.BigDecimal("12.00").compareTo(
                jdbc.queryForObject("SELECT price FROM products WHERE product_id = ?", java.math.BigDecimal.class, id)));
        assertEquals(20, stock(id));

        mvc.perform(delete("/api/products/" + id)).andExpect(status().isOk());
        mvc.perform(get("/api/products/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void productValidationRejectsBadInputAndSavesNothing() throws Exception {
        int before = count("SELECT COUNT(*) FROM products");
        send(post("/api/products"), productJson("Bad " + uniq(), "10", "5", 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("cannot be lower than cost price")));
        send(post("/api/products"), productJson("Bad " + uniq(), "5", "-1", 1)).andExpect(status().isBadRequest());
        send(post("/api/products"), productJson("   ", "5", "9", 1)).andExpect(status().isBadRequest());
        send(post("/api/products"), productJson("Bad " + uniq(), "5", "9", -4)).andExpect(status().isBadRequest());
        send(post("/api/products"), "{ this is not json").andExpect(status().isBadRequest());
        assertEquals(before, count("SELECT COUNT(*) FROM products"));
    }

    @Test
    void unknownIdsGiveNotFound() throws Exception {
        mvc.perform(get("/api/products/999999")).andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Product not found"));
        send(put("/api/products/999999"), productJson("X " + uniq(), "1", "2", 0)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/customers/999999")).andExpect(status().isNotFound());
        mvc.perform(get("/api/does-not-exist")).andExpect(status().isNotFound());
    }

    @Test
    void productWithHistoryCannotBeDeleted() throws Exception {
        mvc.perform(delete("/api/products/1")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("Deactivate")));
        assertEquals(1, count("SELECT COUNT(*) FROM products WHERE product_id = 1"));
    }

    @Test
    void categoryCrudDuplicatesAndProtection() throws Exception {
        String name = "Cat " + uniq();
        String body = send(post("/api/categories"), "{\"category_name\": \"" + name + "\", \"description\": \"d\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        int id = json.readTree(body).get("category_id").asInt();
        send(post("/api/categories"), "{\"category_name\": \"" + name.toUpperCase() + "\"}").andExpect(status().isConflict());
        send(put("/api/categories/" + id), "{\"category_name\": \"" + name + " v2\", \"description\": null}").andExpect(status().isOk());
        mvc.perform(delete("/api/categories/" + id)).andExpect(status().isOk());
        mvc.perform(delete("/api/categories/1")).andExpect(status().isConflict());          // used by products
        send(post("/api/categories"), "{\"category_name\": \"\"}").andExpect(status().isBadRequest());
    }

    @Test
    void supplierCrudAndUniqueEmail() throws Exception {
        String email = "s" + uniq() + "@test.example";
        String body = send(post("/api/suppliers"), "{\"supplier_name\": \"S\", \"email\": \"" + email + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        int id = json.readTree(body).get("supplier_id").asInt();
        send(post("/api/suppliers"), "{\"supplier_name\": \"S2\", \"email\": \"" + email.toUpperCase() + "\"}").andExpect(status().isConflict());
        send(post("/api/suppliers"), "{\"supplier_name\": \"S3\", \"email\": \"not-an-email\"}").andExpect(status().isBadRequest());
        send(put("/api/suppliers/" + id), "{\"supplier_name\": \"S renamed\", \"email\": \"" + email + "\", \"city\": \"Pune\"}").andExpect(status().isOk());
        mvc.perform(get("/api/suppliers/" + id + "/products")).andExpect(status().isOk());
        mvc.perform(delete("/api/suppliers/" + id)).andExpect(status().isOk());
        mvc.perform(delete("/api/suppliers/4")).andExpect(status().isConflict());           // supplies products
    }

    @Test
    void customerCrudAndHistoryProtection() throws Exception {
        String phone = "9" + (long) (Math.random() * 900000000L + 100000000L);
        String body = send(post("/api/customers"), "{\"customer_name\": \"C\", \"phone\": \"" + phone + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        int id = json.readTree(body).get("customer_id").asInt();
        send(post("/api/customers"), "{\"customer_name\": \"C2\", \"phone\": \"" + phone + "\"}").andExpect(status().isConflict());
        send(post("/api/customers"), "{\"customer_name\": \"C3\", \"phone\": \"abc\"}").andExpect(status().isBadRequest());
        mvc.perform(get("/api/customers/" + id + "/orders")).andExpect(status().isOk());
        send(put("/api/customers/" + id), "{\"customer_name\": \"C renamed\", \"phone\": \"" + phone + "\"}").andExpect(status().isOk());
        mvc.perform(delete("/api/customers/" + id)).andExpect(status().isOk());
        mvc.perform(delete("/api/customers/1")).andExpect(status().isConflict());           // has orders
    }

    @Test
    void employeeCrudValidationAndHistoryProtection() throws Exception {
        String body = send(post("/api/employees"), "{\"emp_name\": \"E\", \"role\": \"Cashier\", \"salary\": 20000}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        int id = json.readTree(body).get("emp_id").asInt();
        send(post("/api/employees"), "{\"emp_name\": \"E\", \"role\": \"Boss\"}").andExpect(status().isBadRequest());
        send(post("/api/employees"), "{\"emp_name\": \"E\", \"role\": \"Cashier\", \"hired_on\": \"2999-01-01\"}").andExpect(status().isBadRequest());
        send(post("/api/employees"), "{\"emp_name\": \"E\", \"role\": \"Cashier\", \"salary\": 0}").andExpect(status().isBadRequest());
        send(put("/api/employees/" + id), "{\"emp_name\": \"E renamed\", \"role\": \"Manager\", \"salary\": 30000}").andExpect(status().isOk());
        mvc.perform(get("/api/employees?role=Manager")).andExpect(status().isOk());
        mvc.perform(delete("/api/employees/" + id)).andExpect(status().isOk());
        mvc.perform(delete("/api/employees/2")).andExpect(status().isConflict());           // handled orders
    }
}

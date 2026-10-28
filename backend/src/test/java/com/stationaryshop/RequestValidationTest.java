package com.stationaryshop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stationaryshop.dto.OrderRequest;
import com.stationaryshop.dto.PaymentRequest;
import com.stationaryshop.dto.PurchaseOrderRequest;
import com.stationaryshop.entity.Employee;
import com.stationaryshop.entity.Product;
import com.stationaryshop.entity.Supplier;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests (no database needed): server-side validation rules, which must never rely on the browser. */
class RequestValidationTest {
    private static final Validator V = Validation.buildDefaultValidatorFactory().getValidator();

    private static boolean invalid(Object o, String propertyPrefix) {
        return V.validate(o).stream().anyMatch(v -> v.getPropertyPath().toString().startsWith(propertyPrefix));
    }

    private static OrderRequest order(String discount, String method, String key, Integer qty) {
        return new OrderRequest(null, 2, discount == null ? null : new BigDecimal(discount), method, key,
                List.of(new OrderRequest.Item(1, qty)));
    }

    @Test
    void aNormalOrderIsValid() {
        assertTrue(V.validate(order("10", "UPI", "abcd-1234-efgh", 2)).isEmpty());
        assertTrue(V.validate(order(null, "Cash", null, 1)).isEmpty());
    }

    @Test
    void discountRuleIsZeroToFiftyPercent() {
        assertTrue(V.validate(order("0", "Cash", null, 1)).isEmpty());
        assertTrue(V.validate(order("50", "Cash", null, 1)).isEmpty());
        assertTrue(invalid(order("50.01", "Cash", null, 1), "discountPercent"));
        assertTrue(invalid(order("-0.01", "Cash", null, 1), "discountPercent"));
        assertTrue(invalid(order("100", "Cash", null, 1), "discountPercent"));
    }

    @Test
    void quantityMustBePositiveAndReasonable() {
        assertTrue(invalid(order("0", "Cash", null, 0), "items"));
        assertTrue(invalid(order("0", "Cash", null, -3), "items"));
        assertTrue(invalid(order("0", "Cash", null, 100001), "items"));
    }

    @Test
    void paymentMethodMustBeCashCardOrUpi() {
        assertTrue(invalid(order("0", "Cheque", null, 1), "paymentMethod"));
        assertTrue(invalid(order("0", null, null, 1), "paymentMethod"));
        assertTrue(invalid(new PaymentRequest("Bitcoin"), "paymentMethod"));
        assertTrue(V.validate(new PaymentRequest("Card")).isEmpty());
    }

    @Test
    void idempotencyKeyFormatIsChecked() {
        assertTrue(invalid(order("0", "Cash", "short", 1), "requestId"));
        assertTrue(invalid(order("0", "Cash", "has spaces in it!", 1), "requestId"));
        assertTrue(invalid(order("0", "Cash", "x".repeat(65), 1), "requestId"));
        assertFalse(invalid(order("0", "Cash", "0b9f2c1e-4d7a-4a52-9c1b-aaaaaaaaaaaa", 1), "requestId"));
    }

    @Test
    void anOrderNeedsAnEmployeeAndItems() {
        assertTrue(invalid(new OrderRequest(null, null, null, "Cash", null, List.of(new OrderRequest.Item(1, 1))), "empId"));
        assertTrue(invalid(new OrderRequest(null, 2, null, "Cash", null, List.of()), "items"));
    }

    @Test
    void purchaseOrderLinesAreChecked() {
        assertTrue(V.validate(new PurchaseOrderRequest(4, List.of(new PurchaseOrderRequest.Item(12, 10, new BigDecimal("14.00"))))).isEmpty());
        assertTrue(invalid(new PurchaseOrderRequest(4, List.of(new PurchaseOrderRequest.Item(12, 0, new BigDecimal("14.00")))), "items"));
        assertTrue(invalid(new PurchaseOrderRequest(4, List.of(new PurchaseOrderRequest.Item(12, 5, new BigDecimal("-1")))), "items"));
        assertTrue(invalid(new PurchaseOrderRequest(null, List.of(new PurchaseOrderRequest.Item(12, 5, BigDecimal.ONE))), "supplierId"));
    }

    private static Product product() {
        Product p = new Product();
        p.productName = "Pen";
        p.categoryId = 1;
        p.costPrice = new BigDecimal("5.00");
        p.price = new BigDecimal("9.00");
        p.stockQty = 10;
        p.reorderLevel = 5;
        return p;
    }

    @Test
    void productRules() {
        assertTrue(V.validate(product()).isEmpty());
        Product p = product(); p.price = new BigDecimal("-1"); assertTrue(invalid(p, "price"));
        p = product(); p.costPrice = new BigDecimal("-1"); assertTrue(invalid(p, "costPrice"));
        p = product(); p.productName = "   "; assertTrue(invalid(p, "productName"));
        p = product(); p.stockQty = -1; assertTrue(invalid(p, "stockQty"));
        p = product(); p.reorderLevel = null; assertTrue(invalid(p, "reorderLevel"));
        p = product(); p.categoryId = null; assertTrue(invalid(p, "categoryId"));
        p = product(); p.price = new BigDecimal("123456789.00"); assertTrue(invalid(p, "price"));
    }

    @Test
    void employeeRules() {
        Employee e = new Employee();
        e.empName = "Asha"; e.role = "Cashier"; e.salary = new BigDecimal("20000"); e.hiredOn = LocalDate.now();
        assertTrue(V.validate(e).isEmpty());
        e.role = "Boss"; assertTrue(invalid(e, "role"));
        e.role = "Cashier"; e.hiredOn = LocalDate.now().plusDays(1); assertTrue(invalid(e, "hiredOn"));
        e.hiredOn = LocalDate.now(); e.salary = BigDecimal.ZERO; assertTrue(invalid(e, "salary"));
    }

    @Test
    void contactDetailRules() {
        Supplier s = new Supplier();
        s.supplierName = "Acme"; s.email = "not-an-email";
        assertTrue(invalid(s, "email"));
        s.email = "ok@example.com"; s.phone = "98ab";
        assertTrue(invalid(s, "phone"));
        s.phone = "+91 98200-11111";
        assertTrue(V.validate(s).isEmpty());
    }
}

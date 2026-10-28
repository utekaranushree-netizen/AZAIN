package com.stationaryshop.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Body of POST /api/orders.
 * customer_id = null means a walk-in customer.
 * request_id is the idempotency key (8-64 characters: letters, digits, _ or -). Sending the same key again
 * returns the order that was already created instead of creating a second one.
 * Discount rule (same in the database, here and in the browser): 0 to 50 percent.
 */
public record OrderRequest(
        Integer customerId,
        @NotNull(message = "Employee is required") Integer empId,
        @DecimalMin(value = "0.00", message = "Discount cannot be negative")
        @DecimalMax(value = "50.00", message = "Discount cannot be more than 50 percent")
        @Digits(integer = 2, fraction = 2, message = "Discount can have at most 2 decimals")
        BigDecimal discountPercent,
        @NotNull(message = "Payment method is required")
        @Pattern(regexp = "Cash|Card|UPI", message = "Payment method must be Cash, Card or UPI")
        String paymentMethod,
        @Pattern(regexp = "^[A-Za-z0-9_-]{8,64}$", message = "request_id must be 8-64 characters: letters, digits, _ or -")
        String requestId,
        @NotEmpty(message = "Add at least one product to the order")
        @Size(max = 100, message = "An order can have at most 100 different products")
        @Valid List<Item> items) {

    public record Item(
            @NotNull(message = "Product is required") Integer productId,
            @NotNull(message = "Quantity is required")
            @Min(value = 1, message = "Quantity must be at least 1")
            @Max(value = 100000, message = "Quantity cannot be more than 100000") Integer quantity) {
    }
}

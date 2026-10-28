package com.stationaryshop.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/** Body of POST /api/purchase-orders. */
public record PurchaseOrderRequest(
        @NotNull(message = "Supplier is required") Integer supplierId,
        @NotEmpty(message = "Add at least one product")
        @Size(max = 100, message = "A purchase order can have at most 100 lines")
        @Valid List<Item> items) {

    public record Item(
            @NotNull(message = "Product is required") Integer productId,
            @NotNull(message = "Quantity is required")
            @Min(value = 1, message = "Quantity must be at least 1")
            @Max(value = 1000000, message = "Quantity cannot be more than 1000000") Integer quantity,
            @NotNull(message = "Unit cost is required")
            @DecimalMin(value = "0.00", message = "Unit cost cannot be negative")
            @Digits(integer = 8, fraction = 2, message = "Unit cost can have at most 8 digits and 2 decimals") BigDecimal unitCost) {
    }
}

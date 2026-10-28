package com.stationaryshop.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.DynamicInsert;

/**
 * Maps the table products.
 * stockQty is insertable (opening stock) but NOT updatable: after creation, stock changes only through
 * sales, cancellations and purchase-order receipts (database triggers + stock_log).
 */
@Entity
@Table(name = "products")
@DynamicInsert
public class Product {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer productId;

    @NotBlank(message = "Product name is required")
    @Size(max = 80, message = "Product name can have at most 80 characters")
    public String productName;

    @Size(max = 40, message = "Brand can have at most 40 characters")
    public String brand;

    @NotNull(message = "Category is required")
    public Integer categoryId;

    public Integer supplierId;

    @NotNull(message = "Cost price is required")
    @DecimalMin(value = "0.00", message = "Cost price cannot be negative")
    @Digits(integer = 8, fraction = 2, message = "Cost price can have at most 8 digits and 2 decimals")
    public BigDecimal costPrice;

    @NotNull(message = "Selling price is required")
    @DecimalMin(value = "0.00", message = "Selling price cannot be negative")
    @Digits(integer = 8, fraction = 2, message = "Selling price can have at most 8 digits and 2 decimals")
    public BigDecimal price;

    @Column(updatable = false)
    @Min(value = 0, message = "Stock quantity cannot be negative")
    @Max(value = 1000000, message = "Stock quantity cannot be more than 1000000")
    public Integer stockQty;

    @NotNull(message = "Reorder level is required")
    @Min(value = 0, message = "Reorder level cannot be negative")
    @Max(value = 1000000, message = "Reorder level cannot be more than 1000000")
    public Integer reorderLevel;

    public Boolean isActive;

    @Column(insertable = false, updatable = false)
    public LocalDateTime createdAt;
}

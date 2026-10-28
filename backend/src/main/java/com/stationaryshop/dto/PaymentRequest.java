package com.stationaryshop.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Body of POST /api/orders/{id}/pay (pays a Pending order). */
public record PaymentRequest(
        @NotNull(message = "Payment method is required")
        @Pattern(regexp = "Cash|Card|UPI", message = "Payment method must be Cash, Card or UPI")
        String paymentMethod) {
}

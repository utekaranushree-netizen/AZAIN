package com.stationaryshop.controller;

import com.stationaryshop.dto.OrderRequest;
import com.stationaryshop.dto.PaymentRequest;
import com.stationaryshop.service.OrderService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "status", defaultValue = "") String status) {
        return service.list(from, to, status);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody OrderRequest body) {
        Map<String, Object> result = service.create(body);
        boolean duplicate = Boolean.TRUE.equals(result.get("duplicate"));
        return ResponseEntity.status(duplicate ? 200 : 201).body(result);
    }

    @GetMapping("/{id}/receipt")
    public Map<String, Object> receipt(@PathVariable Integer id) {
        return service.receipt(id);
    }

    @PostMapping("/{id}/pay")
    public Map<String, Object> pay(@PathVariable Integer id, @Valid @RequestBody PaymentRequest body) {
        return service.pay(id, body.paymentMethod());
    }

    @PutMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable Integer id) {
        return service.cancel(id);
    }
}

package com.stationaryshop.controller;

import com.stationaryshop.dto.PurchaseOrderRequest;
import com.stationaryshop.service.PurchaseOrderService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/purchase-orders")
public class PurchaseOrderController {
    private final PurchaseOrderService service;

    public PurchaseOrderController(PurchaseOrderService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(name = "status", defaultValue = "") String status) {
        return service.list(status);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Integer id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody PurchaseOrderRequest body) {
        return service.create(body);
    }

    @PostMapping("/{id}/receive")
    public Map<String, Object> receive(@PathVariable Integer id) {
        return service.receive(id);
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable Integer id) {
        return service.cancel(id);
    }
}

package com.stationaryshop.controller;

import com.stationaryshop.service.InventoryService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {
    private final InventoryService service;

    public InventoryController(InventoryService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(name = "q", defaultValue = "") String q,
                                          @RequestParam(name = "category_id", required = false) Integer categoryId,
                                          @RequestParam(name = "supplier_id", required = false) Integer supplierId,
                                          @RequestParam(name = "status", defaultValue = "") String status) {
        return service.list(q, categoryId, supplierId, status);
    }

    @GetMapping("/low-stock")
    public List<Map<String, Object>> lowStock() {
        return service.lowStock();
    }

    @GetMapping("/stock-log")
    public List<Map<String, Object>> stockLog(@RequestParam(name = "product_id", required = false) Integer productId) {
        return service.stockLog(productId);
    }
}
